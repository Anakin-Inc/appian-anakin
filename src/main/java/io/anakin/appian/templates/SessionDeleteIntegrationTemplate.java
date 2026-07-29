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
 * Permanently deletes a saved browser session via Anakin's
 * {@code DELETE /v1/sessions/:id}.
 *
 * Irreversible -- the user must log in again through the Anakin dashboard to
 * recreate it, and any monitors or requests referencing this session id lose
 * authenticated access. Find ids with {@link SessionListIntegrationTemplate}.
 * Marked {@link IntegrationTemplateRequestPolicy#WRITE}: it irreversibly
 * destroys stored login state.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.sessionDelete}) and its session_delete
 * MCP tool (src/tools/sessions.ts) exactly.
 */
@TemplateId(name = "AnakinSessionDeleteIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class SessionDeleteIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(SessionDeleteIntegrationTemplate.class);

  public static final String ID_PROP = "id";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(ID_PROP)
            .label("Session ID")
            .instructionText("The session ID to delete (from session_list).")
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
      Map<String, Object> result = client.delete("/sessions/" + id);
      long executionTime = System.currentTimeMillis() - startTime;
      diagnosticBuilder.addResponseDiagnostic(new HashMap<>(result)).addExecutionTimeDiagnostic(executionTime);
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin sessions (delete) request failed for id={} statusCode={}: {}",
          id, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin session_delete request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
