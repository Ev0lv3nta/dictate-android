package io.github.ev0lv3nta.dictate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Фиксированный список провайдеров и моделей.
 *
 * Каталог зашит в приложение намеренно: он оставляет в интерфейсе только модели,
 * для которых реализован подходящий формат запроса.
 */
final class ModelCatalog {

    static final String PROVIDER_ELEVENLABS = "elevenlabs";
    static final String PROVIDER_OPENROUTER = "openrouter";
    static final String PROVIDER_GOOGLE = "google";

    /** Как устроен запрос к модели: у каждого транспорта своя форма тела. */
    enum Transport {
        /** ElevenLabs Scribe: multipart с сырым PCM. */
        ELEVENLABS_STT,
        /** OpenRouter /audio/transcriptions: multipart с WAV. */
        OPENROUTER_STT,
        /** OpenRouter /chat/completions: WAV в base64 плюс системный промпт. */
        OPENROUTER_CHAT,
        /** Google Interactions API: специализированные модели транскрипции. */
        GOOGLE_INTERACTIONS,
        /** Google generateContent: обычная мультимодальная модель с промптом. */
        GOOGLE_GENERATE
    }

    static final class Model {
        final String id;
        final String title;
        /** Дополнительная короткая характеристика или пустая строка. */
        final String latency;
        /** Короткая приписка справа или пустая строка. */
        final String note;
        /** Короткая метка-плашка у названия или пустая строка. */
        final String badge;
        final Transport transport;
        /** Gemini через OpenRouter отказывается работать с выключенным reasoning. */
        final boolean needsReasoning;

        Model(String id, String title, String latency, String note, String badge,
              Transport transport, boolean needsReasoning) {
            this.id = id;
            this.title = title;
            this.latency = latency;
            this.note = note;
            this.badge = badge;
            this.transport = transport;
            this.needsReasoning = needsReasoning;
        }

        /** Модель понимает словарь терминов: у чат-моделей он идёт в промпт. */
        boolean supportsKeyterms() {
            return transport == Transport.ELEVENLABS_STT
                    || transport == Transport.GOOGLE_INTERACTIONS
                    || transport == Transport.OPENROUTER_CHAT
                    || transport == Transport.GOOGLE_GENERATE;
        }
    }

    static final class Provider {
        final String id;
        final String title;
        /** Подпись поля ключа в настройках. */
        final String keyLabel;
        /** Как выглядит ключ этого провайдера — подсказка при вводе своего. */
        final String keyHint;
        /** Подпись справа от заголовка секции: что означает колонка справа. */
        final String hint;
        final List<Model> models;

        Provider(String id, String title, String keyLabel, String keyHint, String hint,
                 List<Model> models) {
            this.id = id;
            this.title = title;
            this.keyLabel = keyLabel;
            this.keyHint = keyHint;
            this.hint = hint;
            this.models = Collections.unmodifiableList(new ArrayList<>(models));
        }

        String defaultModel() {
            return models.get(0).id;
        }

        Model model(String modelId) {
            for (Model model : models) {
                if (model.id.equals(modelId)) {
                    return model;
                }
            }
            return models.get(0);
        }

        boolean hasModel(String modelId) {
            for (Model model : models) {
                if (model.id.equals(modelId)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final List<Provider> PROVIDERS = Collections.unmodifiableList(Arrays.asList(
            new Provider(PROVIDER_ELEVENLABS, "ElevenLabs", "Ключ ElevenLabs", "sk_…",
                    "модели",
                    Arrays.asList(
                            new Model("scribe_v2", "Scribe v2", "",
                                    "", "по умолчанию",
                                    Transport.ELEVENLABS_STT, false))),
            new Provider(PROVIDER_OPENROUTER, "OpenRouter", "Ключ OpenRouter", "sk-or-v1-…",
                    "модели",
                    Arrays.asList(
                            new Model("mistralai/voxtral-mini-transcribe",
                                    "Voxtral Mini Transcribe", "",
                                    "", "по умолчанию",
                                    Transport.OPENROUTER_STT, false),
                            new Model("microsoft/mai-transcribe-2",
                                    "MAI Transcribe 2", "",
                                    "", "",
                                    Transport.OPENROUTER_STT, false),
                            new Model("mistralai/voxtral-small-24b-2507-stt",
                                    "Voxtral Small 24B", "",
                                    "", "",
                                    Transport.OPENROUTER_STT, false),
                            new Model("google/gemini-3.6-flash",
                                    "Gemini 3.6 Flash", "",
                                    "", "",
                                    Transport.OPENROUTER_CHAT, true),
                            new Model("google/gemini-3.8-flash",
                                    "Gemini 3.8 Flash", "",
                                    "", "",
                                    Transport.OPENROUTER_CHAT, true))),
            new Provider(PROVIDER_GOOGLE, "Google AI", "Ключ Google AI Studio", "AQ.… или AIza…",
                    "модели",
                    Arrays.asList(
                            new Model("gemini-3.5-transcribe",
                                    "Gemini 3.5 Transcribe", "",
                                    "", "по умолчанию",
                                    Transport.GOOGLE_INTERACTIONS, false),
                            new Model("gemini-3.6-flash",
                                    "Gemini 3.6 Flash", "",
                                    "", "",
                                    Transport.GOOGLE_GENERATE, false)))));

    private ModelCatalog() {
    }

    static List<Provider> providers() {
        return PROVIDERS;
    }

    static String defaultProvider() {
        return PROVIDER_ELEVENLABS;
    }

    static boolean isKnownProvider(String providerId) {
        for (Provider provider : PROVIDERS) {
            if (provider.id.equals(providerId)) {
                return true;
            }
        }
        return false;
    }

    static Provider provider(String providerId) {
        for (Provider provider : PROVIDERS) {
            if (provider.id.equals(providerId)) {
                return provider;
            }
        }
        return PROVIDERS.get(0);
    }

    static Model model(String providerId, String modelId) {
        return provider(providerId).model(modelId);
    }

    static int providerIndex(String providerId) {
        for (int index = 0; index < PROVIDERS.size(); index++) {
            if (PROVIDERS.get(index).id.equals(providerId)) {
                return index;
            }
        }
        return 0;
    }
}
