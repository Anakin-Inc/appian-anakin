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
 * Gets the detected changes for a monitor via Anakin's
 * {@code GET /v1/monitors/:id/changes}.
 *
 * Each entry records when the watched content differed from the previous
 * check, with a diff/summary (and the AI change summary when {@code aiMode}
 * is on). Use {@link MonitorListIntegrationTemplate} first to find the
 * monitor id.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.monitorChanges}) and its monitor_changes
 * MCP tool (src/tools/monitor.ts) exactly.
 */
@TemplateId(name = "AnakinMonitorChangesIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class MonitorChangesIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(MonitorChangesIntegrationTemplate.class);

  public static final String ID_PROP = "id";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(ID_PROP)
            .label("Monitor ID")
            .instructionText("The monitor ID (from monitor_list or monitor_create).")
            .isRequired(true)
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
    String id = integrationConfiguration.getValue(ID_PROP);

    Map<String, Object> requestDiagnostic = new LinkedHashMap<>();
    requestDiagnostic.put("id", id);
    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(requestDiagnostic);

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> result = client.get("/monitors/" + id + "/changes");
      long executionTime = System.currentTimeMillis() - startTime;
      diagnosticBuilder.addResponseDiagnostic(new HashMap<>(result)).addExecutionTimeDiagnostic(executionTime);
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin monitors/changes request failed for id={} statusCode={}: {}",
          id, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin monitor_changes request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
