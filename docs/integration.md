# Подключение клиента

Dictate — не клавиатура и не перехватчик кнопки микрофона. Клиент должен обращаться к Android `SpeechRecognizer`. Выбор системного распознавателя, запуск `RecognizerIntent` Activity и переключение IME — разные механизмы; наличие одного не означает поддержку остальных.

Рабочий пример — модуль [`sample-client`](../sample-client). Установите оба приложения, выдайте каждому разрешение на микрофон, добавьте `io.github.ev0lv3nta.dictate.sample` в разрешённые приложения Dictate и разрешите видимый индикатор записи. В sample нажмите Start, произнесите фразу и нажмите Stop. Cancel отменяет текущий запрос.

```java
SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(context,
        new ComponentName("io.github.ev0lv3nta.dictate",
                "io.github.ev0lv3nta.dictate.DictateRecognitionService"));
recognizer.setRecognitionListener(listener);
recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"));
```

Все вызовы выполняются в main thread. Клиент запрашивает `RECORD_AUDIO`, добавляет видимость сервиса в manifest и вызывает `cancel()` при уходе, `destroy()` при завершении. Полный lifecycle и manifest есть в sample. `EXTRA_CALLING_PACKAGE` не предоставляет доступа.

| Возможность | Контракт |
|---|---|
| Язык | Override в Dictate → `EXTRA_LANGUAGE` → auto |
| Регион и script | Сохраняются; STT ElevenLabs/OpenRouter получает базовый язык по контракту API |
| Результат | Один текст в `RESULTS_RECOGNITION`; confidence не выдумывается |
| Stop | Завершить аудио, дождаться результата |
| Cancel | Прервать захват/HTTP, не возвращать поздний результат |
| Partial results | Не поддерживаются; возвращается финальный текст |
| Offline, segmented session, внешний AudioSource | Отказ `ERROR_CLIENT`, без отправки аудио |
| Model download | Нет: модели работают на стороне провайдера |

На Android 12+ сервис защищён `BIND_SPEECH_RECOGNITION_SERVICE`, а связывание выполняет системный speech manager. На Android 8–11 клиент связывается напрямую; для этих версий manifest требует `RECORD_AUDIO`, затем сервис обязательно проверяет UID и allowlist. Это не открытый доступ к ключу.

Некоторые прошивки позволяют выбрать Dictate системным провайдером. Закрытая клавиатура может игнорировать этот выбор и всегда использовать свой движок. Устанавливать Dictate ради такой клавиатуры без проверки её контракта бессмысленно; универсальная совместимость не заявляется.

Проверка исходников [AnySoftKeyboard](https://github.com/AnySoftKeyboard/AnySoftKeyboard/blob/main/ime/voiceime/src/main/java/com/google/android/voiceime/VoiceRecognitionTrigger.java) показала переключение на voice IME либо [RecognizerIntent Activity](https://github.com/AnySoftKeyboard/AnySoftKeyboard/blob/main/ime/voiceime/src/main/java/com/google/android/voiceime/IntentApiTrigger.java). Это не прямой `SpeechRecognizer`-контракт Dictate, поэтому готовая совместимость с этой клавиатурой не заявляется. Её код не изменялся; проверенным внешним клиентом остаётся sample.
