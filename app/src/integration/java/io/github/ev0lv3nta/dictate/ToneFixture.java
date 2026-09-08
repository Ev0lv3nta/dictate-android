package io.github.ev0lv3nta.dictate;

/** Identify a coherent 440 Hz fragment independently of surrounding silence. */
final class ToneFixture {
    static String diagnostics(byte[] pcm) {
        int previous=0, last=-1, run=0, longest=0, peak=0, expected=0, total=0;
        for (int i=0;i+1<pcm.length;i+=2) {
            int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
            peak=Math.max(peak,Math.abs(sample));
            if (previous<0 && sample>=0) {
                int position=i/2, period=position-last;
                if (last>=0) {
                    total++;
                    if (period>=34 && period<=39) { expected++; run++; }
                    else run=0;
                    longest=Math.max(longest,run);
                }
                last=position;
            }
            previous=sample;
        }
        return "Synthetic microphone: bytes="+pcm.length+", peak="+peak
                +", expectedCycles="+expected+"/"+total+", longestRun="+longest;
    }
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
