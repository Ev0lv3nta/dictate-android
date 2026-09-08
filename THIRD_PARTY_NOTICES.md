# Сторонние компоненты

MIT относится к коду Dictate. Библиотеки сохраняют собственные лицензии; их тексты включены в APK в `assets/licenses/`. В sample-клиент входят только Kotlin standard library и JetBrains annotations.

| Компонент | Версия | Лицензия и исходники |
|---|---|---|
| OkHttp | 4.12.0 | [Apache 2.0](https://github.com/square/okhttp/tree/parent-4.12.0) |
| Okio | 3.6.0 | [Apache 2.0](https://github.com/square/okio/tree/parent-3.6.0) |
| Kotlin standard library | 2.2.10; JDK adapters 1.9.10 | [Apache 2.0](https://github.com/JetBrains/kotlin) |
| JetBrains annotations | 13.0 | [Apache 2.0](https://github.com/JetBrains/java-annotations) |
| Public Suffix List, данные в OkHttp | Снимок из OkHttp 4.12.0 | [MPL 2.0, исходный список](https://publicsuffix.org/list/public_suffix_list.dat) |

Уведомление OkHttp о Public Suffix List сохранено без изменений в `okhttp3/internal/publicsuffix/NOTICE`. Обновление зависимостей должно обновлять и этот список. Лицензии инструментов сборки и тестовых библиотек указаны в их собственных репозиториях; эти инструменты не входят в приложение.
