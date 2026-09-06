package io.github.ev0lv3nta.dictate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.format.DateUtils;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Единственный экран приложения: провайдер, модель, параметры записи и словарь.
 *
 * Разметка живёт в res/layout, здесь — только состояние формы. Форма правится
 * свободно и применяется одной кнопкой внизу, поэтому все изменения до нажатия
 * «Сохранить» держатся в полях этого класса, а не в SharedPreferences.
 */
public final class SettingsActivity extends Activity {

    private static final int REQUEST_MICROPHONE = 701;
    private static final int MAX_VISIBLE_CHIPS = 12;
    private static final int RECORDING_STEP_SECONDS = 30;

    private static final String[] LANGUAGE_CODES = {
            "", "ru", "en", "uk", "de", "fr", "es", "it", "pt", "pl", "tr", "zh", "ja", "ko"};

    private AppPreferences appPreferences;
    private SecureApiKeyStore apiKeyStore;
    private RecordingLibrary library;

    // Правки формы до нажатия «Сохранить».
    private String provider;
    /** Выбранная модель по каждому провайдеру: переключение вкладок её не теряет. */
    private final Map<String, String> models = new HashMap<>();
    private String language;
    private String keyterms;
    private String allowedCallers;
    private int maxRecordingSeconds;

    private LinearLayout statusRow;
    private LinearLayout segmented;
    private LinearLayout modelsCard;
    private TextView providerHint;
    private TextView keyTitle;
    private TextView keySubtitle;
    private Button keyAction;
    private Switch autoStop;
    private SeekBar silenceSeek;
    private TextView silenceValue;
    private View silenceBlock;
    private View silenceDivider;
    private SeekBar thresholdSeek;
    private TextView thresholdValue;
    private TextView maxValue;
    private TextView languageValue;
    private TextView lastRun;
    private TextView recordingMeta;
    private TextView recordingHint;
    private TextView keytermsHint;
    private TextView allowedCallersValue;
    private LinearLayout keytermsCard;
    private LinearLayout keytermsChips;
    private Button save;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        appPreferences = new AppPreferences(this);
        apiKeyStore = new SecureApiKeyStore(this);
        library = new RecordingLibrary(this);
        setContentView(R.layout.activity_settings);
        bindViews();
        loadSettingsIntoForm();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusRow != null) {
            // Диктовка происходит, пока экран настроек в фоне: обновляем на возврате.
            renderLastRun();
            renderRecording();
            renderStatus();
        }
    }

    private void bindViews() {
        statusRow = findViewById(R.id.status_row);
        segmented = findViewById(R.id.segmented);
        modelsCard = findViewById(R.id.models);
        providerHint = findViewById(R.id.provider_hint);
        keyTitle = findViewById(R.id.key_title);
        keySubtitle = findViewById(R.id.key_subtitle);
        keyAction = findViewById(R.id.key_action);
        autoStop = findViewById(R.id.auto_stop);
        silenceSeek = findViewById(R.id.silence_seek);
        silenceValue = findViewById(R.id.silence_value);
        silenceBlock = findViewById(R.id.silence_block);
        silenceDivider = findViewById(R.id.divider_silence);
        thresholdSeek = findViewById(R.id.threshold_seek);
        thresholdValue = findViewById(R.id.threshold_value);
        maxValue = findViewById(R.id.max_value);
        languageValue = findViewById(R.id.language_value);
        lastRun = findViewById(R.id.last_run);
        recordingMeta = findViewById(R.id.recording_meta);
        recordingHint = findViewById(R.id.recording_hint);
        keytermsHint = findViewById(R.id.keyterms_hint);
        allowedCallersValue = findViewById(R.id.allowed_callers_value);
        keytermsCard = findViewById(R.id.keyterms_card);
        keytermsChips = findViewById(R.id.keyterms_chips);
        save = findViewById(R.id.save);

        ((TextView) findViewById(R.id.version)).setText(versionLine());
        ((TextView) findViewById(R.id.footer)).setText(R.string.footer);

        keyAction.setText(R.string.key_replace);
        keyAction.setOnClickListener(view -> showKeyDialog());
        findViewById(R.id.language_row).setOnClickListener(view -> showLanguageDialog());
        keytermsCard.setOnClickListener(view -> showKeytermsDialog());
        findViewById(R.id.keyterms_edit).setOnClickListener(view -> showKeytermsDialog());
        findViewById(R.id.max_minus).setOnClickListener(view -> stepRecording(-1));
        findViewById(R.id.max_plus).setOnClickListener(view -> stepRecording(1));
        save.setOnClickListener(view -> saveSettings());
        findViewById(R.id.recording_open).setOnClickListener(
                view -> startActivity(new Intent(this, RecorderActivity.class)));
        findViewById(R.id.allowed_callers_card).setOnClickListener(
                view -> showAllowedCallersDialog());

        autoStop.setOnCheckedChangeListener((button, checked) -> renderAutoStopState());
        silenceSeek.setOnSeekBarChangeListener(new ProgressListener(
                progress -> silenceValue.setText(silenceMillis() + " мс")));
        thresholdSeek.setOnSeekBarChangeListener(new ProgressListener(
                progress -> thresholdValue.setText(formatDb(thresholdDb()))));
    }

    private void loadSettingsIntoForm() {
        provider = appPreferences.getProvider();
        language = appPreferences.getLanguageOverride();
        keyterms = appPreferences.getKeytermsText();
        allowedCallers = appPreferences.getAllowedCallersText();
        maxRecordingSeconds = appPreferences.getMaxRecordingSeconds();

        autoStop.setChecked(appPreferences.isAutoStopEnabled());
        silenceSeek.setProgress((appPreferences.getSilenceMillis()
                - AppPreferences.MIN_SILENCE_MILLIS) / 100);
        thresholdSeek.setProgress(appPreferences.getSpeechThresholdDb()
                - AppPreferences.MIN_THRESHOLD_DB);

        renderSegmented();
        renderProviderSection();
        renderAutoStopState();
        renderRecordingValues();
        renderLanguage();
        renderKeyterms();
        renderAllowedCallers();
        renderLastRun();
        renderRecording();
        renderStatus();
    }

    // --- Провайдер и модели ---

    private void renderSegmented() {
        segmented.removeAllViews();
        List<ModelCatalog.Provider> providers = ModelCatalog.providers();
        for (int index = 0; index < providers.size(); index++) {
            final ModelCatalog.Provider item = providers.get(index);
            TextView tab = new TextView(this);
            tab.setText(item.title);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(13.5f);
            tab.setSingleLine(true);
            tab.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            tab.setBackgroundResource(R.drawable.seg_item);
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(view -> selectProvider(item.id));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, dp(36), 1f);
            if (index > 0) {
                params.setMarginStart(dp(3));
            }
            segmented.addView(tab, params);
        }
        renderSegmentedState();
    }

    private void renderSegmentedState() {
        List<ModelCatalog.Provider> providers = ModelCatalog.providers();
        for (int index = 0; index < segmented.getChildCount(); index++) {
            TextView tab = (TextView) segmented.getChildAt(index);
            boolean selected = providers.get(index).id.equals(provider);
            tab.setActivated(selected);
            tab.setTextColor(getColor(selected ? R.color.ink : R.color.ink_2));
        }
    }

    private void selectProvider(String providerId) {
        if (providerId.equals(provider)) {
            return;
        }
        provider = providerId;
        renderSegmentedState();
        renderProviderSection();
        renderKeytermsHint();
        renderStatus();
    }

    private void renderProviderSection() {
        ModelCatalog.Provider current = ModelCatalog.provider(provider);
        renderModels(current);
        renderKeyCard(current);
        renderSaveLabel();
        // Состояние ключа и так подписано на карточке ниже, поэтому здесь —
        // расшифровка правой колонки списка моделей.
        providerHint.setText(current.hint);
    }

    /** Текущий выбор модели: правка формы, если она была, иначе сохранённое. */
    private String selectedModel(String providerId) {
        String pending = models.get(providerId);
        return pending != null ? pending : appPreferences.getModel(providerId);
    }

    private void renderModels(ModelCatalog.Provider current) {
        modelsCard.removeAllViews();
        String selectedModel = selectedModel(current.id);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int index = 0; index < current.models.size(); index++) {
            final ModelCatalog.Model model = current.models.get(index);
            if (index > 0) {
                modelsCard.addView(divider());
            }
            View row = inflater.inflate(R.layout.item_model, modelsCard, false);
            ((TextView) row.findViewById(R.id.model_title)).setText(model.title);
            ((TextView) row.findViewById(R.id.model_id)).setText(model.id);
            TextView badge = row.findViewById(R.id.model_badge);
            badge.setText(model.badge);
            badge.setVisibility(model.badge.isEmpty() ? View.GONE : View.VISIBLE);
            TextView latency = row.findViewById(R.id.model_latency);
            latency.setText(model.latency);
            latency.setVisibility(model.latency.isEmpty() ? View.GONE : View.VISIBLE);
            ((TextView) row.findViewById(R.id.model_note)).setText(model.note);
            row.findViewById(R.id.model_radio).setActivated(model.id.equals(selectedModel));
            row.setOnClickListener(view -> selectModel(model.id));
            modelsCard.addView(row);
        }
    }

    private void selectModel(String modelId) {
        models.put(provider, modelId);
        renderModels(ModelCatalog.provider(provider));
        renderSaveLabel();
        renderKeytermsHint();
    }

    private void renderKeyCard(ModelCatalog.Provider current) {
        boolean custom = apiKeyStore.hasCustomKey(provider);
        keyTitle.setText(current.keyLabel);
        keySubtitle.setText(custom
                ? getString(R.string.key_saved_state) + " · "
                        + SecureApiKeyStore.mask(apiKeyStore.load(provider))
                : getString(R.string.key_missing));
    }

    private void showKeyDialog() {
        ModelCatalog.Provider current = ModelCatalog.provider(provider);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        EditText input = content.findViewById(R.id.dialog_input);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setHint(current.keyHint);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(current.keyLabel)
                .setMessage(R.string.key_dialog_hint)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try {
                        apiKeyStore.save(provider, input.getText().toString());
                        toast(getString(R.string.key_saved));
                    } catch (Exception error) {
                        toast(message(error, "Не удалось сохранить ключ"));
                    }
                    renderProviderSection();
                    renderStatus();
                });
        if (apiKeyStore.hasCustomKey(provider)) {
            builder.setNeutralButton(R.string.key_reset, (dialog, which) -> confirmKeyReset());
        }
        builder.show();
    }

    private void confirmKeyReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.key_reset)
                .setMessage(R.string.key_reset_question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    apiKeyStore.clear(provider);
                    toast(getString(R.string.key_reset_done));
                    renderProviderSection();
                    renderStatus();
                })
                .show();
    }

    private void renderAllowedCallers() {
        int count = AppPreferences.parsePackageNames(allowedCallers).size();
        allowedCallersValue.setText(count == 0
                ? getString(R.string.allowed_callers_none)
                : getResources().getQuantityString(
                        R.plurals.allowed_callers_count, count, count));
    }

    private void showAllowedCallersDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        EditText input = content.findViewById(R.id.dialog_input);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(5);
        input.setMaxLines(10);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint(R.string.allowed_callers_example);
        input.setText(allowedCallers);

        new AlertDialog.Builder(this)
                .setTitle(R.string.allowed_callers_title)
                .setMessage(R.string.allowed_callers_dialog_hint)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = input.getText().toString();
                    try {
                        AppPreferences.parsePackageNamesStrict(value);
                    } catch (IllegalArgumentException error) {
                        toast(message(error, "Некорректный список приложений"));
                        return;
                    }
                    allowedCallers = value;
                    renderAllowedCallers();
                })
                .show();
    }

    // --- Запись ---

    private void renderAutoStopState() {
        boolean enabled = autoStop.isChecked();
        silenceBlock.setVisibility(enabled ? View.VISIBLE : View.GONE);
        silenceDivider.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void renderRecordingValues() {
        silenceValue.setText(silenceMillis() + " мс");
        thresholdValue.setText(formatDb(thresholdDb()));
        maxValue.setText(maxRecordingSeconds + " с");
    }

    private void stepRecording(int direction) {
        int next = maxRecordingSeconds + direction * RECORDING_STEP_SECONDS;
        maxRecordingSeconds = Math.max(AppPreferences.MIN_RECORDING_SECONDS,
                Math.min(AppPreferences.MAX_RECORDING_SECONDS, next));
        maxValue.setText(maxRecordingSeconds + " с");
    }

    private int silenceMillis() {
        return AppPreferences.MIN_SILENCE_MILLIS + silenceSeek.getProgress() * 100;
    }

    private int thresholdDb() {
        return AppPreferences.MIN_THRESHOLD_DB + thresholdSeek.getProgress();
    }

    private static String formatDb(int value) {
        return "−" + Math.abs(value) + " dB";
    }

    private void renderLanguage() {
        languageValue.setText(language.isEmpty()
                ? getString(R.string.language_auto) : languageTitle(language));
    }

    private void showLanguageDialog() {
        final String[] titles = new String[LANGUAGE_CODES.length + 1];
        for (int index = 0; index < LANGUAGE_CODES.length; index++) {
            titles[index] = LANGUAGE_CODES[index].isEmpty()
                    ? getString(R.string.language_auto) : languageTitle(LANGUAGE_CODES[index]);
        }
        titles[LANGUAGE_CODES.length] = getString(R.string.language_other);

        new AlertDialog.Builder(this)
                .setTitle(R.string.language_label)
                .setItems(titles, (dialog, which) -> {
                    if (which == LANGUAGE_CODES.length) {
                        showCustomLanguageDialog();
                        return;
                    }
                    language = LANGUAGE_CODES[which];
                    renderLanguage();
                })
                .show();
    }

    private void showCustomLanguageDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        EditText input = content.findViewById(R.id.dialog_input);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setHint(R.string.language_dialog_hint);
        input.setText(language);

        new AlertDialog.Builder(this)
                .setTitle(R.string.language_label)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = AppPreferences.normalizeLanguage(
                            input.getText().toString());
                    if (!input.getText().toString().trim().isEmpty() && value.isEmpty()) {
                        toast("Язык должен быть кодом из 2–3 латинских букв");
                        return;
                    }
                    language = value;
                    renderLanguage();
                })
                .show();
    }

    private static String languageTitle(String code) {
        String name = new Locale(code).getDisplayLanguage(new Locale("ru"));
        if (name == null || name.isEmpty() || name.equals(code)) {
            return code;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " · " + code;
    }

    // --- Словарь ---

    private void renderKeyterms() {
        renderKeytermsHint();
        final List<String> terms = AppPreferences.parseKeyterms(keyterms);
        keytermsChips.removeAllViews();
        if (terms.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.keyterms_empty);
            empty.setTextSize(13f);
            empty.setTextColor(getColor(R.color.ink_3));
            keytermsChips.addView(empty);
            return;
        }
        // Ширина карточки известна только после разметки, а чипы надо разложить
        // по строкам вручную: во фреймворке нет контейнера с переносом.
        keytermsChips.post(() -> layoutChips(terms));
    }

    private void layoutChips(List<String> terms) {
        int available = keytermsChips.getWidth();
        if (available <= 0) {
            return;
        }
        keytermsChips.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        List<View> chips = new ArrayList<>();
        int shown = Math.min(terms.size(), MAX_VISIBLE_CHIPS);
        for (int index = 0; index < shown; index++) {
            chips.add(chip(inflater, terms.get(index), false));
        }
        if (terms.size() > shown) {
            chips.add(chip(inflater, "ещё " + (terms.size() - shown), true));
        }

        LinearLayout row = chipRow();
        int used = 0;
        for (View chip : chips) {
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int width = chip.getMeasuredWidth() + dp(6);
            if (used > 0 && used + width > available) {
                keytermsChips.addView(row);
                row = chipRow();
                used = 0;
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(6));
            row.addView(chip, params);
            used += width;
        }
        if (row.getChildCount() > 0) {
            keytermsChips.addView(row);
        }
    }

    private LinearLayout chipRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        row.setLayoutParams(params);
        return row;
    }

    private View chip(LayoutInflater inflater, String text, boolean muted) {
        TextView chip = (TextView) inflater.inflate(R.layout.item_chip, keytermsChips, false);
        chip.setText(text);
        if (muted) {
            chip.setTextColor(getColor(R.color.ink_3));
        }
        return chip;
    }

    private void renderKeytermsHint() {
        int count = AppPreferences.parseKeyterms(keyterms).size();
        ModelCatalog.Model model = ModelCatalog.model(provider, selectedModel(provider));
        String terms = count + " " + plural(count, "термин", "термина", "терминов");
        keytermsHint.setText(model.supportsKeyterms()
                ? terms : terms + " · " + getString(R.string.keyterms_unsupported));
    }

    private void showKeytermsDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null);
        EditText input = content.findViewById(R.id.dialog_input);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(8);
        input.setMaxLines(12);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHint(R.string.keyterms_dialog_hint);
        input.setText(keyterms);

        new AlertDialog.Builder(this)
                .setTitle(R.string.keyterms_edit)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.keyterms_restore, (dialog, which) -> {
                    keyterms = appPreferences.getDefaultKeytermsText();
                    renderKeyterms();
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = input.getText().toString();
                    try {
                        AppPreferences.parseKeytermsStrict(value);
                    } catch (IllegalArgumentException error) {
                        toast(message(error, "Некорректный словарь"));
                        return;
                    }
                    keyterms = value;
                    renderKeyterms();
                })
                .show();
    }

    // --- Состояние и сохранение ---

    /**
     * Что реально ушло в сеть на прошлой диктовке. Единственный способ увидеть
     * это с телефона, не подключая logcat.
     */
    private void renderLastRun() {
        if (!appPreferences.hasLastRun()) {
            lastRun.setText(R.string.last_run_never);
            return;
        }
        String runProvider = appPreferences.getLastRunProvider();
        String runModel = appPreferences.getLastRunModel();
        ModelCatalog.Provider catalogProvider = ModelCatalog.provider(runProvider);
        String modelTitle = catalogProvider.hasModel(runModel)
                ? catalogProvider.model(runModel).title : runModel;
        lastRun.setText(getString(R.string.last_run_prefix) + ": " + modelTitle
                + " · " + catalogProvider.title
                + " · " + formatSeconds(appPreferences.getLastRunMillis())
                + " · " + appPreferences.getLastRunStatus()
                + ", " + relative(appPreferences.getLastRunAt()));
    }

    private static String formatSeconds(long millis) {
        return String.format(new Locale("ru"), "%.2f с", millis / 1000.0);
    }

    // --- Записи ---

    /**
     * Карточка-указатель на диктофон: сколько записей лежит и когда была
     * последняя. Всё остальное — прослушать, распознать другой моделью,
     * скопировать — делается на отдельном экране.
     */
    private void renderRecording() {
        int count = library.count();
        RecordingLibrary.Entry newest = library.newest();
        recordingMeta.setText(count == 0 ? "" : count + " из " + RecordingLibrary.MAX_ENTRIES);
        if (newest == null) {
            recordingHint.setText(R.string.recordings_empty);
            return;
        }
        recordingHint.setText(getString(R.string.recordings_last) + ": "
                + formatDuration(newest.durationMillis) + " · " + relative(newest.createdAt)
                + ".\n" + getString(R.string.recordings_tail));
    }

    private static String formatDuration(long millis) {
        long totalSeconds = millis / 1000L;
        return String.format(new Locale("ru"), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private String relative(long at) {
        return DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString().toLowerCase(new Locale("ru"));
    }

    private void renderStatus() {
        statusRow.removeAllViews();
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean overlay = Settings.canDrawOverlays(this);

        addPill(microphone, getString(microphone
                        ? R.string.permission_microphone_ok
                        : R.string.permission_microphone_missing),
                microphone ? null : view -> requestPermissions(
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE));
        addPill(overlay, getString(overlay
                        ? R.string.permission_overlay_ok
                        : R.string.permission_overlay_missing),
                overlay ? null : view -> startActivity(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()))));
    }

    private void addPill(boolean ok, String text, View.OnClickListener action) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackgroundResource(ok ? R.drawable.pill : R.drawable.pill_warn);
        pill.setPadding(dp(9), 0, dp(11), 0);
        if (action != null) {
            pill.setOnClickListener(action);
            pill.setClickable(true);
            pill.setFocusable(true);
        }

        View dot = new View(this);
        dot.setBackgroundResource(ok ? R.drawable.dot_ok : R.drawable.dot_warn);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotParams.setMarginEnd(dp(6));
        pill.addView(dot, dotParams);

        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12.5f);
        label.setSingleLine(true);
        label.setTextColor(getColor(ok ? R.color.ink_2 : R.color.ink));
        pill.addView(label);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        if (statusRow.getChildCount() > 0) {
            params.setMarginStart(dp(8));
        }
        statusRow.addView(pill, params);
    }

    private void renderSaveLabel() {
        String modelTitle = ModelCatalog.model(provider, selectedModel(provider)).title;
        String hint = modelTitle + " · " + ModelCatalog.provider(provider).title;
        SpannableStringBuilder label = new SpannableStringBuilder(getString(R.string.save));
        int start = label.length();
        label.append("   ").append(hint);
        int accentInk = getColor(R.color.accent_ink);
        label.setSpan(new RelativeSizeSpan(0.82f), start, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setSpan(new ForegroundColorSpan(Color.argb(185, Color.red(accentInk),
                        Color.green(accentInk), Color.blue(accentInk))),
                start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        save.setText(label);
    }

    private void saveSettings() {
        try {
            appPreferences.save(provider, selectedModel(provider), language, keyterms,
                    allowedCallers, autoStop.isChecked(), silenceMillis(),
                    maxRecordingSeconds, thresholdDb());
            toast(getString(R.string.saved));
            renderStatus();
        } catch (RuntimeException error) {
            toast(message(error, "Не удалось сохранить настройки"));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE) {
            renderStatus();
        }
    }

    private String versionLine() {
        try {
            return "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "";
        }
    }

    private View divider() {
        View view = new View(this);
        view.setBackgroundColor(getColor(R.color.line));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return view;
    }

    private static String plural(int count, String one, String few, String many) {
        int tail = count % 10;
        int hundred = count % 100;
        if (tail == 1 && hundred != 11) {
            return one;
        }
        if (tail >= 2 && tail <= 4 && (hundred < 12 || hundred > 14)) {
            return few;
        }
        return many;
    }

    private static String message(Exception error, String fallback) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** SeekBar без трёх пустых методов на каждый вызов. */
    private static final class ProgressListener implements SeekBar.OnSeekBarChangeListener {
        private final Callback callback;

        interface Callback {
            void onProgress(int progress);
        }

        ProgressListener(Callback callback) {
            this.callback = callback;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            callback.onProgress(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
