package io.github.ev0lv3nta.dictate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;

/** Visible, user-authorized recording surface for external speech clients. */
final class RecordingIndicator {
    private final Service service;
    private Button view;
    private boolean closed;
    RecordingIndicator(Service service) { this.service = service; }

    boolean show(Runnable ready, Runnable stop, Runnable refused) {
        if (!Settings.canDrawOverlays(service)) return false;
        int densityWidth = Math.round(240 * service.getResources().getDisplayMetrics().density);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(densityWidth,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = Math.round(48 * service.getResources().getDisplayMetrics().density);
        view = new Button(service);
        view.setText("Dictate · Stop");
        view.setAllCaps(false);
        view.setMinHeight(Math.round(48 * service.getResources().getDisplayMetrics().density));
        view.setOnClickListener(v -> stop.run());
        view.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            private boolean dispatched;
            @Override public void onDraw() {
                if (dispatched || closed) return;
                dispatched = true;
                view.post(() -> {
                    if (closed) return;
                    try {
                        NotificationManager manager = service.getSystemService(NotificationManager.class);
                        manager.createNotificationChannel(new NotificationChannel("recording", "Dictate",
                                NotificationManager.IMPORTANCE_LOW));
                        Notification notification = new Notification.Builder(service, "recording")
                                .setContentTitle("Dictate")
                                .setContentText("Microphone active")
                                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build();
                        if (Build.VERSION.SDK_INT >= 30) service.startForeground(1, notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                        else service.startForeground(1, notification);
                        ready.run();
                    } catch (RuntimeException error) {
                        android.util.Log.w("DictateIndicator", "FGS refused: " + error.getClass().getSimpleName());
                        close();
                        refused.run();
                    }
                });
            }
        });
        try { service.getSystemService(WindowManager.class).addView(view, params); }
        catch (RuntimeException error) { close(); return false; }
        return true;
    }

    void close() {
        closed = true;
        if (view != null && view.isAttachedToWindow()) {
            service.getSystemService(WindowManager.class).removeView(view);
        }
        service.stopForeground(true);
    }
}
