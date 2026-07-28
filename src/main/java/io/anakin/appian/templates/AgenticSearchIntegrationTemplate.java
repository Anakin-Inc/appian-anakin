package io.anakin.appian.templates;

import static io.anakin.appian.templates.AnakinConnectedSystemTemplate.API_KEY_PROP;

import java.io.IOException;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.anakin.appian.templates.AnakinApiClient.AnakinApiException;

/**
 * Multi-stage AI research pipeline via Anakin's {@code POST /v1/agentic-search}
 * (submit) then {@code GET /v1/agentic-search/:jobId} (poll) pair. Returns an
 * AI-generated summary plus optional structured data matching a caller-supplied
 * JSON Schema. Costs 10 credits (see anakin-py's Anakin.agentic_search docstring).
 */
@TemplateId(name = "AnakinAgenticSearchIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class AgenticSearchIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(AgenticSearchIntegrationTemplate.class);

  public static final String PROMPT_PROP = "prompt";
  public static final String USE_BROWSER_PROP = "useBrowser";
  public static final String SCHEMA_PROP = "schema";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(PROMPT_PROP)
            .label("Prompt")
            .instructionText("Research goal for the multi-stage agentic pipeline, e.g. "
                + "\"compare pricing across the top 3 project management tools\".")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        booleanProperty(USE_BROWSER_PROP)
            .label("Use Browser")
            .instructionText("Allow the agent to render pages in a real browser while "
                + "researching. Defaults to true if left unset.")
            .build(),
        textProperty(SCHEMA_PROP)
            .label("Result Schema (JSON)")
            .instructionText("Optional JSON Schema describing the structured fields you want "
                + "extracted into the response. Leave blank for a free-form summary only.")
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
    Boolean useBrowser = integrationConfiguration.getValue(USE_BROWSER_PROP);
    if (useBrowser == null) {
      useBrowser = Boolean.TRUE;
    }
    String schemaJson = integrationConfiguration.getValue(SCHEMA_PROP);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("prompt", prompt);
    requestBody.put("useBrowser", useBrowser);

    if (schemaJson != null && !schemaJson.isBlank()) {
      try {
        Map<String, Object> schema = new ObjectMapper()
            .readValue(schemaJson, new TypeReference<Map<String, Object>>() { });
        requestBody.put("schema", schema);
      } catch (IOException e) {
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Invalid Result Schema")
                .message("Result Schema (JSON) must be a valid JSON object: " + e.getMessage())
                .build())
            .build();
      }
    }

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> submitted = client.post("/agentic-search", requestBody);
      String jobId = AnakinApiClient.requireJobId(submitted);
      Map<String, Object> result = client.pollUntilTerminal("/agentic-search/" + jobId);
      long executionTime = System.currentTimeMillis() - startTime;

      Map<String, Object> responseDiagnostic = new HashMap<>();
      responseDiagnostic.put("jobId", jobId);
      responseDiagnostic.put("status", result.get("status"));
      diagnosticBuilder.addResponseDiagnostic(responseDiagnostic).addExecutionTimeDiagnostic(executionTime);

      if ("failed".equals(result.get("status"))) {
        Object error = result.get("error");
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Agentic search job failed")
                .message(error != null ? error.toString() : "Anakin agentic-search job failed with no error detail.")
                .build())
            .withDiagnostic(diagnosticBuilder.build())
            .build();
      }
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin agentic-search request failed for prompt={} statusCode={}: {}",
          prompt, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin agentic-search request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
