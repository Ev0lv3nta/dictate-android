package io.github.ev0lv3nta.dictate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Диктофон внутри приложения.
 *
 * Экран нужен, чтобы записать голос без клавиатуры и прогонять одну и ту же
 * запись через разные модели: только так сравнение честное. Здесь же лежат
 * записи, пришедшие с диктовки, — последние десять, новые сверху.
 *
 * Модель берётся сохранённая, а не «черновик» из настроек: экран настроек
 * применяет форму кнопкой, и было бы странно распознавать тем, что там ещё
 * не сохранили.
 */
public final class RecorderActivity extends Activity {

    private static final int REQUEST_MICROPHONE = 702;
    private static final int TIMER_INTERVAL_MILLIS = 100;
    private static final int TEXT_COLLAPSED_LINES = 4;
    /** Те же пороги обрезки тишины, что и у диктовки: звук на входе один. */
    private static final int TRIM_THRESHOLD_DB = -50;
    private static final int TRIM_KEEP_MILLIS = 300;

    private AppPreferences appPreferences;
    private SecureApiKeyStore apiKeyStore;
    private RecordingLibrary library;

    private final ExecutorService captureWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService networkWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService playbackWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile AudioCapture capture;
    private RecordingPlayer player;
    private String playingId;
    /** Записи, по которым сейчас идёт запрос: кнопка у них занята. */
    private final Set<String> running = new HashSet<>();
    private Transcription.Request pendingRequest;
    /** Развёрнутые карточки: длинный текст сворачивается до четырёх строк. */
    private final Set<String> expanded = new HashSet<>();

    private ImageButton record;
    private TextView timer;
    private View level;
    private TextView recordHint;
    private TextView targetModel;
    private TextView listMeta;
    private TextView empty;
    private LinearLayout list;

    private boolean recording;
    private long recordingStartedAt;
    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (!recording) {
                return;
            }
            timer.setText(formatDuration(System.currentTimeMillis() - recordingStartedAt));
            mainHandler.postDelayed(this, TIMER_INTERVAL_MILLIS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appPreferences = new AppPreferences(this);
        apiKeyStore = new SecureApiKeyStore(this);
        library = new RecordingLibrary(this);
        setContentView(R.layout.activity_recorder);
        findViewById(android.R.id.content).setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        record = findViewById(R.id.record);
        timer = findViewById(R.id.timer);
        level = findViewById(R.id.level);
        recordHint = findViewById(R.id.record_hint);
        targetModel = findViewById(R.id.target_model);
        listMeta = findViewById(R.id.list_meta);
        empty = findViewById(R.id.empty);
        list = findViewById(R.id.list);

        findViewById(R.id.back).setOnClickListener(view -> finish());
        record.setOnClickListener(view -> toggleRecording());
        renderIdle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderTarget();
        renderList();
        if (!recording) {
            renderIdle();
        }
    }

    @Override
    protected void onPause() {
        // Уходя с экрана, Android глушит микрофон приложению без
        // foreground-сервиса. Дописывать нечего — останавливаем и сохраняем.
        if (recording) {
            stopRecording();
        }
        stopPlayback();
        super.onPause();
    }

    // --- Запись ---

