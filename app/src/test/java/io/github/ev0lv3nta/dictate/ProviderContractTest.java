package io.github.ev0lv3nta.dictate;

import org.junit.Test;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** Fixtures follow the REST contracts linked in docs/testing.md; no live calls. */
public final class ProviderContractTest {
    private static final String KEY = "test-credential-not-valid";
    private static final byte[] AUDIO = new byte[16000];
    private static String body(Transcription.Body body) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            body.writeTo(bytes);
            assertEquals(body.length(), bytes.size());
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private static Transcription.Config config(String provider, String model) {
        return new Transcription.Config(provider, model, "en-GB", Collections.emptyList());
    }

    @Test public void elevenLabsUsesRawPcmMultipart() throws Exception {
        Transcription.Request request = new Transcription.Request((url, headers, type, data) -> {
            assertEquals("https://api.elevenlabs.io/v1/speech-to-text", url);
            assertEquals(KEY, headers.get("xi-api-key"));
            assertTrue(type.startsWith("multipart/form-data; boundary="));
            String wire = body(data);
            assertTrue(wire.contains("pcm_s16le_16"));
            assertTrue(wire.contains("scribe_v2"));
            assertTrue(wire.contains("name=\"file\""));
            return "{\"text\":\"Hello, мир.\"}";
        });
        assertEquals("Hello, мир.", new ElevenLabsClient().transcribe(AUDIO, KEY,
                config("elevenlabs", "scribe_v2"), request));
    }

    @Test public void openRouterSttUsesWavUpload() throws Exception {
        Transcription.Request request = new Transcription.Request((url, headers, type, data) -> {
            assertEquals("https://openrouter.ai/api/v1/audio/transcriptions", url);
            assertEquals("Bearer " + KEY, headers.get("Authorization"));
            String wire = body(data);
            assertTrue(wire.contains("RIFF"));
            assertTrue(wire.contains("audio/wav"));
            assertTrue(wire.contains("mistralai/voxtral-mini-transcribe"));
            return "{\"text\":\"Hello world\",\"usage\":{}}";
        });
        assertEquals("Hello world", new OpenRouterClient().transcribe(AUDIO, KEY,
                config("openrouter", "mistralai/voxtral-mini-transcribe"), request));
    }

    @Test public void googleUsesStatelessInteractionAndPreservesRegion() throws Exception {
        Transcription.Request request = new Transcription.Request((url, headers, type, data) -> {
            assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", url);
            assertEquals(KEY, headers.get("x-goog-api-key"));
            try {
                JSONObject json = new JSONObject(body(data));
                assertFalse(json.getBoolean("store"));
                assertEquals("en-GB", json.getJSONObject("generation_config")
                        .getJSONObject("transcription_config").getJSONArray("language_codes").getString(0));
                byte[] wav = java.util.Base64.getDecoder().decode(json.getJSONArray("input").getJSONObject(0).getString("data"));
                assertEquals(AUDIO.length + 44, wav.length);
            } catch (Exception error) { throw new AssertionError(error); }
            return "{\"status\":\"completed\",\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}]}";
        });
        assertEquals("Hello", new GoogleAiClient().transcribe(AUDIO, KEY,
                config("google", "gemini-3.5-transcribe"), request));
    }

