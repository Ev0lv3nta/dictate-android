package io.github.ev0lv3nta.dictate;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Общие для всех провайдеров типы и HTTP-обвязка.
 *
 * Каждый провайдер отличается только формой запроса и тем, где в ответе лежит
 * текст; отмена, таймауты и разбор ошибок одинаковы и живут здесь.
 */
final class Transcription {

    private static final int CONNECT_TIMEOUT_MILLIS = 15000;
    private static final int READ_TIMEOUT_MILLIS = 120000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private Transcription() {
    }

    enum ErrorKind {
        AUTH,
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
            super(message, cause);
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
            this.keyterms = keyterms == null ? new ArrayList<String>() : keyterms;
        }
    }

    /** Ручка отмены: закрывает соединение, если клавиатура успела передумать. */
    static final class Request {
        private volatile boolean cancelled;
        private volatile HttpsURLConnection connection;

        void cancel() {
            cancelled = true;
            HttpsURLConnection current = connection;
            if (current != null) {
                current.disconnect();
            }
        }

        boolean isCancelled() {
            return cancelled;
        }

        void attach(HttpsURLConnection value) {
            connection = value;
            if (cancelled) {
                value.disconnect();
            }
        }

        void detach(HttpsURLConnection value) {
            if (connection == value) {
                connection = null;
            }
        }
    }

    interface Client {
        String transcribe(byte[] pcm, String apiKey, Config config, Request request)
                throws ApiException;
    }

    /** Тело запроса известной длины: так HttpsURLConnection не буферизует аудио целиком. */
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
        return new ElevenLabsClient();
    }

    static void requireKey(String apiKey, String provider) throws ApiException {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException(ErrorKind.AUTH, "Ключ " + provider + " не задан");
        }
    }

    static void requireAudio(byte[] pcm) throws ApiException {
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
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            request.attach(connection);
            if (request.isCancelled()) {
                throw new ApiException(ErrorKind.CANCELLED, "Запрос отменён");
            }
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DictateAndroid/1.1");
            connection.setRequestProperty("Content-Type", contentType);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setFixedLengthStreamingMode(body.length());

            try (OutputStream output = connection.getOutputStream()) {
                body.writeTo(output);
                output.flush();
            }
            if (request.isCancelled()) {
                throw new ApiException(ErrorKind.CANCELLED, "Запрос отменён");
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readUtf8(stream, MAX_RESPONSE_BYTES);
            if (status < 200 || status >= 300) {
                throw httpError(provider, status, response);
            }
            // Провайдер иногда отвечает 200 с пустым или не-JSON телом. Раньше
            // это давало невнятное «некорректный JSON»; теперь в сообщение идут
            // длина и заголовки ответа — по ним видно, что произошло. Само тело
            // не логируем и не пересказываем: в нём может быть расшифровка.
            String head = response.trim();
            if (head.isEmpty() || (head.charAt(0) != '{' && head.charAt(0) != '[')) {
                throw new ApiException(ErrorKind.SERVER, status,
                        provider + ": ответ не JSON, len=" + response.length()
                                + " ctype=" + connection.getContentType()
                                + " enc=" + connection.getContentEncoding(), null);
            }
            return response;
        } catch (SocketTimeoutException error) {
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
                connection.disconnect();
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
            throw new ApiException(ErrorKind.SERVER, status,
                    provider + " вернул некорректный JSON", error);
        }
    }

    static String requireText(String provider, String text, int status) throws ApiException {
        String value = text == null ? "" : text.trim();
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

    private static String readUtf8(InputStream input, int maximumBytes) throws IOException {
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
                    throw new IOException("Ответ API превышает допустимый размер");
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
        } else if (status == 408 || status == 429) {
            kind = ErrorKind.TIMEOUT;
        } else {
            kind = ErrorKind.SERVER;
        }
        return new ApiException(kind, status,
                provider + ": " + extractErrorMessage(response), null);
    }

    /** Достаёт человекочитаемое поле из тела ошибки, не роняясь на чужом формате. */
    private static String extractErrorMessage(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "ошибка без описания";
        }
        try {
            JSONObject object = new JSONObject(response);
            Object detail = object.opt("detail");
            if (detail instanceof JSONObject) {
                JSONObject detailObject = (JSONObject) detail;
                String message = detailObject.optString("message", "");
                return limit(message.isEmpty() ? detailObject.toString() : message);
            }
            if (detail != null) {
                return limit(String.valueOf(detail));
            }
            Object error = object.opt("error");
            if (error instanceof JSONObject) {
                JSONObject errorObject = (JSONObject) error;
                String message = errorObject.optString("message", "");
                return limit(message.isEmpty() ? errorObject.toString() : message);
            }
            if (error != null) {
                return limit(String.valueOf(error));
            }
            String message = object.optString("message", "");
            if (!message.isEmpty()) {
                return limit(message);
            }
        } catch (JSONException ignored) {
        }
        return limit(response.trim());
    }

    static String limit(String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300);
    }
}
