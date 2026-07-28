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
 * Scrapes a single URL via Anakin's {@code POST /v1/url-scraper} (submit)
 * then {@code GET /v1/url-scraper/:jobId} (poll) pair.
 *
 * Request/response shapes mirror the official anakin-py SDK
 * (anakin/client.py Anakin.scrape, anakin/models.py Document) exactly.
 */
// Must provide a template id. This value need only be unique within this plug-in.
@TemplateId(name = "AnakinUrlScraperIntegrationTemplate")
// READ: this integration reads/produces a document, it does not mutate anything Appian-side.
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class UrlScraperIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(UrlScraperIntegrationTemplate.class);

  public static final String URL_PROP = "url";
  public static final String COUNTRY_PROP = "country";
  public static final String USE_BROWSER_PROP = "useBrowser";
  public static final String GENERATE_JSON_PROP = "generateJson";
  public static final String FORCE_FRESH_PROP = "forceFresh";
  public static final String SESSION_ID_PROP = "sessionId";
  public static final String SESSION_NAME_PROP = "sessionName";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(URL_PROP)
            .label("URL")
            .instructionText("The page to scrape, including scheme (https://...).")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        textProperty(COUNTRY_PROP)
            .label("Country")
            .instructionText("Two-letter proxy exit country code, e.g. \"us\", \"gb\", \"de\".")
            .placeholder("us")
            .isExpressionable(true)
            .build(),
        booleanProperty(USE_BROWSER_PROP)
            .label("Use Browser")
            .instructionText("Render the page in a real browser first -- for JS-heavy or "
                + "anti-bot sites. Slower and costs more credits than a plain fetch.")
            .build(),
        booleanProperty(GENERATE_JSON_PROP)
            .label("Generate JSON")
            .instructionText("Also ask Anakin for an AI-structured JSON extraction of the page content.")
            .build(),
        booleanProperty(FORCE_FRESH_PROP)
            .label("Force Fresh")
            .instructionText("Bypass the cache and re-scrape even if a recent cached result exists.")
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
    String country = orDefault(integrationConfiguration.getValue(COUNTRY_PROP), "us");
    Boolean useBrowser = orDefault(integrationConfiguration.getValue(USE_BROWSER_PROP), Boolean.FALSE);
    Boolean generateJson = orDefault(integrationConfiguration.getValue(GENERATE_JSON_PROP), Boolean.FALSE);
    Boolean forceFresh = orDefault(integrationConfiguration.getValue(FORCE_FRESH_PROP), Boolean.FALSE);
    String sessionId = integrationConfiguration.getValue(SESSION_ID_PROP);
    String sessionName = integrationConfiguration.getValue(SESSION_NAME_PROP);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("url", url);
    requestBody.put("country", country);
    requestBody.put("useBrowser", useBrowser);
    requestBody.put("generateJson", generateJson);
    requestBody.put("forceFresh", forceFresh);
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
      Map<String, Object> submitted = client.post("/url-scraper", requestBody);
      String jobId = AnakinApiClient.requireJobId(submitted);
      Map<String, Object> result = client.pollUntilTerminal("/url-scraper/" + jobId);
      long executionTime = System.currentTimeMillis() - startTime;

      Map<String, Object> responseDiagnostic = new HashMap<>();
      responseDiagnostic.put("jobId", jobId);
      responseDiagnostic.put("status", result.get("status"));
      diagnosticBuilder.addResponseDiagnostic(responseDiagnostic).addExecutionTimeDiagnostic(executionTime);

      if ("failed".equals(result.get("status"))) {
        Object error = result.get("error");
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Scrape job failed")
                .message(error != null ? error.toString() : "Anakin url-scraper job failed with no error detail.")
                .build())
            .withDiagnostic(diagnosticBuilder.build())
            .build();
      }
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      // Logged with the target URL and job status code (if any) for
      // production debugging -- the designer-facing IntegrationError only
      // shows a short message, not a stack trace.
      LOG.error("Anakin url-scraper request failed for url={} statusCode={}: {}",
          url, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin url-scraper request failed")
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
