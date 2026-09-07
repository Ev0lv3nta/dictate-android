package io.github.ev0lv3nta.dictate;

/** Identify a coherent 440 Hz fragment independently of surrounding silence. */
final class ToneFixture {
    static boolean present(byte[] pcm) {
        int previous=0, lastCrossing=-1, consecutive=0, peak=0;
        for (int i=0;i+1<pcm.length;i+=2) {
            int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
            peak=Math.max(peak,Math.abs(sample));
            if (previous<0 && sample>=0) {
                int position=i/2;
                int period=position-lastCrossing;
                // At 16 kHz a 440 Hz cycle occupies 36 or 37 samples.
                consecutive=lastCrossing>=0 && period>=34 && period<=39 ? consecutive+1 : 0;
                if (consecutive>=80 && peak>100) return true;
                lastCrossing=position;
            }
            previous=sample;
        }
        return false;
    }
}
