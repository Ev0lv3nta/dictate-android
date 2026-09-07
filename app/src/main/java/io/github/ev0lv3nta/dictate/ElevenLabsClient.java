package io.github.ev0lv3nta.dictate;

import org.json.JSONObject;

final class ElevenLabsClient implements Transcription.Client {

    private static final String NAME = "ElevenLabs";
    private static final String ENDPOINT = "https://api.elevenlabs.io/v1/speech-to-text";

    @Override
    public String transcribe(byte[] pcm, String apiKey, Transcription.Config config,
                             Transcription.Request request) throws Transcription.ApiException {
        Transcription.requireKey(apiKey, NAME);
        Transcription.validateConfig(config);
        Transcription.requireAudio(pcm);

        Transcription.Multipart multipart = new Transcription.Multipart()
                .field("model_id", config.model)
                .field("tag_audio_events", "false")
                .field("timestamps_granularity", "none")
                .field("file_format", "pcm_s16le_16")
                .field("language_code", java.util.Locale.forLanguageTag(config.language).getLanguage());
        for (String keyterm : config.keyterms) {
            multipart.field("keyterms", keyterm);
        }
        // Scribe принимает сырой PCM без заголовка: сообщаем формат полем file_format.
        Transcription.Body body =
                multipart.file("file", "audio.pcm", "application/octet-stream", pcm);

        String response = Transcription.post(NAME, ENDPOINT,
                Transcription.headers("xi-api-key", apiKey),
                multipart.contentType(), body, request);
        JSONObject json = Transcription.parseJson(NAME, response, 200);
        return Transcription.requireText(NAME, json.optString("text", ""), 200);
    }
}
