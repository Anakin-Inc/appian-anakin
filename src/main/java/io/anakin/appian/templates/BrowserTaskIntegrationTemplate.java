package io.anakin.appian.templates;

import static io.anakin.appian.templates.AnakinConnectedSystemTemplate.API_KEY_PROP;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
 * Runs a natural-language task in a real cloud browser driven by an AI
 * agent, via Anakin's {@code POST /v1/ai/evaluate} (submit, always with
 * {@code async: true}) then {@code GET /v1/ai/jobs/:workflowId} (poll) pair.
 *
 * The agent navigates, clicks, types, scrolls, and extracts on the caller's
 * behalf ("find the cheapest 65-inch TV on this site and list its specs").
 * Use when {@link MapIntegrationTemplate}/{@link CrawlIntegrationTemplate}
 * can't do the job (multi-step flows, interactions, complex navigation) and
 * no Wire action covers the site. Submitted in async mode because the
 * synchronous variant blocks the HTTP request for up to ~5.5 minutes, far
 * past a typical request timeout; this template polls to one of three
 * terminal states ({@code completed}, {@code failed}, {@code timed_out}).
 * For login-protected tasks pass a Session ID from
 * {@link SessionListIntegrationTemplate} -- never put passwords in the
 * prompt. Marked {@link IntegrationTemplateRequestPolicy#WRITE}: it drives a
 * real browser that can change state on target sites.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.browserTask}, {@code BrowserTaskOptions})
 * and its browser_task MCP tool (src/tools/browser-task.ts) exactly.
 */
@TemplateId(name = "AnakinBrowserTaskIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class BrowserTaskIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(BrowserTaskIntegrationTemplate.class);

  public static final String PROMPT_PROP = "prompt";
  public static final String URL_PROP = "url";
  public static final String SESSION_ID_PROP = "session_id";
  public static final String MAX_STEPS_PROP = "max_steps";
  public static final String TIMEOUT_MS_PROP = "timeout_ms";
  public static final String OUTPUT_SCHEMA_PROP = "output_schema";

  private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "failed", "timed_out");

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(PROMPT_PROP)
            .label("Prompt")
            .instructionText("The task in natural language. Be specific about the goal and what to return. "
                + "Never include passwords or secrets -- use Session ID for authenticated sites.")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        textProperty(URL_PROP)
            .label("URL")
            .instructionText("Navigate here before starting. Leave blank to let the agent follow URLs "
                + "named in the prompt.")
            .isExpressionable(true)
            .build(),
        textProperty(SESSION_ID_PROP)
            .label("Session ID")
            .instructionText("Saved browser-session ID (from session_list) so the task runs logged in.")
            .isExpressionable(true)
            .build(),
        integerProperty(MAX_STEPS_PROP)
            .label("Max Steps")
            .instructionText("Cap on agent steps (navigation/click/type actions).")
            .isExpressionable(true)
            .build(),
        integerProperty(TIMEOUT_MS_PROP)
            .label("Timeout (ms)")
            .instructionText("Task timeout in milliseconds (the server caps runs at ~330s regardless).")
            .isExpressionable(true)
            .build(),
        textProperty(OUTPUT_SCHEMA_PROP)
            .label("Output Schema (JSON)")
            .instructionText("JSON Schema for the result -- the agent returns structured data conforming to it. "
                + "Leave blank for a free-form result.")
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
    String url = integrationConfiguration.getValue(URL_PROP);
    String sessionId = integrationConfiguration.getValue(SESSION_ID_PROP);
    Integer maxSteps = integrationConfiguration.getValue(MAX_STEPS_PROP);
    Integer timeoutMs = integrationConfiguration.getValue(TIMEOUT_MS_PROP);
    String outputSchemaJson = integrationConfiguration.getValue(OUTPUT_SCHEMA_PROP);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("prompt", prompt);
    // Always async: the sync variant can block for up to ~5.5 minutes, far past a typical request timeout.
    requestBody.put("async", true);
    if (url != null && !url.isBlank()) {
      requestBody.put("url", url);
    }
    if (sessionId != null && !sessionId.isBlank()) {
      requestBody.put("session_id", sessionId);
    }
    if (maxSteps != null) {
      requestBody.put("max_steps", maxSteps);
    }
    if (timeoutMs != null) {
      requestBody.put("timeout_ms", timeoutMs);
    }
    if (outputSchemaJson != null && !outputSchemaJson.isBlank()) {
      try {
        requestBody.put("output_schema",
            new ObjectMapper().readValue(outputSchemaJson, new TypeReference<Map<String, Object>>() { }));
      } catch (IOException e) {
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Invalid Output Schema")
                .message("Output Schema (JSON) must be a valid JSON object: " + e.getMessage())
                .build())
            .build();
      }
    }

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> submitted = client.post("/ai/evaluate", requestBody);
      Object workflowId = submitted.get("workflow_id");
      // The service should always answer async given async:true, but tolerate an inline
      // synchronous response the same way anakin-mcp's TypeScript client does.
      Map<String, Object> result = (workflowId instanceof String)
          ? client.pollUntilStatusIn("/ai/jobs/" + workflowId, TERMINAL_STATUSES)
          : submitted;
      long executionTime = System.currentTimeMillis() - startTime;

      Object status = result.get("status");
      Map<String, Object> responseDiagnostic = new HashMap<>();
      responseDiagnostic.put("workflowId", workflowId);
      responseDiagnostic.put("status", status);
      diagnosticBuilder.addResponseDiagnostic(responseDiagnostic).addExecutionTimeDiagnostic(executionTime);

      if ("failed".equals(status) || "timed_out".equals(status)) {
        Object error = result.get("error");
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Browser task " + status)
                .message(error != null ? error.toString() : "Anakin browser task " + status + " with no error detail.")
                .build())
            .withDiagnostic(diagnosticBuilder.build())
            .build();
      }
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin ai/evaluate request failed for prompt={} statusCode={}: {}",
          prompt, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin browser_task request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
