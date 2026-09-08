package io.github.ev0lv3nta.dictate;

/** Deterministic backend for emulator tests. Never compiled into debug or release. */
final class BackendSelection {
    static void requireKey(String key, String provider) { }
    static Transcription.Client client(String provider) {
        return (pcm, key, config, request) -> {
            Transcription.requireAudio(pcm);
            if (!ToneFixture.present(pcm)) {
                android.util.Log.w("DictateFixture", ToneFixture.diagnostics(pcm));
                throw new Transcription.ApiException(
                        Transcription.ErrorKind.INVALID_RESPONSE,"Expected 440 Hz microphone fixture");
            }
            return "Fixture: microphone captured " + pcm.length + " PCM bytes.";
        };
    }
}
