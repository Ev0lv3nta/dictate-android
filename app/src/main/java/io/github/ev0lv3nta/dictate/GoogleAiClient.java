package io.github.ev0lv3nta.dictate;

import java.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GoogleAiClient implements Transcription.Client {

    private static final String NAME = "Google AI";
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta";
    /** Инлайн-аудио у Google ограничено 20 МБ вместе с base64-раздуванием. */
    private static final int MAX_INLINE_BYTES = 14 * 1024 * 1024;
    private static final int MAX_VOCABULARY_TERMS = 1000;

    @Override
    public String transcribe(byte[] pcm, String apiKey, Transcription.Config config,
                             Transcription.Request request) throws Transcription.ApiException {
        Transcription.requireKey(apiKey, NAME);
        Transcription.requireAudio(pcm);
        if (pcm.length > MAX_INLINE_BYTES) {
            throw new Transcription.ApiException(Transcription.ErrorKind.INVALID_REQUEST,
                    "Запись слишком длинная для Google AI");
        }

        byte[] wav = WavEncoder.wrap(pcm, AudioCapture.SAMPLE_RATE, 1);
        String audio = Base64.getEncoder().encodeToString(wav);
        ModelCatalog.Model model = ModelCatalog.model(ModelCatalog.PROVIDER_GOOGLE, config.model);
        return model.transport == ModelCatalog.Transport.GOOGLE_GENERATE
                ? viaGenerateContent(audio, apiKey, config, request)
                : viaInteractions(audio, apiKey, config, request);
    }

    /**
     * Interactions API: модели gemini-*-transcribe. Словарь терминов уходит
     * в custom_vocabulary — он несовместим с таймкодами, но они нам не нужны.
     */
    private String viaInteractions(String audio, String apiKey, Transcription.Config config,
                                   Transcription.Request request)
            throws Transcription.ApiException {
        String json;
        try {
            JSONObject transcriptionConfig = new JSONObject();
            String bcp47 = DictationPrompt.bcp47(config.language);
            if (!bcp47.isEmpty()) {
                transcriptionConfig.put("language_codes", new JSONArray().put(bcp47));
            }
            if (!config.keyterms.isEmpty()) {
                JSONArray vocabulary = new JSONArray();
                for (String term : config.keyterms) {
                    if (vocabulary.length() >= MAX_VOCABULARY_TERMS) {
                        break;
                    }
                    vocabulary.put(term);
                }
                transcriptionConfig.put("custom_vocabulary", vocabulary);
            }
            JSONObject input = new JSONObject()
                    .put("type", "audio")
                    .put("data", audio)
                    .put("mime_type", "audio/wav");
            json = new JSONObject()
                    .put("store", false)
                    .put("model", config.model)
                    .put("input", new JSONArray().put(input))
                    .put("generation_config", new JSONObject()
                            .put("transcription_config", transcriptionConfig))
                    .toString();
        } catch (JSONException error) {
            throw new Transcription.ApiException(Transcription.ErrorKind.INVALID_REQUEST,
                    "Не удалось собрать запрос Google AI", error);
        }

        String response = Transcription.post(NAME, BASE + "/interactions",
                Transcription.headers("x-goog-api-key", apiKey),
                "application/json", Transcription.jsonBody(json), request);
        JSONObject result = Transcription.parseJson(NAME, response, 200);
        if (!"completed".equals(result.optString("status")))
            throw new Transcription.ApiException(Transcription.ErrorKind.INVALID_RESPONSE,"Incomplete interaction");
        String text = result.optString("output_text", "");
        if (text.trim().isEmpty()) {
            text = collectStepText(result);
        }
        return Transcription.requireText(NAME, text, 200);
    }

    /** Обычная мультимодальная модель: аудио как inline_data плюс системный промпт. */
    private String viaGenerateContent(String audio, String apiKey, Transcription.Config config,
                                      Transcription.Request request)
            throws Transcription.ApiException {
        String json;
        try {
            JSONObject inlineData = new JSONObject()
                    .put("mime_type", "audio/wav")
                    .put("data", audio);
            JSONObject contents = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray()
                            .put(new JSONObject().put("inline_data", inlineData)));
            json = new JSONObject()
                    .put("system_instruction", new JSONObject()
                            .put("parts", new JSONArray().put(new JSONObject()
                                    .put("text", DictationPrompt.build(config.language,
                                            config.keyterms)))))
                    .put("contents", new JSONArray().put(contents))
                    .put("generationConfig", new JSONObject().put("temperature", 0))
                    .toString();
        } catch (JSONException error) {
            throw new Transcription.ApiException(Transcription.ErrorKind.INVALID_REQUEST,
                    "Не удалось собрать запрос Google AI", error);
        }

        String response = Transcription.post(NAME,
                BASE + "/models/" + config.model + ":generateContent",
                Transcription.headers("x-goog-api-key", apiKey),
                "application/json", Transcription.jsonBody(json), request);
        JSONObject result = Transcription.parseJson(NAME, response, 200);
        return Transcription.requireText(NAME, collectCandidateText(result), 200);
    }

    private static String collectCandidateText(JSONObject result) throws Transcription.ApiException {
        JSONArray candidates = result.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return "";
        }
        JSONObject first = candidates.optJSONObject(0);
        if (first == null || !"STOP".equals(first.optString("finishReason")))
            throw new Transcription.ApiException(Transcription.ErrorKind.INVALID_RESPONSE,"Incomplete candidate");
        JSONObject content = first == null ? null : first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        return joinText(parts);
    }

    /** Резервный путь: текст лежит внутри шагов, если output_text пуст. */
    private static String collectStepText(JSONObject result) {
        JSONArray steps = result.optJSONArray("steps");
        if (steps == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < steps.length(); index++) {
            JSONObject step = steps.optJSONObject(index);
            if (step != null && "model_output".equals(step.optString("type"))) {
                text.append(joinText(step.optJSONArray("content")));
            }
        }
        return text.toString();
    }

    private static String joinText(JSONArray parts) {
        if (parts == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < parts.length(); index++) {
            JSONObject part = parts.optJSONObject(index);
            if (part != null && !part.optBoolean("thought",false)
                    && (!part.has("type") || "text".equals(part.optString("type")))) {
                text.append(part.optString("text", ""));
            }
        }
        return text.toString();
    }
}
