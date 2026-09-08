package io.github.ev0lv3nta.dictate;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.*;

public class ToneFixtureTest {
    @Test public void acceptsToneWithLongPaddingButRejectsOtherAudio() {
        assertTrue(ToneFixture.present(tone(440,200,700)));
        assertTrue(ToneFixture.present(tone(440,1000,0)));
        assertFalse(ToneFixture.present(tone(440,100,700)));
        assertFalse(ToneFixture.present(tone(220,1000,0)));
        assertFalse(ToneFixture.present(tone(880,1000,0)));
        assertFalse(ToneFixture.present(tone(400,1000,0)));
        assertFalse(ToneFixture.present(tone(480,1000,0)));
        assertFalse(ToneFixture.present(new byte[64000]));
        byte[] noise=new byte[64000]; new Random(42).nextBytes(noise);
        assertFalse(ToneFixture.present(noise));
    }
    @Test public void acceptsToneDespiteNoiseAndCaptureBufferGaps() {
        byte[] pcm=tone(440,2000,0);
        Random random=new Random(7);
        for (int i=0;i<pcm.length/2;i++) {
            int value=(short)((pcm[i*2]&255)|(pcm[i*2+1]<<8));
            // A 25 ms dropped buffer every 100 ms plus small deterministic noise.
            value=i%1600<400 ? 0 : value+random.nextInt(1201)-600;
            pcm[i*2]=(byte)value; pcm[i*2+1]=(byte)(value>>8);
        }
        assertTrue(ToneFixture.present(pcm));
    }
    @Test public void rejectsNoiseAcrossIndependentSeedsAndAnIsolatedShortBurst() {
        for (int seed=0;seed<20;seed++) {
            byte[] noise=new byte[64000]; new Random(seed).nextBytes(noise);
            assertFalse(ToneFixture.present(noise));
        }
        assertFalse(ToneFixture.present(tone(440,100,2000)));
    }
    private byte[] tone(int hz,int durationMs,int paddingMs) {
        int padding=paddingMs*16, samples=durationMs*16;
        byte[] pcm=new byte[(samples+padding*2)*2];
        for (int i=0;i<samples;i++) {
            short value=(short)(10000*Math.sin(2*Math.PI*hz*i/16000));
            pcm[(i+padding)*2]=(byte)value;
            pcm[(i+padding)*2+1]=(byte)(value>>8);
        }
        return pcm;
    }
}
