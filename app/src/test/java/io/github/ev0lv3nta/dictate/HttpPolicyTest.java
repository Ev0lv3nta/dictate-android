package io.github.ev0lv3nta.dictate;

import org.junit.Test;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class HttpPolicyTest {
    @Test public void productionTransportMapsErrorsWithoutReadingSensitiveBody() throws Exception {
        int[] codes={401,403,402,429,503,408,413};
        Transcription.ErrorKind[] kinds={Transcription.ErrorKind.AUTH,Transcription.ErrorKind.AUTH,
                Transcription.ErrorKind.BILLING,Transcription.ErrorKind.RATE_LIMIT,
                Transcription.ErrorKind.SERVER,Transcription.ErrorKind.TIMEOUT,Transcription.ErrorKind.INVALID_REQUEST};
        try (MockWebServer server=new MockWebServer()) {
            for (int i=0;i<codes.length;i++) {
                server.enqueue(new MockResponse().setResponseCode(codes[i]).setBody("private fixture credential and transcript"));
                try {
                    Transcription.post("fixture",server.url("/upload").toString(),java.util.Collections.emptyMap(),
                            "application/json",Transcription.jsonBody("{}"),new Transcription.Request());
                    fail();
                } catch (Transcription.ApiException error) {
                    assertEquals(kinds[i],error.kind);
                    assertFalse(error.toString().contains("private fixture"));
                    assertNull(error.getCause());
                }
            }
            assertEquals(codes.length,server.getRequestCount());
        }
    }
    @Test public void malformedAndOversizedBodiesAreBounded() throws Exception {
        char[] large=new char[2*1024*1024+1]; java.util.Arrays.fill(large,'a'); large[0]='{';
        try (MockWebServer server=new MockWebServer()) {
            for (String content:new String[]{"<html>private</html>",new String(large)}) {
                server.enqueue(new MockResponse().setBody(content));
                try {
                    Transcription.post("fixture",server.url("/upload").toString(),java.util.Collections.emptyMap(),
                            "application/json",Transcription.jsonBody("{}"),new Transcription.Request());
                    fail();
                } catch (Transcription.ApiException error) { assertEquals(Transcription.ErrorKind.INVALID_RESPONSE,error.kind); }
            }
        }
    }
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
