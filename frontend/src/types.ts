// TypeScript interfaces mirroring the FlowScope backend JSON contract
// returned by GET /api/analyze?path=...

export interface Graph {
  project: string;
  languages: string[];
  modules?: string[]; // primary module + auto-detected sibling modules
  nodes: IRNode[];
  edges: IREdge[];
  cfgs: Record<string, CFG>;
}

export interface IRNode {
  id: string;
  kind: string;
  name: string;
  file: string;
  startLine: number;
  endLine: number;
  language: string;
  signature?: string;
  meta?: Record<string, unknown>;
}

export interface IREdge {
  from: string;
  to: string;
  kind: string;
  confidence: number;
  order: number;
}

export interface CFG {
  functionId: string;
  nodes: CFGNode[];
  edges: CFGEdge[];
  groups?: CFGGroup[]; // collapsible inlined-method groups (flow view only)
}

// kind: entry | exit | statement | branch | loop | call | return | throw | merge
export type CFGNodeKind =
  | 'entry'
  | 'exit'
  | 'statement'
  | 'branch'
  | 'loop'
  | 'call'
  | 'return'
  | 'throw'
  | 'merge';

export interface CFGNode {
  id: string;
  kind: string;
  label: string;
  line?: number;
  title?: string; // full untruncated text (present when the label was truncated)
  group?: string; // collapsible inlined-method group id (null for top-level)
  file?: string; // module-relative source file this node's code lives in
}

export interface CFGGroup {
  id: string;
  label: string; // e.g. "OrderService.place"
  parent?: string; // enclosing group id for nested inlines
}

// --- Flow description: the flow chart explained in words --------------------

export interface FlowDescriptionStats {
  nodes: number;
  calls: number;
  decisions: number;
  loops: number;
  thrown: number;
  returns: number;
  methods: number;
}

export interface FlowDescriptionStep {
  index: number; // 1-based; 0 for unnumbered structural notes
  kind: CFGNodeKind | 'note';
  text: string;
  line?: number;
  file?: string;
  method?: string; // inlined method this step belongs to
  depth: number; // nesting level, for indentation
  branches?: string[]; // e.g. ["yes → validate(order)", "no → return 400"]
}

export interface FlowDescription {
  functionId?: string;
  headline: string;
  stats: FlowDescriptionStats;
  steps: FlowDescriptionStep[];
  text: string; // plain-text rendering, for copy/download
  truncated: boolean;
}

// --- Business logic: what the flow is trying to accomplish -------------------

export interface BusinessLogicPhase {
  index: number;
  name: string;
  kind: string; // heuristic concern ("validation", "lookup", …) or "llm"
  purpose?: string;
  evidence?: string[]; // code steps the phase was inferred from
  line?: number;
}

export interface BusinessLogic {
  functionId?: string;
  origin: 'llm' | 'heuristic';
  provider?: string; // endpoint host + model when origin is "llm"
  llmConfigured: boolean;
  summary: string;
  phases?: BusinessLogicPhase[];
  rules?: string[];
  caveats?: string[];
}

export interface LlmStatus {
  available: boolean;
  provider: string;
}

export interface SequenceDiagram {
  entryFunctionId: string;
  mermaidText: string;
  participants: string[];
}

export interface CFGEdge {
  from: string;
  to: string;
  label?: string;
}

export interface ApiError {
  error: string;
}

// --- Service Map: whole-workspace service-to-service topology ---------------

export interface ServiceMap {
  workspace: string;
  nodes: SmNode[];
  edges: SmEdge[];
  stats: SmStats;
}

// type: service | ui | topic | datastore | external
// subtype: spring-boot | node-lambda | node-service | react | library |
//          kafka | dynamodb | s3 | sqs | http
export interface SmNode {
  id: string;
  label: string;
  type: string;
  subtype: string;
  language?: string;
  path?: string;
  meta?: Record<string, unknown>;
}

// kind: produces | consumes | rest | soap | reference | grpc | reads | writes | uses | triggers
// confidence: declared | code | config | inferred | unresolved
export interface SmEdge {
  id: string;
  source: string;
  target: string;
  kind: string;
  label?: string;
  confidence?: Confidence;
  evidence?: SmEvidence[];
}

export type Confidence = 'declared' | 'code' | 'config' | 'inferred' | 'unresolved';

// Where an edge comes from; file is relative to ServiceMap.workspace (or the app).
export interface SmEvidence {
  file?: string;
  line: number;
  snippet?: string;
  via?: string;
}

export interface SmStats {
  services: number;
  topics: number;
  datastores: number;
  externals: number;
  connections: number;
  notes: string[];
}

// --- Component Map: intra-app bean structure -------------------------------

export interface ComponentMap {
  project: string;
  components: CmpNode[];
  dependencies: CmpEdge[];
  stats: CmpStats;
}

// layer: controller | service | repository | client | config | component
export interface CmpNode {
  id: string;
  name: string;
  layer: string;
  pkg: string;
  file: string;
  startLine: number;
  methods: number;
  endpoints: string[];
  fanIn: number;
  fanOut: number;
}

// kind: inject | call
export interface CmpEdge {
  id: string;
  source: string;
  target: string;
  kind: string;
  weight: number;
}

export interface CmpStats {
  components: number;
  dependencies: number;
  byLayer: Record<string, number>;
  notes: string[];
}

// --- Architecture: the whole project in columns ------------------------------

// A folder with several services is shown as a project; a folder with one service as that application.
export interface Architecture {
  project: string;
  workspace: string;
  // Application scope: id of the application's node. Project scope: null.
  focus?: string | null;
  columns: ArchColumn[];
  nodes: ArchNode[];
  edges: ArchEdge[];
  stats: ArchStats;
  scope: 'project' | 'application';
  // Application scope: the application's layers (lanes) and their component groups.
  app?: ArchApp | null;
}

export interface ArchApp {
  id: string;
  lanes: ArchLane[];
}

// id: entry | logic | access
export interface ArchLane {
  id: string;
  title: string;
  groups: ArchGroup[];
}

// A layer's components. The group is also a node (type "group"), so connections can start at it.
export interface ArchGroup {
  id: string;
  title: string;
  nodes: string[];
}

// id: frontends | apis | processing | messaging | data | external | cloud (+ connected | app for one application)
export interface ArchColumn {
  id: string;
  title: string;
  nodes: string[];
}

// type: ui | service | kafka | queue | trigger | grpc | datastore | external | cloud (+ group | component)
export interface ArchNode {
  id: string;
  label: string;
  type: string;
  subtype: string;
  column: string;
  detail: string;
  meta?: Record<string, unknown>;
}

// kind: rest | soap | grpc | async | sends | consumes | triggers | reads | writes | readwrite | uses
// (+ inject | call between components inside one application)
export interface ArchEdge {
  id: string;
  source: string;
  target: string;
  kind: string;
  label?: string | null;
  confidence?: Confidence;
  items?: string[];
  evidence?: SmEvidence[];
}

export interface ArchStats {
  services: number;
  datastores: number;
  messaging: number;
  externals: number;
  connections: number;
  notes: string[];
}
