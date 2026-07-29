package io.anakin.appian.templates;

import static io.anakin.appian.templates.AnakinConnectedSystemTemplate.API_KEY_PROP;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.appian.connectedsystems.simplified.sdk.SimpleIntegrationTemplate;
import com.appian.connectedsystems.simplified.sdk.configuration.SimpleConfiguration;
import com.appian.connectedsystems.templateframework.sdk.ExecutionContext;
import com.appian.connectedsystems.templateframework.sdk.IntegrationError;
import com.appian.connectedsystems.templateframework.sdk.IntegrationResponse;
import com.appian.connectedsystems.templateframework.sdk.TemplateId;
import com.appian.connectedsystems.templateframework.sdk.configuration.PropertyPath;
import com.appian.connectedsystems.templateframework.sdk.diagnostics.IntegrationDesignerDiagnostic;
import com.appian.connectedsystems.templateframework.sdk.metadata.IntegrationTemplateRequestPolicy;
import com.appian.connectedsystems.templateframework.sdk.metadata.IntegrationTemplateType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.anakin.appian.templates.AnakinApiClient.AnakinApiException;

/**
 * Bulk-fetches markdown/HTML across a site via Anakin's {@code POST /v1/crawl}
 * (submit) then {@code GET /v1/crawl/:jobId} (poll) pair. Useful for catalog
 * ingestion or building a site-wide RAG corpus in one call.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.crawl}, {@code CrawlOptions}/{@code CrawlResult}) exactly.
 */
@TemplateId(name = "AnakinCrawlIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class CrawlIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(CrawlIntegrationTemplate.class);

  public static final String URL_PROP = "url";
  public static final String MAX_PAGES_PROP = "maxPages";
  public static final String DEPTH_PROP = "depth";
  public static final String COUNTRY_PROP = "country";
  public static final String USE_BROWSER_PROP = "useBrowser";
  public static final String INCLUDE_PATTERNS_PROP = "includePatterns";
  public static final String EXCLUDE_PATTERNS_PROP = "excludePatterns";
  public static final String SESSION_ID_PROP = "sessionId";
  public static final String SESSION_NAME_PROP = "sessionName";

  private static final int DEFAULT_MAX_PAGES = 10;
  private static final int DEFAULT_DEPTH = 1;

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(URL_PROP)
            .label("URL")
            .instructionText("Starting URL for the crawl.")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        integerProperty(MAX_PAGES_PROP)
            .label("Max Pages")
            .instructionText("Hard cap on pages fetched. Defaults to " + DEFAULT_MAX_PAGES + ".")
            .isExpressionable(true)
            .build(),
        integerProperty(DEPTH_PROP)
            .label("Depth")
            .instructionText("Link-hops from the starting URL to follow (1-5). Defaults to " + DEFAULT_DEPTH + ".")
            .isExpressionable(true)
            .build(),
        textProperty(COUNTRY_PROP)
            .label("Country")
            .instructionText("Two-letter proxy egress country code, e.g. \"us\", \"gb\", \"de\".")
            .placeholder("us")
            .isExpressionable(true)
            .build(),
        booleanProperty(USE_BROWSER_PROP)
            .label("Use Browser")
            .instructionText("Render each page in a headless browser -- needed for JS-heavy single-page apps.")
            .build(),
        textProperty(INCLUDE_PATTERNS_PROP)
            .label("Include Patterns")
            .instructionText("Comma-separated glob/regex patterns. Only URLs matching at least one are fetched. "
                + "Leave blank to fetch every discovered URL.")
            .isExpressionable(true)
            .build(),
        textProperty(EXCLUDE_PATTERNS_PROP)
            .label("Exclude Patterns")
            .instructionText("Comma-separated glob/regex patterns. URLs matching any of these are skipped.")
            .isExpressionable(true)
            .build(),
        textProperty(SESSION_ID_PROP)
            .label("Session ID")
            .instructionText("Reuse an existing saved browser session (cookies/login) by ID. Optional.")
            .isExpressionable(true)
            .build(),
        textProperty(SESSION_NAME_PROP)
            .label("Session Name")
            .instructionText("Reuse an existing saved browser session (cookies/login) by name. Optional.")
            .isExpressionable(true)
            .build()
    );
  }

  @Override
  protected IntegrationResponse execute(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      ExecutionContext executionContext) {
    String apiKey = connectedSystemConfiguration.getValue(API_KEY_PROP);

    String url = integrationConfiguration.getValue(URL_PROP);
    Integer maxPages = orDefault(integrationConfiguration.getValue(MAX_PAGES_PROP), DEFAULT_MAX_PAGES);
    Integer depth = orDefault(integrationConfiguration.getValue(DEPTH_PROP), DEFAULT_DEPTH);
    String country = orDefault(integrationConfiguration.getValue(COUNTRY_PROP), "us");
    Boolean useBrowser = orDefault(integrationConfiguration.getValue(USE_BROWSER_PROP), Boolean.FALSE);
    String includePatterns = integrationConfiguration.getValue(INCLUDE_PATTERNS_PROP);
    String excludePatterns = integrationConfiguration.getValue(EXCLUDE_PATTERNS_PROP);
    String sessionId = integrationConfiguration.getValue(SESSION_ID_PROP);
    String sessionName = integrationConfiguration.getValue(SESSION_NAME_PROP);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("url", url);
    requestBody.put("maxPages", maxPages);
    requestBody.put("depth", depth);
    requestBody.put("country", country);
    requestBody.put("useBrowser", useBrowser);
    List<String> includeList = splitCsv(includePatterns);
    if (!includeList.isEmpty()) {
      requestBody.put("includePatterns", includeList);
    }
    List<String> excludeList = splitCsv(excludePatterns);
    if (!excludeList.isEmpty()) {
      requestBody.put("excludePatterns", excludeList);
    }
    if (sessionId != null && !sessionId.isBlank()) {
      requestBody.put("sessionId", sessionId);
    }
    if (sessionName != null && !sessionName.isBlank()) {
      requestBody.put("sessionName", sessionName);
    }

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> submitted = client.post("/crawl", requestBody);
      String jobId = AnakinApiClient.requireJobId(submitted);
      Map<String, Object> result = client.pollUntilTerminal("/crawl/" + jobId);
      long executionTime = System.currentTimeMillis() - startTime;

      Map<String, Object> responseDiagnostic = new HashMap<>();
      responseDiagnostic.put("jobId", jobId);
      responseDiagnostic.put("status", result.get("status"));
      diagnosticBuilder.addResponseDiagnostic(responseDiagnostic).addExecutionTimeDiagnostic(executionTime);

      if ("failed".equals(result.get("status"))) {
        Object error = result.get("error");
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Crawl job failed")
                .message(error != null ? error.toString() : "Anakin crawl job failed with no error detail.")
                .build())
            .withDiagnostic(diagnosticBuilder.build())
            .build();
      }
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin crawl request failed for url={} statusCode={}: {}",
          url, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin crawl request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }

  /** Splits a comma-separated Appian text field into a trimmed, non-blank list of patterns. */
  private static List<String> splitCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }

  private static <T> T orDefault(T value, T defaultValue) {
    return value != null ? value : defaultValue;
  }
}
