package io.github.ev0lv3nta.dictate;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Проигрывание сохранённого PCM через AudioTrack.
 *
 * Записи лежат сырым потоком без контейнера, поэтому MediaPlayer их не берёт,
 * а заворачивать в WAV ради прослушивания незачем: AudioTrack принимает тот
 * же формат, в котором пишет AudioCapture.
 */
final class RecordingPlayer {

    interface Listener {
        /** Прогресс 0..1 по ходу воспроизведения. */
        void onProgress(float fraction);

        /** Конец потока или остановка — в обоих случаях кнопка возвращается в исходное. */
        void onFinished();
    }

    private static final int CHUNK_BYTES = 4096;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private volatile AudioTrack track;

    /** Блокирующее воспроизведение: вызывается из фонового потока. */
    void play(byte[] pcm, Listener listener) {
        int minimumBytes = AudioTrack.getMinBufferSize(AudioCapture.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(minimumBytes > 0 ? minimumBytes : CHUNK_BYTES, CHUNK_BYTES * 4);
        AudioTrack player;
        try {
            player = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AudioCapture.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } catch (RuntimeException error) {
            listener.onFinished();
            return;
        }
        track = player;
        try {
            player.play();
            int offset = 0;
            while (offset < pcm.length && !stopped.get()) {
                int size = Math.min(CHUNK_BYTES, pcm.length - offset);
                int written = player.write(pcm, offset, size);
                if (written <= 0) {
                    break;
                }
                offset += written;
                listener.onProgress((float) offset / pcm.length);
            }
            if (!stopped.get()) {
                // Даём доиграть уже отданному буферу, иначе хвост фразы срежется.
                sleepFor(bufferBytes);
            }
        } catch (RuntimeException ignored) {
        } finally {
            stopQuietly(player);
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            track = null;
            listener.onFinished();
        }
    }

    void stop() {
        stopped.set(true);
        AudioTrack player = track;
        if (player != null) {
            stopQuietly(player);
        }
    }

    boolean isStopped() {
        return stopped.get();
    }

    private static void sleepFor(int bufferBytes) {
        long millis = bufferBytes * 1000L / (AudioCapture.SAMPLE_RATE * 2L);
        try {
            Thread.sleep(Math.min(Math.max(millis, 60L), 500L));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stopQuietly(AudioTrack player) {
        try {
            if (player.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                player.pause();
                player.flush();
                player.stop();
            }
        } catch (RuntimeException ignored) {
        }
    }
}
