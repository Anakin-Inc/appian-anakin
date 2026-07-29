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

## Extended to all 21 capabilities (this pass)

The plug-in initially covered 3 of Anakin's 21 API capabilities (scrape,
search, agentic_search). This pass added the remaining 18 as
`SimpleIntegrationTemplate` classes, following the exact conventions of the
original 3 (same `@TemplateId`/`@IntegrationTemplateType` annotation shapes,
same `getConfiguration()`/`execute()` structure, same
`IntegrationDesignerDiagnostic`/`IntegrationError` error handling, same
plain-`Map<String,Object>` passthrough of API responses): map, crawl,
wire_discover, wire_catalog, wire_read_action, wire_write_action,
wire_identities, wire_login, wire_build, monitor_create, monitor_list,
monitor_changes, monitor_control, ai_visibility_search, ai_visibility_sources,
session_list, session_delete, browser_task.

Ground truth for every one of these 18 was read directly from
**anakin-mcp's own TypeScript source** (`anakin-mcp/src/client.ts` --
the `AnakinClient` class -- and `anakin-mcp/src/tools/*.ts`), not guessed or
inferred: exact endpoint paths, request field names/casing (Wire's fields
are `snake_case`; scrape/map/crawl/monitor fields are `camelCase` -- both
carried through faithfully into the Appian property keys, matching this
plug-in's existing convention that a property key equals the literal JSON
field name it maps to), default values, and the terminal-state contract of
every async job. Three nuances specifically pulled from that source, not
assumed:
- **AI Visibility search's "still working" signal is the literal `"running"`
  status, not the absence of `"failed"`** -- a `"failed"` search there still
  carries usable per-source results/errors, so
  `AnakinApiClient.pollUntilStatusNot` treats it as a normal (successful)
  terminal response rather than raising an `IntegrationError`, mirroring
  `aiVisibilitySearch` in `client.ts`, which returns rather than throws on
  it.
- **Wire task submissions (`POST /wire/task`) don't always return a
  `job_id`** -- some read actions answer synchronously inline. Both
  `WireReadActionIntegrationTemplate` and `WireWriteActionIntegrationTemplate`
  branch on whether `job_id` is present before deciding to poll, matching
  `wireRun` in `client.ts`.
- **`monitor_control`'s `run_now` action maps to the `/run` endpoint, not
  `/run_now`** -- confirmed against `monitorControl` in `client.ts`
  (`case 'run_now': return this.request('POST', \`${base}/run\`)`), not
  assumed from the action name.

`AnakinApiClient` (the shared plain-Java HTTP helper) gained four
primitives the original 3 templates didn't need: a bodyless `POST` (for
monitor pause/resume/run), `DELETE` (for session/monitor delete),
`withQuery()` (URL-encoded query-string building for the GET-with-params
endpoints -- wire_discover, wire_identities, session_list), and two
additional polling-terminal-condition variants (`pollUntilStatusNot`,
`pollUntilStatusIn`) alongside the original `pollUntilTerminal`, refactored
onto one shared private loop so all three share the same timeout/backoff
behavior. All four are covered by new tests in `AnakinApiClientTest`
(15 tests total, up from 9), still entirely against the local
`com.sun.net.httpserver` mock -- no live API key needed.

## What's here

```
pom.xml                                        Maven build, deps, assembly-plugin wiring
src/assembly/plugin.xml                        Custom jar layout (appian-plugin.xml + classes at root, deps under META-INF/lib)
src/main/resources/appian-plugin.xml            Plug-in manifest (connected system + 21 integration templates)
src/main/resources/resources_en_US.properties   Display names/descriptions (Appian requires this file)
src/main/java/.../AnakinConnectedSystemTemplate.java   Connected system: API key (encrypted) + testConnection() via GET /sessions
src/main/java/.../AnakinApiClient.java          Plain-Java HTTP helper (java.net.http, Jackson) -- no Appian SDK dependency, shared by all 21 integrations
src/main/java/.../UrlScraperIntegrationTemplate.java         POST /url-scraper + poll GET /url-scraper/:jobId
src/main/java/.../MapIntegrationTemplate.java                POST /map + poll GET /map/:jobId
src/main/java/.../CrawlIntegrationTemplate.java              POST /crawl + poll GET /crawl/:jobId
src/main/java/.../SearchIntegrationTemplate.java             POST /search (synchronous, no polling)
src/main/java/.../AgenticSearchIntegrationTemplate.java      POST /agentic-search + poll GET /agentic-search/:jobId
src/main/java/.../WireDiscoverIntegrationTemplate.java       GET /wire/resolve
src/main/java/.../WireCatalogIntegrationTemplate.java        GET /wire/catalog[/:slug]
src/main/java/.../WireReadActionIntegrationTemplate.java     POST /wire/task (+ poll GET /wire/jobs/:jobId when async) -- read
src/main/java/.../WireWriteActionIntegrationTemplate.java    POST /wire/task (+ poll GET /wire/jobs/:jobId when async) -- write
src/main/java/.../WireIdentitiesIntegrationTemplate.java     GET /wire/identities
src/main/java/.../WireLoginIntegrationTemplate.java          POST /wire/login
src/main/java/.../WireBuildIntegrationTemplate.java          POST /wire/build-request
src/main/java/.../MonitorCreateIntegrationTemplate.java      POST /monitors
src/main/java/.../MonitorListIntegrationTemplate.java        GET /monitors[/:id]
src/main/java/.../MonitorChangesIntegrationTemplate.java     GET /monitors/:id/changes
src/main/java/.../MonitorControlIntegrationTemplate.java     POST /monitors/:id/{pause,resume,run} + DELETE /monitors/:id
src/main/java/.../AiVisibilitySearchIntegrationTemplate.java POST /ai-visibility/search + poll GET /ai-visibility/search/:searchId
src/main/java/.../AiVisibilitySourcesIntegrationTemplate.java GET /ai-visibility/sources
src/main/java/.../SessionListIntegrationTemplate.java        GET /sessions
src/main/java/.../SessionDeleteIntegrationTemplate.java      DELETE /sessions/:id
src/main/java/.../BrowserTaskIntegrationTemplate.java        POST /ai/evaluate (async) + poll GET /ai/jobs/:workflowId
src/test/java/.../AnakinApiClientTest.java      15 JUnit tests against a local com.sun.net.httpserver mock (no live API key needed)
```

