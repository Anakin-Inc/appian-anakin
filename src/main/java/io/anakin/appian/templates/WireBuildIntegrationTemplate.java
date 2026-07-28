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
 * Requests a brand-new Wire action for a website not yet in the catalog, via
 * Anakin's {@code POST /v1/wire/build-request}.
 *
 * Describe the site and what the action should do or extract; Wire generates
 * and auto-tests a scraper, then publishes it. Asynchronous (the response
 * comes back with status "pending"; this template does not poll it -- there
 * is no documented poll endpoint for build requests) and charges credits,
 * refunded automatically if the build fails. Only use this after
 * {@link WireDiscoverIntegrationTemplate} / {@link WireCatalogIntegrationTemplate}
 * confirm no existing action covers the site. Marked
 * {@link IntegrationTemplateRequestPolicy#WRITE}: it spends credits and
 * publishes a new catalog action, a side effect.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.wireBuild}, {@code WireBuildRequest})
 * and its wire_build MCP tool (src/tools/wire.ts) exactly.
 */
@TemplateId(name = "AnakinWireBuildIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class WireBuildIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(WireBuildIntegrationTemplate.class);

  public static final String WEBSITE_URL_PROP = "website_url";
  public static final String GOAL_PROP = "goal";
  public static final String CATALOG_ID_PROP = "catalog_id";
  public static final String VISIBILITY_PROP = "visibility";
  public static final String FORCE_PROP = "force";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(WEBSITE_URL_PROP)
            .label("Website URL")
            .instructionText("The site to build an action for. The domain is extracted automatically.")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        textProperty(GOAL_PROP)
            .label("Goal")
            .instructionText("Natural-language description of what the action should do or extract. "
                + "Be specific -- the builder synthesizes the scraper from this.")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        textProperty(CATALOG_ID_PROP)
            .label("Catalog ID")
            .instructionText("Optional -- attach to an existing catalog instead of creating one.")
            .isExpressionable(true)
            .build(),
        textProperty(VISIBILITY_PROP)
            .label("Visibility")
            .instructionText("Action visibility: \"private\" or \"public\". Defaults to private.")
            .placeholder("private")
            .isExpressionable(true)
            .build(),
        booleanProperty(FORCE_PROP)
            .label("Force")
            .instructionText("Build even if similar actions already exist for the domain "
                + "(otherwise the request is rejected with ACTION_EXISTS).")
            .build()
    );
  }

  @Override
  protected IntegrationResponse execute(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      ExecutionContext executionContext) {
    String apiKey = connectedSystemConfiguration.getValue(API_KEY_PROP);
    String websiteUrl = integrationConfiguration.getValue(WEBSITE_URL_PROP);
    String goal = integrationConfiguration.getValue(GOAL_PROP);
    String catalogId = integrationConfiguration.getValue(CATALOG_ID_PROP);
    String visibility = integrationConfiguration.getValue(VISIBILITY_PROP);
    Boolean force = orDefault(integrationConfiguration.getValue(FORCE_PROP), Boolean.FALSE);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("website_url", websiteUrl);
    requestBody.put("goal", goal);
    if (catalogId != null && !catalogId.isBlank()) {
      requestBody.put("catalog_id", catalogId);
    }
    if ("private".equals(visibility) || "public".equals(visibility)) {
      requestBody.put("visibility", visibility);
    }
    requestBody.put("force", force);

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> result = client.post("/wire/build-request", requestBody);
      long executionTime = System.currentTimeMillis() - startTime;
      diagnosticBuilder.addResponseDiagnostic(new HashMap<>(result)).addExecutionTimeDiagnostic(executionTime);
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin wire/build-request request failed for websiteUrl={} statusCode={}: {}",
          websiteUrl, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin wire_build request failed")
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
