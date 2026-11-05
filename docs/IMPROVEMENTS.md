# FlowScope — Improvement Plan

FlowScope exists to help people understand an unfamiliar or legacy codebase from its source alone. Someone new to a system reads the flow charts, sequence diagrams, architecture views and service map, and builds their mental model from them. That only works if the diagrams are:

- **Correct** — every arrow exists in the code. A wrong arrow is worse than a missing one, because readers trust it and go looking for an integration that isn't there.
- **Complete** — the connections that matter most in legacy systems (shared databases, queues, SOAP, batch jobs, UIs) are shown.
- **Verifiable** — a reader can see *why* an arrow is drawn and jump to the code behind it.

## How these were found

FlowScope (commit `da5dec7`) was run through its MCP server on a real multi-service workspace of about 25 projects: Spring Boot services, TypeScript AWS Lambdas, and Angular and React front ends. Its service map and architecture views were compared with a hand-checked architecture of the same system, and every disagreement was traced in the source code.

**What it got right.** FlowScope got the core Kafka pipeline, REST externals and AWS resource names right. It also found several real connections the hand-drawn diagram had missed. The items below are what kept it from being trustworthy on its own.

**Line numbers** refer to `backend/src/main/java/com/flowscope/` at `da5dec7`.

## Summary

| ID | Priority | Theme | Problem |
|---|---|---|---|
| A1 | Medium | Accuracy | Config keys that no code reads become arrows |
| A2 | High | Accuracy | `database-url` matches the base-url rule |
| A3 | High | Accuracy | Read/write direction decided per service; `.send(` counts as a write |
| A4 | Medium | Accuracy | URL → service matching on one hostname word |
| A5 | Low | Accuracy | Test mocks and non-prod environment files leak in |
| A6 | Low | Accuracy | Libraries drawn as running services |
| A7 | Low | Accuracy | Different systems behind one host merged into one node |
| B1 | High | Coverage | No relational or other non-AWS datastores |
| B2 | Medium | Coverage | Kafka topics passed through helper methods are lost |
| B3 | Medium | Coverage | Serverless triggers and multi-handler packages |
| B4 | Medium | Coverage | Front ends are not linked to their back ends |
| B5 | Low | Coverage | SOAP clients and cloud SDK services not detected |
| B6 | Medium | Coverage | Unresolved references disappear silently |
| C1 | High | Trust | No evidence (file:line) behind an arrow |
| C2 | Medium | Trust | No confidence level per arrow |
| C3 | Medium | Trust | No override file for facts that live outside the code |
| D1 | High | MCP / safety | `--mcp` starts an unauthenticated web server on all interfaces |
| D2 | High | MCP | Workspace results are too large for agent tool output |
| D3 | Low | MCP | Legacy SSE transport only; stale sample config |

## A. Accuracy — stop drawing arrows that aren't real

### A1 — Config keys that no code reads become arrows
- **Problem:** legacy config files are full of settings for integrations that were removed long ago. Today any URL under a base-url key, or under a `*_BASE_URL` / `*_HOST` env var, becomes a REST arrow. That happens even when no code reads the key.
- **Example:** `partner.api.base-url: https://partner.example.com` sits in `application-prod.yml`, but nothing references `partner.api`. FlowScope still draws `service → partner.example.com`.
- **Root cause:** `service/ServiceMapBuilder.scanUrls` (≈ line 694) trusts the config value alone.
- **Fix:** add the arrow only when the key is read:
  - `${key` in `@Value`;
  - a `@ConfigurationProperties` prefix;
  - `process.env.KEY`, `os.getenv` or `viper.GetString`.

  Report unread keys separately as *configured but unused*. That list is useful clean-up information for a legacy system.
- **Done when:** a fixture with one referenced and one unreferenced base-url key gives one arrow and one "unused config" note.

