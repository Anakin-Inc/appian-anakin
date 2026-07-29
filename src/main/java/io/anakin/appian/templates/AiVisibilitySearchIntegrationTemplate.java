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
 * Asks multiple AI answer engines (ChatGPT, Gemini, Google AI Overview, ...)
 * the same question and compares their answers, via Anakin's
 * {@code POST /v1/ai-visibility/search} (submit) then
 * {@code GET /v1/ai-visibility/search/:searchId} (poll) pair.
 *
 * Returns one result per engine plus an AI-generated synthesis of where the
 * engines agree and diverge. Use for brand/AI-SEO visibility checks. Unlike
 * the job polling on the other async templates in this plug-in, this job's
 * "still working" signal is specifically the {@code "running"} status, not
 * the absence of {@code "failed"} -- a {@code "failed"} search here can still
 * carry usable per-source results for the sources that did answer, so this
 * template treats it as a normal (successful) response rather than an
 * {@code IntegrationError}, matching anakin-mcp's TypeScript client
 * ({@code aiVisibilitySearch} in src/client.ts, which returns rather than
 * throws on a failed job for this reason).
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.aiVisibilitySearch}, {@code AIVisibilitySearchOptions})
 * and its ai_visibility_search MCP tool (src/tools/ai-visibility.ts) exactly.
 */
@TemplateId(name = "AnakinAiVisibilitySearchIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class AiVisibilitySearchIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(AiVisibilitySearchIntegrationTemplate.class);

  public static final String QUERY_PROP = "query";
  public static final String SOURCES_PROP = "sources";
  public static final String COUNTRY_PROP = "country";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(QUERY_PROP)
            .label("Query")
            .instructionText("The question to ask every engine (max 2000 characters).")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        textProperty(SOURCES_PROP)
            .label("Sources")
            .instructionText("Comma-separated engine slugs to query (see ai_visibility_sources). "
                + "Leave blank to query all enabled engines.")
            .isExpressionable(true)
            .build(),
        textProperty(COUNTRY_PROP)
            .label("Country")
            .instructionText("Two-letter ISO country for the search geography (proxy exit). Defaults to \"us\".")
            .placeholder("us")
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
    String query = integrationConfiguration.getValue(QUERY_PROP);
    String sources = integrationConfiguration.getValue(SOURCES_PROP);
    String country = integrationConfiguration.getValue(COUNTRY_PROP);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("query", query);
    List<String> sourceList = splitCsv(sources);
    if (!sourceList.isEmpty()) {
      requestBody.put("sources", sourceList);
    }
    if (country != null && !country.isBlank()) {
      requestBody.put("country", country);
    }

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> submitted = client.post("/ai-visibility/search", requestBody);
      Object searchId = submitted.get("search_id");
      Map<String, Object> result = (searchId instanceof String)
          ? client.pollUntilStatusNot("/ai-visibility/search/" + searchId, "running")
          : submitted;
      long executionTime = System.currentTimeMillis() - startTime;

      Map<String, Object> responseDiagnostic = new HashMap<>();
      responseDiagnostic.put("searchId", searchId);
      responseDiagnostic.put("status", result.get("status"));
      diagnosticBuilder.addResponseDiagnostic(responseDiagnostic).addExecutionTimeDiagnostic(executionTime);

      // A "failed" status here still carries usable per-source results/errors --
      // always forSuccess, never forError, matching the TS client's own semantics.
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin ai-visibility/search request failed for query={} statusCode={}: {}",
          query, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin ai_visibility_search request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }

  /** Splits a comma-separated Appian text field into a trimmed, non-blank list. */
  private static List<String> splitCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }
}
