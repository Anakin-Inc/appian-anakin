package io.anakin.appian.templates;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Minimal HTTP client for the Anakin API (https://api.anakin.io/v1).
 *
 * <p>This is a plain Java class -- it does not depend on any Appian SDK type
 * on purpose, so it can be unit tested (see {@code AnakinApiClientTest})
 * without the Appian Integration SDK on the classpath. It is shared by the
 * three integration templates in this plug-in for request/response plumbing:
 * the {@code X-API-Key} auth header, JSON (de)serialization, and job
 * polling for the two asynchronous Anakin endpoints ({@code /url-scraper}
 * and {@code /agentic-search}).
 *
 * <p>Uses {@link java.net.http.HttpClient} (built into the JDK since 11)
 * rather than the Apache HttpClient used in Appian's own CSP examples, to
 * avoid adding an HTTP dependency beyond Jackson.
 */
final class AnakinApiClient {

  static final String BASE_URL = "https://api.anakin.io/v1";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
  private static final Duration POLL_TIMEOUT = Duration.ofMinutes(5);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String apiKey;

  AnakinApiClient(String apiKey) {
    this(apiKey, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  /** Package-private constructor used by tests to inject a client pointed at a local mock server. */
  AnakinApiClient(String apiKey, HttpClient httpClient) {
    this.apiKey = apiKey;
    this.httpClient = httpClient;
    this.objectMapper = new ObjectMapper();
  }

  /** POSTs {@code body} as JSON to {@code BASE_URL + path}; returns the parsed JSON response. */
  Map<String, Object> post(String path, Map<String, Object> body) throws AnakinApiException {
    return post(BASE_URL, path, body);
  }

  /** GETs {@code BASE_URL + path}; returns the parsed JSON response. */
  Map<String, Object> get(String path) throws AnakinApiException {
    return get(BASE_URL, path);
  }

  // Overloads taking an explicit base URL exist only so tests can point at a local mock server.
  Map<String, Object> post(String baseUrl, String path, Map<String, Object> body) throws AnakinApiException {
    String json;
    try {
      json = objectMapper.writeValueAsString(body);
    } catch (IOException e) {
      throw new AnakinApiException("Failed to serialize Anakin request body for " + path, e);
    }
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json")
        .header("X-API-Key", apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(json))
        .build();
    return send(request);
  }

  Map<String, Object> get(String baseUrl, String path) throws AnakinApiException {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + path))
        .timeout(REQUEST_TIMEOUT)
        .header("X-API-Key", apiKey)
        .GET()
        .build();
    return send(request);
  }

  /**
   * Polls {@code GET path} (an Anakin job-status endpoint) until the job's
   * {@code status} field reaches a terminal value ({@code "completed"} or
   * {@code "failed"}), or until {@link #POLL_TIMEOUT} elapses.
   *
   * @throws AnakinApiException if the request fails, the response is
   *     malformed, or the job does not reach a terminal state before the
   *     poll timeout -- callers must handle this and surface it to the
   *     Appian designer as an {@code IntegrationError}, never let it
   *     propagate as an unchecked failure.
   */
  Map<String, Object> pollUntilTerminal(String path) throws AnakinApiException {
    return pollUntilTerminal(BASE_URL, path);
  }

  /** Overload taking an explicit base URL so tests can poll a local mock server. */
  Map<String, Object> pollUntilTerminal(String baseUrl, String path) throws AnakinApiException {
    long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
    Object lastStatus = null;
    while (true) {
      Map<String, Object> result = get(baseUrl, path);
      lastStatus = result.get("status");
      if ("completed".equals(lastStatus) || "failed".equals(lastStatus)) {
        return result;
      }
      if (System.currentTimeMillis() >= deadline) {
        throw new AnakinApiException(
            "Timed out after " + POLL_TIMEOUT.toMinutes() + " minute(s) waiting for " + path
                + " to reach a terminal state (last status: " + lastStatus + ")",
            (Throwable) null);
      }
      try {
        Thread.sleep(POLL_INTERVAL.toMillis());
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new AnakinApiException("Interrupted while polling " + path, ie);
      }
    }
  }

  private Map<String, Object> send(HttpRequest request) throws AnakinApiException {
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new AnakinApiException("Network error calling Anakin API (" + request.uri() + "): " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AnakinApiException("Interrupted calling Anakin API (" + request.uri() + ")", e);
    }

    Map<String, Object> body = parseJson(response.body());
    if (response.statusCode() >= 400) {
      Object apiMessage = body.get("error");
      String message = apiMessage != null
          ? apiMessage.toString()
          : "Anakin API returned HTTP " + response.statusCode() + " with no error detail";
      throw new AnakinApiException(message, response.statusCode(), body);
    }
    return body;
  }

  private Map<String, Object> parseJson(String json) throws AnakinApiException {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
    } catch (IOException e) {
      throw new AnakinApiException("Anakin API returned malformed JSON: " + e.getMessage(), e);
    }
  }

  /** Extracts the job id from a submit response, tolerating jobId / job_id / id keys. */
  static String requireJobId(Map<String, Object> submitted) throws AnakinApiException {
    Object jobId = submitted.get("jobId");
    if (jobId == null) {
      jobId = submitted.get("job_id");
    }
    if (jobId == null) {
      jobId = submitted.get("id");
    }
    if (!(jobId instanceof String) || ((String) jobId).isEmpty()) {
      throw new AnakinApiException(
          "Anakin API response did not include a job id: " + submitted, (Throwable) null);
    }
    return (String) jobId;
  }

  /**
   * Checked exception wrapping every way an Anakin API call can fail:
   * network/timeout errors, HTTP error responses, malformed JSON, and
   * poll-timeout. Deliberately checked -- integration templates must
   * catch it and translate it into an {@code IntegrationError} rather than
   * letting a runtime exception surface to the Appian designer.
   */
  static final class AnakinApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Integer statusCode;
    private final transient Map<String, Object> responseBody;

    AnakinApiException(String message, Throwable cause) {
      super(message, cause);
      this.statusCode = null;
      this.responseBody = null;
    }

    AnakinApiException(String message, Integer statusCode, Map<String, Object> responseBody) {
      super(message);
      this.statusCode = statusCode;
      this.responseBody = responseBody;
    }

    Integer getStatusCode() {
      return statusCode;
    }

    Map<String, Object> getResponseBody() {
      return responseBody;
    }
  }
}
