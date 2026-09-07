package io.github.ev0lv3nta.dictate.sample;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/** Independent Binder client: no dependency on :app implementation. */
public final class MainActivity extends Activity implements RecognitionListener {
    private SpeechRecognizer recognizer;
    private TextView events;
    private TextView result;
    private boolean levelReported;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(24, insets.getSystemWindowInsetTop() + 24,
                    24, insets.getSystemWindowInsetBottom() + 24);
            return insets;
        });
        result = new TextView(this);
        result.setText("Dictate · independent SpeechRecognizer client");
        result.setTextSize(20);
        result.setTextIsSelectable(true);
        root.addView(result);
        add(root, "Start", () -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                return;
            }
            events.setText("");
            levelReported = false;
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-GB");
            recognizer.startListening(intent);
            event("start");
        });
        add(root, "Stop", () -> { recognizer.stopListening(); event("stop"); });
        add(root, "Cancel", () -> { recognizer.cancel(); event("cancel"); });
        events = new TextView(this);
        root.addView(events);
        setContentView(root);
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, new ComponentName(
                "io.github.ev0lv3nta.dictate", "io.github.ev0lv3nta.dictate.DictateRecognitionService"));
        recognizer.setRecognitionListener(this);
    }

    private void add(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setMinHeight(48);
        button.setOnClickListener(view -> action.run());
        root.addView(button);
    }
    private void event(String text) { events.append(text + "\n"); }
    @Override public void onReadyForSpeech(Bundle params) { event("ready"); }
    @Override public void onBeginningOfSpeech() { event("speech"); }
    @Override public void onRmsChanged(float rms) {
        if (rms > 1 && !levelReported) { levelReported = true; event("audio level > 1"); }
    }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { event("processing"); }
    @Override public void onError(int code) { event("error " + code); }
    @Override public void onResults(Bundle data) {
        ArrayList<String> texts = data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        result.setText(texts == null || texts.isEmpty() ? "Empty result" : texts.get(0));
        event("result");
    }
    @Override public void onPartialResults(Bundle data) { event("partial"); }
    @Override public void onEvent(int type, Bundle data) { }
    @Override protected void onStop() {
        if (recognizer != null) recognizer.cancel();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }
}
