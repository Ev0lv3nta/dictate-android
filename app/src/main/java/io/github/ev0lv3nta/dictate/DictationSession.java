package io.github.ev0lv3nta.dictate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.SpeechRecognizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** One attempt, immutable settings and bounded ownership across service and built-in UI. */
final class DictationSession {
    interface Listener {
        void ready();
        void speech();
        void level(float value);
        void processing();
        void result(String text);
        void failure(int code, String message);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RecognitionSessionState state = new RecognitionSessionState();
    private final Transcription.Request request = new Transcription.Request();
    private final AudioCapture capture;
    private final Transcription.Config config;
    private final boolean history;
    private final int threshold;
    private boolean started;
    private String key;
    private volatile String savedRecording;

    DictationSession(Context context, String clientLanguage, Listener listener) {
        this.context = context;
        this.listener = listener;
        AppPreferences prefs = new AppPreferences(context);
        String language = prefs.getLanguageOverride();
        if (language.isEmpty()) language = AppPreferences.normalizeLanguage(clientLanguage);
        config = new Transcription.Config(prefs.getProvider(), prefs.getModel(), language, prefs.getKeyterms());
        threshold = prefs.getSpeechThresholdDb();
        history = prefs.isHistoryEnabled();
        capture = new AudioCapture(context, new AudioCapture.Config(prefs.isAutoStopEnabled(),
                prefs.getSilenceMillis(), AppPreferences.DEFAULT_NO_SPEECH_TIMEOUT_MILLIS,
                prefs.getMaxRecordingSeconds() * 1000, threshold));
    }

    void start() {
        if (started || state.isCancelled()) return;
        started = true;
        if (!OperationGate.acquire(this)) {
            fail(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, "Микрофон занят другой диктовкой");
            return;
        }
        worker.execute(this::run);
    }

    void stop() {
        if (state.requestStop()) capture.requestStop();
    }

    void cancel() {
        if (!state.cancel()) return;
        main.removeCallbacksAndMessages(null);
        capture.cancel();
        request.cancel();
        key = null;
        try {
            worker.execute(() -> {
                String id=savedRecording;
                if (id!=null) new RecordingLibrary(context).delete(id);
            });
        } catch (java.util.concurrent.RejectedExecutionException finished) { }
        // The old worker releases only its own lease in finally.
        // Let a queued task observe cancellation and release its lease in finally.
        worker.shutdown();
    }

    private void post(Runnable event) {
        main.post(() -> { if (!state.isCancelled() && !state.isCompleted()) event.run(); });
    }

    private void run() {
        long start = SystemClock.elapsedRealtime();
        try {
            if (state.isCancelled()) return;
            SecureApiKeyStore keys = new SecureApiKeyStore(context);
            if (keys.isUnreadable(config.provider)) {
                fail(SpeechRecognizer.ERROR_CLIENT, "Не удалось расшифровать ключ. Введите его заново.");
                return;
            }
            key = keys.load(config.provider);
            BackendSelection.requireKey(key, config.provider);
            Transcription.validateConfig(config);
            if (state.isCancelled()) return;
            AudioCapture.Result result = capture.record(new AudioCapture.Listener() {
                @Override public void onReady() { post(listener::ready); }
                @Override public void onBeginningOfSpeech() { post(listener::speech); }
                @Override public void onRms(float rms) { post(() -> listener.level(rms)); }
            });
            if (state.isCancelled()) return;
            byte[] pcm = PcmSilenceTrimmer.trimEdges(result.pcm, AudioCapture.SAMPLE_RATE, threshold, 300).pcm;
            if (!result.speechStarted || pcm.length < 6400) {
                fail(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, "Речь не обнаружена");
                return;
            }
            post(listener::processing);
            String text = BackendSelection.client(config.provider).transcribe(pcm, key, config, request);
            if (state.isCancelled()) return;
            if (history) {
                RecordingLibrary library = new RecordingLibrary(context);
                if (state.isCancelled()) return;
                RecordingLibrary.Entry saved = library.add(pcm, RecordingLibrary.SOURCE_KEYBOARD);
                if (saved != null) {
                    savedRecording=saved.id;
                    library.setText(saved.id, text, config.provider, config.model);
                    if (state.isCancelled()) { library.delete(saved.id); return; }
                }
            }
            new AppPreferences(context).recordLastRun(config.provider, config.model,
                    SystemClock.elapsedRealtime() - start, "OK");
            main.post(() -> {
                try { if (state.complete()) listener.result(text); }
                finally { worker.shutdown(); }
            });
        } catch (SecurityException error) {
            fail(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, "Разрешите доступ к микрофону");
        } catch (AudioCapture.CaptureException error) {
            fail(SpeechRecognizer.ERROR_AUDIO, "Не удалось открыть микрофон");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Transcription.ApiException error) {
            fail(Transcription.androidError(error.kind), error.getMessage());
        } catch (RuntimeException error) {
            fail(SpeechRecognizer.ERROR_CLIENT, "Не удалось завершить диктовку");
        } finally {
            key = null;
            OperationGate.release(this);
        }
    }

    private void fail(int code, String message) {
        main.post(() -> { if (state.complete()) listener.failure(code, message); });
        worker.shutdown();
    }
}
