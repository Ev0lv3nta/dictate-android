package io.github.ev0lv3nta.dictate;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Общие для всех провайдеров типы и HTTP-обвязка.
 *
 * Каждый провайдер отличается только формой запроса и тем, где в ответе лежит
 * текст; отмена, таймауты и разбор ошибок одинаковы и живут здесь.
 */
final class Transcription {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TRANSCRIPT_CHARS = 32768;

    private Transcription() {
    }

    enum ErrorKind {
        AUTH,
        BILLING,
        RATE_LIMIT,
        INVALID_RESPONSE,
        NETWORK,
        TIMEOUT,
        INVALID_REQUEST,
        NO_MATCH,
        SERVER,
        CANCELLED
    }

    static final class ApiException extends Exception {
        private static final long serialVersionUID = 2L;

        final ErrorKind kind;
        final int httpStatus;

        ApiException(ErrorKind kind, String message) {
            this(kind, 0, message, null);
        }

        ApiException(ErrorKind kind, String message, Throwable cause) {
            this(kind, 0, message, cause);
        }

        ApiException(ErrorKind kind, int httpStatus, String message, Throwable cause) {
            // Exception messages and causes must never expose a provider response.
            super(safeMessage(kind));
            this.kind = kind;
            this.httpStatus = httpStatus;
        }
    }

    /** Настройки одного распознавания: провайдер, модель, язык, словарь. */
    static final class Config {
        final String provider;
        final String model;
        final String language;
        final List<String> keyterms;

        Config(String provider, String model, String language, List<String> keyterms) {
            this.provider = provider;
            this.model = model;
            this.language = language;
            this.keyterms = java.util.Collections.unmodifiableList(keyterms == null
                    ? new ArrayList<String>() : new ArrayList<>(keyterms));
        }
    }

    /** Ручка отмены: закрывает соединение, если клавиатура успела передумать. */
    static final class Request {
        final Transport transport;
        Request() { this(null); }
        Request(Transport transport) { this.transport = transport; }
        private volatile boolean cancelled;
        private volatile okhttp3.Call connection;

        void cancel() {
            cancelled = true;
            okhttp3.Call current = connection;
            if (current != null) {
                current.cancel();
            }
        }

        boolean isCancelled() {
            return cancelled;
        }

        void attach(okhttp3.Call value) {
            connection = value;
            if (cancelled) {
                value.cancel();
            }
        }

        void detach(okhttp3.Call value) {
            if (connection == value) {
                connection = null;
            }
        }
    }

    interface Client {
        String transcribe(byte[] pcm, String apiKey, Config config, Request request)
                throws ApiException;
    }

    interface Transport {
        String post(String url, Map<String, String> headers, String contentType, Body body)
                throws ApiException;
    }

    /** Streaming request with a known length; no extra full-size audio copy. */
    interface Body {
        long length();

        void writeTo(OutputStream output) throws IOException;
    }

    static Client clientFor(String provider) {
        if (ModelCatalog.PROVIDER_OPENROUTER.equals(provider)) {
            return new OpenRouterClient();
        }
        if (ModelCatalog.PROVIDER_GOOGLE.equals(provider)) {
            return new GoogleAiClient();
        }
        if (ModelCatalog.PROVIDER_ELEVENLABS.equals(provider)) return new ElevenLabsClient();
        throw new IllegalArgumentException("Unsupported provider");
    }

    static void validateConfig(Config config) throws ApiException {
        if (!ModelCatalog.isKnownProvider(config.provider)
                || !ModelCatalog.provider(config.provider).hasModel(config.model)) {
            throw new ApiException(ErrorKind.INVALID_REQUEST, "Unsupported configuration");
        }
        ModelCatalog.Model model = ModelCatalog.model(config.provider, config.model);
        if (config.keyterms.size() > 1000 || config.language == null
                || (!config.language.isEmpty() && !config.language.equals(AppPreferences.normalizeLanguage(config.language))))
            throw new ApiException(ErrorKind.INVALID_REQUEST, "Invalid language or vocabulary");
        if (!config.keyterms.isEmpty() && (!model.supportsKeyterms()
                || ((model.transport == ModelCatalog.Transport.OPENROUTER_CHAT
                || model.transport == ModelCatalog.Transport.GOOGLE_GENERATE) && config.keyterms.size() > 200))) {
            throw new ApiException(ErrorKind.INVALID_REQUEST, "Unsupported vocabulary");
        }
    }

