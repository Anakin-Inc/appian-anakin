package io.anakin.appian.templates;

import static io.anakin.appian.templates.AnakinConnectedSystemTemplate.API_KEY_PROP;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * Discovers all reachable URLs under a site via Anakin's {@code POST /v1/map}
 * (submit) then {@code GET /v1/map/:jobId} (poll) pair. Useful for
 * understanding a domain's structure before crawling it, or finding the
 * sub-pages an agent should scrape next.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.map}, {@code MapOptions}/{@code MapResult}) exactly.
 */
@TemplateId(name = "AnakinMapIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class MapIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(MapIntegrationTemplate.class);

  public static final String URL_PROP = "url";
  public static final String LIMIT_PROP = "limit";
  public static final String DEPTH_PROP = "depth";
  public static final String LIMIT_PER_LEVEL_PROP = "limitPerLevel";
  public static final String INCLUDE_SUBDOMAINS_PROP = "includeSubdomains";
  public static final String INCLUDE_EXTERNAL_LINKS_PROP = "includeExternalLinks";
  public static final String USE_BROWSER_PROP = "useBrowser";
  public static final String SEARCH_PROP = "search";

  private static final int DEFAULT_LIMIT = 100;
  private static final int DEFAULT_DEPTH = 2;
  private static final int DEFAULT_LIMIT_PER_LEVEL = 100;

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(URL_PROP)
            .label("URL")
            .instructionText("Starting URL for discovery (typically a homepage or section root).")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        integerProperty(LIMIT_PROP)
            .label("Limit")
            .instructionText("Maximum number of URLs to return overall. Defaults to " + DEFAULT_LIMIT + ".")
            .isExpressionable(true)
            .build(),
        integerProperty(DEPTH_PROP)
            .label("Depth")
            .instructionText("How many link-hops from the starting URL to follow (1-5). Defaults to "
                + DEFAULT_DEPTH + ".")
            .isExpressionable(true)
            .build(),
        integerProperty(LIMIT_PER_LEVEL_PROP)
            .label("Limit Per Level")
            .instructionText("Maximum URLs collected per depth level, controlling breadth. Defaults to "
                + DEFAULT_LIMIT_PER_LEVEL + ".")
            .isExpressionable(true)
            .build(),
        booleanProperty(INCLUDE_SUBDOMAINS_PROP)
            .label("Include Subdomains")
            .instructionText("Include URLs on subdomains of the starting host.")
            .build(),
        booleanProperty(INCLUDE_EXTERNAL_LINKS_PROP)
            .label("Include External Links")
            .instructionText("Also collect (but do not follow) external links.")
            .build(),
        booleanProperty(USE_BROWSER_PROP)
            .label("Use Browser")
            .instructionText("Render with a headless browser -- needed for JS-heavy single-page apps.")
            .build(),
        textProperty(SEARCH_PROP)
            .label("Search")
            .instructionText("Optional keyword filter -- only return URLs whose path/title matches.")
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
    Integer limit = orDefault(integrationConfiguration.getValue(LIMIT_PROP), DEFAULT_LIMIT);
    Integer depth = orDefault(integrationConfiguration.getValue(DEPTH_PROP), DEFAULT_DEPTH);
    Integer limitPerLevel = orDefault(integrationConfiguration.getValue(LIMIT_PER_LEVEL_PROP), DEFAULT_LIMIT_PER_LEVEL);
    Boolean includeSubdomains = orDefault(integrationConfiguration.getValue(INCLUDE_SUBDOMAINS_PROP), Boolean.FALSE);
    Boolean includeExternalLinks = orDefault(integrationConfiguration.getValue(INCLUDE_EXTERNAL_LINKS_PROP), Boolean.FALSE);
    Boolean useBrowser = orDefault(integrationConfiguration.getValue(USE_BROWSER_PROP), Boolean.FALSE);
    String search = integrationConfiguration.getValue(SEARCH_PROP);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("url", url);
    requestBody.put("limit", limit);
    requestBody.put("depth", depth);
    requestBody.put("limitPerLevel", limitPerLevel);
    requestBody.put("includeSubdomains", includeSubdomains);
    requestBody.put("includeExternalLinks", includeExternalLinks);
    requestBody.put("useBrowser", useBrowser);
    if (search != null && !search.isBlank()) {
      requestBody.put("search", search);
    }

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> submitted = client.post("/map", requestBody);
      String jobId = AnakinApiClient.requireJobId(submitted);
      Map<String, Object> result = client.pollUntilTerminal("/map/" + jobId);
      long executionTime = System.currentTimeMillis() - startTime;

      Map<String, Object> responseDiagnostic = new HashMap<>();
      responseDiagnostic.put("jobId", jobId);
      responseDiagnostic.put("status", result.get("status"));
      diagnosticBuilder.addResponseDiagnostic(responseDiagnostic).addExecutionTimeDiagnostic(executionTime);

      if ("failed".equals(result.get("status"))) {
        Object error = result.get("error");
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Map job failed")
                .message(error != null ? error.toString() : "Anakin map job failed with no error detail.")
                .build())
            .withDiagnostic(diagnosticBuilder.build())
            .build();
      }
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin map request failed for url={} statusCode={}: {}",
          url, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin map request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }

  private static <T> T orDefault(T value, T defaultValue) {
    return value != null ? value : defaultValue;
  }
}
