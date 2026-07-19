# FlowScope — Architecture & Design

This document explains how FlowScope turns source code into diagrams. For build/run
see the [README](../README.md); for operating it see [DEPLOYMENT.md](DEPLOYMENT.md).

## Pipeline

```
Source dir
   │
   ▼
FileWalker            skip-list + symlink-safe traversal; ProjectRoots auto-detects
   │                  sibling Maven modules a project depends on (multi-module support)
   ▼
ParserEngine          JavaParser (pure-JVM, no compilation needed)
   │
   ▼
JavaProgramModel      whole-program symbol model: classes, fields, methods, supertypes,
   │  / IRBuilder     Spring endpoints; type-aware call resolution. Cached by source root.
   ▼
Diagram builders      Flow / Sequence / Component / Architecture / ServiceMap
   │
   ▼
REST API  ──►  React views (Cytoscape for graphs, Mermaid for sequence & architecture)
```

The parsed `JavaProgramModel` is **expensive to build and cached** (LRU by absolute
source root) in `FlowService`, so Flow, Sequence, Component, and Architecture for the
same app all reuse one parse.

## The five diagrams

### 1. Flow Chart — `JavaFlowBuilder`
Endpoint-rooted, inter-procedural control-flow graph. When a statement calls a method
that resolves to internal source, that callee's flow is **inlined in place** (bounded
depth, recursion-safe). Interfaces resolve to implementations; virtual dispatch uses the
concrete `this` type. External/unresolved calls remain leaf nodes.

### 2. Sequence — `JavaSequenceBuilder`
Emits Mermaid `sequenceDiagram` text tracing a request across participants. Shares the
call-resolution logic with the Flow builder so the two views stay consistent.

### 3. Component — `ComponentMapBuilder`
The intra-app bean graph. Each class is classified into a **layer**
(controller / service / repository / client / config / component) using Spring stereotype
annotations first, then name conventions, with generic `@Component` last (so a
`@Component`-annotated `FooRepository` is still a repository). Single-implementation
interfaces are folded into their impl; Spring Data repository interfaces are kept. Edges
come from **field/constructor injection** and **resolved cross-class calls** (via the same
`resolveCallees` engine), de-duplicated and weighted. Test-source classes are excluded.

### 4. Architecture — `ArchitectureBuilder`
A coarse, always-legible view. It aggregates the Component map up to the six **layer
boxes** (with class counts) and aggregated layer→layer edges, then attaches the external
resources the app touches — datastores, Kafka topics, external systems — obtained from the
Service Map's single-app comm scan (`ServiceMapBuilder.comms`). Rendered with Mermaid
(grouped subgraphs) to match hand-drawn architecture docs.

### 5. Service Map — `WorkspaceScanner` + `ConfigIndex` + `ServiceMapBuilder`
The cross-language, whole-workspace topology. This is **pattern-based**, not AST-based, so
it spans Java and Node/TypeScript uniformly:

- **`WorkspaceScanner`** discovers services (dirs with `pom.xml` / `package.json`),
  expands multi-module Maven aggregators, and classifies each (spring-boot / node-lambda /
  node-service / react / library). Dependency/build dirs (`node_modules`, `target`, …) are
  **pruned during the walk** — never descended into.
- **`ConfigIndex`** resolves indirected names to literals per service: Spring YAML
  (`kafka.topics.*`, `listener.*.topics`, `dynamodb.table.*`, `*.base-url`), `.env` vars,
  Java `static final String` constants, and `@Value("${k:default}")` fields. **Only base +
  prod profiles are loaded** — merging dev/qa/stage would pollute names with env suffixes.
- **`ServiceMapBuilder`** scans produce/consume/REST/datastore sites, resolves them via the
  index, and links services: producer → topic → consumer (matched on the resolved topic
  literal), REST host → internal service (token match) or external system, plus datastores
  and a curated external-systems dictionary.

## Extensibility

- **New language:** implement `com.flowscope.service.Extractor`, register a `LanguageSpec` in
  `LanguageConfig`. IR, walker, and CFG models are language-neutral.
- **New comm type in the Service Map:** add a scan + resolver pass in `ServiceMapBuilder`;
  `ConfigIndex` already handles name indirection.

## Guardrails

- 120 s per-analysis timeout, 2 GB heap ceiling (`AnalysisService`).
- 180 s workspace-scan timeout (`ServiceMapService`).
- Bounded inline depth (Flow/Sequence) and node caps (CFG) to keep graphs renderable.
- LRU caches keyed by source root for both program models and service maps.
