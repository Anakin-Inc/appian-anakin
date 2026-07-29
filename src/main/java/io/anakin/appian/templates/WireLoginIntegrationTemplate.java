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
 * Signs in to a credentials-mode Wire site via Anakin's
 * {@code POST /v1/wire/login}, returning a {@code credential_id} usable
 * immediately with {@link WireReadActionIntegrationTemplate} /
 * {@link WireWriteActionIntegrationTemplate}.
 *
 * The password is never stored by Anakin, only the resulting encrypted
 * session. Only needed for actions whose {@code auth_mode} is "required",
 * and only for catalogs that support password sign-in; cookie-based sites
 * use the dashboard connect flow instead. Marked
 * {@link IntegrationTemplateRequestPolicy#WRITE}: it establishes and stores
 * an encrypted session server-side, a side effect.
 *
 * Request/response shapes mirror anakin-mcp's TypeScript client
 * (src/client.ts {@code AnakinClient.wireLogin}, {@code WireLoginRequest})
 * and its wire_login MCP tool (src/tools/wire.ts) exactly.
 */
@TemplateId(name = "AnakinWireLoginIntegrationTemplate")
@IntegrationTemplateType(IntegrationTemplateRequestPolicy.WRITE)
public class WireLoginIntegrationTemplate extends SimpleIntegrationTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(WireLoginIntegrationTemplate.class);

  public static final String CATALOG_SLUG_PROP = "catalog_slug";
  public static final String PARAMS_PROP = "params";
  public static final String IDENTITY_NAME_PROP = "identity_name";
  public static final String SOURCE_ID_PROP = "source_id";
  public static final String SOURCE_REF_PROP = "source_ref";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration integrationConfiguration,
      SimpleConfiguration connectedSystemConfiguration,
      PropertyPath propertyPath,
      ExecutionContext executionContext) {
    return integrationConfiguration.setProperties(
        textProperty(CATALOG_SLUG_PROP)
            .label("Catalog Slug")
            .instructionText("The catalog to sign in to, e.g. \"neb\".")
            .isRequired(true)
            .isExpressionable(true)
            .build(),
        textProperty(PARAMS_PROP)
            .label("Login Params (JSON)")
            .instructionText("Login fields defined by the catalog as a JSON object, e.g. "
                + "{\"email\": \"...\", \"password\": \"...\"}. Use wire_catalog's login_input_schema "
                + "to learn the field names.")
            .isExpressionable(true)
            .build(),
        textProperty(IDENTITY_NAME_PROP)
            .label("Identity Name")
            .instructionText("Optional name for the identity. Derived from Login Params in password mode; "
                + "required when using a 1Password locator (Source ID).")
            .isExpressionable(true)
            .build(),
        textProperty(SOURCE_ID_PROP)
            .label("Source ID")
            .instructionText("Optional 1Password identity-source ID (alternative to Login Params).")
            .isExpressionable(true)
            .build(),
        textProperty(SOURCE_REF_PROP)
            .label("Source Ref (JSON)")
            .instructionText("Optional 1Password item locator as a JSON object "
                + "{\"vault_id\": ..., \"item_id\": ..., \"fields\": ...} (use with Source ID instead of Login Params).")
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
    String catalogSlug = integrationConfiguration.getValue(CATALOG_SLUG_PROP);
    String paramsJson = integrationConfiguration.getValue(PARAMS_PROP);
    String identityName = integrationConfiguration.getValue(IDENTITY_NAME_PROP);
    String sourceId = integrationConfiguration.getValue(SOURCE_ID_PROP);
    String sourceRefJson = integrationConfiguration.getValue(SOURCE_REF_PROP);

    ObjectMapper objectMapper = new ObjectMapper();
    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("catalog_slug", catalogSlug);
    if (paramsJson != null && !paramsJson.isBlank()) {
      try {
        requestBody.put("params", objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() { }));
      } catch (IOException e) {
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Invalid Login Params")
                .message("Login Params (JSON) must be a valid JSON object: " + e.getMessage())
                .build())
            .build();
      }
    }
    if (identityName != null && !identityName.isBlank()) {
      requestBody.put("identity_name", identityName);
    }
    if (sourceId != null && !sourceId.isBlank()) {
      requestBody.put("source_id", sourceId);
    }
    if (sourceRefJson != null && !sourceRefJson.isBlank()) {
      try {
        requestBody.put("source_ref", objectMapper.readValue(sourceRefJson, new TypeReference<Map<String, Object>>() { }));
      } catch (IOException e) {
        return IntegrationResponse.forError(
            IntegrationError.builder()
                .title("Invalid Source Ref")
                .message("Source Ref (JSON) must be a valid JSON object: " + e.getMessage())
                .build())
            .build();
      }
    }

    IntegrationDesignerDiagnostic.IntegrationDesignerDiagnosticBuilder diagnosticBuilder =
        IntegrationDesignerDiagnostic.builder().addRequestDiagnostic(new HashMap<>(requestBody));

    AnakinApiClient client = new AnakinApiClient(apiKey);
    long startTime = System.currentTimeMillis();
    try {
      Map<String, Object> result = client.post("/wire/login", requestBody);
      long executionTime = System.currentTimeMillis() - startTime;
      diagnosticBuilder.addResponseDiagnostic(new HashMap<>(result)).addExecutionTimeDiagnostic(executionTime);
      return IntegrationResponse.forSuccess(result).withDiagnostic(diagnosticBuilder.build()).build();
    } catch (AnakinApiException e) {
      LOG.error("Anakin wire/login request failed for catalogSlug={} statusCode={}: {}",
          catalogSlug, e.getStatusCode(), e.getMessage(), e);
      diagnosticBuilder.addExecutionTimeDiagnostic(System.currentTimeMillis() - startTime);
      return IntegrationResponse.forError(
          IntegrationError.builder()
              .title("Anakin wire_login request failed")
              .message(e.getMessage())
              .build())
          .withDiagnostic(diagnosticBuilder.build())
          .build();
    }
  }
}
