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
 * Runs a Wire WRITE action -- one whose {@code type} is {@code "write"} (it
 * performs a state-changing interaction on the target site): submit a form,
 * add an item to a cart, post or send content, update account settings.
 *
 * Submits {@code POST /v1/wire/task} with {@code {action_id, params?,
 * credential_id?, identity_id?}}. Most write actions come back with a
 * {@code job_id} to poll at {@code GET /v1/wire/jobs/:jobId}; this template
 * handles both the polled and inline-response cases without the caller
 * needing to know which. Most write actions need auth -- pass a
 * {@code credential_id} from {@link WireIdentitiesIntegrationTemplate} or
 * {@link WireLoginIntegrationTemplate}. Discover {@code action_id}s first
 * with {@link WireDiscoverIntegrationTemplate} or
 * {@link WireCatalogIntegrationTemplate} and confirm the action's type is
 * "write"; for data extraction that does not change state (type "read") use
 * {@link WireReadActionIntegrationTemplate} instead.
 *
 * Marked {@link IntegrationTemplateRequestPolicy#WRITE} (unlike its read
 * counterpart) because it changes state on the target site.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.wireRun}) and its wire_write_action
 * MCP tool (src/tools/wire.ts) exactly.
 */
@TemplateId(name = "AnakinWireWriteActionIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class WireWriteActionIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(WireWriteActionIntegrationTemplate.class);

  // Property keys match the Anakin Wire API's JSON field names exactly (snake_case),
  // same convention as the other templates' camelCase keys matching their APIs' camelCase fields.
  public static final String ACTION_ID_PROP = "action_id";
  public static final String PARAMS_PROP = "params";
  public static final String CREDENTIAL_ID_PROP = "credential_id";
  public static final String IDENTITY_ID_PROP = "identity_id";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(ACTION_ID_PROP)
            .label("Action ID")
            .instructionText("The Wire action to run (from wire_discover / wire_catalog), "
                + "e.g. \"amazon.add_to_cart\".")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        textProperty(PARAMS_PROP)
            .label("Params (JSON)")
            .instructionText("The action's input parameters as a JSON object -- shape depends on the "
                + "action; use its parameter schema from discovery. Leave blank for actions that take none.")
            .isExpressionable(true)
            .build(),
        textProperty(CREDENTIAL_ID_PROP)
            .label("Credential ID")
            .instructionText("Required when the action's auth_mode is \"required\"; honored when \"optional\"; "
                + "ignored when \"none\". Get one from wire_identities or wire_login.")
            .isExpressionable(true)
            .build(),
        textProperty(IDENTITY_ID_PROP)
            .label("Identity ID")
            .instructionText("Optional identity selector -- the server resolves a credential from it "
                + "(alternative to Credential ID).")
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
    String actionId = integrationConfiguration.getValue(ACTION_ID_PROP);
    String paramsJson = integrationConfiguration.getValue(PARAMS_PROP);
    String credentialId = integrationConfiguration.getValue(CREDENTIAL_ID_PROP);
    String identityId = integrationConfiguration.getValue(IDENTITY_ID_PROP);

    Map<String, Object> params;
    if (paramsJson != null && !paramsJson.isBlank()) {
      try {
        params = new ObjectMapper().readValue(paramsJson, new TypeReference<Map<String, Object>>() { });
      } catch (IOException e) {
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Invalid Params")
                .message("Params (JSON) must be a valid JSON object: " + e.getMessage())
                .build())
            .build();
      }
    } else {
      params = Map.of();
    }

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("action_id", actionId);
    if (!params.isEmpty()) {
      requestBody.put("params", params);
    }
    if (credentialId != null && !credentialId.isBlank()) {
      requestBody.put("credential_id", credentialId);
    }
    if (identityId != null && !identityId.isBlank()) {
      requestBody.put("identity_id", identityId);
    }

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> submitted = client.post("/wire/task", requestBody);
      Object jobId = submitted.get("job_id");
      // Sync actions answer inline (no job_id); async ones need a poll to a terminal state.
      Map<String, Object> result = (jobId instanceof String)
          ? client.pollUntilTerminal("/wire/jobs/" + jobId)
          : submitted;
      long executionTime = System.currentTimeMillis() - startTime;

      Map<String, Object> responseDiagnostic = new HashMap<>();
      responseDiagnostic.put("jobId", jobId);
      responseDiagnostic.put("status", result.get("status"));
      diagnosticBuilder.addResponseDiagnostic(responseDiagnostic).addExecutionTimeDiagnostic(executionTime);

      if ("failed".equals(result.get("status"))) {
        Object error = result.get("error");
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Wire write action failed")
                .message(error != null ? error.toString() : "Anakin Wire action \"" + actionId + "\" failed with no error detail.")
                .build())
            .withDiagnostic(diagnosticBuilder.build())
            .build();
      }
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin wire/task (write) request failed for actionId={} statusCode={}: {}",
          actionId, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin wire_write_action request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
