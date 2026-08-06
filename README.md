# FlowScope Web

**Reverse-engineering diagram tool — point it at source code and it generates interactive diagrams.**

FlowScope parses a codebase into a language-neutral intermediate representation (IR)
and renders five complementary views of it, from a single function up to a whole
workspace of microservices. Think draw.io, except the diagrams are derived from the
code and stay true to it.

| # | Diagram | Scope | Answers |
|---|---------|-------|---------|
| 1 | **Flow Chart** | one function | How does this request flow, end to end (inter-procedural, calls inlined)? |
| 2 | **Sequence** | one function | Which components talk, in what order, for this request? |
| 3 | **Component** | one app | What are this service's Spring beans and how do they depend on each other? |
| 4 | **Architecture** | one app | What are the layers, and which datastores / topics / external systems does it use? |
| 5 | **Service Map** | a workspace | How do all the services talk — Kafka, REST, shared datastores, external systems? |

The Flow Chart also explains itself in prose: **Describe flow** walks the graph step by step,
and **Business logic** says what the flow accomplishes — offline by default, or via an LLM
provider if you configure one (see **[LLM provider](#llm-provider-optional)**).

- **Backend:** Java 21 · Spring Boot 3.4 · [JavaParser](https://javaparser.org/) for Java · [tree-sitter](https://tree-sitter.github.io/) for Go and Python (both pure-JVM — no external toolchain).
- **Frontend:** Vite · React · TypeScript · Cytoscape.js (flow/component) · Mermaid (sequence/architecture).
- **Languages:** **Java, Go, and Python** are parsed into the IR, so all five diagrams work for each. Go and Python classification is naming-convention based rather than annotation-driven, so their Component/Architecture views are shallower than Java's.
- **Service Map** additionally covers **Node/TypeScript, NestJS, Next.js, and React** by extracting communication primitives (Kafka, REST, gRPC, datastores) with targeted patterns — no full TS AST required.

---

## Quick start

The backend (REST API) and frontend (web UI) are **independent projects** — build and
run them separately.

**Prerequisites:** JDK 21+, Maven, and Node 22.

```bash
# 1. Backend — pure REST API on :8080
mvn -f backend/pom.xml clean package
java -jar backend/target/flowscope-web.jar

# 2. Frontend — Vite dev server on :5173 (in a second terminal)
cd frontend
npm install
npm run dev            # proxies /api → :8080
```

Open **<http://localhost:5173>**, paste an absolute source directory, and click **Analyze**.

- Single application → use **Flow Chart / Sequence / Component / Architecture**.
- A folder that *contains* multiple services → use **Service Map**.

> **Backend on a different port?** Start it with `PORT=8097 java -jar …` and point the
> dev proxy at it: `VITE_PROXY_TARGET=http://localhost:8097 npm run dev`.

### Production build

```bash
# Backend jar (API only — does not serve the UI)
mvn -f backend/pom.xml clean package

# Frontend static bundle → frontend/dist/, deploy behind any static host
cd frontend && npm run build
```

For production the SPA calls the API cross-origin, so set:

- `VITE_API_BASE_URL` (frontend build) → the backend's public URL.
- `flowscope.cors.allowed-origins` (backend) → the frontend's origin(s).

### Interactive docs & health (on the backend)

| URL | What |
|-----|------|
| `/swagger-ui.html` | Interactive API explorer (OpenAPI 3) |
| `/v3/api-docs` | Raw OpenAPI JSON |
| `/actuator/health` | Liveness/readiness |
| `/actuator/info` | Build & runtime info |
| `/actuator/metrics` | Micrometer metrics |

---

## API

All endpoints return `application/json`; failures return `{ "error": "..." }` with an
appropriate status (mapped centrally by `GlobalExceptionHandler`). Full request/response
schemas are in **Swagger UI**.

| Method & path | Purpose |
|---|---|
| `GET /api/analyze?path=` | Walk + parse a directory → IR (`nodes`, `edges`, per-function `cfgs`). |
| `GET /api/flow?path=&functionId=&maxDepth=` | Endpoint-rooted inter-procedural flow chart (CFG). Depth 1–8 (default 4). |
| `GET /api/flow-description?path=&functionId=&maxDepth=` | The same flow as a step-by-step walkthrough in execution order, with decisions and their branches. |
| `GET /api/business-logic?path=&functionId=&maxDepth=` | What the flow *accomplishes* — phases, rules enforced, caveats. Uses the configured LLM provider when available, else offline heuristics; the `origin` field says which. |
| `GET /api/llm-status` | Whether an LLM provider is configured, plus a non-secret label for it. Never returns the API key. |
| `GET /api/sequence?path=&functionId=&maxDepth=` | Mermaid sequence diagram. Depth 1–20 (default 10). |
| `GET /api/component?path=` | One app's beans + injection/call dependencies. |
| `GET /api/architecture?path=` | One app's layers + datastores/topics/externals. |
| `GET /api/servicemap?path=&exclude=` | Whole-workspace service topology. |

Common statuses: **400** missing/invalid `path` · **404** unknown `functionId` ·
**500** internal / heap exceeded · **504** analysis timed out.

---

## Architecture (pipeline)

```
Source dir ─► FileWalker ─► ParserEngine ─► JavaProgramModel / IRBuilder ─► REST API ─► React views
                (skip-list,     (JavaParser)    (+ type-aware call            (Cytoscape / Mermaid)
                 symlink-safe)                    resolution, cached)
```

The backend follows a standard Spring **layered package** structure under `com.flowscope`:

| Layer | Package | Contents |
|---|---|---|
| Controllers | `controller` | the 7 REST controllers |
| Services | `service` | `*Service` plus all builders/engines/registries/scanners (`JavaExtractor`, `IRBuilder`, `ComponentMapBuilder`, `ArchitectureBuilder`, `WorkspaceScanner`, `ConfigIndex`, `ServiceMapBuilder`, `FlowDescriptionService`, `BusinessLogicHeuristic`, `LlmClient`, …) |
| Domain model | `entity` | `JavaProgramModel`, `Graph`, `IRNode`, `IREdge`, `CFG`, enums, `SourceFile`, `LanguageSpec`, … |
| API responses | `dto` | `Architecture`, `ComponentMap`, `ServiceMap`, `SequenceDiagram`, `FlowDescription`, `BusinessLogic`, `ErrorResponse` |
| Error handling | `exception` | `GlobalExceptionHandler`, `ApiExceptions` |
| Configuration | `config` | `OpenApiConfig`, `LanguageConfig`, `CorsConfig`, `LlmProperties` |
| Utilities | `util` | `TextUtil`, `SpringEndpoints` |
| Frontend | `frontend/src/` | one `*View.tsx` per diagram, `api.ts`, `types.ts` |

`FlowScopeApplication` sits at the `com.flowscope` root (Spring component-scan base).

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for design detail and **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** for the production checklist.

### Extending to a new language

Implement `Extractor` and register a `LanguageSpec` in `com.flowscope.config.LanguageConfig`.
The walker, IR, and CFG models are language-neutral and need no changes. The Service
Map's comm extraction is pattern-based and already spans Java + Node/TypeScript.

---

## Configuration

Config lives in `backend/src/main/resources/application.properties` and can be
overridden by environment variables or JVM args (standard Spring Boot relaxed binding).

| Property | Default | Notes |
|---|---|---|
| `PORT` / `server.port` | `8080` | HTTP port |
| `flowscope.cors.allowed-origins` | `http://localhost:5173` | Comma-separated browser origins allowed to call `/api/**` |
| `spring.mvc.async.request-timeout` | `190000` | Cap for slow workspace scans (ms) |
| `management.endpoints.web.exposure.include` | `health,info,metrics` | Exposed actuator endpoints |
| `server.compression.enabled` | `true` | Gzip large JSON payloads |
| `server.shutdown` | `graceful` | Drain in-flight requests on stop |

Analysis guards (in `AnalysisService` / `ServiceMapService`): 120 s per-analysis timeout,
2 GB heap ceiling, and an LRU cache of parsed program models keyed by source root.

---

## LLM provider (optional)

The Flow Chart's **Business logic** button explains what a flow *accomplishes* rather than
what it executes. It works two ways, and always tells you which one produced the answer:

| Origin | When | What you get |
|---|---|---|
| **Offline** (default) | No provider configured | Phases grouped by concern (validation, lookup, persistence, messaging, external, error…) and the business rules the code enforces, inferred from naming and control flow. Deterministic, free, no network. |
| **LLM** | A provider is configured | An interpretation of *intent* — what real-world operation this performs and why. |

Offline analysis reports what the code **touches**; only the LLM path explains **why**. The
UI badges each result (`Offline` / `LLM`) so the two are never mistaken for each other.

**FlowScope is provider-neutral — no vendor SDK, and no extra Maven dependency.** It speaks
two wire formats over plain HTTP, so any endpoint implementing either works: hosted APIs,
corporate gateways, or a local model.

### Configuring it

Config lives in the same `application.properties`, with every value reading from an
environment variable. **Nothing needs editing or rebuilding — export and restart the jar.**

All of these are env-overridable through Spring Boot's relaxed binding (`flowscope.llm.max-tokens`
← `FLOWSCOPE_LLM_MAX_TOKENS`); the four most-used ones are wired to explicit placeholders in
`application.properties` so they're obvious.

| Property | Env var | Default | Notes |
|---|---|---|---|
| `flowscope.llm.enabled` | `FLOWSCOPE_LLM_ENABLED` | `true` | Master switch; `false` forces offline even with a key set |
| `flowscope.llm.base-url` | `FLOWSCOPE_LLM_BASE_URL` | *(blank)* | Full URL of the completion endpoint |
| `flowscope.llm.api-key` | `FLOWSCOPE_LLM_API_KEY` | *(blank)* | **Blank ⇒ offline heuristics.** Prefer the env var over committing a key |
| `flowscope.llm.model` | `FLOWSCOPE_LLM_MODEL` | *(blank)* | Model id, passed through verbatim |
| `flowscope.llm.wire-format` | `FLOWSCOPE_LLM_WIRE_FORMAT` | `chat_completions` | `chat_completions` (OpenAI-style) or `messages` (Anthropic-style) |
| `flowscope.llm.auth-scheme` | `FLOWSCOPE_LLM_AUTH_SCHEME` | `bearer` | `bearer` · `api_key_header` (`x-api-key`) · `azure_api_key` (`api-key`) · `none` |
| `flowscope.llm.extra-header` | `FLOWSCOPE_LLM_EXTRA_HEADER` | *(blank)* | One extra `Name: value` header some gateways require |
| `flowscope.llm.max-tokens` | `FLOWSCOPE_LLM_MAX_TOKENS` | `2000` | Upper bound on generated tokens |
| `flowscope.llm.timeout-seconds` | `FLOWSCOPE_LLM_TIMEOUT_SECONDS` | `90` | Per-request timeout; keep below `spring.mvc.async.request-timeout` |

Only three fields change between providers:

| Provider | `BASE_URL` | `WIRE_FORMAT` | `AUTH_SCHEME` |
|---|---|---|---|
| OpenAI-compatible | `https://api.openai.com/v1/chat/completions` | `chat_completions` | `bearer` |
| Anthropic-style | `https://api.anthropic.com/v1/messages` | `messages` | `api_key_header` |
| Azure OpenAI | `https://<res>.openai.azure.com/openai/deployments/<dep>/chat/completions?api-version=2024-02-01` | `chat_completions` | `azure_api_key` |
| Ollama / vLLM / LM Studio (local) | `http://localhost:11434/v1/chat/completions` | `chat_completions` | `none` |

```bash
export FLOWSCOPE_LLM_BASE_URL="https://api.openai.com/v1/chat/completions"
export FLOWSCOPE_LLM_API_KEY="sk-..."
export FLOWSCOPE_LLM_MODEL="gpt-4o"
java -jar backend/target/flowscope-web.jar

# Confirm it took (never echoes the key):
curl -s http://localhost:8080/api/llm-status
# {"available":true,"provider":"api.openai.com · gpt-4o"}
```

An Anthropic-style endpoint also wants `FLOWSCOPE_LLM_EXTRA_HEADER="anthropic-version: 2023-06-01"`.
A local runtime needs no key at all — `auth-scheme=none` and a `localhost` base URL keep
FlowScope entirely inside your network.

### Behaviour and limits

- **Fails soft.** If a configured provider is unreachable or answers unusably, the request
  still succeeds with the offline analysis plus a caveat naming the failure. A bad endpoint
  degrades the feature; it never takes it down.
- **The key is never returned.** `/api/llm-status` and the `provider` field expose only the
  endpoint host and model name.
- **What gets sent:** the flow's structured walkthrough (steps, branches, file/line
  anchors) — not your whole repository. Still, this sends code structure to whatever
  endpoint you configure; point it at a provider your organisation permits.
- **No settings UI yet.** Configuration is by env var / properties only, and because Spring
  binds these at startup, changing a value means restarting the jar.

---

## Frontend dev loop

```bash
# with the backend running on :8080:
cd frontend && npm install && npm run dev   # Vite on :5173, proxies /api → :8080
```

The dev proxy avoids CORS in development. Override the target with
`VITE_PROXY_TARGET` when the backend runs on another port.

---

## Development notes

- **Backend compile (offline, fast):** `mvn -o -f backend/pom.xml compile` (all deps cached).
- **Type-check the frontend:** `cd frontend && npm run typecheck`.
- Backend and frontend are decoupled: the jar is an API only and does not serve the UI.
