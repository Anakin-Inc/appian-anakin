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
 * Lists saved browser sessions via Anakin's {@code GET /v1/sessions?domain=}.
 *
 * Saved browser sessions are encrypted login states (cookies/localStorage)
 * created via the Anakin dashboard or Browser API, then reused for
 * authenticated work: each session's id is what {@link MapIntegrationTemplate},
 * {@link CrawlIntegrationTemplate}, {@link MonitorCreateIntegrationTemplate},
 * and {@link BrowserTaskIntegrationTemplate} take as their session id
 * property. Optionally filter by website domain.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.sessionsList}) and its session_list
 * MCP tool (src/tools/sessions.ts) exactly. Also used by
 * {@link AnakinConnectedSystemTemplate#testConnection} as the lightweight,
 * no-credit-cost connection check.
 */
@TemplateId(name = "AnakinSessionListIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class SessionListIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(SessionListIntegrationTemplate.class);

  public static final String DOMAIN_PROP = "domain";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(DOMAIN_PROP)
            .label("Domain")
            .instructionText("Filter to sessions for one website domain, e.g. \"amazon.com\". Leave blank for all.")
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
    String domain = integrationConfiguration.getValue(DOMAIN_PROP);

    Map<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put("domain", domain);
    String path = AnakinApiClient.withQuery("/sessions", queryParams);

    Map<String, Object> requestDiagnostic = new LinkedHashMap<>();
    requestDiagnostic.put("domain", domain);
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
      LOG.error("Anakin sessions (list) request failed for domain={} statusCode={}: {}",
          domain, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin session_list request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
