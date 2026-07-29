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
 * Lists website monitors via Anakin's {@code GET /v1/monitors}, or (when an
 * ID is supplied) fetches one monitor's full configuration and status via
 * {@code GET /v1/monitors/:id} -- next/last check time, active state,
 * per-check credit cost, alert settings. Use this to find a monitor's id
 * before {@link MonitorChangesIntegrationTemplate} or
 * {@link MonitorControlIntegrationTemplate}.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.monitorList}/{@code monitorGet}) and
 * its monitor_list MCP tool (src/tools/monitor.ts) exactly.
 */
@TemplateId(name = "AnakinMonitorListIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class MonitorListIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(MonitorListIntegrationTemplate.class);

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
            .instructionText("Fetch just this monitor instead of the full list. Leave blank to list all.")
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

    String path = (id != null && !id.isBlank()) ? "/monitors/" + id : "/monitors";

    Map<String, Object> requestDiagnostic = new LinkedHashMap<>();
    requestDiagnostic.put("id", id);
    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(requestDiagnostic);

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> result = client.get(path);
      long executionTime = System.currentTimeMillis() - startTime;
      diagnosticBuilder.addResponseDiagnostic(new HashMap<>(result)).addExecutionTimeDiagnostic(executionTime);
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin monitors (list/get) request failed for id={} statusCode={}: {}",
          id, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin monitor_list request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
