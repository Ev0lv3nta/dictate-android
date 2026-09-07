package io.github.ev0lv3nta.dictate;

import org.junit.Test;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class HttpPolicyTest {
    @Test public void redirectsAreNotFollowed() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(307).addHeader("Location","/other"));
            Call call=HttpPolicy.CLIENT.newCall(new Request.Builder().url(server.url("/upload"))
                    .post(RequestBody.create("fixture",MediaType.get("text/plain"))).build());
            try (Response response=call.execute()) { assertEquals(307,response.code()); }
            assertEquals(1,server.getRequestCount());
        }
    }
    @Test public void cancellationInterruptsAnOutstandingResponse() throws Exception {
        try (MockWebServer server=new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            Call call=HttpPolicy.CLIENT.newCall(new Request.Builder().url(server.url("/upload"))
                    .post(RequestBody.create("fixture",MediaType.get("text/plain"))).build());
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> result=worker.submit(() -> {
                    try (Response response=call.execute()) { return false; }
                    catch (java.io.IOException cancelled) { return call.isCanceled(); }
                });
                assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));
                call.cancel();
                assertTrue(result.get(2,TimeUnit.SECONDS));
                assertEquals(1,server.getRequestCount());
            } finally { worker.shutdownNow(); }
        }
    }
}
