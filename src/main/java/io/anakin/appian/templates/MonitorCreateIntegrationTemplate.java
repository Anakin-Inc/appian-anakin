package io.anakin.appian.templates;

import static io.anakin.appian.templates.AnakinConnectedSystemTemplate.API_KEY_PROP;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * Creates a scheduled website monitor via Anakin's {@code POST /v1/monitors}.
 *
 * A monitor checks a URL (or a whole site, or a Wire action's JSON) every
 * {@code intervalMinutes} (minimum 15) and records a change whenever the
 * content differs, optionally alerting a webhook or email. {@code scope}
 * "page" (default) watches one URL; "site" crawls the site each run and
 * tracks pages added/removed/changed; "wire" runs a Wire action each check
 * and diffs its JSON. {@code watchMode} "full_page" compares the whole page;
 * "specific_data" extracts only the fields in {@code outputSchema} with AI --
 * ideal for price/stock/status tracking. Marked
 * {@link IntegrationTemplateRequestPolicy#WRITE}: it creates a recurring,
 * credit-billed job that can send alerts.
 *
 * The API response includes an {@code alertWebhookSecret} (an HMAC signing
 * secret for verifying webhook alerts); unlike anakin-mcp's chat-facing
 * monitor_create tool (which redacts it -- any secret entering an LLM
 * transcript is compromised by definition), this Appian integration returns
 * the Anakin API's JSON as-is, matching every other template in this
 * plug-in: an Appian designer's own dictionary/process-variable output is
 * not an LLM transcript, and the secret is exactly what they need to
 * configure webhook signature verification downstream.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.monitorCreate}, {@code MonitorCreateOptions})
 * and its monitor_create MCP tool (src/tools/monitor.ts) exactly.
 */