### A2 — `database-url` matches the base-url rule
- **Problem:** the key rule `(base-url|baseurl|base_url)` is a substring match, so `data`**`base-url`** counts.
- **Example:** `notifications.database-url: https://example-app.firebaseio.com` gives `service → Firebase`, even though no Firebase client exists in the code.
- **Root cause:** the unanchored regex in `ServiceMapBuilder.scanUrls` (≈ line 698).
- **Fix:**
  - anchor the match to a whole key segment, e.g. `(^|[.\-_])(base-url|baseurl|base_url)$`;
  - detect Firebase/FCM from code (`FirebaseMessaging`, `firebase-admin`) rather than from config.
- **Done when:** `x.database-url` produces no arrow, and `x.base-url` (when referenced) does.

### A3 — Read/write direction is per service, and `.send(` counts as a write
- **Problem:** a single write anywhere in a service marks every table it touches as written. The verb list includes `send`, and AWS SDK v3 sends *every* command through `client.send(...)`. So read-only services appear to write their tables.
- **Example:**
  ```ts
  await ddb.send(new GetCommand({ TableName: process.env.ORDERS_TABLE, Key: { id } }))
  ```
  produces `service → writes → ORDERS`.
- **Root cause:**
  - `ServiceMapBuilder.scanDatastores` (≈ lines 680–690) sets one `writesData` flag for the whole service;
  - `build()` (≈ line 330) then applies it to every store.
- **Fix:**
  - Classify per call:
    - SDK v3 command names: `Get` / `Query` / `Scan` / `BatchGet` mean read; `Put` / `Update` / `Delete` / `TransactWrite` / `BatchWrite` mean write;
    - for SDK v2, the DynamoDB mapper, Spring Data and JDBC, use the method names.
  - Tie each call to its table using the nearby table constant or env var.
  - Draw a neutral `uses` arrow when unsure.
- **Done when:** a fixture with one read-only and one read-write table gives `reads` and `writes` arrows respectively.

### A4 — URL → service matching on one hostname word
- **Problem:** an outbound host is assigned to the first service whose name shares one distinctive word with it. The URL path is ignored, and so is how the URL is used.
- **Examples:**
  - `app.base-url: https://orders.example.com` is only used to build links in emails, but it produces `billing → orders-service (REST)`.
  - `https://reports.example.com/admin/api` with two services, `reports-api` and `reports-dashboard`: the arrow goes to whichever was indexed first, even though `/admin/api` is served by `reports-dashboard`.
- **Root cause:** `ServiceMapBuilder.buildTokenIndex` / `matchService` (≈ lines 792–845) use `putIfAbsent` on single tokens.
- **Fix:**
  1. Mark tokens shared by several services as ambiguous.
  2. Pick between candidates by matching the URL path against each one's controller route prefixes. Spring routes are already parsed (`util/SpringEndpoints`).
  3. Draw a URL that only reaches string formatting (not an HTTP client) as a weak "reference", not a call.
  4. Allow overrides (C3).
- **Done when:** the two examples above resolve to "reference only" and to `reports-dashboard`.

### A5 — Test mocks and non-prod environment files leak in
- **Problem:** URLs from test helpers, and from dev/stage environment files, appear as production connections.
- **Example:**
  - `src/app/common/testing/config.mock.ts` and `*.mock.ts` files aren't treated as tests;
  - Angular `environment.dev.ts` / `environment.stage.ts` contribute hosts.
- **Root cause:**
  - the test filter in `ServiceMapBuilder.forEachSourceFile` (≈ line 984) covers only `/test/`, `/tests/`, `__tests__`, `.test.`, `.spec.` and `*Test.java`;
  - the literal-URL scan ignores the prod-only rule that `ConfigIndex` applies to Spring profiles and `.env`.
- **Fix:**
  - add `/testing/`, `/mocks/`, `__mocks__`, `.mock.` and `/fixtures/` to the test filter;
  - read only `environment.ts` + `environment.prod.ts` (and the equivalents for other frameworks).

