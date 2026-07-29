package io.anakin.appian.templates;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.anakin.appian.templates.AnakinApiClient.AnakinApiException;

/**
 * Exercises {@link AnakinApiClient} against a real local HTTP server
 * ({@code com.sun.net.httpserver.HttpServer}, built into the JDK -- no
 * extra test dependency needed) instead of the real api.anakin.io, since
 * this sandbox has no network access to it and no live API key. This does
 * NOT touch the Appian SDK at all: {@code AnakinApiClient} is deliberately
 * plain Java precisely so this class can be tested in isolation.
 */
public class AnakinApiClientTest {

  private HttpServer server;
  private String baseUrl;

  @Before
  public void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @After
  public void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  public void postReturnsParsedJsonOnSuccess() throws Exception {
    server.createContext("/search", exchange -> {
      assertEquals("POST", exchange.getRequestMethod());
      assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
      writeJson(exchange, 200, "{\"id\":\"abc123\",\"results\":[{\"url\":\"https://example.com\"}]}");
    });
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key");
    Map<String, Object> response = client.post(baseUrl, "/search", Map.of("prompt", "test", "limit", 5));

    assertEquals("abc123", response.get("id"));
    assertTrue(response.get("results") instanceof java.util.List);
  }

  @Test
  public void getSendsApiKeyHeader() throws Exception {
    server.createContext("/sessions", exchange -> {
      assertEquals("GET", exchange.getRequestMethod());
      assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
      writeJson(exchange, 200, "{\"sessions\":[]}");
    });
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key");
    Map<String, Object> response = client.get(baseUrl, "/sessions");

    assertTrue(response.containsKey("sessions"));
  }

  @Test
  public void errorResponseThrowsWithApiMessage() throws Exception {
    server.createContext("/url-scraper", exchange ->
        writeJson(exchange, 401, "{\"error\":\"Invalid API key\"}"));
    server.start();

    AnakinApiClient client = new AnakinApiClient("bad-key");
    try {
      client.post(baseUrl, "/url-scraper", Map.of("url", "https://example.com"));
      fail("Expected AnakinApiException");
    } catch (AnakinApiException e) {
      assertEquals("Invalid API key", e.getMessage());
      assertEquals(Integer.valueOf(401), e.getStatusCode());
    }
  }

