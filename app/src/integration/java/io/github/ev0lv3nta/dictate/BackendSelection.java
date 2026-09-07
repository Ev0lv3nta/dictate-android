package io.github.ev0lv3nta.dictate;

/** Deterministic backend for emulator tests. Never compiled into debug or release. */
final class BackendSelection {
    static void requireKey(String key, String provider) { }
    static Transcription.Client client(String provider) {
        return (pcm, key, config, request) -> {
            Transcription.requireAudio(pcm);
            int crossings=0;
            int previous=0;
            for (int i=0;i+1<pcm.length;i+=2) {
                int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
                if (previous<0 && sample>=0) crossings++;
                previous=sample;
            }
            double hz=crossings*16000.0/(pcm.length/2);
            android.util.Log.i("DictateFixture", "Signal: " + Math.round(hz) + " Hz, " + pcm.length + " bytes");
            if (hz<300 || hz>500) throw new Transcription.ApiException(
                    Transcription.ErrorKind.INVALID_RESPONSE,"Expected 440 Hz microphone fixture");
            return "Fixture: microphone captured " + pcm.length + " PCM bytes.";
        };
    }
}