### A6 — Libraries drawn as running services
- **Problem:** a shared Java library that happens to contain a `@SpringBootApplication` class is shown as a deployable service. Node packages are called libraries only if their name contains `common` or `utils`.
- **Root cause:** `service/WorkspaceScanner.javaService` / `hasSpringBootApp` (≈ lines 168 and 272) and `nodeService` (≈ line 195).
- **Fix:** treat a module as a library when:
  - another workspace module declares it as a dependency (Maven / Gradle / `package.json`); or
  - it isn't packaged as an executable (no Spring Boot repackage plugin, no Lambda handler, no `start` script).

  Libraries already drop out of REST matching, so this also improves A4.

### A7 — Different systems behind one host merged into one node
- **Problem:**
  - the external-systems dictionary keys on a hostname substring, so a corporate API gateway serving several back-end systems collapses into one node;
  - some entries pair two unrelated systems under one label.
- **Root cause:** the `EXTERNALS` map in `ServiceMapBuilder` (≈ line 46) and `classifyKnownExternal`.
- **Fix:**
  - allow entries keyed on host **and** path prefix (e.g. `api.example.com/billing/*` and `api.example.com/identity/*`);
  - keep one system per entry;
  - make the dictionary extensible from the override file (C3).

## B. Coverage — show what's invisible today

### B1 — No relational or other non-AWS datastores *(biggest gap for legacy work)*
- **Problem:**
  - only DynamoDB, SQS and S3 are detected;
  - JDBC databases, JPA / Spring Data repositories, Cassandra, MongoDB, Redis and Elasticsearch never appear.

  In legacy estates the shared relational database is often the main thing coupling services together, and it is completely missing. The Architecture view can report dozens of repository classes and zero databases.
- **Example:** `spring.datasource.url: jdbc:postgresql://db-host:5432/orders` plus 40 `JpaRepository` interfaces produces no datastore node.
- **Root cause:**
  - `ServiceMapBuilder.scanDatastores` (≈ line 656) only knows the three AWS stores;
  - `service/ArchitectureBuilder` reuses `ServiceMapBuilder.comms`, so it inherits the gap.
- **Fix:**
  - Parse JDBC URLs. Use the database type plus database name as the node id, so services sharing a database link to one node.
  - Parse Cassandra contact points and keyspace, and Mongo / Redis / Elasticsearch URIs.
  - Parse Node / Python `DB_HOST` + `DB_NAME` / `PG*` env vars.
  - In the Architecture view, attach the Repositories layer to that node when repository interfaces exist.
  - Optional: list entity tables (`@Table` / `@Entity`) in the node's metadata.
- **Done when:** two fixture services pointing at the same JDBC database share one database node, and the Architecture view shows Repositories → database.

### B2 — Kafka topics passed through helper methods are lost
- **Problem:** many codebases wrap the producer in a helper, so the topic reaches `send` as a method parameter that can't be resolved by name.
- **Example (Java):**
  ```java
  @Value("${kafka.topics.success}") private String successTopic;
  private void ack(String id, String topic, Ack a) { kafkaTemplate.send(topic, json(a)); }
  ...
  ack(id, this.successTopic, a);
  ```
  No topic is found.
- **Example (TypeScript):** the `{ topic }` shorthand isn't recognised either.
  ```ts
  this.commandTopic = process.env.KAFKA_TOPIC_COMMAND
  private async produce(topic: string, value: string) {
    await this.producer.send({ topic, messages: [{ value }] })
  }
  ```
- **Root cause:**
  - `KAFKA_SEND_RE` (≈ line 63) captures the parameter name, and `ConfigIndex.resolve` finds nothing for it;
  - `NODE_TOPIC_RE` (≈ line 73) requires `topic\s*:`.
- **Fix:**
  - When the captured token is a parameter of the enclosing method, resolve the argument at each call site. The IR and `JavaCallResolver` already know the call sites. For TypeScript, follow `this.<method>(this.<field>, …)` call sites in the same file.
  - Support the `{ topic }` shorthand.
  - As a fallback, a file with a Kafka producer that binds `KAFKA_TOPIC*` env vars produces those topics.

