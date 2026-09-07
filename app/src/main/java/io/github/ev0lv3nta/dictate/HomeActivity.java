package io.github.ev0lv3nta.dictate;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ProgressBar;

/** Short dictation flow; settings and history remain separate screens. */
public final class HomeActivity extends Activity {
    private State state;
    private TextView status;
    private TextView output;
    private TextView route;
    private Button primary;
    private ProgressBar level;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            if (state.recording) main.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        state = (State) getLastNonConfigurationInstance();
        if (state == null) {
            state = new State();
            if (saved != null) state.text = saved.getString("result", "");
        }
        state.screen = this;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(24);
        content.setPadding(margin, margin, margin, margin);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = label("Dictate", 32);
        content.addView(title);
        route = label("", 16);
        content.addView(route);
        content.addView(label(getString(R.string.home_privacy), 16));
        status = label("", 18);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        level = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        level.setMax(100);
        level.setContentDescription(getString(R.string.indicator_microphone));
        content.addView(level);
        primary = button(content, getString(R.string.home_record), this::action);
        output = label("", 22);
        output.setTextIsSelectable(true);
        content.addView(output);
        button(content, getString(R.string.recording_copy_short), () -> {
            if (!state.text.isEmpty()) {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Dictate", state.text));
                status.setText(R.string.recording_copied);
            }
        });
        button(content, getString(R.string.home_settings), () -> startActivity(new Intent(this, SettingsActivity.class)));
        button(content, getString(R.string.section_recordings), () -> startActivity(new Intent(this, RecorderActivity.class)));
        setContentView(scroll);
        render();
    }

    private void action() {
        if (state.session != null) {
            if (state.recording) { state.session.stop(); state.recording = false; }
            else { state.session.cancel(); state.session = null; state.message = getString(R.string.home_cancelled); }
            render();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 71);
            return;
        }
        AppPreferences prefs = new AppPreferences(this);
        if (!"integration".equals(BuildConfig.BUILD_TYPE)
                && !new SecureApiKeyStore(this).hasCustomKey(prefs.getProvider())) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        state.text = "";
        state.processing = false;
        state.message = getString(R.string.home_preparing);
        state.session = new DictationSession(getApplicationContext(), "", state);
        state.session.start();
        render();
    }

    private void render() {
        if (status == null) return;
        AppPreferences prefs = new AppPreferences(this);
        route.setText(prefs.getProvider() + " · " + prefs.getModel());
        String text = state.message;
        if (state.recording) text = getString(R.string.home_listening) + " · "
                + (SystemClock.elapsedRealtime() - state.started) / 1000 + " s";
        status.setText(text.isEmpty() ? getString(R.string.home_ready) : text);
        primary.setText(state.session == null ? R.string.home_record
                : state.recording ? R.string.recording_stop : android.R.string.cancel);
        output.setText(state.text);
        level.setVisibility(state.recording ? View.VISIBLE : View.GONE);
        level.setProgress(state.level);
    }

    @Override protected void onResume() { super.onResume(); render(); }
    @Override protected void onStop() {
        main.removeCallbacks(tick);
        if (!state.processing && state.session != null) {
            state.session.cancel();
            state.session = null;
            state.recording = false;
            state.message = getString(R.string.home_cancelled);
        }
        super.onStop();
    }
    @Override public Object onRetainNonConfigurationInstance() { return state; }
    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putString("result", state.text);
    }
    @Override protected void onDestroy() {
        state.screen = null;
        if (!isChangingConfigurations() && state.session != null) state.session.cancel();
        super.onDestroy();
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.ink));
        view.setTextSize(size);
        view.setPadding(0, dp(12), 0, dp(12));
        return view;
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
        return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class State implements DictationSession.Listener {
        HomeActivity screen;
        DictationSession session;
        boolean recording;
        boolean processing;
        int level;
        long started;
        String text = "";
        String message = "";
        private void update() { if (screen != null) screen.render(); }
        @Override public void ready() {
            recording = true;
            started = SystemClock.elapsedRealtime();
            if (screen != null) screen.main.post(screen.tick);
            update();
        }
        @Override public void speech() { }
        @Override public void level(float value) {
            level = Math.max(0, Math.min(100, Math.round(value * 10)));
            if (screen != null) screen.level.setProgress(level);
        }
        @Override public void processing() {
            recording = false;
            processing = true;
            message = screen == null ? "Processing" : screen.getString(R.string.home_processing);
            update();
        }
        @Override public void result(String value) {
            session = null;
            recording = false;
            processing = false;
            text = value;
            message = "";
            update();
        }
        @Override public void failure(int code, String error) {
            session = null;
            recording = false;
            processing = false;
            message = error;
            update();
        }
    }
}
