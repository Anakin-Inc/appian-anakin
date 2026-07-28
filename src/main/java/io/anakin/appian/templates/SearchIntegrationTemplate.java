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
 * AI-powered web search via Anakin's {@code POST /v1/search}.
 *
 * Synchronous -- unlike the other two templates in this plug-in, there is
 * no job id and no polling; the API returns results directly. Costs 3
 * credits per call (see anakin-py's Anakin.search docstring).
 */
@TemplateId(name = "AnakinSearchIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class SearchIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(SearchIntegrationTemplate.class);

  public static final String PROMPT_PROP = "prompt";
  public static final String LIMIT_PROP = "limit";

  private static final int DEFAULT_LIMIT = 5;

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(PROMPT_PROP)
            .label("Prompt")
            .instructionText("Natural-language search query, e.g. \"top 5 CRM platforms for startups\".")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        integerProperty(LIMIT_PROP)
            .label("Limit")
            .instructionText("Maximum number of results to return. Defaults to " + DEFAULT_LIMIT + ".")
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
    String prompt = integrationConfiguration.getValue(PROMPT_PROP);
    Integer limit = integrationConfiguration.getValue(LIMIT_PROP);
    if (limit == null) {
      limit = DEFAULT_LIMIT;
    }

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("prompt", prompt);
    requestBody.put("limit", limit);

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      // Synchronous endpoint -- no job id, no polling.
      Map<String, Object> result = client.post("/search", requestBody);
      long executionTime = System.currentTimeMillis() - startTime;
      diagnosticBuilder.addResponseDiagnostic(new HashMap<>(result)).addExecutionTimeDiagnostic(executionTime);
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin search request failed for prompt={} statusCode={}: {}",
          prompt, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin search request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
