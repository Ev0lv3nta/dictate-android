package io.github.ev0lv3nta.dictate;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Each setting is applied explicitly; invalid text stays in its dialog. */
public final class SettingsActivity extends Activity {
    private AppPreferences prefs;
    private SecureApiKeyStore keys;
    private LinearLayout content;
    private final ExecutorService storage = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new AppPreferences(this);
        keys = new SecureApiKeyStore(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(20));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    Math.max(insets.getSystemWindowInsetBottom(), 0));
            return insets;
        });
        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); render(); }

    private void render() {
        if (content == null || isDestroyed()) return;
        content.removeAllViews();
        heading("Настройки", 28);
        row("‹ Диктовка", () -> { startActivity(new Intent(this, HomeActivity.class)); finish(); });
        heading("Провайдер и ключ", 20);
        row(prefs.getProvider(), this::providerDialog);
        row(prefs.getModel().isEmpty() ? "Выберите модель" : prefs.getModel(), this::modelDialog);
        row("API-ключ · добавить или заменить", this::keyDialog);
        TextView keyState = heading("Проверка локального ключа…", 14);
        String provider = prefs.getProvider();
        storage.execute(() -> {
            boolean unreadable = keys.isUnreadable(provider);
            String key = keys.load(provider);
            String label = unreadable ? "Ключ не читается — введите заново"
                    : key == null ? "Ключ не настроен" : "Сохранён · " + SecureApiKeyStore.mask(key);
            runOnUiThread(() -> { if (!isDestroyed()) keyState.setText(label); });
        });
        heading("Сохранение ключа не проверяет доступ к API и не отправляет платный запрос.", 14);

        heading("Язык и словарь", 20);
        row("Язык · " + (prefs.getLanguageOverride().isEmpty() ? "Авто" : prefs.getLanguageOverride()),
                () -> edit("Язык BCP 47", prefs.getLanguageOverride(), "en-GB, pt-BR, zh-Hant-TW", false, value -> {
                    if (!value.isEmpty() && AppPreferences.normalizeLanguage(value).isEmpty()) throw new IllegalArgumentException("Некорректный языковой тег");
                    save(prefs.getProvider(), prefs.getModel(), value, prefs.getKeytermsText(), prefs.getAllowedCallersText(),
                            prefs.isAutoStopEnabled(), prefs.getSilenceMillis(), prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb());
                }));
        row("Словарь · " + prefs.getKeyterms().size() + " терминов", () -> edit("Словарь", prefs.getKeytermsText(),
                "Один термин на строку. До 1000 для STT, до 200 для chat. OpenRouter STT словарь не принимает.", true,
                value -> save(prefs.getProvider(), prefs.getModel(), prefs.getLanguageOverride(), value,
                        prefs.getAllowedCallersText(), prefs.isAutoStopEnabled(), prefs.getSilenceMillis(),
                        prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb())));

        heading("Подключение клиента", 20);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            row("Нужен микрофон", () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        row(Settings.canDrawOverlays(this) ? "Индикатор записи разрешён" : "Разрешить индикатор записи",
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()))));
        heading("Для внешних клиентов используется видимая кнопка Stop поверх экрана. Это отдельное разрешение; встроенная диктовка его не требует.", 14);
        row("Установленные клавиатуры", this::imeDialog);
        row("Разрешённые приложения · " + prefs.getAllowedCallerPackages().size(),
                () -> edit("Разрешённые приложения", prefs.getAllowedCallersText(), "org.example.keyboard", true, value -> {
                    Set<String> packages = AppPreferences.parsePackageNamesStrict(value);
                    for (String name : packages) {
                        try { getPackageManager().getApplicationInfo(name, 0); }
                        catch (PackageManager.NameNotFoundException error) { throw new IllegalArgumentException("Пакет не установлен или не виден: " + name); }
                    }
                    save(prefs.getProvider(), prefs.getModel(), prefs.getLanguageOverride(), prefs.getKeytermsText(), value,
                            prefs.isAutoStopEnabled(), prefs.getSilenceMillis(), prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb());
                }));
        row("Открыть sample client", () -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName()+".sample");
            if (intent == null) new AlertDialog.Builder(this).setMessage("Установите sample-client из Releases репозитория.")
                    .setPositiveButton(android.R.string.ok, null).show();
            else startActivity(intent);
        });

        heading("История и приватность", 20);
        CheckBox history = new CheckBox(this);
        history.setText("Сохранять последние записи на устройстве");
        history.setChecked(prefs.isHistoryEnabled());
        history.setMinHeight(dp(48));
        history.setOnCheckedChangeListener((v, enabled) -> prefs.setHistoryEnabled(enabled));
        content.addView(history);
        heading("По умолчанию история выключена. Старые записи остаются до удаления. Аудио отправляется выбранному API; OpenRouter передаёт его downstream-провайдеру.", 14);
        row("Очистить всю историю", () -> new AlertDialog.Builder(this).setMessage("Удалить все сохранённые аудио и тексты?")
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton("Удалить", (d,w) ->
                        storage.execute(() -> new RecordingLibrary(this).clear())).show());
        row("Дополнительные параметры записи", this::advancedDialog);
        heading("Dictate " + BuildConfig.VERSION_NAME, 14);
    }

    private void providerDialog() {
        List<ModelCatalog.Provider> providers = ModelCatalog.providers();
        String[] labels = new String[providers.size()];
        for (int i=0;i<labels.length;i++) labels[i]=providers.get(i).title;
        new AlertDialog.Builder(this).setTitle("Провайдер").setItems(labels, (d,index) -> {
            String p = providers.get(index).id;
            save(p, prefs.getModel(p), prefs.getLanguageOverride(), prefs.getKeytermsText(), prefs.getAllowedCallersText(),
                    prefs.isAutoStopEnabled(), prefs.getSilenceMillis(), prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb());
            render();
        }).show();
    }

    private void modelDialog() {
        if (!ModelCatalog.isKnownProvider(prefs.getProvider())) { providerDialog(); return; }
        List<ModelCatalog.Model> models = ModelCatalog.provider(prefs.getProvider()).models;
        String[] labels = new String[models.size()];
        for (int i=0;i<labels.length;i++) labels[i]=models.get(i).title;
        new AlertDialog.Builder(this).setTitle("Модель").setItems(labels, (d,index) -> {
            save(prefs.getProvider(), models.get(index).id, prefs.getLanguageOverride(), prefs.getKeytermsText(), prefs.getAllowedCallersText(),
                    prefs.isAutoStopEnabled(), prefs.getSilenceMillis(), prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb());
            render();
        }).show();
    }

    private void imeDialog() {
        List<InputMethodInfo> imes = getSystemService(InputMethodManager.class).getInputMethodList();
        String[] labels = new String[imes.size()];
        for (int i=0;i<labels.length;i++) labels[i] = imes.get(i).loadLabel(getPackageManager()) + " · " + imes.get(i).getPackageName();
        new AlertDialog.Builder(this).setTitle("Разрешить клиент — совместимость не гарантируется")
                .setItems(labels,(d,index) -> {
                    Set<String> packages = prefs.getAllowedCallerPackages();
                    packages.add(imes.get(index).getPackageName());
                    save(prefs.getProvider(), prefs.getModel(), prefs.getLanguageOverride(), prefs.getKeytermsText(), String.join("\n",packages),
                            prefs.isAutoStopEnabled(), prefs.getSilenceMillis(), prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb());
                    render();
                }).show();
    }

    private void keyDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20),0,dp(20),0);
        EditText input = new EditText(this); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); box.addView(input);
        CheckBox reveal = new CheckBox(this); reveal.setText("Показать ключ"); box.addView(reveal);
        reveal.setOnCheckedChangeListener((v,on) -> input.setTransformationMethod(on ? null : android.text.method.PasswordTransformationMethod.getInstance()));
        String provider = prefs.getProvider();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Ключ · " + provider).setView(box)
                .setPositiveButton("Сохранить",null).setNegativeButton(android.R.string.cancel,null).setNeutralButton("Удалить",null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(d -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE));
        dialog.show();
        View.OnClickListener save = v -> {
            boolean clear = v == dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            String key = input.getText().toString();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            storage.execute(() -> {
                boolean success;
                try { if (clear) keys.clear(provider); else keys.save(provider,key); success=true; }
                catch (Exception error) { success=false; }
                final boolean ok=success;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (ok) { dialog.dismiss(); render(); }
                    else { input.setError("Не удалось сохранить изменение"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
                });
            });
        };
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(save);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(save);
    }

    private void advancedDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20),0,dp(20),0);
        CheckBox auto = new CheckBox(this);
        auto.setText("Останавливать после паузы"); auto.setChecked(prefs.isAutoStopEnabled()); fields.addView(auto);
        EditText silence = number(fields,"Пауза, мс (500–5000)",prefs.getSilenceMillis());
        EditText max = number(fields,"Максимум, секунд (5–300)",prefs.getMaxRecordingSeconds());
        EditText threshold = number(fields,"Порог речи, dB (−70…−30)",prefs.getSpeechThresholdDb());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Параметры записи")
                .setMessage("Тихая речь — ниже порог; фоновый шум — выше. Один порог используется и при обрезке тишины.")
                .setView(fields).setNegativeButton(android.R.string.cancel,null).setPositiveButton("Сохранить",null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                save(prefs.getProvider(),prefs.getModel(),prefs.getLanguageOverride(),prefs.getKeytermsText(),prefs.getAllowedCallersText(),
                        auto.isChecked(),Integer.parseInt(silence.getText().toString()),Integer.parseInt(max.getText().toString()),Integer.parseInt(threshold.getText().toString()));
                dialog.dismiss();
            } catch (RuntimeException error) { threshold.setError("Проверьте числа и допустимые диапазоны"); }
        });
    }

    private EditText number(LinearLayout fields,String label,int value) {
        TextView title=new TextView(this); title.setText(label); fields.addView(title);
        EditText input=new EditText(this); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(Integer.toString(value)); fields.addView(input); return input;
    }

    interface SaveText { void save(String value); }
    private void edit(String title, String value, String hint, boolean multiline, SaveText action) {
        EditText input=new EditText(this); input.setText(value); input.setHint(hint); input.setSingleLine(!multiline);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | (multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        if (multiline) { input.setMinLines(4); input.setGravity(Gravity.TOP); }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(input)
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton("Сохранить",null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { action.save(input.getText().toString().trim()); dialog.dismiss(); render(); }
            catch (RuntimeException error) { input.setError(error instanceof NumberFormatException?"Введите числа":error.getMessage()); }
        });
    }
    private void save(String provider,String model,String language,String terms,String callers,boolean auto,int silence,int max,int threshold) {
        prefs.save(provider,model,language,terms,callers,auto,silence,max,threshold);
    }
    private TextView heading(String text,int size) {
        TextView label=new TextView(this); label.setText(text); label.setTextSize(size); label.setTextColor(getColor(R.color.ink));
        label.setPadding(0,dp(12),0,dp(8)); content.addView(label); return label;
    }
    private void row(String text,Runnable action) {
        Button button=new Button(this); button.setText(text); button.setAllCaps(false); button.setMinHeight(dp(48));
        button.setOnClickListener(v -> action.run()); content.addView(button);
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { storage.shutdown(); super.onDestroy(); }
}
