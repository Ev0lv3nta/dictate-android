package io.github.ev0lv3nta.dictate;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AppPreferences {

    static final int DEFAULT_SILENCE_MILLIS = 500;
    static final int DEFAULT_MAX_RECORDING_SECONDS = 120;
    static final int DEFAULT_NO_SPEECH_TIMEOUT_MILLIS = 8000;
    static final int DEFAULT_SPEECH_THRESHOLD_DB = -48;

    static final int MIN_SILENCE_MILLIS = 500;
    static final int MAX_SILENCE_MILLIS = 5000;
    static final int MIN_RECORDING_SECONDS = 5;
    static final int MAX_RECORDING_SECONDS = 300;
    static final int MIN_THRESHOLD_DB = -70;
    static final int MAX_THRESHOLD_DB = -30;

    private static final String PREFS = "dictate_settings";
    private static final String KEY_PROVIDER = "provider";
    /** Модель запоминается отдельно для каждого провайдера: KEY_MODEL_PREFIX + id. */
    private static final String KEY_MODEL_PREFIX = "model_";
    private static final String KEY_LEGACY_MODEL = "model";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_KEYTERMS = "keyterms";
    private static final String KEY_AUTO_STOP = "auto_stop";
    private static final String KEY_SILENCE_MILLIS = "silence_millis";
    private static final String KEY_MAX_RECORDING_SECONDS = "max_recording_seconds";
    private static final String KEY_THRESHOLD_DB = "speech_threshold_db";
    private static final String KEY_ALLOWED_CALLERS = "allowed_callers";
    private static final String KEY_LAST_PROVIDER = "last_run_provider";
    private static final String KEY_LAST_MODEL = "last_run_model";
    private static final String KEY_LAST_MILLIS = "last_run_millis";
    private static final String KEY_LAST_STATUS = "last_run_status";
    private static final String KEY_LAST_AT = "last_run_at";

    private final Context context;
    private final SharedPreferences preferences;

    AppPreferences(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String getProvider() {
        String value = preferences.getString(KEY_PROVIDER, ModelCatalog.defaultProvider());
        return ModelCatalog.isKnownProvider(value) ? value : ModelCatalog.defaultProvider();
    }

    String getModel() {
        return getModel(getProvider());
    }

    String getModel(String providerId) {
        ModelCatalog.Provider provider = ModelCatalog.provider(providerId);
        String value = preferences.getString(KEY_MODEL_PREFIX + provider.id, null);
        if (value == null && ModelCatalog.PROVIDER_ELEVENLABS.equals(provider.id)) {
            // Настройка из версии с единственным провайдером.
            value = preferences.getString(KEY_LEGACY_MODEL, null);
        }
        return value != null && provider.hasModel(value) ? value : provider.defaultModel();
    }

    String getLanguageOverride() {
        String value = preferences.getString(KEY_LANGUAGE, "");
        return value == null ? "" : normalizeLanguage(value);
    }

    /** Стандартный список из res/raw: к нему можно вернуться после чистки. */
    String getDefaultKeytermsText() {
        return readDefaultKeyterms();
    }

    String getKeytermsText() {
        if (preferences.contains(KEY_KEYTERMS)) {
            String saved = preferences.getString(KEY_KEYTERMS, "");
            return saved == null ? "" : saved;
        }
        return readDefaultKeyterms();
    }

    List<String> getKeyterms() {
        return parseKeyterms(getKeytermsText());
    }

    boolean isAutoStopEnabled() {
        return preferences.getBoolean(KEY_AUTO_STOP, true);
    }

    int getSilenceMillis() {
        return clamp(preferences.getInt(KEY_SILENCE_MILLIS, DEFAULT_SILENCE_MILLIS),
                MIN_SILENCE_MILLIS, MAX_SILENCE_MILLIS);
    }

    int getMaxRecordingSeconds() {
        return clamp(preferences.getInt(KEY_MAX_RECORDING_SECONDS,
                DEFAULT_MAX_RECORDING_SECONDS), MIN_RECORDING_SECONDS, MAX_RECORDING_SECONDS);
    }

    int getSpeechThresholdDb() {
        return clamp(preferences.getInt(KEY_THRESHOLD_DB, DEFAULT_SPEECH_THRESHOLD_DB),
                MIN_THRESHOLD_DB, MAX_THRESHOLD_DB);
    }

    String getAllowedCallersText() {
        String value = preferences.getString(KEY_ALLOWED_CALLERS, "");
        return value == null ? "" : value;
    }

    Set<String> getAllowedCallerPackages() {
        return parsePackageNames(getAllowedCallersText());
    }

    void save(String provider, String model, String language, String keyterms,
              String allowedCallers, boolean autoStop, int silenceMillis,
              int maxRecordingSeconds, int thresholdDb) {
        if (!ModelCatalog.isKnownProvider(provider)) {
            throw new IllegalArgumentException("Неизвестный провайдер");
        }
        if (!isValidModel(model)) {
            throw new IllegalArgumentException("Некорректный Model ID");
        }
        String normalizedLanguage = normalizeLanguage(language);
        if (!language.trim().isEmpty() && normalizedLanguage.isEmpty()) {
            throw new IllegalArgumentException("Язык должен быть кодом из 2–3 латинских букв");
        }
        // Parsing here makes invalid keyterms fail while the user is still on the settings screen.
        parseKeytermsStrict(keyterms);
        Set<String> callerPackages = parsePackageNamesStrict(allowedCallers);
        if (silenceMillis < MIN_SILENCE_MILLIS || silenceMillis > MAX_SILENCE_MILLIS) {
            throw new IllegalArgumentException("Тишина должна быть от 500 до 5000 мс");
        }
        if (maxRecordingSeconds < MIN_RECORDING_SECONDS
                || maxRecordingSeconds > MAX_RECORDING_SECONDS) {
            throw new IllegalArgumentException("Максимум записи должен быть от 5 до 300 секунд");
        }
        if (thresholdDb < MIN_THRESHOLD_DB || thresholdDb > MAX_THRESHOLD_DB) {
            throw new IllegalArgumentException("Порог речи должен быть от −70 до −30 dB");
        }

        preferences.edit()
                .putString(KEY_PROVIDER, provider)
                .putString(KEY_MODEL_PREFIX + provider, model.trim())
                .putString(KEY_LANGUAGE, normalizedLanguage)
                .putString(KEY_KEYTERMS, keyterms.trim())
                .putString(KEY_ALLOWED_CALLERS, String.join("\n", callerPackages))
                .putBoolean(KEY_AUTO_STOP, autoStop)
                .putInt(KEY_SILENCE_MILLIS, silenceMillis)
                .putInt(KEY_MAX_RECORDING_SECONDS, maxRecordingSeconds)
                .putInt(KEY_THRESHOLD_DB, thresholdDb)
                .apply();
    }

    static Set<String> parsePackageNames(String text) {
        try {
            return parsePackageNamesStrict(text);
        } catch (IllegalArgumentException ignored) {
            return new LinkedHashSet<>();
        }
    }

    static Set<String> parsePackageNamesStrict(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }
        for (String line : text.split("[\\r\\n,]+")) {
            String packageName = line.trim();
            if (packageName.isEmpty()) {
                continue;
            }
            if (packageName.length() > 255
                    || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
                throw new IllegalArgumentException("Некорректное имя пакета: " + packageName);
            }
            result.add(packageName);
            if (result.size() > 50) {
                throw new IllegalArgumentException("Допустимо не более 50 приложений");
            }
        }
        return result;
    }

    /**
     * Итог последнего распознавания: какой провайдер и модель реально ушли в
     * сеть. Только метаданные — ни текста, ни аудио здесь нет.
     */
    void recordLastRun(String provider, String model, long millis, String status) {
        preferences.edit()
                .putString(KEY_LAST_PROVIDER, provider)
                .putString(KEY_LAST_MODEL, model)
                .putLong(KEY_LAST_MILLIS, millis)
                .putString(KEY_LAST_STATUS, status)
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .apply();
    }

    boolean hasLastRun() {
        return preferences.contains(KEY_LAST_AT);
    }

    String getLastRunProvider() {
        return preferences.getString(KEY_LAST_PROVIDER, "");
    }

    String getLastRunModel() {
        return preferences.getString(KEY_LAST_MODEL, "");
    }

    long getLastRunMillis() {
        return preferences.getLong(KEY_LAST_MILLIS, 0L);
    }

    String getLastRunStatus() {
        return preferences.getString(KEY_LAST_STATUS, "");
    }

    long getLastRunAt() {
        return preferences.getLong(KEY_LAST_AT, 0L);
    }

    /** Слэш разрешён: у OpenRouter идентификаторы вида mistralai/voxtral-mini-transcribe. */
    static boolean isValidModel(String model) {
        return model != null && model.trim().matches("[A-Za-z0-9._/-]{1,120}");
    }

    static String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        String value = language.trim().replace('_', '-');
        if (value.isEmpty()) {
            return "";
        }
        String base = value.split("-", 2)[0].toLowerCase(Locale.ROOT);
        return base.matches("[a-z]{2,3}") ? base : "";
    }

    static List<String> parseKeyterms(String text) {
        try {
            return parseKeytermsStrict(text);
        } catch (IllegalArgumentException ignored) {
            return new ArrayList<>();
        }
    }

    static List<String> parseKeytermsStrict(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.trim().isEmpty()) {
            return new ArrayList<>();
        }
        String[] lines = text.split("\\r?\\n");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String term = lines[lineNumber].trim();
            if (term.isEmpty() || term.startsWith("#")) {
                continue;
            }
            if (term.length() >= 50) {
                throw new IllegalArgumentException("Keyterm в строке "
                        + (lineNumber + 1) + " длиннее 49 символов");
            }
            if (term.matches(".*[<>{}\\[\\]\\\\].*")) {
                throw new IllegalArgumentException("Недопустимый символ в keyterm, строка "
                        + (lineNumber + 1));
            }
            if (term.split("\\s+").length > 5) {
                throw new IllegalArgumentException("Keyterm в строке "
                        + (lineNumber + 1) + " длиннее 5 слов");
            }
            result.add(term);
            if (result.size() > 1000) {
                throw new IllegalArgumentException("Допустимо не более 1000 keyterms");
            }
        }
        return new ArrayList<>(result);
    }

    private String readDefaultKeyterms() {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getResources().openRawResource(R.raw.default_keyterms),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().startsWith("#")) {
                    if (result.length() > 0) {
                        result.append('\n');
                    }
                    result.append(line);
                }
            }
        } catch (IOException ignored) {
            return "";
        }
        return result.toString().trim();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