    private void toggleRecording() {
        if (!appPreferences.isHistoryEnabled()) {
            startActivity(new android.content.Intent(this, HomeActivity.class));
            return;
        }
        if (recording) {
            stopRecording();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_MICROPHONE);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        stopPlayback();
        int maxMillis = appPreferences.getMaxRecordingSeconds() * 1000;
        // Останавливаем только руками: пауза в мысли не должна обрывать запись,
        // поэтому автостоп и таймаут «нет речи» здесь выключены.
        AudioCapture.Config config = new AudioCapture.Config(false, maxMillis, maxMillis,
                maxMillis, appPreferences.getSpeechThresholdDb());
        final AudioCapture active = new AudioCapture(this, config);
        if (!OperationGate.acquire(active)) { toast(getString(R.string.error_busy)); return; }
        capture = active;

        recording = true;
        recordingStartedAt = System.currentTimeMillis();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        record.setBackgroundResource(R.drawable.mic_button_live);
        record.setImageResource(R.drawable.ic_stop);
        recordHint.setText(R.string.recorder_hint_live);
        timer.setText(R.string.recorder_zero);
        mainHandler.post(timerTick);

        captureWorker.execute(() -> {
            AudioCapture.Result result = null;
            String failure = null;
            try {
                result = active.record(new AudioCapture.Listener() {
                    @Override
                    public void onReady() {
                    }

                    @Override
                    public void onBeginningOfSpeech() {
                    }

                    @Override
                    public void onRms(float normalizedRms) {
                        mainHandler.post(() -> renderLevel(normalizedRms));
                    }
                });
            } catch (AudioCapture.CaptureException error) {
                failure = getString(R.string.recorder_failed);
            } catch (SecurityException error) {
                failure = getString(R.string.permission_microphone_missing);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                OperationGate.release(active);
            }

            final RecordingLibrary.Entry captured = active.isCancelled() || !appPreferences.isHistoryEnabled()
                    ? null : saveCaptured(result, config.speechThresholdDb);
            final boolean limited = result != null && result.stopReason == AudioCapture.StopReason.MAX_DURATION;
            final String captureFailure = failure;
            mainHandler.post(() -> finishRecording(captured, limited, captureFailure));
        });
    }

    private void stopRecording() {
        AudioCapture active = capture;
        if (active != null) {
            active.requestStop();
        }
    }