### B3 — Serverless triggers and multi-handler packages
- **Problem:**
  - a Lambda triggered by Kafka, SQS, SNS, EventBridge or a schedule shows only as consuming a generic "external event source", because the trigger lives in infrastructure config;
  - a package that exports several handlers appears as one box, which hides pipelines such as consumer → worker → effect.
- **Root cause:** `LAMBDA_KAFKA_EVENT_RE` (≈ line 85) by design; services are discovered per `package.json`.
- **Fix, in increasing effort:**
  1. One node per exported handler, named from `index.ts` exports and, when present, the function names in CI / deploy scripts.
  2. Read triggers from IaC found in the scanned tree:
     - Terraform `aws_lambda_event_source_mapping`, `aws_cloudwatch_event_rule`;
     - SAM / Serverless `events:`.
  3. An override file entry (C3) for triggers defined in another repository.

### B4 — Front ends are not linked to their back ends
- **Problem:** Angular-style UIs call relative paths such as `this.http.get('api/v3/orders')`, with the base URL set in `environment.prod.ts`. No arrow appears from the UI to the API that serves it. (React apps that use `REACT_APP_*_URL` *are* linked.)
- **Fix:**
  - read `environment.prod.ts` (and similar files) as config;
  - collect the relative paths used by the UI's HTTP services;
  - match them against the route prefixes of the back-end services. This is the same route index as A4.

### B5 — SOAP clients and cloud SDK services not detected
- **Problem:**
  - SOAP endpoints (`WebServiceTemplate`, JAX-WS / Axis stubs, `*.soap.*-url` keys) are invisible;
  - so are AWS services used through the SDK (Secrets Manager, SNS, Step Functions, Parameter Store) and their equivalents on other clouds.
- **Fix:**
  - accept `*-url` / `*.url` / `*-uri` keys when they are referenced (A1), using the anchored matching from A2;
  - detect SOAP client classes;
  - map SDK client classes and command names (`SecretsManagerClient`, `GetSecretValueCommand`, `SNSClient`, `SFNClient`, …) to cloud-service nodes.