    @Test public void chatRequestUsesWavAndIgnoresReasoningParts() throws Exception {
        Transcription.Request request = new Transcription.Request((url,headers,type,data) -> {
            assertEquals("https://openrouter.ai/api/v1/chat/completions", url);
            assertEquals("Bearer " + KEY, headers.get("Authorization"));
            try {
                JSONObject json = new JSONObject(body(data));
                assertEquals("google/gemini-3.6-flash", json.getString("model"));
                assertEquals(1, json.getInt("top_p"));
                assertEquals("wav", json.getJSONArray("messages").getJSONObject(1)
                        .getJSONArray("content").getJSONObject(0).getJSONObject("input_audio").getString("format"));
            } catch (Exception error) { throw new AssertionError(error); }
            return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[{\"type\":\"reasoning\",\"text\":\"private thought\"},{\"type\":\"text\",\"text\":\"Hello\"}]}}]}";
        });
        assertEquals("Hello",new OpenRouterClient().transcribe(AUDIO,KEY,
                config("openrouter","google/gemini-3.6-flash"),request));
    }

    @Test public void googleGenerateUsesAudioAndSkipsThoughts() throws Exception {
        Transcription.Request request = new Transcription.Request((url,headers,type,data) -> {
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent",url);
            assertEquals(KEY,headers.get("x-goog-api-key"));
            assertTrue(body(data).contains("inline_data"));
            return "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"private\"},{\"text\":\"Hello\"}]}}]}";
        });
        assertEquals("Hello",new GoogleAiClient().transcribe(AUDIO,KEY,
                config("google","gemini-3.6-flash"),request));
    }

    @Test public void unknownModelAndOversizedAudioNeverReachTransport() throws Exception {
        Transcription.Request forbidden = new Transcription.Request((u,h,t,b) -> { fail("Invalid request sent"); return ""; });
        try {
            new ElevenLabsClient().transcribe(AUDIO,KEY,config("elevenlabs","unknown"),forbidden);
            fail();
        } catch (Transcription.ApiException error) { assertEquals(Transcription.ErrorKind.INVALID_REQUEST,error.kind); }
        try {
            Transcription.requireAudio(new byte[9600002]); fail();
        } catch (Transcription.ApiException error) { assertEquals(Transcription.ErrorKind.INVALID_REQUEST,error.kind); }
    }

    @Test public void transcriptCannotOverflowBinderCallback() throws Exception {
        char[] text=new char[32769]; Arrays.fill(text,'x');
        try { Transcription.requireText("fixture",new String(text),200); fail(); }
        catch (Transcription.ApiException error) { assertEquals(Transcription.ErrorKind.INVALID_RESPONSE,error.kind); }
    }

    @Test public void chatRejectsTruncationAndRefusal() throws Exception {
        for (String response : Arrays.asList(
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"refusal\":\"no\",\"content\":\"no\"}}]}")) {
            try {
                new OpenRouterClient().transcribe(AUDIO, KEY, config("openrouter", "google/gemini-3.6-flash"),
                        new Transcription.Request((u,h,t,b) -> response));
                fail("Incomplete/refused response accepted");
            } catch (Transcription.ApiException error) { assertEquals(Transcription.ErrorKind.INVALID_RESPONSE, error.kind); }
        }
    }

    @Test public void exceptionsNeverExposeProviderTextOrCause() {
        String sensitive = "private dictated sentence";
        Transcription.ApiException error = new Transcription.ApiException(Transcription.ErrorKind.SERVER,
                500, sensitive, new Exception(sensitive));
        assertFalse(error.toString().contains(sensitive));
        assertNull(error.getCause());
    }

    @Test public void cancelledRequestCannotReachTransport() throws Exception {
        Transcription.Request request = new Transcription.Request((u,h,t,b) -> { fail("Cancelled request sent"); return ""; });
        request.cancel();
        try {
            new ElevenLabsClient().transcribe(AUDIO, KEY, config("elevenlabs", "scribe_v2"), request);
            fail("Cancellation lost");
        } catch (Transcription.ApiException error) { assertEquals(Transcription.ErrorKind.CANCELLED, error.kind); }
    }

    @Test public void preservesScriptAndRejectsBadPcm() {
        assertEquals("zh-Hant-TW", AppPreferences.normalizeLanguage("zh-Hant-TW"));
        assertEquals("pt-BR", DictationPrompt.bcp47("pt-BR"));
        for (int channels : new int[]{0,3}) {
            try { WavEncoder.wrap(AUDIO, 16000, channels); fail(); } catch (IllegalArgumentException expected) { }
        }
        try { WavEncoder.wrap(new byte[3], 16000, 1); fail(); } catch (IllegalArgumentException expected) { }
    }
}