    private void finishRecording(RecordingLibrary.Entry entry, boolean limited, String failure) {
        capture = null;
        recording = false;
        mainHandler.removeCallbacks(timerTick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (isFinishing() || isDestroyed()) {
            return;
        }
        renderIdle();

        if (failure != null) {
            toast(failure);
            return;
        }
        if (entry == null) {
            toast(getString(R.string.recorder_failed));
            return;
        }
        recordHint.setText(limited
                ? getString(R.string.recorder_limit)
                : getString(R.string.recorder_hint_saved));
        renderList();
    }

    /** Обрезаем тишину по краям: пауза между нажатием и первым словом ни к чему. */
    private RecordingLibrary.Entry saveCaptured(AudioCapture.Result result, int threshold) {
        if (result == null || result.pcm == null || result.pcm.length == 0) {
            return null;
        }
        byte[] pcm = PcmSilenceTrimmer.trimEdges(result.pcm, AudioCapture.SAMPLE_RATE,
                threshold, TRIM_KEEP_MILLIS).pcm;
        if (pcm.length * 1000L / (AudioCapture.SAMPLE_RATE * 2L) < 200L) {
            return null;
        }
        return library.add(pcm, RecordingLibrary.SOURCE_APP);
    }

    // --- Список ---

    private void renderIdle() {
        record.setBackgroundResource(R.drawable.mic_button);
        record.setImageResource(R.drawable.ic_mic);
        record.setContentDescription(getString(R.string.recorder_start));
        timer.setText(R.string.recorder_zero);
        renderLevel(0f);
        boolean granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        recordHint.setText(granted
                ? R.string.recorder_hint_idle
                : R.string.recorder_hint_permission);
    }

    private void renderLevel(float normalizedRms) {
        int width = ((View) level.getParent()).getWidth();
        if (width <= 0) {
            return;
        }
        float fraction = Math.max(0f, Math.min(1f, normalizedRms / 10f));
        ViewGroup.LayoutParams params = level.getLayoutParams();
        params.width = Math.round(width * fraction);
        level.setLayoutParams(params);
    }

    private void renderTarget() {
        String providerId = appPreferences.getProvider();
        String modelId = appPreferences.getModel(providerId);
        ModelCatalog.Provider provider = ModelCatalog.provider(providerId);
        String modelTitle = provider.hasModel(modelId)
                ? provider.model(modelId).title : modelId;
        targetModel.setText(getString(R.string.recorder_target) + ": "
                + modelTitle + " · " + provider.title);
    }

    private void renderList() {
        List<RecordingLibrary.Entry> entries = library.list();
        listMeta.setText(entries.isEmpty() ? "" : getString(R.string.history_count, entries.size(), RecordingLibrary.MAX_ENTRIES));
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (RecordingLibrary.Entry entry : entries) {
            list.addView(buildRow(inflater, entry));
        }
    }

    private View buildRow(LayoutInflater inflater, RecordingLibrary.Entry entry) {
        View row = inflater.inflate(R.layout.item_recording, list, false);
        ((TextView) row.findViewById(R.id.when)).setText(moment(entry.createdAt));
        ((TextView) row.findViewById(R.id.duration))
                .setText(formatDuration(entry.durationMillis));

        LinearLayout tags = row.findViewById(R.id.tags);
        tags.removeAllViews();
        addTag(tags, getString(entry.fromKeyboard()
                ? R.string.recording_source_keyboard
                : R.string.recording_source_app), false);
        if (entry.hasText() && entry.textModel != null && !entry.textModel.isEmpty()) {
            addTag(tags, modelTitle(entry.textProvider, entry.textModel), true);
        }

        TextView text = row.findViewById(R.id.text);
        boolean open = expanded.contains(entry.id);
        text.setVisibility(entry.hasText() ? View.VISIBLE : View.GONE);
        if (entry.hasText()) {
            text.setText(entry.text);
            text.setMaxLines(open ? Integer.MAX_VALUE : TEXT_COLLAPSED_LINES);
            text.setOnClickListener(view -> {
                if (!expanded.remove(entry.id)) {
                    expanded.add(entry.id);
                }
                renderList();
            });
        }

        Button play = row.findViewById(R.id.play);
        if (getResources().getConfiguration().fontScale > 1.3f
                || getResources().getConfiguration().screenWidthDp < 360) {
            LinearLayout actions = row.findViewById(R.id.recording_actions);
            actions.setOrientation(LinearLayout.VERTICAL);
            for (int i=0;i<actions.getChildCount();i++) {
                actions.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
        }
        boolean playing = entry.id.equals(playingId);
        play.setText(playing ? R.string.recording_stop : R.string.recording_play);
        play.setOnClickListener(view -> {
            if (entry.id.equals(playingId)) {
                stopPlayback();
            } else {
                startPlayback(entry);
            }
        });

        Button recognize = row.findViewById(R.id.recognize);
        boolean busy = running.contains(entry.id);
        recognize.setText(busy ? R.string.recording_running : R.string.recording_recognize);
        recognize.setEnabled(!busy);
        recognize.setAlpha(busy ? 0.5f : 1f);
        recognize.setOnClickListener(view -> recognize(entry));

        Button copy = row.findViewById(R.id.copy);
        copy.setEnabled(entry.hasText());
        copy.setAlpha(entry.hasText() ? 1f : 0.4f);
        copy.setOnClickListener(view -> copyText(entry.text));

        row.findViewById(R.id.delete).setOnClickListener(view -> confirmDelete(entry));
        return row;
    }

    private void addTag(LinearLayout container, String label, boolean accent) {
        TextView tag = new TextView(this);
        tag.setText(label);
        tag.setTextSize(11f);
        tag.setSingleLine(true);
        tag.setBackgroundResource(accent ? R.drawable.tag : R.drawable.tag_muted);
        tag.setTextColor(getColor(accent ? R.color.accent : R.color.ink_3));
        tag.setPadding(dp(7), dp(3), dp(7), dp(3));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (container.getChildCount() > 0) {
            params.setMarginStart(dp(6));
        }
        container.addView(tag, params);
    }

    private String modelTitle(String providerId, String modelId) {
        ModelCatalog.Provider provider = ModelCatalog.provider(providerId);
        return provider.hasModel(modelId) ? provider.model(modelId).title : modelId;
    }

    // --- Действия над записью ---

    private void startPlayback(RecordingLibrary.Entry entry) {
        stopPlayback();
        final RecordingPlayer active = new RecordingPlayer(this);
        player = active;
        playingId = entry.id;
        renderList();
        playbackWorker.execute(() -> {
            byte[] pcm;
            try {
                pcm = library.load(entry.id);
            } catch (IOException error) {
                mainHandler.post(() -> {
                    toast(getString(R.string.recorder_failed));
                    clearPlayback(active);
                });
                return;
            }
            active.play(pcm, new RecordingPlayer.Listener() {
                @Override
                public void onProgress(float fraction) {
                }

                @Override
                public void onFinished() {
                    mainHandler.post(() -> clearPlayback(active));
                }
            });
        });
    }

    private void stopPlayback() {
        RecordingPlayer active = player;
        if (active != null) {
            active.stop();
        }
    }

    private void clearPlayback(RecordingPlayer active) {
        if (player != active) {
            return;
        }
        player = null;
        playingId = null;
        if (!isFinishing() && !isDestroyed()) {
            renderList();
        }
    }

    /**
     * Гоняем через модель, выбранную и сохранённую в настройках сейчас, —
     * смысл кнопки в том, чтобы попробовать другую на том же звуке.
     */
    private void recognize(RecordingLibrary.Entry entry) {
        if (!running.isEmpty()) {
            return;
        }
        final String providerId = appPreferences.getProvider();
        final String modelId = appPreferences.getModel(providerId);
        final String apiKey = apiKeyStore.load(providerId);
        final Transcription.Config config = new Transcription.Config(providerId, modelId,
                appPreferences.getLanguageOverride(), appPreferences.getKeyterms());
        final Transcription.Request request = new Transcription.Request();
        if (!OperationGate.acquire(request)) { toast(getString(R.string.error_busy)); return; }
        pendingRequest = request;

        running.add(entry.id);
        renderList();

        networkWorker.execute(() -> {
            String text = null;
            String failure = null;
            try {
                text = Transcription.clientFor(providerId)
                        .transcribe(library.load(entry.id), apiKey, config, request);
            } catch (Transcription.ApiException error) {
                failure = Transcription.userMessage(this, error.kind);
            } catch (IOException error) {
                failure = getString(R.string.error_read_recording);
            } catch (RuntimeException error) {
                failure = getString(R.string.error_recognize);
            } finally {
                OperationGate.release(request);
            }
            if (text != null && !request.isCancelled()) {
                library.setText(entry.id, text, providerId, modelId);
            }
            final String resultFailure = failure;
            final boolean ok = text != null;
            mainHandler.post(() -> {
                running.remove(entry.id);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!ok) {
                    toast(resultFailure == null ? getString(R.string.error_recognize)
                            : Transcription.limit(resultFailure));
                } else {
                    expanded.add(entry.id);
                }
                renderList();
            });
        });
    }

    private void copyText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Dictate", text));
        Toast.makeText(this, R.string.recording_copied, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(RecordingLibrary.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.recording_delete)
                .setMessage(R.string.recording_delete_question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (entry.id.equals(playingId)) {
                        stopPlayback();
                    }
                    library.delete(entry.id);
                    expanded.remove(entry.id);
                    toast(getString(R.string.recording_deleted));
                    renderList();
                })
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_MICROPHONE) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            renderIdle();
        }
    }

    @Override
    protected void onDestroy() {
        AudioCapture active = capture;
        if (active != null) {
            active.cancel();
        }
        stopPlayback();
        Transcription.Request pending = pendingRequest;
        if (pending != null) {
            pending.cancel();
        }
        mainHandler.removeCallbacks(timerTick);
        captureWorker.shutdownNow();
        networkWorker.shutdownNow();
        playbackWorker.shutdownNow();
        super.onDestroy();
    }

    // --- Формат ---

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        return String.format(new Locale("ru"), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /** Сегодняшние записи различает время, вчерашние и старше — ещё и дата. */
    private String moment(long at) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(at);
        boolean sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        String clock = new SimpleDateFormat("HH:mm", new Locale("ru")).format(new Date(at));
        if (sameDay) {
            return getString(R.string.history_today, clock) + " · " + DateUtils.getRelativeTimeSpanString(
                    at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    .toString().toLowerCase(new Locale("ru"));
        }
        return new SimpleDateFormat("d MMMM, HH:mm", new Locale("ru")).format(new Date(at));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