### B6 — Unresolved references disappear silently
- **Problem:** when FlowScope finds a send, listener, datastore or URL but can't resolve its name, it draws nothing. The service then looks isolated, and readers wrongly conclude that it talks to nothing.
- **Example:**
  ```ts
  QueueUrl: `https://sqs.${region}.amazonaws.com/${account}/${process.env.QUEUE_NAME}`
  ```
  where `QUEUE_NAME` is set only at deploy time. Another case: a container job that runs `psql` and `aws s3 cp` from a shell script.
- **Fix:**
  - draw a placeholder node such as *"SQS queue (name from env QUEUE_NAME)"* and add a line to `stats.notes`;
  - optionally scan `Dockerfile` / `*.sh` for `psql`, `mysql`, `aws s3`, `aws sqs`, `curl`.

## C. Trust — let readers check every arrow

### C1 — Evidence behind every arrow
- **Problem:** `dto/ServiceMap.Edge` and `dto/Architecture.Edge` carry only `id`, `source`, `target`, `kind` and `label`. A reader who doubts an arrow has nothing to check it against. That is exactly the situation in legacy work, where the code is the only documentation.
- **Fix:**
  - Add `evidence: [{ file, line, snippet, via }]` to each edge, where `via` is `code`, `config key …` or `name match …`.
  - In the UI, clicking an arrow shows its evidence and opens the file.
  - Over MCP, include the evidence in compact form (D2) so an agent can cite it.

### C2 — Confidence level per arrow
- **Problem:** an arrow built from a real `kafkaTemplate.send("orders", …)` looks the same as one guessed from a hostname word.
- **Fix:**
  - Give each edge a `confidence`:
    - `code` — call site found and resolved;
    - `config` — referenced config value;
    - `inferred` — name or host match;
    - `unresolved` — placeholder from B6.
  - Draw them differently (solid, dashed, dotted, grey) and allow filtering.

### C3 — Override file for facts that live outside the code
- **Problem:** much of a legacy system's wiring lives outside its repositories: API gateways, Lambda triggers, DNS names, deploy-time environment variables. Without a way to state these facts, users can't correct FlowScope.
- **Fix:** read an optional `flowscope.yml` at the workspace root, for example:
  ```yaml
  hosts:            # host or host+path → service or external system
    api.example.com/admin/*: reports-dashboard
  triggers:         # handler → topics / queues / schedules
    orders-lambda:commandConsumer: { consumes: [orders-commands] }
  externals:
    partner.example.com: Partner Billing API
  ignore: [legacy/, sandbox/]
  ```
  Edges from the file get confidence `declared` and point to the file in their evidence.

## D. MCP and agent use

### D1 — `--mcp` starts an unauthenticated web server on all interfaces
- **Problem:** `java -jar flowscope-web.jar --mcp` starts Tomcat on port 8080 next to the STDIO loop, bound to every network interface. `/api/*` and `POST /mcp/message` have no authentication, and they parse and return any absolute `path` the process can read. CORS only restricts browsers.
- **Root cause:** `FlowScopeApplication.main` turns off the banner for `--mcp` but keeps the servlet web application type. `application.properties` sets no `server.address`.
- **Fix:**
  - with `--mcp`, call `app.setWebApplicationType(WebApplicationType.NONE)`, unless HTTP is requested explicitly (e.g. `--mcp-http`);
  - default `server.address=127.0.0.1`;
  - optionally restrict `path` to configured roots (`flowscope.allowed-roots`).

  Until then, the workaround is `--spring.main.web-application-type=none`.

### D2 — Workspace results are too large for agent tool output
- **Problem:** `FlowScopeMcpToolHandler` returns the full model as pretty-printed JSON. A service map for about 25 projects was 44,000 characters, above common agent tool-output limits (30,000 in Kiro). The agent had to save it to a file and post-process it.
- **Fix:**
  - Add a `format` argument:
    - `summary` — counts plus one line per edge, `source -kind-> target label [confidence]`;
    - `mermaid`;
    - `json`, compact by default.
  - Add `service`, `kind` and `confidence` filters, and optionally pagination.
  - Give `flowscope_analyze` a summary mode too: modules, entry points and counts rather than the whole IR.
  - A `find_entry_points(path, query)` tool would let agents discover `functionId`s for the flow, sequence and business-logic tools without pulling the full IR.

### D3 — Legacy SSE transport only; stale sample config
- **Problem:**
  - The HTTP transport is the 2024-11-05 HTTP+SSE pair (`GET /mcp/sse` + `POST /mcp/message`), while current clients prefer Streamable HTTP (a single `POST /mcp`).
  - `initialize` always answers protocol `2024-11-05`. That is valid, but it limits newer clients.
  - `mcp-config.json` points at absolute paths on one developer's machine.
- **Fix:**
  - add a Streamable HTTP endpoint, bound to localhost (D1);
  - use placeholders such as `<FLOWSCOPE_HOME>` in the sample config;
  - document `--spring.main.web-application-type=none` for STDIO clients until D1 lands.

## Testing approach

Each fix should ship with a small synthetic fixture under `backend/src/test/resources/fixtures/`. Each fixture is a few files that reproduce one case. Its test should assert both that the expected arrows **are** present and that the wrong ones **are not**. Most of the problems above are false positives, so an "absence" assertion matters as much as a "presence" assertion.

The existing test stack (JUnit, jqwik, Jimfs in `pom.xml`) is enough for this.

## Suggested order

1. **D1, A2.** Small and contained. They remove a security exposure and a whole class of phantom arrows.
2. **A3, A1, A5.** Cheap accuracy fixes in `scanDatastores` / `scanUrls`.
3. **C1, C2.** Evidence and confidence make every later improvement checkable. They are also the most valuable features for legacy understanding.
4. **D2.** Makes the MCP tools usable on large workspaces.
5. **B1, then the route index for A4 and B4.** The largest coverage gains for database-centric and Spring estates.
6. **B2, B3, B6, C3.** Messaging, serverless coverage and the override file.
7. **A6, A7, B5, D3.** Polish.