  @Test
  public void malformedJsonThrowsAnakinApiException() throws Exception {
    server.createContext("/search", exchange -> {
      String body = "{not valid json";
      exchange.sendResponseHeaders(200, body.length());
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body.getBytes());
      }
    });
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key");
    try {
      client.get(baseUrl, "/search");
      fail("Expected AnakinApiException for malformed JSON");
    } catch (AnakinApiException e) {
      assertTrue(e.getMessage().contains("malformed JSON"));
    }
  }

  @Test
  public void pollUntilTerminalPollsUntilCompleted() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);
    server.createContext("/url-scraper/job-1", exchange -> {
      int call = callCount.incrementAndGet();
      String status = call < 3 ? "processing" : "completed";
      writeJson(exchange, 200, "{\"status\":\"" + status + "\",\"markdown\":\"hello\"}");
    });
    server.start();

    // Exercises the real pollUntilTerminal loop (2s fixed poll interval, so
    // this test takes ~4s to reach the 3rd, terminal, response).
    AnakinApiClient client = new AnakinApiClient("test-key");
    Map<String, Object> result = client.pollUntilTerminal(baseUrl, "/url-scraper/job-1");

    assertEquals("completed", result.get("status"));
    assertEquals("hello", result.get("markdown"));
    assertEquals(3, callCount.get());
  }

  @Test
  public void pollUntilTerminalReturnsImmediatelyOnFailed() throws Exception {
    server.createContext("/agentic-search/job-2", exchange ->
        writeJson(exchange, 200, "{\"status\":\"failed\",\"error\":\"job blew up\"}"));
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key");
    Map<String, Object> result = client.pollUntilTerminal(baseUrl, "/agentic-search/job-2");

    assertEquals("failed", result.get("status"));
    assertEquals("job blew up", result.get("error"));
  }

  @Test
  public void requireJobIdAcceptsJobIdJobUnderscoreIdOrId() throws Exception {
    assertEquals("j1", AnakinApiClient.requireJobId(Map.of("jobId", "j1")));
    assertEquals("j2", AnakinApiClient.requireJobId(Map.of("job_id", "j2")));
    assertEquals("j3", AnakinApiClient.requireJobId(Map.of("id", "j3")));
  }

  @Test
  public void requireJobIdThrowsWhenMissing() {
    try {
      AnakinApiClient.requireJobId(Map.of("status", "pending"));
      fail("Expected AnakinApiException");
    } catch (AnakinApiException e) {
      assertTrue(e.getMessage().contains("did not include a job id"));
    }
  }

  @Test
  public void customHttpClientConstructorIsUsable() throws Exception {
    server.createContext("/search", exchange -> writeJson(exchange, 200, "{\"id\":\"x\"}"));
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key", HttpClient.newHttpClient());
    Map<String, Object> response = client.get(baseUrl, "/search");
    assertEquals("x", response.get("id"));
  }

  @Test
  public void postWithNoBodySendsRequestAndApiKeyHeader() throws Exception {
    server.createContext("/monitors/mon-1/pause", exchange -> {
      assertEquals("POST", exchange.getRequestMethod());
      assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
      writeJson(exchange, 200, "{\"isActive\":false}");
    });
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key");
    Map<String, Object> response = client.post(baseUrl, "/monitors/mon-1/pause");

    assertEquals(Boolean.FALSE, response.get("isActive"));
  }

  @Test
  public void deleteSendsRequestAndApiKeyHeader() throws Exception {
    server.createContext("/sessions/sess-1", exchange -> {
      assertEquals("DELETE", exchange.getRequestMethod());
      assertEquals("test-key", exchange.getRequestHeaders().getFirst("X-API-Key"));
      writeJson(exchange, 200, "{\"deleted\":true}");
    });
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key");
    Map<String, Object> response = client.delete(baseUrl, "/sessions/sess-1");

    assertEquals(Boolean.TRUE, response.get("deleted"));
  }

  @Test
  public void withQueryBuildsEncodedQueryStringSkippingBlankValues() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("q", "top phones on walmart");
    params.put("limit", "5");
    params.put("domain", null);
    params.put("country", "");

    String result = AnakinApiClient.withQuery("/wire/resolve", params);

    assertEquals("/wire/resolve?q=top+phones+on+walmart&limit=5", result);
  }

  @Test
  public void withQueryReturnsBarePathWhenAllParamsBlank() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("domain", null);

    assertEquals("/sessions", AnakinApiClient.withQuery("/sessions", params));
  }

  @Test
  public void pollUntilStatusNotStopsAsSoonAsStatusLeavesPending() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);
    server.createContext("/ai-visibility/search/search-1", exchange -> {
      int call = callCount.incrementAndGet();
      // Mirrors the real API: a "failed" search still returns usable data and is NOT
      // "running", so pollUntilStatusNot must treat it as terminal just like "completed".
      String status = call < 2 ? "running" : "failed";
      writeJson(exchange, 200, "{\"status\":\"" + status + "\",\"results\":[]}");
    });
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key");
    Map<String, Object> result = client.pollUntilStatusNot(baseUrl, "/ai-visibility/search/search-1", "running");

    assertEquals("failed", result.get("status"));
    assertEquals(2, callCount.get());
  }

  @Test
  public void pollUntilStatusInStopsOnAnyConfiguredTerminalStatus() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);
    server.createContext("/ai/jobs/wf-1", exchange -> {
      int call = callCount.incrementAndGet();
      String status = call < 2 ? "running" : "timed_out";
      writeJson(exchange, 200, "{\"status\":\"" + status + "\"}");
    });
    server.start();

    AnakinApiClient client = new AnakinApiClient("test-key");
    Map<String, Object> result =
        client.pollUntilStatusIn(baseUrl, "/ai/jobs/wf-1", Set.of("completed", "failed", "timed_out"));

    assertEquals("timed_out", result.get("status"));
    assertEquals(2, callCount.get());
  }

  private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes();
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