@TemplateId(name = "AnakinMonitorCreateIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class MonitorCreateIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(MonitorCreateIntegrationTemplate.class);

  public static final String URL_PROP = "url";
  public static final String INTERVAL_MINUTES_PROP = "intervalMinutes";
  public static final String SCOPE_PROP = "scope";
  public static final String WATCH_MODE_PROP = "watchMode";
  public static final String WATCH_FORMAT_PROP = "watchFormat";
  public static final String OUTPUT_SCHEMA_PROP = "outputSchema";
  public static final String AI_MODE_PROP = "aiMode";
  public static final String AI_GOAL_PROP = "aiGoal";
  public static final String USE_BROWSER_PROP = "useBrowser";
  public static final String COUNTRY_PROP = "country";
  public static final String SESSION_ID_PROP = "sessionId";
  public static final String IS_ACTIVE_PROP = "isActive";
  public static final String EXPIRES_AT_PROP = "expiresAt";
  public static final String ALERT_WEBHOOK_URL_PROP = "alertWebhookUrl";
  public static final String ALERT_EMAILS_PROP = "alertEmails";
  public static final String MAX_PAGES_PROP = "maxPages";
  public static final String MAX_DEPTH_PROP = "maxDepth";
  public static final String INCLUDE_PATTERNS_PROP = "includePatterns";
  public static final String EXCLUDE_PATTERNS_PROP = "excludePatterns";
  public static final String WIRE_ACTION_ID_PROP = "wireActionId";
  public static final String WIRE_CATALOG_SLUG_PROP = "wireCatalogSlug";
  public static final String WIRE_CREDENTIAL_ID_PROP = "wireCredentialId";
  public static final String WIRE_PARAMS_PROP = "wireParams";
  public static final String WIRE_WATCH_PATHS_PROP = "wireWatchPaths";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(URL_PROP)
            .label("URL")
            .instructionText("The URL to watch (root URL for site scope; the Wire site's URL for wire scope).")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        integerProperty(INTERVAL_MINUTES_PROP)
            .label("Interval Minutes")
            .instructionText("Check frequency in minutes. Minimum 15.")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        textProperty(SCOPE_PROP)
            .label("Scope")
            .instructionText("What to monitor: \"page\" (default), \"site\", or \"wire\".")
            .placeholder("page")
            .isExpressionable(true)
            .build(),
        textProperty(WATCH_MODE_PROP)
            .label("Watch Mode")
            .instructionText("\"full_page\" (default) compares the whole page; \"specific_data\" extracts "
                + "only the fields in Output Schema, with AI.")
            .placeholder("full_page")
            .isExpressionable(true)
            .build(),
        textProperty(WATCH_FORMAT_PROP)
            .label("Watch Format")
            .instructionText("Format compared in full_page mode: \"markdown\" (default), \"html\", or \"cleaned_html\".")
            .placeholder("markdown")
            .isExpressionable(true)
            .build(),
        textProperty(OUTPUT_SCHEMA_PROP)
            .label("Output Schema (JSON)")
            .instructionText("JSON Schema of the fields to track. Required when Watch Mode is \"specific_data\".")
            .isExpressionable(true)
            .build(),
        booleanProperty(AI_MODE_PROP)
            .label("AI Mode")
            .instructionText("AI meaningful-change filtering: ignores trivial noise (ads, timestamps) and "
                + "summarizes real changes. Costs +1 credit per check.")
            .build(),
        textProperty(AI_GOAL_PROP)
            .label("AI Goal")
            .instructionText("Natural-language description of which changes count as meaningful "
                + "(used with AI Mode), e.g. \"only when the price drops or it goes out of stock\".")
            .isExpressionable(true)
            .build(),
        booleanProperty(USE_BROWSER_PROP)
            .label("Use Browser")
            .instructionText("Render checks with a stealth headless browser (needed for JS-heavy pages). "
                + "Forced true when Session ID is set.")
            .build(),
        textProperty(COUNTRY_PROP)
            .label("Country")
            .instructionText("Two-letter proxy country code. Defaults to \"us\".")
            .placeholder("us")
            .isExpressionable(true)
            .build(),
        textProperty(SESSION_ID_PROP)
            .label("Session ID")
            .instructionText("Saved browser-session ID for monitoring login-protected pages.")
            .isExpressionable(true)
            .build(),
        booleanProperty(IS_ACTIVE_PROP)
            .label("Is Active")
            .instructionText("Start running immediately. Defaults to true.")
            .build(),
        textProperty(EXPIRES_AT_PROP)
            .label("Expires At")
            .instructionText("Optional end date (ISO 8601 timestamp or YYYY-MM-DD); the monitor auto-pauses "
                + "when it passes.")
            .isExpressionable(true)
            .build(),
        textProperty(ALERT_WEBHOOK_URL_PROP)
            .label("Alert Webhook URL")
            .instructionText("Webhook URL that receives signed change alerts.")
            .isExpressionable(true)
            .build(),
        textProperty(ALERT_EMAILS_PROP)
            .label("Alert Emails")
            .instructionText("Comma-separated email recipients for change alerts.")
            .isExpressionable(true)
            .build(),
        integerProperty(MAX_PAGES_PROP)
            .label("Max Pages")
            .instructionText("Site scope: max pages crawled per run.")
            .isExpressionable(true)
            .build(),
        integerProperty(MAX_DEPTH_PROP)
            .label("Max Depth")
            .instructionText("Site scope: crawl depth (1-5). Defaults to 2.")
            .isExpressionable(true)
            .build(),
        textProperty(INCLUDE_PATTERNS_PROP)
            .label("Include Patterns")
            .instructionText("Site scope: comma-separated glob patterns or hand-picked same-site URLs to track.")
            .isExpressionable(true)
            .build(),
        textProperty(EXCLUDE_PATTERNS_PROP)
            .label("Exclude Patterns")
            .instructionText("Site scope: comma-separated glob patterns to skip.")
            .isExpressionable(true)
            .build(),
        textProperty(WIRE_ACTION_ID_PROP)
            .label("Wire Action ID")
            .instructionText("Wire scope (required there): the Wire action run each check, "
                + "e.g. \"amazon.search_products\" (see wire_discover).")
            .isExpressionable(true)
            .build(),
        textProperty(WIRE_CATALOG_SLUG_PROP)
            .label("Wire Catalog Slug")
            .instructionText("Wire scope: catalog slug of the Wire site.")
            .isExpressionable(true)
            .build(),
        textProperty(WIRE_CREDENTIAL_ID_PROP)
            .label("Wire Credential ID")
            .instructionText("Wire scope: credential ID when the action needs auth (see wire_identities).")
            .isExpressionable(true)
            .build(),
        textProperty(WIRE_PARAMS_PROP)
            .label("Wire Params (JSON)")
            .instructionText("Wire scope: parameters passed to the action each check, as a JSON object.")
            .isExpressionable(true)
            .build(),
        textProperty(WIRE_WATCH_PATHS_PROP)
            .label("Wire Watch Paths")
            .instructionText("Wire scope: comma-separated JSON paths to diff instead of the whole response.")
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

    String url = integrationConfiguration.getValue(URL_PROP);
    Integer intervalMinutes = integrationConfiguration.getValue(INTERVAL_MINUTES_PROP);
    String scope = integrationConfiguration.getValue(SCOPE_PROP);
    String watchMode = integrationConfiguration.getValue(WATCH_MODE_PROP);
    String watchFormat = integrationConfiguration.getValue(WATCH_FORMAT_PROP);
    String outputSchemaJson = integrationConfiguration.getValue(OUTPUT_SCHEMA_PROP);
    Boolean aiMode = integrationConfiguration.getValue(AI_MODE_PROP);
    String aiGoal = integrationConfiguration.getValue(AI_GOAL_PROP);
    Boolean useBrowser = integrationConfiguration.getValue(USE_BROWSER_PROP);
    String country = integrationConfiguration.getValue(COUNTRY_PROP);
    String sessionId = integrationConfiguration.getValue(SESSION_ID_PROP);
    Boolean isActive = integrationConfiguration.getValue(IS_ACTIVE_PROP);
    String expiresAt = integrationConfiguration.getValue(EXPIRES_AT_PROP);
    String alertWebhookUrl = integrationConfiguration.getValue(ALERT_WEBHOOK_URL_PROP);
    String alertEmails = integrationConfiguration.getValue(ALERT_EMAILS_PROP);
    Integer maxPages = integrationConfiguration.getValue(MAX_PAGES_PROP);
    Integer maxDepth = integrationConfiguration.getValue(MAX_DEPTH_PROP);
    String includePatterns = integrationConfiguration.getValue(INCLUDE_PATTERNS_PROP);
    String excludePatterns = integrationConfiguration.getValue(EXCLUDE_PATTERNS_PROP);
    String wireActionId = integrationConfiguration.getValue(WIRE_ACTION_ID_PROP);
    String wireCatalogSlug = integrationConfiguration.getValue(WIRE_CATALOG_SLUG_PROP);
    String wireCredentialId = integrationConfiguration.getValue(WIRE_CREDENTIAL_ID_PROP);
    String wireParamsJson = integrationConfiguration.getValue(WIRE_PARAMS_PROP);
    String wireWatchPaths = integrationConfiguration.getValue(WIRE_WATCH_PATHS_PROP);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("url", url);
    requestBody.put("intervalMinutes", intervalMinutes);
    // Only send fields the designer actually set -- the API applies its own defaults otherwise
    // (matches anakin-mcp's monitorCreate passthrough behavior in src/client.ts).
    putIfNotBlank(requestBody, "scope", scope);
    putIfNotBlank(requestBody, "watchMode", watchMode);
    putIfNotBlank(requestBody, "watchFormat", watchFormat);
    putIfNotBlank(requestBody, "aiGoal", aiGoal);
    putIfNotBlank(requestBody, "country", country);
    putIfNotBlank(requestBody, "sessionId", sessionId);
    putIfNotBlank(requestBody, "expiresAt", expiresAt);
    putIfNotBlank(requestBody, "alertWebhookUrl", alertWebhookUrl);
    putIfNotBlank(requestBody, "alertEmails", alertEmails);
    putIfNotBlank(requestBody, "wireActionId", wireActionId);
    putIfNotBlank(requestBody, "wireCatalogSlug", wireCatalogSlug);
    putIfNotBlank(requestBody, "wireCredentialId", wireCredentialId);
    if (aiMode != null) {
      requestBody.put("aiMode", aiMode);
    }
    if (useBrowser != null) {
      requestBody.put("useBrowser", useBrowser);
    }
    if (isActive != null) {
      requestBody.put("isActive", isActive);
    }
    if (maxPages != null) {
      requestBody.put("maxPages", maxPages);
    }
    if (maxDepth != null) {
      requestBody.put("maxDepth", maxDepth);
    }
    List<String> includeList = splitCsv(includePatterns);
    if (!includeList.isEmpty()) {
      requestBody.put("includePatterns", includeList);
    }
    List<String> excludeList = splitCsv(excludePatterns);
    if (!excludeList.isEmpty()) {
      requestBody.put("excludePatterns", excludeList);
    }
    List<String> wireWatchPathsList = splitCsv(wireWatchPaths);
    if (!wireWatchPathsList.isEmpty()) {
      requestBody.put("wireWatchPaths", wireWatchPathsList);
    }

    ObjectMapper objectMapper = new ObjectMapper();
    if (outputSchemaJson != null && !outputSchemaJson.isBlank()) {
      try {
        requestBody.put("outputSchema",
            objectMapper.readValue(outputSchemaJson, new TypeReference<Map<String, Object>>() { }));
      } catch (IOException e) {
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Invalid Output Schema")
                .message("Output Schema (JSON) must be a valid JSON object: " + e.getMessage())
                .build())
            .build();
      }
    }
    if (wireParamsJson != null && !wireParamsJson.isBlank()) {
      try {
        requestBody.put("wireParams",
            objectMapper.readValue(wireParamsJson, new TypeReference<Map<String, Object>>() { }));
      } catch (IOException e) {
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Invalid Wire Params")
                .message("Wire Params (JSON) must be a valid JSON object: " + e.getMessage())
                .build())
            .build();
      }
    }

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> result = client.post("/monitors", requestBody);
      long executionTime = System.currentTimeMillis() - startTime;
      diagnosticBuilder.addResponseDiagnostic(new HashMap<>(result)).addExecutionTimeDiagnostic(executionTime);
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin monitors (create) request failed for url={} statusCode={}: {}",
          url, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin monitor_create request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }

  private static void putIfNotBlank(Map<String, Object> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value);
    }
  }

  /** Splits a comma-separated Appian text field into a trimmed, non-blank list. */
  private static List<String> splitCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());
  }
}
