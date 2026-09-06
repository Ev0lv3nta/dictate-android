# Dictate

[![CI](https://github.com/Ev0lv3nta/dictate-android/actions/workflows/ci.yml/badge.svg)](https://github.com/Ev0lv3nta/dictate-android/actions/workflows/ci.yml)
[![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Dictate — самостоятельный сервис распознавания речи для Android. Он записывает голос, убирает тишину по краям, отправляет аудио выбранному STT-провайдеру и возвращает результат через стандартный `SpeechRecognizer`.

Поддерживаются ElevenLabs, OpenRouter и Google AI Studio. Ключи вводятся в приложении и шифруются ключом из Android Keystore; в исходниках и APK ключей нет.

## Возможности

- системный `RecognitionService` с явным списком разрешённых клиентов;
- автостоп по тишине и ограничение длительности записи;
- выбор провайдера, модели, языка и словаря терминов;
- локальная история из десяти последних записей;
- повторное распознавание сохранённого аудио другой моделью;
- отсутствие аналитики и сторонних SDK.

## Установка и настройка

Готовая сборка публикуется в [Releases](https://github.com/Ev0lv3nta/dictate-android/releases). Для работы нужны Android 8.0 или новее, разрешение на микрофон и API-ключ выбранного провайдера.

После установки:

1. Разрешите доступ к микрофону и показ поверх других окон.
2. Выберите провайдера и сохраните его API-ключ.
3. Добавьте package name клавиатуры или другого клиента в «Разрешённые приложения».
4. Настройте клиент на компонент `io.github.ev0lv3nta.dictate/.DictateRecognitionService`.

Пустой список клиентов блокирует все внешние вызовы. Package name установленного приложения можно узнать через его manifest или ADB.

## Подключение клиента

Клиент может обратиться к сервису через явный компонент:

```java
ComponentName component = new ComponentName(
        "io.github.ev0lv3nta.dictate",
        "io.github.ev0lv3nta.dictate.DictateRecognitionService");
SpeechRecognizer recognizer = SpeechRecognizer.createSpeechRecognizer(context, component);
```

На прошивках, где Android позволяет выбрать системный сервис распознавания, Dictate также виден по intent `android.speech.RecognitionService`.

Совместимость с любой закрытой клавиатурой не гарантируется: часть клавиатур всегда использует собственный движок и не позволяет заменить его. Для таких приложений требуется поддержка системного `SpeechRecognizer` со стороны разработчика клавиатуры.

## Сборка

Нужны JDK 17 и Android SDK 36:

```bash
./gradlew test lint assembleDebug assembleRelease
```

Debug APK появится в `app/build/outputs/apk/debug/`. Release-сборка остаётся неподписанной: ключ подписи хранится у владельца приложения и не входит в репозиторий.

Наличие конкретных моделей и условия API меняются у провайдеров. Каталог моделей находится в `ModelCatalog.java` и обновляется вместе с приложением.

## Состояние проекта

Версия `0.1.0` проходит локальную сборку, unit-тесты и Android Lint. Интеграция нового универсального компонента с реальным устройством пока не проверена.

Лицензия — [MIT](LICENSE). Правила для изменений находятся в [CONTRIBUTING.md](CONTRIBUTING.md), описание данных — в [PRIVACY.md](PRIVACY.md).
