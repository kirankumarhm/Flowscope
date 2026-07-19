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

**Prerequisites:** JDK 21+, Maven, and Node 22 (for building the frontend).

```bash
# 1. Build the frontend bundle
cd frontend && npm install && npm run build && cd ..

# 2. Package the backend with the pre-built SPA
mvn clean package -DskipFrontendBuild=true

# 3. Run
java -jar backend/target/flowscope-web.jar
```

Open **<http://localhost:8080>**, paste an absolute source directory, and click **Analyze**.

- Single application → use **Flow Chart / Sequence / Component / Architecture**.
- A folder that *contains* multiple services → use **Service Map**.

> **Networked build:** if the machine can reach `nodejs.org`, a plain `mvn clean package`
> self-provisions Node and builds the frontend for you. On restricted networks, use the
> two-step build above (`-DskipFrontendBuild=true` packages the pre-built `frontend/dist/`).

### Interactive docs & health

Once running:

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

| Concern | Where |
|---|---|
| IR + CFG model | `com.flowscope.ir.*` (`Graph`, `IRNode`, `IREdge`, `CFG`, `SequenceDiagram`) |
| Language registry / seam | `com.flowscope.lang.*` (`LanguageRegistry`, `LanguageSpec`, `Extractor`) |
| File discovery | `com.flowscope.ingest.*` (`DefaultFileWalker`, `ProjectRoots` multi-module detection) |
| Java parsing & whole-program model | `com.flowscope.extract.*` (`JavaExtractor`, `JavaProgramModel`, `JavaCfgBuilder`, `JavaFlowBuilder`, `JavaSequenceBuilder`) |
| Component diagram | `com.flowscope.component.*` (`ComponentMapBuilder`) |
| Architecture diagram | `com.flowscope.architecture.*` (`ArchitectureBuilder`) |
| Service Map | `com.flowscope.servicemap.*` (`WorkspaceScanner`, `ConfigIndex`, `ServiceMapBuilder`) |
| REST API + error handling | `com.flowscope.api.*` (controllers, services, `GlobalExceptionHandler`) |
| OpenAPI / config | `com.flowscope.config.OpenApiConfig` |
| Frontend | `frontend/src/` — one `*View.tsx` per diagram, `api.ts`, `types.ts` |

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for design detail and **[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)** for the production checklist.

### Extending to a new language

Implement `Extractor` and register a `LanguageSpec` in `com.flowscope.lang.LanguageConfig`.
The walker, IR, and CFG models are language-neutral and need no changes. The Service
Map's comm extraction is pattern-based and already spans Java + Node/TypeScript.

---

## Configuration

Config lives in `backend/src/main/resources/application.properties` and can be
overridden by environment variables or JVM args (standard Spring Boot relaxed binding).

| Property | Default | Notes |
|---|---|---|
| `PORT` / `server.port` | `8080` | HTTP port |
| `spring.mvc.async.request-timeout` | `190000` | Cap for slow workspace scans (ms) |
| `management.endpoints.web.exposure.include` | `health,info,metrics` | Exposed actuator endpoints |
| `server.compression.enabled` | `true` | Gzip large JSON payloads |
| `server.shutdown` | `graceful` | Drain in-flight requests on stop |

Analysis guards (in `AnalysisService` / `ServiceMapService`): 120 s per-analysis timeout,
2 GB heap ceiling, and an LRU cache of parsed program models keyed by source root.

---

## Frontend dev loop

```bash
# backend on :8080, then:
cd frontend && npm install && npm run dev   # Vite on :5173, proxies /api → :8080
```

---

## Development notes

- **Backend only, fast:** `mvn -o -pl backend compile` (offline; all deps are cached).
- **Type-check the frontend:** `cd frontend && npm run typecheck`.
- After a frontend rebuild, **hard-refresh** the browser (Cmd/Ctrl+Shift+R) to drop the stale bundle.
