package io.github.ev0lv3nta.dictate;

/**
 * Оборачивает сырой PCM16 в WAV: OpenRouter и Google принимают файл с заголовком,
 * а не голый поток сэмплов.
 */
final class WavEncoder {

    private static final int HEADER_BYTES = 44;

    private WavEncoder() {
    }

    static byte[] wrap(byte[] pcm, int sampleRate, int channels) {
        int dataLength = pcm.length;
        int byteRate = sampleRate * channels * 2;
        byte[] wav = new byte[HEADER_BYTES + dataLength];

        putAscii(wav, 0, "RIFF");
        putInt(wav, 4, 36 + dataLength);
        putAscii(wav, 8, "WAVE");
        putAscii(wav, 12, "fmt ");
        putInt(wav, 16, 16);              // размер fmt-блока
        putShort(wav, 20, 1);             // PCM без сжатия
        putShort(wav, 22, channels);
        putInt(wav, 24, sampleRate);
        putInt(wav, 28, byteRate);
        putShort(wav, 32, channels * 2);  // выравнивание блока
        putShort(wav, 34, 16);            // бит на сэмпл
        putAscii(wav, 36, "data");
        putInt(wav, 40, dataLength);
        System.arraycopy(pcm, 0, wav, HEADER_BYTES, dataLength);
        return wav;
    }

    private static void putAscii(byte[] target, int offset, String value) {
        for (int index = 0; index < value.length(); index++) {
            target[offset + index] = (byte) value.charAt(index);
        }
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
        target[offset + 2] = (byte) ((value >> 16) & 0xFF);
        target[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void putShort(byte[] target, int offset, int value) {
        target[offset] = (byte) (value & 0xFF);
        target[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
