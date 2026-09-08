package io.github.ev0lv3nta.dictate.sample;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Run after emulator_smoke.py has configured the external client through public UI. */
@RunWith(AndroidJUnit4.class)
public class SpeechContractTest {
    private final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    private final List<Probe> probes=new ArrayList<>();
    private Activity activity;

    @Before public void showClient() {
        activity=instrument.startActivitySync(new Intent(instrument.getTargetContext(),MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @After public void cleanup() {
        instrument.runOnMainSync(() -> {
            for (Probe probe:probes) { probe.recognizer.cancel(); probe.recognizer.destroy(); }
            activity.finish();
        });
        instrument.waitForIdleSync();
    }
    private Probe probe() {
        Probe probe=new Probe();
        instrument.runOnMainSync(() -> {
            probe.recognizer=SpeechRecognizer.createSpeechRecognizer(activity,new ComponentName(
                    "io.github.ev0lv3nta.dictate","io.github.ev0lv3nta.dictate.DictateRecognitionService"));
            probe.recognizer.setRecognitionListener(probe);
        });
        probes.add(probe);
        return probe;
    }
    private void start(Probe probe, Intent intent) {
        instrument.runOnMainSync(() -> probe.recognizer.startListening(intent));
    }
    private Intent request() { return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); }

    @Test public void unsupportedModesFailWithoutOpeningMicrophone() throws Exception {
        Probe probe=probe();
        for (Intent intent:new Intent[]{request().putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true),
                request().putExtra("android.speech.extra.SEGMENTED_SESSION","unsupported"),
                request().putExtra("android.speech.extra.AUDIO_SOURCE",0)}) {
            start(probe,intent);
            assertEquals("error:5",probe.events.poll(12,TimeUnit.SECONDS));
        }
    }

    @Test public void secondClientIsBusyAndCancelAllowsRestart() throws Exception {
        Probe first=probe(); start(first,request()); first.await("ready");
        Probe second=probe(); start(second,request());
        assertEquals("error:8",second.events.poll(12,TimeUnit.SECONDS));
        instrument.runOnMainSync(first.recognizer::cancel);
        // The framework serializes cancel/start on its main queue; no fixed sleep.
        start(first,request()); first.await("ready");
        instrument.runOnMainSync(first.recognizer::stopListening);
        first.await("error:6");
    }

    @Test public void cancelBeforeReadyAndDestroyDoNotDeliverResults() throws Exception {
        Probe first=probe();
        instrument.runOnMainSync(() -> {
            first.recognizer.startListening(request());
            first.recognizer.cancel();
        });
        start(first,request()); first.await("ready");
        instrument.runOnMainSync(first.recognizer::destroy);
        assertFalse(first.events.contains("result"));
    }

    private static final class Probe implements RecognitionListener {
        SpeechRecognizer recognizer;
        final LinkedBlockingQueue<String> events=new LinkedBlockingQueue<>();
        void await(String expected) throws Exception {
            String event=events.poll(12,TimeUnit.SECONDS);
            assertEquals(expected,event);
        }
        @Override public void onReadyForSpeech(Bundle params) { events.add("ready"); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rms) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onError(int code) { events.add("error:"+code); }
        @Override public void onResults(Bundle data) { events.add("result"); }
        @Override public void onPartialResults(Bundle data) { }
        @Override public void onEvent(int type,Bundle data) { }
    }
}
