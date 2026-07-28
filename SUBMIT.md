# Appian -- submission instructions

Real Appian Connected System Plug-in (Java, Maven), targeting `api.anakin.io/v1`.
Distinct from a marketplace/directory PR (Dagster, RapidAPI, etc.) -- Appian
plug-ins are deployed directly to a customer's Appian environment (Admin
Console > Plug-ins), not published to a public registry. There is no
central "Appian plug-in marketplace" repo to open a PR against; the
deliverable here is the plug-in jar itself plus its source.

## Real structure, confirmed against source -- not assumed

The task description's initial assumptions (`@PaletteInfo`/`@Category`
annotations, `.zip` packaging) turned out to be wrong for this SDK, and were
corrected against real sources rather than followed blindly:

- **Pulled the actual `appian/integration-sdk-examples` GitHub repo** via
  `gh api` (not a guess, not training-data recall) and read four full
  working examples end to end:
  - `Connected System Plug-in (CSP) Examples/Hello World/` -- minimal
    `SimpleConnectedSystemTemplate` + `SimpleIntegrationTemplate` pair.
  - `Connected System Plug-in (CSP) Examples/Google Locations (API Key Auth)/`
    -- the closest real analog to this plug-in: API-key auth stored on the
    connected system, a separate HTTP-client class, `testConnection()`,
    generic `Map<String,Object>` JSON responses via Jackson.
  - `Connected System Plug-in (CSP) Examples/Error Handling/` -- SLF4J
    logging, `IntegrationError` / `IntegrationDesignerDiagnostic` builder
    patterns used on error paths.
  - Every `appian-plugin.xml`, `build.gradle`, and `resources_en_US.properties`
    in those examples.
- **`@TemplateId`, `@IntegrationTemplateType(IntegrationTemplateRequestPolicy.READ)`,
  `SimpleIntegrationTemplate`, `SimpleConnectedSystemTemplate`/
  `SimpleTestableConnectedSystemTemplate`, `getConfiguration()`/`execute()`/
  `testConnection()` signatures, the `textProperty()`/`booleanProperty()`/
  `integerProperty()`/`encryptedTextProperty()` builder DSL, `IntegrationResponse.forSuccess()/forError()`,
  `IntegrationError.builder()`, `IntegrationDesignerDiagnostic.builder()`,
  `TestConnectionResult.success()/error()`** -- all copied from the real
  example source, not invented.
- **`@PaletteInfo` / `@Category` do not exist in this SDK.** Searched
  `appian/integration-sdk-examples` for both (`gh api search/code`, 0 hits
  for either) and confirmed via web search that `@PaletteInfo` belongs to
  Appian's older Process Modeling **Smart Service** SDK
  (`com.appiancorp.suiteapi.process.framework`), a completely different,
  older plug-in framework -- not the Connected System / Integration SDK
  (`com.appian.connectedsystems.*`) this plug-in uses. The task brief's
  premise was wrong here; built against the real framework instead of
  forcing in annotations that don't apply to it.
