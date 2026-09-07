package io.github.ev0lv3nta.dictate;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.ContextParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Collections;

/** Bound speech adapter. The client owns its lifetime; all Binder calls use the main looper. */
public final class DictateRecognitionService extends RecognitionService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private DictationSession session;
    private Callback client;
    private boolean destroyed;
    private RecordingIndicator indicator;

    @Override protected void onStartListening(Intent intent, Callback callback) {
        if (destroyed) return;
        if (session != null) {
            error(callback, SpeechRecognizer.ERROR_RECOGNIZER_BUSY);
            return;
        }
        if (!allowed(callback) || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("DictateSpeech", "Rejected: caller access or microphone permission");
            error(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }
        if (intent != null && (intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                || intent.hasExtra("android.speech.extra.AUDIO_SOURCE")
                || intent.hasExtra("android.speech.extra.SEGMENTED_SESSION"))) {
            error(callback, SpeechRecognizer.ERROR_CLIENT);
            return;
        }
        Context attributed = Build.VERSION.SDK_INT >= 31 ? Api31.context(this, callback) : this;
        String language = intent == null ? "" : intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE);
        client = callback;
        DictationSession created = new DictationSession(attributed, language,
                new DictationSession.Listener() {
                    @Override public void ready() { emit(callback, () -> callback.readyForSpeech(new Bundle())); }
                    @Override public void speech() { emit(callback, callback::beginningOfSpeech); }
                    @Override public void level(float value) { emit(callback, () -> callback.rmsChanged(value)); }
                    @Override public void processing() { emit(callback, callback::endOfSpeech); }
                    @Override public void result(String text) {
                        if (client != callback || destroyed) return;
                        client = null;
                        session = null;
                        closeIndicator();
                        Bundle result = new Bundle();
                        result.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION,
                                new ArrayList<>(Collections.singletonList(text)));
                        try { callback.results(result); } catch (RemoteException ignored) { }
                    }
                    @Override public void failure(int code, String message) {
                        if (client != callback || destroyed) return;
                        client = null;
                        session = null;
                        closeIndicator();
                        error(callback, code);
                    }
                });
        session = created;
        // Return to the framework first so it can finish permission / attribution checks.
        indicator = new RecordingIndicator(this);
        boolean shown = indicator.show(
                () -> { if (!destroyed && session == created) created.start(); },
                created::stop,
                () -> { if (session == created) { cancel(); error(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); } });
        if (!shown) { cancel(); error(callback, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); }
    }

    @Override protected void onStopListening(Callback callback) {
        if (client == callback && session != null) session.stop();
    }

    @Override protected void onCancel(Callback callback) {
        if (client == callback) cancel();
    }

    private void cancel() {
        DictationSession current = session;
        client = null;
        session = null;
        if (current != null) current.cancel();
        closeIndicator();
    }

    private void closeIndicator() {
        if (indicator != null) { indicator.close(); indicator = null; }
    }

    private boolean allowed(Callback callback) {
        int uid = callback.getCallingUid();
        if (uid == android.os.Process.myUid()) return true;
        String attributed = Build.VERSION.SDK_INT >= 31 ? Api31.packageName(callback) : null;
        if (attributed != null) {
            try {
                getSystemService(android.app.AppOpsManager.class).checkPackage(uid, attributed);
                return CallerPolicy.isAllowed(attributed, new String[]{attributed},
                        new AppPreferences(this).getAllowedCallerPackages());
            } catch (SecurityException mismatch) { return false; }
        }
        String[] packages = getPackageManager().getPackagesForUid(uid);
        return CallerPolicy.isAllowed(attributed, packages,
                new AppPreferences(this).getAllowedCallerPackages());
    }

    private interface RemoteCall { void run() throws RemoteException; }
    private void emit(Callback callback, RemoteCall call) {
        if (destroyed || client != callback) return;
        try { call.run(); } catch (RemoteException failure) { cancel(); }
    }

    private static void error(Callback callback, int code) {
        try { callback.error(code); } catch (RemoteException ignored) { }
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        cancel();
        super.onDestroy();
    }

    @TargetApi(31) private static final class Api31 {
        static Context context(Context context, Callback callback) {
            return context.createContext(new ContextParams.Builder()
                    .setNextAttributionSource(callback.getCallingAttributionSource()).build());
        }
        static String packageName(Callback callback) {
            return callback.getCallingAttributionSource().getPackageName();
        }
    }
}
