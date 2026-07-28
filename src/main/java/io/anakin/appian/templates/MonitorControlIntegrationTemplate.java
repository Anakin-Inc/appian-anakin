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
 * Controls an existing website monitor via Anakin's
 * {@code POST /v1/monitors/:id/pause}, {@code POST /v1/monitors/:id/resume},
 * {@code POST /v1/monitors/:id/run}, or {@code DELETE /v1/monitors/:id}.
 *
 * "pause" stops scheduled checks, "resume" restarts them (may hit the plan's
 * active-monitor cap), "run_now" triggers an immediate out-of-schedule check
 * (billed like a normal check, and maps to the {@code /run} endpoint --
 * confirmed against anakin-mcp's TypeScript client, not the action name
 * itself), and "delete" permanently removes the monitor and its history.
 * Use {@link MonitorListIntegrationTemplate} to find the id. Marked
 * {@link IntegrationTemplateRequestPolicy#WRITE}: every action mutates the
 * monitor, and delete is irreversible.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.monitorControl}) and its
 * monitor_control MCP tool (src/tools/monitor.ts) exactly.
 */
@TemplateId(name = "AnakinMonitorControlIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class MonitorControlIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(MonitorControlIntegrationTemplate.class);

  public static final String ID_PROP = "id";
  public static final String ACTION_PROP = "action";

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
            .build(),
        textProperty(ACTION_PROP)
            .label("Action")
            .instructionText("What to do with the monitor: \"pause\", \"resume\", \"run_now\", or \"delete\".")
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
    String action = integrationConfiguration.getValue(ACTION_PROP);

    Map<String, Object> requestDiagnostic = new LinkedHashMap<>();
    requestDiagnostic.put("id", id);
    requestDiagnostic.put("action", action);
    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(requestDiagnostic);

    String base = "/monitors/" + id;
    String endpoint;
    boolean isDelete = false;
    switch (action == null ? "" : action) {
      case "pause":
        endpoint = base + "/pause";
        break;
      case "resume":
        endpoint = base + "/resume";
        break;
      case "run_now":
        endpoint = base + "/run";
        break;
      case "delete":
        endpoint = base;
        isDelete = true;
        break;
      default:
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Invalid Action")
                .message("Unknown monitor action \"" + action + "\" -- use pause, resume, run_now, or delete.")
                .build())
            .withDiagnostic(diagnosticBuilder.build())
            .build();
    }

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> result = isDelete ? client.delete(endpoint) : client.post(endpoint);
      long executionTime = System.currentTimeMillis() - startTime;
      diagnosticBuilder.addResponseDiagnostic(new HashMap<>(result)).addExecutionTimeDiagnostic(executionTime);
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin monitor control request failed for id={} action={} statusCode={}: {}",
          id, action, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin monitor_control request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
