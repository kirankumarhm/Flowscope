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

- **Backend:** Java 21 · Spring Boot 3.4 · [JavaParser](https://javaparser.org/) (pure-JVM, accurate).
- **Frontend:** Vite · React · TypeScript · Cytoscape.js (flow/component) · Mermaid (sequence/architecture).
- **Cross-language:** the Service Map extracts communication primitives from **Java and Node/TypeScript** via targeted pattern extraction (no full TS AST required).

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
| Controllers | `controller` | the 6 REST controllers |
| Services | `service` | `*Service` plus all builders/engines/registries/scanners (`JavaExtractor`, `IRBuilder`, `ComponentMapBuilder`, `ArchitectureBuilder`, `WorkspaceScanner`, `ConfigIndex`, `ServiceMapBuilder`, …) |
| Domain model | `entity` | `JavaProgramModel`, `Graph`, `IRNode`, `IREdge`, `CFG`, enums, `SourceFile`, `LanguageSpec`, … |
| API responses | `dto` | `Architecture`, `ComponentMap`, `ServiceMap`, `SequenceDiagram`, `ErrorResponse` |
| Error handling | `exception` | `GlobalExceptionHandler`, `ApiExceptions` |
| Configuration | `config` | `OpenApiConfig`, `LanguageConfig`, `CorsConfig` |
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