Request bodies (`{url, country='us', useBrowser=false, generateJson=false,
forceFresh=false, sessionId?, sessionName?}` for url-scraper; `{prompt,
limit=5}` for search; `{prompt, useBrowser=true, schema?}` for
agentic-search) and the `jobId`/`status` polling contract for the original
3 templates were read directly from `anakin-py/src/anakin/client.py`
(`Anakin.scrape`, `Anakin.search`, `Anakin.agentic_search`) and
`anakin-py/src/anakin/models.py`. The 18 templates added in this pass were
read from `anakin-mcp/src/client.ts` and `anakin-mcp/src/tools/*.ts`
instead (see above) -- neither was inferred. Response bodies are passed
through as raw `Map<String,Object>` (Anakin's JSON as-is) rather than
mapped onto a fixed Appian CDT, matching the same pattern the official
Google Places example uses for its response handling, and applied
consistently across all 21 templates (including monitor_create, whose
response carries an `alertWebhookSecret` HMAC secret that anakin-mcp's
chat-facing tool redacts for LLM-transcript-safety reasons that don't apply
to an Appian designer's own dictionary output -- see the Javadoc on
`MonitorCreateIntegrationTemplate` for the reasoning).

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
  all 23 main-source files (`javac --release 11`, zero warnings confirmed by
  running `javac -Xlint:all -Werror` directly against the full source set
  with `mvn dependency:build-classpath`, not just inferred from Maven's
  default output), runs `mvn test` (15/15 passing, up from 9 -- 6 new tests
  cover the 4 `AnakinApiClient` primitives added for the 18 new templates:
  bodyless `POST`, `DELETE`, `withQuery()`, `pollUntilStatusNot`,
  `pollUntilStatusIn`), and assembles `target/appian-anakin-1.0.0.jar`.
  Verified the resulting jar's internal layout with `jar tf` /
  `unzip -p ... META-INF/MANIFEST.MF`: all 21 `IntegrationTemplate.class`
  files plus `appian-plugin.xml` and `resources_en_US.properties` at the
  jar root, `connected-systems-client` + Jackson nested under
  `META-INF/lib` (`connected-systems-core` correctly excluded -- it's
  Appian-provided, "provided" scope), and
  `Spring-Context: *;publish-context:=false` present on the manifest --
  matching the real example jars byte-for-byte in structure.
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
- **`AnakinApiClientTest` (15 tests) runs for real** against a local
  `com.sun.net.httpserver.HttpServer` (built into the JDK, zero extra test
  dependency) standing in for `api.anakin.io`: POST/GET with the
  `X-API-Key` header, JSON success/error parsing, HTTP 4xx error mapping,
  malformed-JSON handling, the real `pollUntilTerminal()` loop reaching
  both `completed` and `failed`, the `jobId`/`job_id`/`id` key tolerance,
  and (added this pass) the bodyless `POST`, `DELETE`, `withQuery()`
  encoding/blank-skipping, and the `pollUntilStatusNot`/`pollUntilStatusIn`
  loops reaching their respective terminal conditions. `AnakinApiClient`
  was deliberately written with zero Appian SDK imports specifically so
  this could be tested in isolation.

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
4. Add each of the 21 integrations to a test process model / interface and
   run them against the live `api.anakin.io` to confirm the response shapes
   match what `anakin-py`'s models (original 3) and anakin-mcp's TypeScript
   client (18 added this pass) predict.
