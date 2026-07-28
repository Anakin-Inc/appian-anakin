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
 * Finds Wire actions for a task from a natural-language intent, via
 * Anakin's {@code GET /v1/wire/resolve?q=&limit=}.
 *
 * Wire is Anakin's catalog of pre-built automation actions across hundreds
 * of sites (Amazon, Walmart, LinkedIn, Airbnb, Zillow, and others). Returns
 * ranked candidate actions, each with its {@code action_id}, {@code type}
 * ("read" or "write"), required/optional params, credit cost, and whether
 * auth is needed -- run the chosen action with
 * {@link WireReadActionIntegrationTemplate} or {@link WireWriteActionIntegrationTemplate}.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.wireResolve}) and its wire_discover
 * MCP tool (src/tools/wire.ts) exactly.
 */
@TemplateId(name = "AnakinWireDiscoverIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)
public class WireDiscoverIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(WireDiscoverIntegrationTemplate.class);

  public static final String Q_PROP = "q";
  public static final String LIMIT_PROP = "limit";

  private static final int DEFAULT_LIMIT = 5;

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(Q_PROP)
            .label("Intent")
            .instructionText("The task in natural language, e.g. \"top phones on walmart\", "
                + "\"search airbnb listings in Lisbon\".")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        integerProperty(LIMIT_PROP)
            .label("Limit")
            .instructionText("Maximum number of candidate actions to return. Defaults to " + DEFAULT_LIMIT + ".")
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
    String q = integrationConfiguration.getValue(Q_PROP);
    Integer limit = orDefault(integrationConfiguration.getValue(LIMIT_PROP), DEFAULT_LIMIT);

    Map<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put("q", q);
    queryParams.put("limit", String.valueOf(limit));
    String path = AnakinApiClient.withQuery("/wire/resolve", queryParams);

    Map<String, Object> requestDiagnostic = new LinkedHashMap<>();
    requestDiagnostic.put("q", q);
    requestDiagnostic.put("limit", limit);
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
      LOG.error("Anakin wire/resolve request failed for q={} statusCode={}: {}",
          q, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin wire_discover request failed")
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
