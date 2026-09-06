package io.github.ev0lv3nta.dictate;

import java.util.Arrays;

final class PcmSilenceTrimmer {

    private static final int FRAME_MILLIS = 20;

    private PcmSilenceTrimmer() {
    }

    static Result trimEdges(byte[] pcm, int sampleRate, int thresholdDb, int keepMillis) {
        if (pcm == null || pcm.length < 2 || sampleRate <= 0) {
            return new Result(new byte[0], 0, 0);
        }

        int usableLength = pcm.length - (pcm.length % 2);
        int frameSamples = Math.max(1, sampleRate * FRAME_MILLIS / 1000);
        int frameBytes = frameSamples * 2;
        int frameCount = (usableLength + frameBytes - 1) / frameBytes;
        int firstActive = -1;
        int lastActive = -1;

        for (int frame = 0; frame < frameCount; frame++) {
            int start = frame * frameBytes;
            int end = Math.min(usableLength, start + frameBytes);
            if (dbfs(pcm, start, end) >= thresholdDb) {
                if (firstActive < 0) {
                    firstActive = frame;
                }
                lastActive = frame;
            }
        }

        if (firstActive < 0) {
            return new Result(new byte[0], usableLength, 0);
        }

        int keepBytes = Math.max(0, sampleRate * keepMillis / 1000) * 2;
        int start = Math.max(0, firstActive * frameBytes - keepBytes);
        int end = Math.min(usableLength, (lastActive + 1) * frameBytes + keepBytes);
        start -= start % 2;
        end -= end % 2;
        return new Result(Arrays.copyOfRange(pcm, start, end), usableLength, end - start);
    }

    private static double dbfs(byte[] pcm, int start, int end) {
        long sumSquares = 0L;
        int samples = 0;
        for (int offset = start; offset + 1 < end; offset += 2) {
            int low = pcm[offset] & 0xff;
            int high = pcm[offset + 1];
            short sample = (short) ((high << 8) | low);
            long value = sample;
            sumSquares += value * value;
            samples++;
        }
        if (samples == 0 || sumSquares == 0L) {
            return -120.0;
        }
        double rms = Math.sqrt((double) sumSquares / samples) / 32768.0;
        return 20.0 * Math.log10(Math.max(rms, 0.000001));
    }

    static final class Result {
        final byte[] pcm;
        final int originalBytes;
        final int trimmedBytes;

        Result(byte[] pcm, int originalBytes, int trimmedBytes) {
            this.pcm = pcm;
            this.originalBytes = originalBytes;
            this.trimmedBytes = trimmedBytes;
        }

        int savedPercent() {
            if (originalBytes <= 0) {
                return 0;
            }
            return Math.max(0, Math.min(100,
                    Math.round(100f * (originalBytes - trimmedBytes) / originalBytes)));
        }
    }
}

