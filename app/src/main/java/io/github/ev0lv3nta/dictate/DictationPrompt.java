package io.github.ev0lv3nta.dictate;

import java.util.List;
import java.util.Locale;

/**
 * Системный промпт для чат-моделей.
 *
 * Обычная мультимодальная модель без инструкции ведёт себя как собеседник:
 * пересказывает запись, добавляет «Вот транскрипция:», кавычки и markdown.
 * Диктовке нужен голый текст, поэтому промпт запрещает всё лишнее, а язык
 * фиксируется явно — иначе русская речь иногда приходит переведённой.
 */
final class DictationPrompt {

    private static final int MAX_PROMPT_TERMS = 200;

    private DictationPrompt() {
    }

    static String build(String language, List<String> keyterms) {
        StringBuilder prompt = new StringBuilder(
                "You are a speech-to-text engine. Transcribe the audio verbatim");
        if (language != null && !language.isEmpty()) {
            prompt.append(" in ").append(englishName(language));
        } else {
            prompt.append(" in its original language");
        }
        prompt.append(". Do not translate, summarise, answer or comment. "
                + "Output only the transcript text: no quotes, no speaker labels, "
                + "no markdown, no leading or trailing whitespace. "
                + "Keep the speaker's own wording and punctuation. "
                + "If the audio contains no speech, output nothing.");

        if (keyterms != null && !keyterms.isEmpty()) {
            prompt.append("\n\nThese terms may occur; spell them exactly as written: ");
            int count = 0;
            for (String term : keyterms) {
                if (count >= MAX_PROMPT_TERMS) {
                    break;
                }
                if (count > 0) {
                    prompt.append(", ");
                }
                prompt.append(term);
                count++;
            }
            prompt.append('.');
        }
        return prompt.toString();
    }

    /**
     * Модель понимает название языка надёжнее, чем код: "ru" в промпте иногда
     * читается как кусок текста, а "Russian" — никогда.
     */
    static String englishName(String language) {
        String name = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH);
        return name == null || name.isEmpty() || name.equals(language)
                ? "the language of the recording" : name;
    }

    /** Приводит наш двухбуквенный код к BCP-47, который ждёт Google. */
    static String bcp47(String language) {
        return AppPreferences.normalizeLanguage(language);
    }

}
