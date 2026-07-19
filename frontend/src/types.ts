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

// kind: produces | consumes | rest | reads | writes
export interface SmEdge {
  id: string;
  source: string;
  target: string;
  kind: string;
  label?: string;
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

// --- Architecture: high-level layered view of one app ----------------------

export interface Architecture {
  project: string;
  nodes: ArchNode[];
  edges: ArchEdge[];
  stats: ArchStats;
}

// type: layer | datastore | topic | external
export interface ArchNode {
  id: string;
  label: string;
  type: string;
  subtype: string;
  count: number;
}

// kind: depends | uses | produces | consumes | calls
export interface ArchEdge {
  id: string;
  source: string;
  target: string;
  kind: string;
  weight: number;
}

export interface ArchStats {
  layers: number;
  datastores: number;
  topics: number;
  externals: number;
  notes: string[];
}