    static String userMessage(android.content.Context context, ErrorKind kind) {
        int[] messages = {R.string.error_auth, R.string.error_billing, R.string.error_rate_limit, R.string.error_invalid_response, R.string.error_network, R.string.error_timeout, R.string.error_invalid_request, R.string.error_no_match, R.string.error_server, R.string.error_cancelled};
        return context.getString(messages[kind.ordinal()]);
    }

    static String safeMessage(ErrorKind kind) {
        switch (kind) {
            case AUTH: return "Ключ отсутствует или отклонён. Проверьте настройки провайдера.";
            case BILLING: return "Лимит API исчерпан. Проверьте аккаунт провайдера.";
            case RATE_LIMIT: return "Слишком много запросов. Повторите позже.";
            case INVALID_RESPONSE: return "Провайдер вернул некорректный или неполный ответ.";
            case NETWORK: return "Не удалось подключиться. Проверьте сеть и повторите.";
            case TIMEOUT: return "Время ожидания истекло. Повторите запрос вручную.";
            case NO_MATCH: return "Речь не обнаружена.";
            case INVALID_REQUEST: return "Неподдерживаемые настройки. Проверьте модель, язык и словарь.";
            case CANCELLED: return "Запрос отменён.";
            default: return "Ошибка ответа провайдера. Повторите запрос вручную.";
        }
    }

    static int androidError(ErrorKind kind) {
        switch (kind) {
            case NETWORK: return android.speech.SpeechRecognizer.ERROR_NETWORK;
            case TIMEOUT: return android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT;
            case NO_MATCH: return android.speech.SpeechRecognizer.ERROR_NO_MATCH;
            case SERVER: return android.speech.SpeechRecognizer.ERROR_SERVER;
            default: return android.speech.SpeechRecognizer.ERROR_CLIENT;
        }
    }

