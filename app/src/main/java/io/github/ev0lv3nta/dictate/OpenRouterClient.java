package io.github.ev0lv3nta.dictate;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class OpenRouterClient implements Transcription.Client {

    private static final String NAME = "OpenRouter";
    private static final String STT_ENDPOINT =
            "https://openrouter.ai/api/v1/audio/transcriptions";
    private static final String CHAT_ENDPOINT =
            "https://openrouter.ai/api/v1/chat/completions";

    @Override
    public String transcribe(byte[] pcm, String apiKey, Transcription.Config config,
                             Transcription.Request request) throws Transcription.ApiException {
        Transcription.requireKey(apiKey, NAME);
        Transcription.requireAudio(pcm);

        byte[] wav = WavEncoder.wrap(pcm, AudioCapture.SAMPLE_RATE, 1);
        ModelCatalog.Model model = ModelCatalog.model(ModelCatalog.PROVIDER_OPENROUTER,
                config.model);
        return model.transport == ModelCatalog.Transport.OPENROUTER_CHAT
                ? viaChat(wav, apiKey, config, model, request)
                : viaSpeechToText(wav, apiKey, config, request);
    }

    /** Специализированные STT-модели: Voxtral, MAI. Отдают только текст. */
    private String viaSpeechToText(byte[] wav, String apiKey, Transcription.Config config,
                                   Transcription.Request request)
            throws Transcription.ApiException {
        Transcription.Multipart multipart = new Transcription.Multipart()
                .field("model", config.model)
                .field("language", config.language);
        Transcription.Body body = multipart.file("file", "audio.wav", "audio/wav", wav);

        String response = Transcription.post(NAME, STT_ENDPOINT,
                Transcription.headers("Authorization", "Bearer " + apiKey),
                multipart.contentType(), body, request);
        JSONObject json = Transcription.parseJson(NAME, response, 200);
        return Transcription.requireText(NAME, json.optString("text", ""), 200);
    }

    /**
     * Чат-модели: аудио уходит в base64 внутри сообщения, а поведение задаёт
     * системный промпт. temperature=0 требует top_p=1 — иначе Mistral отвечает
     * 400 «top_p must be 1 when using greedy sampling».
     */
    private String viaChat(byte[] wav, String apiKey, Transcription.Config config,
                           ModelCatalog.Model model, Transcription.Request request)
            throws Transcription.ApiException {
        String json;
        try {
            JSONObject audio = new JSONObject()
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                    .put("format", "wav");
            JSONArray content = new JSONArray().put(new JSONObject()
                    .put("type", "input_audio")
                    .put("input_audio", audio));
            JSONArray messages = new JSONArray()
                    .put(new JSONObject()
                            .put("role", "system")
                            .put("content", DictationPrompt.build(config.language,
                                    config.keyterms)))
                    .put(new JSONObject().put("role", "user").put("content", content));

            JSONObject body = new JSONObject()
                    .put("model", config.model)
                    .put("messages", messages)
                    .put("temperature", 0)
                    .put("top_p", 1);
            if (model.needsReasoning) {
                // Gemini на этом эндпоинте отказывается работать с reasoning:none,
                // а "low" не заметен по времени ответа.
                body.put("reasoning", new JSONObject().put("effort", "low"));
            }
            json = body.toString();
        } catch (JSONException error) {
            throw new Transcription.ApiException(Transcription.ErrorKind.INVALID_REQUEST,
                    "Не удалось собрать запрос OpenRouter", error);
        }

        String response = Transcription.post(NAME, CHAT_ENDPOINT,
                Transcription.headers("Authorization", "Bearer " + apiKey),
                "application/json", Transcription.jsonBody(json), request);
        return Transcription.requireText(NAME, extractChatText(response), 200);
    }

    private static String extractChatText(String response) throws Transcription.ApiException {
        JSONObject json = Transcription.parseJson(NAME, response, 200);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return "";
        }
        JSONObject message = choices.optJSONObject(0) == null
                ? null : choices.optJSONObject(0).optJSONObject("message");
        if (message == null) {
            return "";
        }
        Object content = message.opt("content");
        if (content instanceof String) {
            return (String) content;
        }
        // Некоторые модели возвращают content массивом частей.
        if (content instanceof JSONArray) {
            StringBuilder text = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int index = 0; index < parts.length(); index++) {
                JSONObject part = parts.optJSONObject(index);
                if (part != null) {
                    text.append(part.optString("text", ""));
                }
            }
            return text.toString();
        }
        return "";
    }
}
