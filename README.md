# appian-anakin

Appian Connected System Plug-in for [Anakin](https://anakin.io) -- web
scraping, site mapping/crawling, AI-powered search and agentic research, the
Wire pre-built action catalog, website change monitoring, AI-visibility
search, browser sessions, and AI browser automation, via
`https://api.anakin.io/v1`. Covers all 21 of Anakin's API capabilities as
21 `SimpleIntegrationTemplate` classes under one connected system.

## Install

Build the plug-in jar, then upload it in the Appian Admin Console:

```sh
mvn clean package
```

This produces `target/appian-anakin-1.0.0.jar`. In Appian: **Admin Console
> Plug-ins > Deploy** (or your environment's plug-in deployment process) and
upload that jar. Appian plug-ins ship as a single jar with third-party
dependencies nested under `META-INF/lib` -- there is no separate zip step.

## Usage

1. Create a new **Anakin** connected system and paste in your API key (get a
   free one -- 300 credits, no card required -- at
   [anakin.io/dashboard](https://anakin.io/dashboard)). Use **Test
   Connection** to confirm the key is valid.
2. Add any of the 21 integrations to a process model or interface, under
   that connected system:

   | Integration | Anakin endpoint(s) | Notes |
   |---|---|---|
   | **Anakin - Scrape URL** | `POST /url-scraper`, poll `GET /url-scraper/:jobId` | Returns markdown/HTML/JSON depending on options. |
   | **Anakin - Map Site** | `POST /map`, poll `GET /map/:jobId` | Discovers reachable URLs under a site. |
   | **Anakin - Crawl Site** | `POST /crawl`, poll `GET /crawl/:jobId` | Bulk-fetches markdown/HTML across a site. |
   | **Anakin - AI Search** | `POST /search` | Synchronous -- no polling. Costs 3 credits/call. |
   | **Anakin - Agentic Search** | `POST /agentic-search`, poll `GET /agentic-search/:jobId` | Multi-stage research with optional JSON Schema for structured output. Costs 10 credits/call. |
   | **Anakin - Wire Discover** | `GET /wire/resolve` | Finds Wire action candidates from a natural-language intent. |
   | **Anakin - Wire Catalog** | `GET /wire/catalog[/:slug]` | Lists every Wire catalog, or one site's full action list. |
   | **Anakin - Wire Read Action** | `POST /wire/task`, poll `GET /wire/jobs/:jobId` | Runs a data-extracting ("read") Wire action. |
   | **Anakin - Wire Write Action** | `POST /wire/task`, poll `GET /wire/jobs/:jobId` | Runs a state-changing ("write") Wire action. |
   | **Anakin - Wire Identities** | `GET /wire/identities` | Lists saved Wire identities/credentials. |
   | **Anakin - Wire Login** | `POST /wire/login` | Signs in to a credentials-mode Wire site; returns a `credential_id`. |
   | **Anakin - Wire Build** | `POST /wire/build-request` | Requests a new Wire action for a site not yet in the catalog. Charges credits. |
   | **Anakin - Create Monitor** | `POST /monitors` | Creates a scheduled website-change monitor. |
   | **Anakin - List Monitors** | `GET /monitors[/:id]` | Lists monitors, or fetches one by ID. |
   | **Anakin - Monitor Changes** | `GET /monitors/:id/changes` | Gets a monitor's detected changes. |
   | **Anakin - Control Monitor** | `POST /monitors/:id/{pause,resume,run}`, `DELETE /monitors/:id` | Pause/resume/run-now/delete a monitor. |
   | **Anakin - AI Visibility Search** | `POST /ai-visibility/search`, poll `GET /ai-visibility/search/:searchId` | Compares answers across AI engines (ChatGPT, Gemini, ...). |
   | **Anakin - AI Visibility Sources** | `GET /ai-visibility/sources` | Lists the AI engines available to AI Visibility Search. |
   | **Anakin - List Sessions** | `GET /sessions` | Lists saved browser sessions (encrypted login states). |
   | **Anakin - Delete Session** | `DELETE /sessions/:id` | Permanently deletes a saved browser session. |
   | **Anakin - Browser Task** | `POST /ai/evaluate` (async), poll `GET /ai/jobs/:workflowId` | Runs a natural-language task in an AI-driven cloud browser. |

Every integration returns the Anakin API's JSON response as-is (as an
Appian `Map`/dictionary), so downstream expression rules can pull whatever
fields they need (`markdown`, `results`, `generatedJson`, etc.) without a
fixed Appian CDT.

## Test

```sh
mvn test
```

Runs `AnakinApiClientTest` against a local `com.sun.net.httpserver.HttpServer`
mock -- no live Anakin API key or network access required. See
[SUBMIT.md](SUBMIT.md) for exactly what this does and does not verify.

## Build

```sh
mvn clean package
```

Compiles against the real Appian Integration SDK (`com.appian:connected-systems-core`,
`com.appian:connected-systems-client`, both published on Maven Central) and
assembles `target/appian-anakin-1.0.0.jar` in Appian's plug-in jar layout
(`appian-plugin.xml` + compiled classes at the jar root, runtime
dependencies nested under `META-INF/lib`).
