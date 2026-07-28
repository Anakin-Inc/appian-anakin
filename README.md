# appian-anakin

Appian Connected System Plug-in for [Anakin](https://anakin.io) -- web
scraping, AI-powered search, and multi-stage agentic research over hundreds
of popular sites, via `https://api.anakin.io/v1`.

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
2. Add one of the three integrations to a process model or interface, under
   that connected system:

   | Integration | Anakin endpoint(s) | Notes |
   |---|---|---|
   | **Anakin - Scrape URL** | `POST /url-scraper`, poll `GET /url-scraper/:jobId` | Returns markdown/HTML/JSON depending on options. |
   | **Anakin - AI Search** | `POST /search` | Synchronous -- no polling. Costs 3 credits/call. |
   | **Anakin - Agentic Search** | `POST /agentic-search`, poll `GET /agentic-search/:jobId` | Multi-stage research with optional JSON Schema for structured output. Costs 10 credits/call. |

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
