package io.github.ev0lv3nta.dictate;

/** Identify 200 ms of dominant 440 Hz audio, allowing gaps between capture buffers. */
final class ToneFixture {
    private static final int WINDOW=320; // 20 ms at the application's 16 kHz rate
    private static final double[] COS=new double[WINDOW], SIN=new double[WINDOW];
    static {
        for (int i=0;i<WINDOW;i++) {
            double phase=2*Math.PI*440*i/16000;
            COS[i]=Math.cos(phase); SIN[i]=Math.sin(phase);
        }
    }
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
                +", expectedCycles="+expected+"/"+total+", longestRun="+longest
                +", toneWindows="+toneWindows(pcm);
    }
    static boolean present(byte[] pcm) {
        return toneWindows(pcm)>=10;
    }
    private static int toneWindows(byte[] pcm) {
        int matched=0;
        for (int offset=0;offset+WINDOW*2<=pcm.length;offset+=WINDOW*2) {
            double energy=0, real=0, imaginary=0;
            for (int i=0;i<WINDOW;i++) {
                int at=offset+i*2;
                int sample=(short)((pcm[at]&255)|(pcm[at+1]<<8));
                energy+=(double)sample*sample;
                real+=sample*COS[i]; imaginary+=sample*SIN[i];
            }
            // Require both audible power and >80% energy at the injected frequency.
            // Non-overlapping windows prevent counting one short burst repeatedly.
            if (energy>WINDOW*10000.0
                    && 2*(real*real+imaginary*imaginary)/(WINDOW*energy)>.8) matched++;
        }
        return matched;
    }
}
