package io.anakin.appian.templates;

import com.appian.connectedsystems.simplified.sdk.configuration.SimpleConfiguration;
import com.appian.connectedsystems.simplified.sdk.connectiontesting.SimpleTestableConnectedSystemTemplate;
import com.appian.connectedsystems.templateframework.sdk.ExecutionContext;
import com.appian.connectedsystems.templateframework.sdk.TemplateId;
import com.appian.connectedsystems.templateframework.sdk.connectiontesting.TestConnectionResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.anakin.appian.templates.AnakinApiClient.AnakinApiException;

/**
 * Connected System for the Anakin API (https://anakin.io). Holds the one
 * credential every Anakin integration in this plug-in shares: the API key,
 * sent as the {@code X-API-Key} header on every request.
 *
 * <p>Get a free key (300 credits, no card required) at
 * https://anakin.io/dashboard.
 */
@TemplateId(name = "AnakinConnectedSystemTemplate")
public class AnakinConnectedSystemTemplate extends SimpleTestableConnectedSystemTemplate {

  private static final Logger LOG = LoggerFactory.getLogger(AnakinConnectedSystemTemplate.class);

  public static final String API_KEY_PROP = "apiKey";

  @Override
  protected SimpleConfiguration getConfiguration(
      SimpleConfiguration simpleConfiguration, ExecutionContext executionContext) {
    return simpleConfiguration.setProperties(
        // Sensitive value -- encryptedTextProperty, same as the official
        // Google Places (API Key Auth) CSP example.
        encryptedTextProperty(API_KEY_PROP)
            .label("API Key")
            .instructionText("Find your key at https://anakin.io/dashboard "
                + "(Settings > API Keys). Sent as the X-API-Key header on every request.")
            .isRequired(true)
            .build()
    );
  }

  @Override
  protected TestConnectionResult testConnection(
      SimpleConfiguration simpleConfiguration, ExecutionContext executionContext) {
    String apiKey = simpleConfiguration.getValue(API_KEY_PROP);
    if (apiKey == null || apiKey.isBlank()) {
      return TestConnectionResult.error("API Key is required.");
    }
    try {
      // GET /sessions is a lightweight, authenticated, no-credit-cost
      // endpoint (unlike /search or /agentic-search, which spend credits
      // per call) -- a 200 response here is enough to confirm the key is
      // valid without charging the designer's account just to test the
      // connection.
      new AnakinApiClient(apiKey).get("/sessions");
      return TestConnectionResult.success();
    } catch (AnakinApiException e) {
      LOG.error("Anakin connection test failed, statusCode={}: {}", e.getStatusCode(), e.getMessage(), e);
      return TestConnectionResult.error("Could not authenticate with Anakin: " + e.getMessage());
    }
  }
}