    static void requireKey(String apiKey, String provider) throws ApiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException(ErrorKind.AUTH, "Ключ " + provider + " не задан");
        }
    }

    static void requireAudio(byte[] pcm) throws ApiException {
        if (pcm != null && (pcm.length % 2 != 0 || pcm.length > 9600000))
            throw new ApiException(ErrorKind.INVALID_REQUEST,"Invalid PCM size");
        if (pcm == null || pcm.length < AudioCapture.SAMPLE_RATE / 5 * 2) {
            throw new ApiException(ErrorKind.NO_MATCH, "Слишком короткая запись");
        }
    }

    /** POST с телом произвольной длины; на 2xx возвращает тело ответа. */
    static String post(String provider, String url, Map<String, String> headers,
                       String contentType, Body body, Request request) throws ApiException {
        if (request.isCancelled()) {
            throw new ApiException(ErrorKind.CANCELLED, "Запрос отменён");
        }
        if (request.transport != null) return request.transport.post(url, headers, contentType, body);
        okhttp3.Call connection = null;
        try {
            okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "DictateAndroid/" + BuildConfig.VERSION_NAME);
            for (Map.Entry<String, String> header : headers.entrySet()) builder.header(header.getKey(), header.getValue());
            builder.post(new okhttp3.RequestBody() {
                @Override public okhttp3.MediaType contentType() { return okhttp3.MediaType.get(contentType); }
                @Override public long contentLength() { return body.length(); }
                @Override public boolean isOneShot() { return true; }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException { body.writeTo(sink.outputStream()); }
            });
            connection = HttpPolicy.CLIENT.newCall(builder.build());
            request.attach(connection);
            if (request.isCancelled()) {
                throw new ApiException(ErrorKind.CANCELLED, "Запрос отменён");
            }
            try (okhttp3.Response response = connection.execute()) {
                if (!response.isSuccessful()) throw httpError(provider, response.code(), "");
                if (response.body() == null) throw new ApiException(ErrorKind.INVALID_RESPONSE, "Empty response");
                String content = readUtf8(response.body().byteStream(), MAX_RESPONSE_BYTES);
                String head = content.trim();
                if (head.isEmpty() || head.charAt(0) != '{') throw new ApiException(ErrorKind.INVALID_RESPONSE, "Invalid JSON");
                return content;
            }
        } catch (java.io.InterruptedIOException error) {
            if (request.isCancelled()) throw new ApiException(ErrorKind.CANCELLED, "Cancelled");
            throw new ApiException(ErrorKind.TIMEOUT, provider + " не ответил вовремя", error);
        } catch (ApiException error) {
            throw error;
        } catch (IOException error) {
            if (request.isCancelled()) {
                throw new ApiException(ErrorKind.CANCELLED, "Запрос отменён", error);
            }
            throw new ApiException(ErrorKind.NETWORK,
                    "Ошибка сети при запросе " + provider, error);
        } finally {
            if (connection != null) {
                request.detach(connection);
                connection.cancel();
            }
        }
    }

    static Map<String, String> headers(String name, String value) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put(name, value);
        return result;
    }

    static JSONObject parseJson(String provider, String response, int status)
            throws ApiException {
        try {
            return new JSONObject(response);
        } catch (JSONException error) {
            throw new ApiException(ErrorKind.INVALID_RESPONSE, status,
                    provider + " вернул некорректный JSON", error);
        }
    }

    static String requireText(String provider, String text, int status) throws ApiException {
        String value = text == null ? "" : text.trim();
        // Keep callbacks and saved Activity state comfortably below Binder's limit.
        if (value.length() > MAX_TRANSCRIPT_CHARS) {
            throw new ApiException(ErrorKind.INVALID_RESPONSE, status, "Transcript too large", null);
        }
        if (value.isEmpty()) {
            throw new ApiException(ErrorKind.NO_MATCH, status,
                    provider + " не распознал речь", null);
        }
        return value;
    }

    static Body jsonBody(String json) {
        final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return new Body() {
            @Override
            public long length() {
                return bytes.length;
            }

            @Override
            public void writeTo(OutputStream output) throws IOException {
                output.write(bytes);
            }
        };
    }

    /** multipart/form-data: текстовые поля, затем один файл. */
    static final class Multipart {
        final String boundary =
                "----DictateAndroid" + Long.toHexString(System.nanoTime())
                        + Integer.toHexString(new Object().hashCode());
        private final ByteArrayOutputStream prefix = new ByteArrayOutputStream();

        Multipart field(String name, String value) {
            if (value == null || value.isEmpty()) {
                return this;
            }
            write("--" + boundary + "\r\n");
            write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
            write(value);
            write("\r\n");
            return this;
        }

        Body file(String name, String filename, String mimeType, final byte[] content) {
            write("--" + boundary + "\r\n");
            write("Content-Disposition: form-data; name=\"" + name
                    + "\"; filename=\"" + filename + "\"\r\n");
            write("Content-Type: " + mimeType + "\r\n\r\n");
            final byte[] head = prefix.toByteArray();
            final byte[] tail = ("\r\n--" + boundary + "--\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            return new Body() {
                @Override
                public long length() {
                    return (long) head.length + content.length + tail.length;
                }

                @Override
                public void writeTo(OutputStream output) throws IOException {
                    output.write(head);
                    output.write(content);
                    output.write(tail);
                }
            };
        }

        String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        private void write(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            prefix.write(bytes, 0, bytes.length);
        }
    }

    private static String readUtf8(InputStream input, int maximumBytes) throws IOException, ApiException {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) {
                    throw new ApiException(ErrorKind.INVALID_RESPONSE, "Response too large");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ApiException httpError(String provider, int status, String response) {
        ErrorKind kind;
        if (status == 401 || status == 403) {
            kind = ErrorKind.AUTH;
        } else if (status == 400 || status == 404 || status == 413 || status == 422) {
            kind = ErrorKind.INVALID_REQUEST;
        } else if (status == 402) {
            kind = ErrorKind.BILLING;
        } else if (status == 429) {
            kind = ErrorKind.RATE_LIMIT;
        } else if (status == 408) {
            kind = ErrorKind.TIMEOUT;
        } else {
            kind = ErrorKind.SERVER;
        }
        return new ApiException(kind, status, safeMessage(kind), null);
    }


    static String limit(String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300);
    }
}
