package io.github.ev0lv3nta.dictate;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioCapture {

    static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int CALLBACK_INTERVAL_MILLIS = 80;

    interface Listener {
        void onReady();

        void onBeginningOfSpeech();

        void onRms(float normalizedRms);
    }

    enum StopReason {
        CLIENT,
        SILENCE,
        NO_SPEECH,
        MAX_DURATION
    }

    static final class Config {
        final boolean autoStop;
        final int silenceMillis;
        final int noSpeechTimeoutMillis;
        final int maxRecordingMillis;
        final int speechThresholdDb;

        Config(boolean autoStop, int silenceMillis, int noSpeechTimeoutMillis,
               int maxRecordingMillis, int speechThresholdDb) {
            this.autoStop = autoStop;
            this.silenceMillis = silenceMillis;
            this.noSpeechTimeoutMillis = noSpeechTimeoutMillis;
            this.maxRecordingMillis = maxRecordingMillis;
            this.speechThresholdDb = speechThresholdDb;
        }
    }

    static final class Result {
        final byte[] pcm;
        final long durationMillis;
        final boolean speechStarted;
        final StopReason stopReason;

        Result(byte[] pcm, long durationMillis, boolean speechStarted, StopReason stopReason) {
            this.pcm = pcm;
            this.durationMillis = durationMillis;
            this.speechStarted = speechStarted;
            this.stopReason = stopReason;
        }
    }

    static final class CaptureException extends Exception {
        private static final long serialVersionUID = 1L;

        CaptureException(String message) {
            super(message);
        }

        CaptureException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Context attributionContext;
    private final Config config;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile AudioRecord activeRecord;

    AudioCapture(Context attributionContext, Config config) {
        this.attributionContext = attributionContext;
        this.config = config;
    }

    @SuppressLint("MissingPermission")
    Result record(Listener listener) throws CaptureException, InterruptedException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        int channel = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minimumBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding);
        if (minimumBytes <= 0) {
            throw new CaptureException("AudioRecord не вернул допустимый размер буфера");
        }

        int bufferBytes = Math.max(minimumBytes, SAMPLE_RATE * BYTES_PER_SAMPLE / 10);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(channel)
                .build();
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder = Api31.setContext(builder, attributionContext);
        }

        AudioRecord record;
        try {
            record = builder.build();
        } catch (RuntimeException error) {
            throw new CaptureException("Не удалось создать AudioRecord", error);
        }
        activeRecord = record;
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            releaseQuietly(record);
            activeRecord = null;
            throw new CaptureException("AudioRecord не инициализирован");
        }

        int shortCount = Math.max(800, bufferBytes / BYTES_PER_SAMPLE);
        short[] samples = new short[shortCount];
        int initialCapacity = (int) Math.min(
                (long) config.maxRecordingMillis * SAMPLE_RATE * BYTES_PER_SAMPLE / 1000L,
                4L * 1024L * 1024L);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(initialCapacity);

        long startedAt;
        long lastSpeechAt = 0L;
        long lastRmsCallbackAt = 0L;
        boolean speechStarted = false;
        int consecutiveSpeechFrames = 0;
        StopReason reason = StopReason.CLIENT;

        try {
            if (cancelled.get()) throw new InterruptedException();
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new CaptureException("AudioRecord не начал запись");
            }
            listener.onReady();
            startedAt = SystemClock.elapsedRealtime();

            while (!cancelled.get()) {
                if (stopRequested.get()) {
                    reason = StopReason.CLIENT;
                    break;
                }

                int read = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (read < 0) {
                    if (stopRequested.get() || cancelled.get()) {
                        break;
                    }
                    throw new CaptureException("AudioRecord.read завершился кодом " + read);
                }
                if (read == 0) {
                    continue;
                }

                long maximumBytes = (long) config.maxRecordingMillis * SAMPLE_RATE * 2 / 1000;
                int remainingSamples = (int) Math.max(0, (maximumBytes - pcm.size()) / 2);
                writeLittleEndian(pcm, samples, Math.min(read, remainingSamples));
                long now = SystemClock.elapsedRealtime();
                long elapsed = now - startedAt;
                double db = dbfs(samples, read);

                if (now - lastRmsCallbackAt >= CALLBACK_INTERVAL_MILLIS) {
                    listener.onRms(normalizeRms(db));
                    lastRmsCallbackAt = now;
                }

                if (db >= config.speechThresholdDb) {
                    consecutiveSpeechFrames++;
                    lastSpeechAt = now;
                    if (!speechStarted && consecutiveSpeechFrames >= 2) {
                        speechStarted = true;
                        listener.onBeginningOfSpeech();
                    }
                } else {
                    consecutiveSpeechFrames = 0;
                }

                if (!speechStarted && elapsed >= config.noSpeechTimeoutMillis) {
                    reason = StopReason.NO_SPEECH;
                    break;
                }
                if (speechStarted && config.autoStop
                        && now - lastSpeechAt >= config.silenceMillis) {
                    reason = StopReason.SILENCE;
                    break;
                }
                if (elapsed >= config.maxRecordingMillis
                        || pcm.size() >= (long) config.maxRecordingMillis * SAMPLE_RATE * 2 / 1000) {
                    reason = StopReason.MAX_DURATION;
                    break;
                }
            }

            if (cancelled.get()) {
                throw new InterruptedException("Запись отменена");
            }
            long duration = pcm.size() * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE);
            return new Result(pcm.toByteArray(), duration, speechStarted, reason);
        } catch (IllegalStateException error) {
            if (cancelled.get()) {
                throw new InterruptedException("Запись отменена");
            }
            throw new CaptureException("Ошибка состояния AudioRecord", error);
        } finally {
            stopQuietly(record);
            releaseQuietly(record);
            activeRecord = null;
        }
    }

    void requestStop() {
        stopRequested.set(true);
        AudioRecord record = activeRecord;
        if (record != null) stopQuietly(record);
    }

    void cancel() {
        cancelled.set(true);
        AudioRecord record = activeRecord;
        if (record != null) {
            stopQuietly(record);
        }
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    private static void writeLittleEndian(ByteArrayOutputStream output,
                                          short[] samples, int count) {
        for (int index = 0; index < count; index++) {
            int sample = samples[index];
            output.write(sample & 0xff);
            output.write((sample >>> 8) & 0xff);
        }
    }

    private static double dbfs(short[] samples, int count) {
        double sumSquares = 0.0;
        for (int index = 0; index < count; index++) {
            double value = samples[index];
            sumSquares += value * value;
        }
        if (count == 0 || sumSquares == 0.0) {
            return -120.0;
        }
        double rms = Math.sqrt(sumSquares / count) / 32768.0;
        return 20.0 * Math.log10(Math.max(rms, 0.000001));
    }

    private static float normalizeRms(double db) {
        // Recognition clients conventionally display values in the 0..10 range.
        return (float) Math.max(0.0, Math.min(10.0, (db + 60.0) / 6.0));
    }

    private static void stopQuietly(AudioRecord record) {
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static void releaseQuietly(AudioRecord record) {
        try {
            record.release();
        } catch (RuntimeException ignored) {
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private static final class Api31 {
        private Api31() {
        }

        static AudioRecord.Builder setContext(AudioRecord.Builder builder, Context context) {
            return builder.setContext(context);
        }
    }
}
