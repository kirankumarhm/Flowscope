# FlowScope — Deployment & Production Checklist

## Build

```bash
cd frontend && npm ci && npm run build && cd ..
mvn clean package -DskipFrontendBuild=true
```

Artifacts:
- `backend/target/flowscope-web.jar` — self-contained executable JAR (bundled SPA + API).
- `build-info.properties` is generated into the JAR for `/actuator/info`.

## Run

```bash
java -jar backend/target/flowscope-web.jar
# override port / memory / profile:
PORT=9090 java -Xmx2g -jar backend/target/flowscope-web.jar
```

### Container (example)

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY backend/target/flowscope-web.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app.jar"]
```

Build the JAR first (frontend + backend), then `docker build`.

## Configuration

Everything is overridable via env vars / JVM args (Spring relaxed binding).
Common ones:

| Env / property | Default | Notes |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `JAVA_TOOL_OPTIONS` / `-Xmx` | JVM default | Allow ≥ 2 GB heap for large codebases |
| `SPRING_MVC_ASYNC_REQUEST_TIMEOUT` | `190000` | ms; raise for very large workspaces |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics` | Actuator surface |

## Observability

| Endpoint | Use |
|---|---|
| `GET /actuator/health` | Load-balancer / k8s liveness & readiness (probes enabled) |
| `GET /actuator/health/liveness`, `/readiness` | k8s probe paths |
| `GET /actuator/info` | Version & build metadata |
| `GET /actuator/metrics` | Micrometer metrics (JVM, HTTP, GC, …) |

Kubernetes probe example:

```yaml
livenessProbe:  { httpGet: { path: /actuator/health/liveness,  port: 8080 } }
readinessProbe: { httpGet: { path: /actuator/health/readiness, port: 8080 } }
```

## Production checklist

- [x] **Executable JAR** with embedded server and bundled SPA — single artifact.
- [x] **Health / info / metrics** via Actuator (k8s probes enabled).
- [x] **OpenAPI 3 + Swagger UI** for API discoverability.
- [x] **Graceful shutdown** (`server.shutdown=graceful`) drains in-flight requests.
- [x] **Response compression** for large JSON diagram payloads.
- [x] **Central error handling** — no stack traces leaked; clean `{ "error": … }` bodies.
- [x] **Resource guards** — analysis timeout, heap ceiling, bounded graph sizes.
- [x] **Externalized config** — port, timeouts, actuator surface via env vars.
- [ ] **Authentication / authorization** — none built in; FlowScope reads local source
      paths, so run it **behind your own auth / network boundary**, not exposed publicly.
- [ ] **HTTPS / reverse proxy** — terminate TLS at a proxy (nginx / ingress) in front.
- [ ] **Filesystem scope** — the `path` parameter reads the server's local filesystem;
      restrict who can reach the API and which roots are analyzable in your environment.

## Notes & limits

- FlowScope analyzes the **server's local filesystem** by design (it's a developer/architcture
  tool). Treat the API as privileged: keep it internal.
- Deep call resolution is Java-only today; the Service Map's comm detection is pattern-based
  and covers Java + Node/TypeScript.
- Very large monoliths render dense diagrams — use the in-app filters (layer toggles,
  hubs-only, focus search) rather than expecting a legible full-graph at once.