- **Deployment is a single `.jar`, not a `.zip`.** Confirmed two ways: (1)
  none of the four real example `build.gradle` files produce a zip -- the
  Gradle `jar` task nests runtime dependencies under `META-INF/lib` inside
  one jar and sets `Spring-Context: *;publish-context:=false` on the
  manifest; (2) web search of Appian's own docs/community
  ("Plug-ins" admin page, `pluginuploader-1.0.0.jar`) confirms jar upload,
  not zip. `pom.xml` + `src/assembly/plugin.xml` reproduce that exact jar
  layout with `maven-assembly-plugin` (Maven has no built-in equivalent of
  Gradle's `jar { into(...) { from(configurations.runtimeClasspath) } }`).

## What's here

```
pom.xml                                        Maven build, deps, assembly-plugin wiring
src/assembly/plugin.xml                        Custom jar layout (appian-plugin.xml + classes at root, deps under META-INF/lib)
src/main/resources/appian-plugin.xml            Plug-in manifest (connected system + 3 integration templates)
src/main/resources/resources_en_US.properties   Display names/descriptions (Appian requires this file)
src/main/java/.../AnakinConnectedSystemTemplate.java   Connected system: API key (encrypted) + testConnection() via GET /sessions
src/main/java/.../AnakinApiClient.java          Plain-Java HTTP helper (java.net.http, Jackson) -- no Appian SDK dependency, shared by all 3 integrations
src/main/java/.../UrlScraperIntegrationTemplate.java   POST /url-scraper + poll GET /url-scraper/:jobId
src/main/java/.../SearchIntegrationTemplate.java       POST /search (synchronous, no polling)
src/main/java/.../AgenticSearchIntegrationTemplate.java  POST /agentic-search + poll GET /agentic-search/:jobId
src/test/java/.../AnakinApiClientTest.java      9 JUnit tests against a local com.sun.net.httpserver mock (no live API key needed)
```

Request bodies (`{url, country='us', useBrowser=false, generateJson=false,
forceFresh=false, sessionId?, sessionName?}` for url-scraper; `{prompt,
limit=5}` for search; `{prompt, useBrowser=true, schema?}` for
agentic-search) and the `jobId`/`status` polling contract were read
directly from `anakin-py/src/anakin/client.py` (`Anakin.scrape`,
`Anakin.search`, `Anakin.agentic_search`) and `anakin-py/src/anakin/models.py`,
not inferred. Response bodies are passed through as raw
`Map<String,Object>` (Anakin's JSON as-is) rather than mapped onto a fixed
Appian CDT, matching the same pattern the official Google Places example
uses for its response handling.

## Verified, not assumed

- **`javac`, `mvn`, and the real Appian SDK jars are all available here** --
  contrary to the task brief's assumption. `com.appian:connected-systems-core`
  (latest `1.10.0`), `com.appian:connected-systems-client` (latest `1.4.0`),
  and `com.appian:appian-plug-in-sdk` (`26.3`) are published on public Maven
  Central under the `com.appian` groupId (confirmed by fetching
  `maven-metadata.xml` directly from `repo1.maven.org` and by successfully
  resolving them with `mvn dependency:tree`). This is not the same as
  Appian's other, closed developer/partner tooling.
- **`mvn clean package` actually succeeds against those real jars**: compiles
  all 5 main-source files (`javac --release 11`, zero warnings even under
  `-Xlint:all`), runs `mvn test` (9/9 passing), and assembles
  `target/appian-anakin-1.0.0.jar`. Verified the resulting jar's internal
  layout with `jar tf` / `unzip -p ... META-INF/MANIFEST.MF`: `appian-plugin.xml`
  and `resources_en_US.properties` at the jar root, compiled classes at the
  jar root, `connected-systems-client` + Jackson nested under `META-INF/lib`
  (`connected-systems-core` correctly excluded -- it's Appian-provided,
  "provided" scope), and `Spring-Context: *;publish-context:=false` present
  on the manifest -- matching the real example jars byte-for-byte in
  structure.
- **Caught and fixed a real bug during this process**: an initial `pom.xml`
  had two `com.appian:connected-systems-core` dependency declarations (one
  `provided`, one redundant `test`). Maven's dependency mediation silently
  collapsed them to test-only scope, which made `connected-systems-core`
  disappear from the *main* compile classpath -- `mvn compile` failed with
  "package ... does not exist" even though `mvn dependency:tree` showed the
  jar was present. Found via `mvn dependency:tree`, which flags duplicate
  declarations as a build warning. Fixed by removing the unnecessary
  `test`-scoped duplicate (Maven's `provided` scope is already visible on
  both compile and test classpaths).
- **`AnakinApiClientTest` (9 tests) runs for real** against a local
  `com.sun.net.httpserver.HttpServer` (built into the JDK, zero extra test
  dependency) standing in for `api.anakin.io`: POST/GET with the
  `X-API-Key` header, JSON success/error parsing, HTTP 4xx error mapping,
  malformed-JSON handling, the real `pollUntilTerminal()` loop reaching
  both `completed` and `failed`, and the `jobId`/`job_id`/`id` key
  tolerance. `AnakinApiClient` was deliberately written with zero Appian
  SDK imports specifically so this could be tested in isolation.

## Not verified / not done

- **Never deployed to a real Appian environment.** No access to an Appian
  instance in this sandbox -- never uploaded the jar via Admin Console,
  never opened the Integration Designer to confirm the property fields
  (labels, instruction text, choices) actually render as expected, never
  exercised `getConfiguration()`/`execute()`/`testConnection()` through
  Appian's own runtime (only compiled and unit-tested the plain-Java parts
  in isolation).
- **Never called the real `api.anakin.io`** -- no live API key available
  here. `AnakinApiClientTest` exercises the same request/response code path
  against a local mock server instead; the real endpoint's actual behavior
  (rate limits, exact error payload shapes beyond the documented `{"error":
  "..."}`, actual job-completion latency) is unverified.
- **`integerProperty()`'s full builder chain (`.label().instructionText().build()`)
  is inferred, not directly confirmed.** Confirmed the method exists and
  returns a builder in the same family as `textProperty()`/`booleanProperty()`
  (found one real usage: `Data Entry (Dynamic Data Structures)` example
  calls `integerProperty("")` directly), but no example in the repo shows
  its full fluent chain the way `textProperty()` and `booleanProperty()` are
  shown. Given it's built on the same `PropertyDescriptorBuilder` framework
  as the other two (confirmed by successful compilation against the real
  `connected-systems-core-1.10.0.jar`), this is a low-risk inference, not a
  guess pulled from nowhere -- but calling it out rather than presenting it
  as independently confirmed.
- **`<application-version min="23.1.0.0"/>` in `appian-plugin.xml` is an
  estimate, not a verified compatibility figure.** Could not find a public
  Appian-published mapping from `connected-systems-core`/`connected-systems-client`
  release versions to the minimum compatible Appian platform version.
- **No connected-system icon PNGs.** The real examples ship
  `*_40px.png`/`*_50px.png`/`*_80px.png` icon files referenced implicitly by
  Appian's plug-in loader (by filename convention next to the template
  class in `src/main/resources`); this submission has none, so the
  connected system will use Appian's default icon in the Designer palette
  until real Anakin brand icons are added.
- **No integration test against Appian's actual property-validation /
  expression-evaluation engine** (e.g. whether `isExpressionable(true)`
  fields correctly accept Appian expressions at runtime) -- that requires a
  live Appian Designer session.

## Steps (needs an Appian environment + a live Anakin API key)

1. `mvn clean package` (already done here; reproducible).
2. Admin Console > Plug-ins > deploy `target/appian-anakin-1.0.0.jar` to a
   real Appian environment.
3. Create an Anakin connected system, paste in a real API key from
   [anakin.io/dashboard](https://anakin.io/dashboard), click **Test
   Connection** -- first real exercise of `testConnection()` against the
   live API.
4. Add each of the 3 integrations to a test process model / interface and
   run them against the live `api.anakin.io` to confirm the response
   shapes match what `anakin-py`'s models predict.
