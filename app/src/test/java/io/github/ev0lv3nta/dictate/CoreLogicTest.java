package io.github.ev0lv3nta.dictate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CoreLogicTest {

    @Test
    public void normalizesLanguageAndVocabulary() {
        assertEquals("ru", AppPreferences.normalizeLanguage("ru-RU"));
        assertEquals("en", AppPreferences.normalizeLanguage("EN_us"));
        assertEquals("", AppPreferences.normalizeLanguage("russian"));

        List<String> terms = AppPreferences.parseKeytermsStrict(
                "ElevenLabs\n# comment\nMCP\nElevenLabs\n");
        assertEquals(Arrays.asList("ElevenLabs", "MCP"), terms);
        expectFailure(() -> AppPreferences.parseKeytermsStrict(
                "one two three four five six"));
        expectFailure(() -> AppPreferences.parseKeytermsStrict("bad[keyterm"));
    }

    @Test
    public void validatesAllowedCallerPackages() {
        Set<String> packages = AppPreferences.parsePackageNamesStrict(
                "org.example.keyboard\ncom.example.ime, org.example.keyboard");
        assertEquals(new LinkedHashSet<>(Arrays.asList(
                "org.example.keyboard", "com.example.ime")), packages);
        expectFailure(() -> AppPreferences.parsePackageNamesStrict("not-a-package"));

        assertFalse(CallerPolicy.isAllowed("org.example.keyboard", null,
                new LinkedHashSet<>()));
        assertTrue(CallerPolicy.isAllowed("org.example.keyboard", null, packages));
        assertTrue(CallerPolicy.isAllowed(null,
                new String[]{"com.example.ime"}, packages));
        assertFalse(CallerPolicy.isAllowed("org.other.app",
                new String[]{"org.other.app"}, packages));
    }

    @Test
    public void stopWaitsForCompletionAndCancelIsTerminal() {
        RecognitionSessionState stopped = new RecognitionSessionState();
        assertTrue(stopped.requestStop());
        assertTrue(stopped.isStopRequested());
        assertFalse(stopped.isCancelled());
        assertTrue(stopped.complete());
        assertFalse(stopped.cancel());

        RecognitionSessionState cancelled = new RecognitionSessionState();
        assertTrue(cancelled.cancel());
        assertTrue(cancelled.isCancelled());
        assertFalse(cancelled.complete());
        assertFalse(cancelled.requestStop());
    }

    @Test
    public void trimsPcmAndBuildsWav() {
        byte[] silence = pcm(16000, 1000, 0, 0, 0);
        PcmSilenceTrimmer.Result noSpeech = PcmSilenceTrimmer.trimEdges(
                silence, 16000, -50, 100);
        assertEquals(0, noSpeech.pcm.length);

        byte[] speech = pcm(16000, 1000, 300, 700, 12000);
        PcmSilenceTrimmer.Result trimmed = PcmSilenceTrimmer.trimEdges(
                speech, 16000, -50, 100);
        assertEquals(600, trimmed.pcm.length * 1000 / (16000 * 2));

        byte[] wav = WavEncoder.wrap(new byte[3200], 16000, 1);
        assertEquals(3244, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(16000, readInt(wav, 24));
    }

    @Test
    public void catalogAndPromptRemainValid() {
        for (ModelCatalog.Provider provider : ModelCatalog.providers()) {
            for (ModelCatalog.Model model : provider.models) {
                assertTrue("Rejected model: " + model.id,
                        AppPreferences.isValidModel(model.id));
            }
        }
        assertEquals("scribe_v2",
                ModelCatalog.provider(ModelCatalog.PROVIDER_ELEVENLABS).defaultModel());
        String prompt = DictationPrompt.build("ru", Arrays.asList("Hammerspoon"));
        assertTrue(prompt.contains("Russian"));
        assertTrue(prompt.contains("Hammerspoon"));
    }

    private static byte[] pcm(int sampleRate, int totalMillis,
                              int activeStartMillis, int activeEndMillis, int amplitude) {
        int samples = sampleRate * totalMillis / 1000;
        byte[] data = new byte[samples * 2];
        for (int index = 0; index < samples; index++) {
            int millis = index * 1000 / sampleRate;
            short value = (short) (millis >= activeStartMillis && millis < activeEndMillis
                    ? amplitude : 0);
            data[index * 2] = (byte) (value & 0xff);
            data[index * 2 + 1] = (byte) ((value >>> 8) & 0xff);
        }
        return data;
    }

    private static int readInt(byte[] source, int offset) {
        return (source[offset] & 0xFF)
                | ((source[offset + 1] & 0xFF) << 8)
                | ((source[offset + 2] & 0xFF) << 16)
                | ((source[offset + 3] & 0xFF) << 24);
    }

    private static void expectFailure(Runnable runnable) {
        try {
            runnable.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
