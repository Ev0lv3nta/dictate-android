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
        assertFalse(ToneFixture.present(new byte[64000]));
        byte[] noise=new byte[64000]; new Random(42).nextBytes(noise);
        assertFalse(ToneFixture.present(noise));
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
