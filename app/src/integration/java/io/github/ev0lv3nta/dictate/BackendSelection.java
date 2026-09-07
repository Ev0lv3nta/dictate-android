package io.github.ev0lv3nta.dictate;

/** Deterministic backend for emulator tests. Never compiled into debug or release. */
final class BackendSelection {
    static void requireKey(String key, String provider) { }
    static Transcription.Client client(String provider) {
        return (pcm, key, config, request) -> {
            Transcription.requireAudio(pcm);
            // Inspect active 200 ms windows, not the whole clip including edge padding.
            // Real-time emulator delivery can insert silence when the host is busy.
            int matching=0;
            int minHz=Integer.MAX_VALUE, maxHz=0, maxRms=0;
            for (int start=0;start+6400<=pcm.length;start+=3200) {
                int crossings=0, previous=0;
                long squares=0;
                for (int i=start;i<start+6400;i+=2) {
                    int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
                    if (previous<0 && sample>=0) crossings++;
                    squares+=(long)sample*sample;
                    previous=sample;
                }
                double hz=crossings*5.0;
                int rms=(int)Math.sqrt(squares/3200.0);
                maxRms=Math.max(maxRms,rms);
                if (rms>100) { minHz=Math.min(minHz,(int)hz); maxHz=Math.max(maxHz,(int)hz); }
                if (hz>=400 && hz<=480 && rms>100) matching++;
            }
            android.util.Log.i("DictateFixture", "Matching tone windows: " + matching + ", bytes: " + pcm.length
                    + ", active Hz: " + minHz + ".." + maxHz + ", peak RMS: " + maxRms);
            if (matching<2) throw new Transcription.ApiException(
                    Transcription.ErrorKind.INVALID_RESPONSE,"Expected 440 Hz microphone fixture");
            return "Fixture: microphone captured " + pcm.length + " PCM bytes.";
        };
    }
}
