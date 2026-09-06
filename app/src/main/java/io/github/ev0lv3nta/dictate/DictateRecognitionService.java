package io.github.ev0lv3nta.dictate;

import android.annotation.TargetApi;
import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.content.Context;
import android.content.ContextParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RecognitionService entry point used by compatible keyboards and speech clients.
 */
public final class DictateRecognitionService extends RecognitionService {

    private static final String TAG = "DictateService";
    private static final int TRIM_THRESHOLD_DB = -50;
    private static final int TRIM_KEEP_MILLIS = 300;

    private final Object sessionLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private Session activeSession;

    @Override
    public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dictate-recognizer");
            thread.setDaemon(true);
            return thread;
        });
        Log.i(TAG, "service created");
    }



    private View overlayView = null;

    /**
     * Крошечное прозрачное окно поверх экрана. Пока оно висит, система считает
     * процесс видимым: это снимает запрет на микрофон в фоне и разрешает
     * поднять foreground-сервис. Пользователь его не видит — 1x1 пиксель.
     */
    private void showOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) {
            return;
        }
        WindowManager manager = getSystemService(WindowManager.class);
        if (manager == null) {
            return;
        }
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        View view = new View(this);
        try {
            manager.addView(view, params);
            overlayView = view;
            Log.i(TAG, "overlay shown");
        } catch (RuntimeException error) {
            Log.w(TAG, "overlay refused: " + error.getClass().getSimpleName());
        }
    }

    private void hideOverlay() {
        View view = overlayView;
        overlayView = null;
        if (view == null) {
            return;
        }
        WindowManager manager = getSystemService(WindowManager.class);
        if (manager != null) {
            try {
                manager.removeView(view);
            } catch (RuntimeException ignored) {
                // окно уже могло быть снято системой
            }
        }
    }

    private static final String MIC_CHANNEL = "dictate_mic";
    private static final int MIC_NOTIFICATION_ID = 4711;
    private static final long OVERLAY_SETTLE_MILLIS = 300L;
    private volatile boolean micForegroundActive = false;
    /**
     * Микрофонное разрешение выдано в режиме "только пока приложение на экране",
     * а сервис работает в фоне по вызову клавиатуры. Без foreground-сервиса
     * audioserver отдаёт тишину: "App op 27 missing, silencing record".
     */
    private void startMicForeground() {
        if (micForegroundActive) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        if (manager.getNotificationChannel(MIC_CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(MIC_CHANNEL,
                    getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, MIC_CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.mic_notification_text))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(MIC_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(MIC_NOTIFICATION_ID, notification);
            }
            micForegroundActive = true;
        } catch (RuntimeException error) {
            Log.w(TAG, "foreground service refused: " + error.getClass().getSimpleName());
        }
    }

    private void stopMicForeground() {
        if (!micForegroundActive) {
            return;
        }
        hideOverlay();
        micForegroundActive = false;
        try {
            stopForeground(true);
        } catch (RuntimeException ignored) {
            // сервис уже мог быть остановлен системой
        }
    }

    @Override
    protected void onStartListening(Intent intent, Callback callback) {
        Context attributionContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            attributionContext = Api31.createAttributionContext(this, callback);
        }

        if (!isAllowedCaller(callback)) {
            Log.w(TAG, "start rejected: unsupported caller=" + callerDescription(callback));
            sendDirectError(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        synchronized (sessionLock) {
            if (activeSession != null) {
                sendDirectError(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
                return;
            }
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "start rejected: service RECORD_AUDIO is not granted");
            sendDirectError(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        AppPreferences preferences = new AppPreferences(this);
        String provider = preferences.getProvider();
        String apiKey = new SecureApiKeyStore(this).load(provider);
        if (apiKey == null || apiKey.isEmpty()) {
            Log.w(TAG, "start rejected: API key is not configured for " + provider);
            sendDirectError(callback, SpeechRecognizer.ERROR_CLIENT);
            return;
        }

        String language = preferences.getLanguageOverride();
        if (language.isEmpty() && intent != null) {
            language = AppPreferences.normalizeLanguage(
                    intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE));
        }
        List<String> keyterms = preferences.getKeyterms();
        AudioCapture.Config captureConfig = new AudioCapture.Config(
                preferences.isAutoStopEnabled(),
                preferences.getSilenceMillis(),
                AppPreferences.DEFAULT_NO_SPEECH_TIMEOUT_MILLIS,
                preferences.getMaxRecordingSeconds() * 1000,
                preferences.getSpeechThresholdDb());
        AudioCapture capture = new AudioCapture(attributionContext, captureConfig);
        Transcription.Config apiConfig = new Transcription.Config(
                provider, preferences.getModel(provider), language, keyterms);
        Session session = new Session(callback, attributionContext, capture,
                new Transcription.Request(), apiConfig, apiKey);

        synchronized (sessionLock) {
            if (activeSession != null) {
                session.clearSecret();
                sendDirectError(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
                return;
            }
            activeSession = session;
        }

        showOverlay();
        Log.i(TAG, "startListening caller=" + callerDescription(callback)
                + " provider=" + provider + " model=" + apiConfig.model
                + " language=" + (language.isEmpty() ? "auto" : language)
                + " keyterms=" + keyterms.size());
        // Системе нужно время, чтобы зарегистрировать окно-оверлей как видимое.
        // Без этой паузы startForeground выполняется на 1 мс раньше и получает
        // отказ, а запись затем глушится: "App op 27 missing".
        mainHandler.postDelayed(() -> {
            if (session.state.isCancelled()) {
                hideOverlay();
                return;
            }
            // Keep the service alive while the request is being processed.
            try {
                startService(new Intent(this, DictateRecognitionService.class));
            } catch (RuntimeException error) {
                Log.w(TAG, "keep-alive start refused: " + error.getClass().getSimpleName());
            }
            startMicForeground();
            worker.execute(() -> runSession(session));
        }, OVERLAY_SETTLE_MILLIS);
    }

    @Override
    protected void onStopListening(Callback callback) {
        Session session = findSession(callback);
        if (session == null) {
            Log.w(TAG, "stopListening without active session");
            return;
        }
        if (!session.state.requestStop()) {
            return;
        }
        Log.i(TAG, "stopListening requested by client");
        session.capture.requestStop();
    }

    @Override
    protected void onCancel(Callback callback) {
        Session session = findSession(callback);
        if (session == null) {
            return;
        }
        Log.i(TAG, "cancel requested by client");
        cancelSession(session);
    }

    private void runSession(Session session) {
        try {
            AudioCapture.Result captureResult = session.capture.record(
                    new AudioCapture.Listener() {
                        @Override
                        public void onReady() {
                            if (isDeliverable(session)) {
                                try {
                                    session.callback.readyForSpeech(new Bundle());
                                    Log.i(TAG, "callback readyForSpeech");
                                } catch (RemoteException error) {
                                    cancelSession(session);
                                }
                            }
                        }

                        @Override
                        public void onBeginningOfSpeech() {
                            if (isDeliverable(session)) {
                                try {
                                    session.callback.beginningOfSpeech();
                                    Log.i(TAG, "callback beginningOfSpeech");
                                } catch (RemoteException error) {
                                    cancelSession(session);
                                }
                            }
                        }

                        @Override
                        public void onRms(float normalizedRms) {
                            if (isDeliverable(session)) {
                                try {
                                    session.callback.rmsChanged(normalizedRms);
                                } catch (RemoteException error) {
                                    cancelSession(session);
                                }
                            }
                        }
                    });

            if (!isDeliverable(session)) {
                return;
            }
            if (captureResult.stopReason == AudioCapture.StopReason.NO_SPEECH
                    && !captureResult.speechStarted) {
                finishError(session, SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        "no speech before timeout");
                return;
            }

            sendEndOfSpeech(session);
            PcmSilenceTrimmer.Result trimmed = PcmSilenceTrimmer.trimEdges(
                    captureResult.pcm, AudioCapture.SAMPLE_RATE,
                    TRIM_THRESHOLD_DB, TRIM_KEEP_MILLIS);
            long trimmedMillis = trimmed.pcm.length * 1000L
                    / (AudioCapture.SAMPLE_RATE * 2L);
            Log.i(TAG, "recording stopped reason=" + captureResult.stopReason
                    + " rawMs=" + captureResult.durationMillis
                    + " uploadMs=" + trimmedMillis
                    + " trimmed=" + trimmed.savedPercent() + "%");
            session.uploadStarted = true;
            // Сохраняем до отправки: если сеть отвалится, запись можно будет
            // прогнать заново из диктофона, а не переговаривать заново.
            RecordingLibrary library = new RecordingLibrary(this);
            RecordingLibrary.Entry saved = library.add(trimmed.pcm,
                    RecordingLibrary.SOURCE_KEYBOARD);
            if (trimmedMillis < 200L) {
                finishError(session, SpeechRecognizer.ERROR_NO_MATCH,
                        "recording contains no usable speech");
                return;
            }

            String apiKey = session.apiKey;
            if (apiKey == null || !isDeliverable(session)) {
                return;
            }
            Transcription.Client client = Transcription.clientFor(session.apiConfig.provider);
            long requestStartedAt = SystemClock.elapsedRealtime();
            String text = transcribeWithRetry(client, trimmed.pcm, apiKey, session);
            apiKey = null;
            session.clearSecret();
            if (!isDeliverable(session)) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - requestStartedAt;
            Log.i(TAG, "transcription received from " + session.apiConfig.provider
                    + " model=" + session.apiConfig.model
                    + " in " + elapsed + "ms chars=" + text.length());
            recordRun(session, elapsed, "ок");
            // Текст кладём к записи: в диктофоне видно, что дала эта модель,
            // и можно сравнить с другой на том же звуке. Наружу и в логи он
            // по-прежнему не уходит.
            if (saved != null) {
                library.setText(saved.id, text, session.apiConfig.provider,
                        session.apiConfig.model);
            }
            sendSuccess(session, text);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            cancelSession(session);
        } catch (SecurityException error) {
            Log.e(TAG, "microphone permission failure");
            finishError(session, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    "microphone permission failure");
        } catch (AudioCapture.CaptureException error) {
            Log.e(TAG, "audio capture failed", error);
            finishError(session, SpeechRecognizer.ERROR_AUDIO, "audio capture failed");
        } catch (Transcription.ApiException error) {
            session.clearSecret();
            if (error.kind == Transcription.ErrorKind.CANCELLED) {
                cancelSession(session);
                return;
            }
            // Сообщение содержит только метаданные ответа провайдера,
            // распознанного текста в нём нет.
            Log.e(TAG, session.apiConfig.provider + " failed kind=" + error.kind
                    + " status=" + error.httpStatus + " detail=" + error.getMessage());
            recordRun(session, SystemClock.elapsedRealtime() - session.startedAt,
                    error.kind + (error.httpStatus > 0 ? " " + error.httpStatus : ""));
            finishError(session, mapApiError(error.kind), "transcription request failed");
        } catch (RuntimeException error) {
            session.clearSecret();
            Log.e(TAG, "unexpected recognizer failure", error);
            finishError(session, SpeechRecognizer.ERROR_CLIENT, "unexpected failure");
        } finally {
            session.clearSecret();
        }
    }

    /**
     * Один повтор на сбой сервера или сети. Запрос идемпотентный, аудио уже в
     * памяти, а без повтора случайный 200 с пустым телом — и наговорённое
     * пропало. Таймаут не повторяем: ждать ещё две минуты хуже, чем отдать ошибку.
     */
    private String transcribeWithRetry(Transcription.Client client, byte[] pcm, String apiKey,
                                       Session session) throws Transcription.ApiException {
        try {
            return client.transcribe(pcm, apiKey, session.apiConfig, session.request);
        } catch (Transcription.ApiException error) {
            boolean retryable = error.kind == Transcription.ErrorKind.SERVER
                    || error.kind == Transcription.ErrorKind.NETWORK;
            if (!retryable || session.request.isCancelled() || !isDeliverable(session)) {
                throw error;
            }
            Log.w(TAG, "retrying after " + error.kind + " status=" + error.httpStatus
                    + " detail=" + error.getMessage());
            return client.transcribe(pcm, apiKey, session.apiConfig, session.request);
        }
    }

    /** Метаданные последнего запроса для экрана настроек: ни текста, ни аудио. */
    private void recordRun(Session session, long millis, String status) {
        try {
            new AppPreferences(this).recordLastRun(session.apiConfig.provider,
                    session.apiConfig.model, millis, status);
        } catch (RuntimeException error) {
            Log.w(TAG, "last-run journal refused: " + error.getClass().getSimpleName());
        }
    }

    private void sendEndOfSpeech(Session session) {
        synchronized (sessionLock) {
            if (activeSession != session || session.state.isCancelled() || session.endSent) {
                return;
            }
            session.endSent = true;
        }
        try {
            session.callback.endOfSpeech();
            Log.i(TAG, "callback endOfSpeech");
        } catch (RemoteException error) {
            cancelSession(session);
        }
    }
    private void sendSuccess(Session session, String text) {
        Runnable finalCallback = () -> {
            if (!claimCompletion(session)) {
                return;
            }
            try {
                session.callback.results(makeResults(text, 0.99f));
                Log.i(TAG, "callback results chars=" + text.length());
            } catch (RemoteException error) {
                Log.w(TAG, "client disappeared before final result");
            } catch (RuntimeException error) {
                Log.w(TAG, "client rejected the final result: "
                        + error.getClass().getSimpleName());
            }
        };
        session.finalCallback = finalCallback;
        mainHandler.post(finalCallback);
    }

    private void finishError(Session session, int speechRecognizerError, String reason) {
        if (!claimCompletion(session)) {
            return;
        }
        try {
            session.callback.error(speechRecognizerError);
            Log.i(TAG, "callback error=" + speechRecognizerError + " reason=" + reason);
        } catch (RemoteException error) {
            Log.w(TAG, "client disappeared before error callback");
        }
    }

    private boolean claimCompletion(Session session) {
        synchronized (sessionLock) {
            if (activeSession != session || !session.state.complete()) {
                return false;
            }
            activeSession = null;
        }
        session.clearSecret();
        hideOverlay();
        stopMicForeground();
        stopSelf();
        return true;
    }

    private boolean isDeliverable(Session session) {
        synchronized (sessionLock) {
            return activeSession == session
                    && !session.state.isCancelled() && !session.state.isCompleted();
        }
    }

    private Session findSession(Callback callback) {
        synchronized (sessionLock) {
            return activeSession != null && activeSession.callback == callback
                    ? activeSession : null;
        }
    }

    private void cancelSession(Session session) {
        synchronized (sessionLock) {
            if (!session.state.cancel()) {
                return;
            }
            if (activeSession == session) {
                activeSession = null;
            }
        }
        hideOverlay();
        stopMicForeground();
        Runnable pendingFinal = session.finalCallback;
        if (pendingFinal != null) {
            mainHandler.removeCallbacks(pendingFinal);
        }
        session.capture.cancel();
        session.request.cancel();
        session.clearSecret();
    }

    private static Bundle makeResults(String text, float confidence) {
        Bundle bundle = new Bundle();
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,
                new ArrayList<>(Collections.singletonList(text)));
        bundle.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES,
                new float[]{confidence});
        return bundle;
    }

    private static int mapApiError(Transcription.ErrorKind kind) {
        switch (kind) {
            case NETWORK:
                return SpeechRecognizer.ERROR_NETWORK;
            case TIMEOUT:
                return SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
            case NO_MATCH:
                return SpeechRecognizer.ERROR_NO_MATCH;
            case SERVER:
                return SpeechRecognizer.ERROR_SERVER;
            case AUTH:
            case INVALID_REQUEST:
            default:
                return SpeechRecognizer.ERROR_CLIENT;
        }
    }

    private static void sendDirectError(Callback callback, int errorCode) {
        try {
            callback.error(errorCode);
        } catch (RemoteException ignored) {
        }
    }

    private static String callerDescription(Callback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return Api31.describeCaller(callback);
        }
        return "uid=" + callback.getCallingUid();
    }

    private boolean isAllowedCaller(Callback callback) {
        String attributedPackage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? Api31.callerPackage(callback) : null;
        String[] packages = getPackageManager().getPackagesForUid(callback.getCallingUid());
        return CallerPolicy.isAllowed(attributedPackage, packages,
                new AppPreferences(this).getAllowedCallerPackages());
    }

    @Override
    public void onDestroy() {
        Session session;
        synchronized (sessionLock) {
            session = activeSession;
        }
        if (session != null && !session.state.isStopRequested() && !session.uploadStarted) {
            cancelSession(session);
        } else if (session != null) {
            Log.i(TAG, "destroy while finishing: letting the upload complete");
        }
        if (worker != null) {
            // shutdown вместо shutdownNow: не прерываем уже идущую отправку
            worker.shutdown();
        }
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    private static final class Session {
        final Callback callback;
        // A strong reference keeps microphone attribution valid for the session lifetime.
        final Context attributionContext;
        final AudioCapture capture;
        final Transcription.Request request;
        final Transcription.Config apiConfig;
        volatile String apiKey;
        final RecognitionSessionState state = new RecognitionSessionState();
        volatile boolean uploadStarted;
        volatile boolean endSent;
        volatile Runnable finalCallback;
        final long startedAt = SystemClock.elapsedRealtime();

        Session(Callback callback, Context attributionContext, AudioCapture capture,
                Transcription.Request request, Transcription.Config apiConfig,
                String apiKey) {
            this.callback = callback;
            this.attributionContext = attributionContext;
            this.capture = capture;
            this.request = request;
            this.apiConfig = apiConfig;
            this.apiKey = apiKey;
        }

        void clearSecret() {
            apiKey = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static final class Api31 {
        private Api31() {
        }

        static Context createAttributionContext(
                DictateRecognitionService service, Callback callback) {
            return service.createContext(new ContextParams.Builder()
                    .setNextAttributionSource(callback.getCallingAttributionSource())
                    .build());
        }

        static String describeCaller(Callback callback) {
            return callback.getCallingAttributionSource().getPackageName()
                    + "/" + callback.getCallingUid();
        }

        static String callerPackage(Callback callback) {
            return callback.getCallingAttributionSource().getPackageName();
        }
    }
}
