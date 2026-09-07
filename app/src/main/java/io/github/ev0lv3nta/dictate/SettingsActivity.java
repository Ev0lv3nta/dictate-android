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
        heading(getString(R.string.settings_settings), 28);
        row(getString(R.string.settings_dictation), () -> { startActivity(new Intent(this, HomeActivity.class)); finish(); });
        heading(getString(R.string.settings_provider_and_key), 20);
        row(prefs.getProvider(), this::providerDialog);
        row(prefs.getModel().isEmpty() ? getString(R.string.settings_choose_a_model) : prefs.getModel(), this::modelDialog);
        row(getString(R.string.settings_api_key_add_or_replace), this::keyDialog);
        TextView keyState = heading(getString(R.string.settings_reading_local_key), 14);
        String provider = prefs.getProvider();
        storage.execute(() -> {
            boolean unreadable = keys.isUnreadable(provider);
            String key = keys.load(provider);
            String label = unreadable ? getString(R.string.settings_key_unreadable_enter_it_again)
                    : key == null ? getString(R.string.settings_key_not_configured) : getString(R.string.settings_saved) + SecureApiKeyStore.mask(key);
            runOnUiThread(() -> { if (!isDestroyed()) keyState.setText(label); });
        });
        heading(getString(R.string.settings_saving_a_key_does_not_verify_api_access_or_send_a_paid_), 14);

        heading(getString(R.string.settings_language_and_vocabulary), 20);
        row(getString(R.string.settings_language) + (prefs.getLanguageOverride().isEmpty() ? getString(R.string.settings_auto) : prefs.getLanguageOverride()),
                () -> edit(getString(R.string.settings_language_bcp_47), prefs.getLanguageOverride(), "en-GB, pt-BR, zh-Hant-TW", false, value -> {
                    if (!value.isEmpty() && AppPreferences.normalizeLanguage(value).isEmpty()) throw new IllegalArgumentException(getString(R.string.settings_invalid_language_tag));
                    save(prefs.getProvider(), prefs.getModel(), value, prefs.getKeytermsText(), prefs.getAllowedCallersText(),
                            prefs.isAutoStopEnabled(), prefs.getSilenceMillis(), prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb());
                }));
        row(getString(R.string.settings_vocabulary) + prefs.getKeyterms().size() + getString(R.string.settings_terms), () -> edit(getString(R.string.settings_vocabulary_17), prefs.getKeytermsText(),
                getString(R.string.settings_one_term_per_line_up_to_1000_for_supported_stt_and_200_), true,
                value -> save(prefs.getProvider(), prefs.getModel(), prefs.getLanguageOverride(), value,
                        prefs.getAllowedCallersText(), prefs.isAutoStopEnabled(), prefs.getSilenceMillis(),
                        prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb())));

        heading(getString(R.string.settings_client_access), 20);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            row(getString(R.string.settings_microphone_permission_required), () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        row(Settings.canDrawOverlays(this) ? getString(R.string.settings_recording_indicator_allowed) : getString(R.string.settings_allow_recording_indicator),
                () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()))));
        heading(getString(R.string.settings_external_clients_use_a_visible_stop_button_over_the_scr), 14);
        row(getString(R.string.settings_installed_keyboards), this::imeDialog);
        row(getString(R.string.settings_allowed_apps) + prefs.getAllowedCallerPackages().size(),
                () -> edit(getString(R.string.settings_allowed_apps_26), prefs.getAllowedCallersText(), "org.example.keyboard", true, value -> {
                    Set<String> packages = AppPreferences.parsePackageNamesStrict(value);
                    for (String name : packages) {
                        try { getPackageManager().getApplicationInfo(name, 0); }
                        catch (PackageManager.NameNotFoundException error) { throw new IllegalArgumentException(getString(R.string.settings_package_not_installed_or_not_visible) + name); }
                    }
                    save(prefs.getProvider(), prefs.getModel(), prefs.getLanguageOverride(), prefs.getKeytermsText(), value,
                            prefs.isAutoStopEnabled(), prefs.getSilenceMillis(), prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb());
                }));
        row(getString(R.string.settings_try_sample_client), () -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName()+".sample");
            if (intent == null) new AlertDialog.Builder(this).setMessage(getString(R.string.settings_install_the_sample_client_from_the_repository_releases))
                    .setPositiveButton(android.R.string.ok, null).show();
            else startActivity(intent);
        });

        heading(getString(R.string.settings_history_and_privacy), 20);
        CheckBox history = new CheckBox(this);
        history.setText(getString(R.string.settings_save_recent_recordings_on_this_device));
        history.setChecked(prefs.isHistoryEnabled());
        history.setMinHeight(dp(48));
        history.setOnCheckedChangeListener((v, enabled) -> prefs.setHistoryEnabled(enabled));
        content.addView(history);
        heading(getString(R.string.settings_history_is_off_by_default_existing_recordings_remain_un), 14);
        row(getString(R.string.settings_clear_all_history), () -> new AlertDialog.Builder(this).setMessage(getString(R.string.settings_delete_all_saved_audio_and_transcripts))
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(getString(R.string.settings_delete), (d,w) ->
                        storage.execute(() -> new RecordingLibrary(this).clear())).show());
        row(getString(R.string.settings_recording_options), this::advancedDialog);
        heading("Dictate " + BuildConfig.VERSION_NAME, 14);
    }

    private void providerDialog() {
        List<ModelCatalog.Provider> providers = ModelCatalog.providers();
        String[] labels = new String[providers.size()];
        for (int i=0;i<labels.length;i++) labels[i]=providers.get(i).title;
        new AlertDialog.Builder(this).setTitle(getString(R.string.settings_provider)).setItems(labels, (d,index) -> {
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
        new AlertDialog.Builder(this).setTitle(getString(R.string.settings_model)).setItems(labels, (d,index) -> {
            save(prefs.getProvider(), models.get(index).id, prefs.getLanguageOverride(), prefs.getKeytermsText(), prefs.getAllowedCallersText(),
                    prefs.isAutoStopEnabled(), prefs.getSilenceMillis(), prefs.getMaxRecordingSeconds(), prefs.getSpeechThresholdDb());
            render();
        }).show();
    }

    private void imeDialog() {
        List<InputMethodInfo> imes = getSystemService(InputMethodManager.class).getInputMethodList();
        String[] labels = new String[imes.size()];
        for (int i=0;i<labels.length;i++) labels[i] = imes.get(i).loadLabel(getPackageManager()) + " · " + imes.get(i).getPackageName();
        new AlertDialog.Builder(this).setTitle(getString(R.string.settings_allow_client_compatibility_is_not_guaranteed))
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
        CheckBox reveal = new CheckBox(this); reveal.setText(getString(R.string.settings_show_key)); box.addView(reveal);
        reveal.setOnCheckedChangeListener((v,on) -> input.setTransformationMethod(on ? null : android.text.method.PasswordTransformationMethod.getInstance()));
        String provider = prefs.getProvider();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.settings_key) + provider).setView(box)
                .setPositiveButton(getString(R.string.settings_save),null).setNegativeButton(android.R.string.cancel,null).setNeutralButton(getString(R.string.settings_delete),null).create();
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
                    else { input.setError(getString(R.string.settings_could_not_save_the_change)); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); }
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
        auto.setText(getString(R.string.settings_stop_after_silence)); auto.setChecked(prefs.isAutoStopEnabled()); fields.addView(auto);
        EditText silence = number(fields,getString(R.string.settings_silence_ms_500_5000),prefs.getSilenceMillis());
        EditText max = number(fields,getString(R.string.settings_maximum_seconds_5_300),prefs.getMaxRecordingSeconds());
        EditText threshold = number(fields,getString(R.string.settings_speech_threshold_db_70_30),prefs.getSpeechThresholdDb());
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.settings_recording_options_48))
                .setMessage(getString(R.string.settings_lower_the_threshold_for_quiet_speech_raise_it_for_noise))
                .setView(fields).setNegativeButton(android.R.string.cancel,null).setPositiveButton(getString(R.string.settings_save),null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                save(prefs.getProvider(),prefs.getModel(),prefs.getLanguageOverride(),prefs.getKeytermsText(),prefs.getAllowedCallersText(),
                        auto.isChecked(),Integer.parseInt(silence.getText().toString()),Integer.parseInt(max.getText().toString()),Integer.parseInt(threshold.getText().toString()));
                dialog.dismiss();
            } catch (RuntimeException error) { threshold.setError(getString(R.string.settings_check_the_numbers_and_their_allowed_ranges)); }
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
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(getString(R.string.settings_save),null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { action.save(input.getText().toString().trim()); dialog.dismiss(); render(); }
            catch (RuntimeException error) { input.setError(error instanceof NumberFormatException?getString(R.string.settings_enter_numbers):error.getMessage()); }
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
