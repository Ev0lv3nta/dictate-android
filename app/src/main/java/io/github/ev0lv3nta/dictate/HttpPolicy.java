package io.github.ev0lv3nta.dictate;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;

final class HttpPolicy {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS).build();
    private HttpPolicy() { }
}
