# FlowScope — Deployment & Production Checklist

The backend (REST API) and frontend (static SPA) are **independent deployables**.

## Build

```bash
# Backend — API-only executable JAR
mvn -f backend/pom.xml clean package

# Frontend — static bundle in frontend/dist/
cd frontend && npm ci && VITE_API_BASE_URL=https://api.example.com npm run build
```

Artifacts:
- `backend/target/flowscope-web.jar` — executable JAR, **API only** (does not serve the UI).
- `build-info.properties` is generated into the JAR for `/actuator/info`.
- `frontend/dist/` — static assets to serve from any web server / CDN. Set
  `VITE_API_BASE_URL` at build time to the backend's public URL.

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

Build the JAR first (`mvn -f backend/pom.xml clean package`), then `docker build`. This
image serves the API only; deploy `frontend/dist/` separately (static host / CDN) and set
`flowscope.cors.allowed-origins` to the frontend's origin.

## Configuration

Everything is overridable via env vars / JVM args (Spring relaxed binding).
Common ones:

| Env / property | Default | Notes |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `FLOWSCOPE_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Comma-separated origins allowed to call `/api/**` |
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

- [x] **Executable JAR** (API only) with embedded server; SPA deployed separately as static assets.
- [x] **Configurable CORS** (`flowscope.cors.allowed-origins`) for the separately-hosted SPA.
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
