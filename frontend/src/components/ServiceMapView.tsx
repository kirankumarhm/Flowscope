import { useEffect, useMemo, useRef, useState } from 'react';
import cytoscape from 'cytoscape';
import type { Core, ElementDefinition } from 'cytoscape';
import dagre from 'cytoscape-dagre';
import svg from 'cytoscape-svg';
import { jsPDF } from 'jspdf';
import type { Confidence, ServiceMap, SmEdge, SmNode } from '../types';
import { cyToDrawioXml } from '../drawio';

cytoscape.use(dagre);
cytoscape.use(svg);

interface ServiceMapViewProps {
  map: ServiceMap | null;
  loading: boolean;
  error?: string;
  filename?: string;
}

type ExportFormat = 'png' | 'svg' | 'pdf' | 'drawio';
const EXPORT_SCALE = 2;
const EXPORT_BG = '#ffffff';

// Visual identity per node kind — colour + cytoscape shape.
interface Style {
  color: string;
  shape: string;
  glyph: string;
}
const NODE_STYLES: Record<string, Style> = {
  'spring-boot': { color: '#2563eb', shape: 'round-rectangle', glyph: '☕' },
  'node-lambda': { color: '#f59e0b', shape: 'round-rectangle', glyph: 'λ' },
  'node-service': { color: '#0ea5e9', shape: 'round-rectangle', glyph: '⬡' },
  nestjs: { color: '#e0234e', shape: 'round-rectangle', glyph: '🐈' },
  nextjs: { color: '#0f172a', shape: 'round-rectangle', glyph: '▲' },
  'go-service': { color: '#00add8', shape: 'round-rectangle', glyph: '🐹' },
  'python-service': { color: '#3776ab', shape: 'round-rectangle', glyph: '🐍' },
  react: { color: '#8b5cf6', shape: 'round-rectangle', glyph: '⚛' },
  angular: { color: '#dd0031', shape: 'round-rectangle', glyph: '🅰' },
  vue: { color: '#42b883', shape: 'round-rectangle', glyph: '▼' },
  container: { color: '#475569', shape: 'round-rectangle', glyph: '📦' },
  library: { color: '#94a3b8', shape: 'round-rectangle', glyph: '⚙' },
  grpc: { color: '#0891b2', shape: 'cut-rectangle', glyph: '📡' },
  kafka: { color: '#10b981', shape: 'hexagon', glyph: '📨' },
  sns: { color: '#e11d48', shape: 'hexagon', glyph: '📣' },
  dynamodb: { color: '#0d9488', shape: 'barrel', glyph: '🗄' },
  s3: { color: '#ca8a04', shape: 'barrel', glyph: '🪣' },
  sqs: { color: '#db2777', shape: 'barrel', glyph: '📥' },
  postgresql: { color: '#336791', shape: 'barrel', glyph: '🐘' },
  mysql: { color: '#00758f', shape: 'barrel', glyph: '🐬' },
  mariadb: { color: '#003545', shape: 'barrel', glyph: '🗃' },
  oracle: { color: '#c74634', shape: 'barrel', glyph: '🗃' },
  sqlserver: { color: '#a91d22', shape: 'barrel', glyph: '🗃' },
  db2: { color: '#054ada', shape: 'barrel', glyph: '🗃' },
  sql: { color: '#475569', shape: 'barrel', glyph: '🗃' },
  cassandra: { color: '#1287b1', shape: 'barrel', glyph: '👁' },
  mongodb: { color: '#13aa52', shape: 'barrel', glyph: '🍃' },
  redis: { color: '#d82c20', shape: 'barrel', glyph: '⚡' },
  elasticsearch: { color: '#00bfb3', shape: 'barrel', glyph: '🔎' },
  cloud: { color: '#ff9900', shape: 'round-rectangle', glyph: '☁' },
  soap: { color: '#7c3aed', shape: 'round-rectangle', glyph: '🧼' },
  schedule: { color: '#d97706', shape: 'tag', glyph: '⏰' },
  external: { color: '#64748b', shape: 'round-rectangle', glyph: '🌐' },
};
function nodeStyle(subtype: string, type: string): Style {
  return NODE_STYLES[subtype] ?? NODE_STYLES[type] ?? NODE_STYLES.library;
}

const EDGE_COLORS: Record<string, string> = {
  produces: '#10b981',
  consumes: '#2563eb',
  rest: '#6366f1',
  soap: '#7c3aed',
  reference: '#a8a29e',
  grpc: '#0891b2',
  writes: '#e11d48',
  reads: '#0d9488',
  uses: '#78716c',
  triggers: '#d97706',
};

// Line style says how sure FlowScope is: solid = found in code (or declared),
// dashed = referenced config value, dotted = name/host/route match, grey dotted = placeholder.
const CONFIDENCE_LEVELS: Confidence[] = ['declared', 'code', 'config', 'inferred', 'unresolved'];
const CONFIDENCE_LABEL: Record<Confidence, string> = {
  declared: 'Declared (flowscope.yml)',
  code: 'Code',
  config: 'Config',
  inferred: 'Inferred',
  unresolved: 'Unresolved',
};

const stylesheet: any[] = [
  {
    selector: 'node',
    style: {
      'background-color': (e: cytoscape.NodeSingular) => nodeStyle(e.data('subtype'), e.data('type')).color,
      shape: (e: cytoscape.NodeSingular) => nodeStyle(e.data('subtype'), e.data('type')).shape,
      label: 'data(display)',
      color: '#ffffff',
      'font-family': 'Inter, system-ui, sans-serif',
      'font-size': '12px',
      'font-weight': 600,
      'text-valign': 'center',
      'text-halign': 'center',
      'text-wrap': 'wrap',
      'text-max-width': '150px',
      width: 'label',
      height: 'label',
      padding: '12px',
      'border-width': 1,
      'border-color': 'rgba(15,23,42,0.15)',
    },
  },
  {
    // Externals are boundary systems — quieter, dashed, dark text.
    selector: 'node[type = "external"]',
    style: { 'border-width': 1.5, 'border-style': 'dashed', 'border-color': '#94a3b8', color: '#f1f5f9' },
  },
  {
    selector: 'node[type = "topic"]',
    style: { 'font-size': '11px', 'text-max-width': '130px' },
  },
  {
    selector: 'edge',
    style: {
      width: (e: cytoscape.EdgeSingular) => Math.min(5, 1.4 + (Number(e.data('weight')) || 1) * 0.5),
      'line-color': (e: cytoscape.EdgeSingular) => EDGE_COLORS[e.data('kind') as string] ?? '#94a3b8',
      'target-arrow-color': (e: cytoscape.EdgeSingular) => EDGE_COLORS[e.data('kind') as string] ?? '#94a3b8',
      'target-arrow-shape': 'triangle',
      'arrow-scale': 1,
      // Orthogonal routing — only horizontal/vertical segments (layout is LR).
      'curve-style': 'taxi',
      'taxi-direction': 'horizontal',
      'taxi-turn': 30,
      'taxi-turn-min-distance': 5,
      opacity: 0.85,
      label: 'data(label)',
      'font-size': '9px',
      'font-weight': 600,
      color: '#475569',
      'text-background-color': '#ffffff',
      'text-background-opacity': 0.9,
      'text-background-padding': '2px',
    },
  },
  {
    selector: 'edge[confidence = "config"]',
    style: { 'line-style': 'dashed' },
  },
  {
    selector: 'edge[confidence = "inferred"]',
    style: { 'line-style': 'dotted', width: 2 },
  },
  {
    selector: 'edge[confidence = "unresolved"]',
    style: { 'line-style': 'dotted', 'line-color': '#cbd5e1', 'target-arrow-color': '#cbd5e1' },
  },
  {
    selector: 'edge[confidence = "declared"]',
    style: { width: 3 },
  },
  {
    selector: 'edge[kind = "reference"]',
    style: { 'line-style': 'dotted', 'target-arrow-shape': 'circle' },
  },
  {
    // Placeholders for names that could not be resolved.
    selector: 'node[?unresolved]',
    style: { 'background-color': '#e2e8f0', color: '#475569', 'border-style': 'dashed', 'border-width': 1.5 },
  },
  {
    selector: 'node:selected',
    style: { 'border-width': 3, 'border-color': '#1d4ed8' },
  },
  {
    selector: 'edge:selected',
    style: { width: 5, opacity: 1 },
  },
  {
    // Dim everything not connected to the selected node (set via .faded class).
    selector: '.faded',
    style: { opacity: 0.12 },
  },
];

function triggerDownload(href: string, filename: string): void {
  const a = document.createElement('a');
  a.href = href;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
}
function sanitize(name: string): string {
  return (name || 'service-map').replace(/[^\w.-]+/g, '_').replace(/^_+|_+$/g, '') || 'service-map';
}

interface Filters {
  topics: boolean;
  datastores: boolean;
  externals: boolean;
}

export default function ServiceMapView({ map, loading, error, filename = 'service-map' }: ServiceMapViewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const cyRef = useRef<Core | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [selected, setSelected] = useState<SmNode | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<SmEdge | null>(null);
  const [filters, setFilters] = useState<Filters>({ topics: true, datastores: true, externals: true });
  const [shownConfidence, setShownConfidence] = useState<Set<Confidence>>(new Set(CONFIDENCE_LEVELS));

  const zoomBy = (factor: number) => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.zoom({ level: cy.zoom() * factor, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  };
  const fitView = () => cyRef.current?.fit(undefined, 40);
  const resetView = () => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.zoom(1);
    cy.center();
  };
  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else wrapRef.current?.requestFullscreen?.();
  };

  // Index nodes by id for the detail panel + filter decisions.
  const nodeById = useMemo(() => {
    const m = new Map<string, SmNode>();
    map?.nodes.forEach((n) => m.set(n.id, n));
    return m;
  }, [map]);
  const edgeById = useMemo(() => {
    const m = new Map<string, SmEdge>();
    map?.edges.forEach((e) => m.set(e.id, e));
    return m;
  }, [map]);

  useEffect(() => {
    if (!containerRef.current || !map) return;

    const hidden = (n: SmNode): boolean =>
      (!filters.topics && n.type === 'topic') ||
      (!filters.datastores && n.type === 'datastore') ||
      (!filters.externals && n.type === 'external');

    const edgeShown = (e: SmEdge): boolean => !e.confidence || shownConfidence.has(e.confidence);
    const shownEdges = map.edges.filter(edgeShown);
    // Non-service nodes only appear while at least one shown edge touches them.
    const touched = new Set<string>();
    shownEdges.forEach((e) => {
      touched.add(e.source);
      touched.add(e.target);
    });
    const visibleNodes = map.nodes.filter(
      (n) => !hidden(n) && (n.type === 'service' || n.type === 'ui' || touched.has(n.id)),
    );
    const visibleIds = new Set(visibleNodes.map((n) => n.id));

    const elements: ElementDefinition[] = [
      ...visibleNodes.map((n) => {
        const s = nodeStyle(n.subtype, n.type);
        return {
          data: {
            id: n.id,
            label: n.label,
            display: `${s.glyph}  ${n.label}`,
            type: n.type,
            subtype: n.subtype,
            unresolved: n.meta?.unresolved === true,
          },
        };
      }),
      ...shownEdges
        .filter((e) => visibleIds.has(e.source) && visibleIds.has(e.target))
        .map((e) => ({
          data: {
            id: e.id,
            source: e.source,
            target: e.target,
            kind: e.kind,
            confidence: e.confidence ?? 'code',
          },
        })),
    ];

    const cy = cytoscape({
      container: containerRef.current,
      elements,
      style: stylesheet,
      wheelSensitivity: 0.2,
      minZoom: 0.05,
      maxZoom: 3,
    });
    cyRef.current = cy;

    cy.layout({
      name: 'dagre',
      rankDir: 'LR',
      rankSep: 90,
      nodeSep: 30,
      edgeSep: 12,
      fit: true,
      padding: 40,
    } as unknown as cytoscape.LayoutOptions).run();
    cy.fit(undefined, 40);

    // Click a node → highlight its neighbourhood + open detail; click bg → clear.
    cy.on('tap', 'node', (evt: cytoscape.EventObject) => {
      const id = evt.target.id();
      const neighborhood = evt.target.closedNeighborhood();
      cy.elements().addClass('faded');
      neighborhood.removeClass('faded');
      const n = nodeById.get(id);
      if (n) {
        setSelected(n);
        setSelectedEdge(null);
      }
    });
    // Click an arrow → show why it is drawn (evidence + confidence).
    cy.on('tap', 'edge', (evt: cytoscape.EventObject) => {
      const e = edgeById.get(evt.target.id());
      cy.elements().addClass('faded');
      evt.target.removeClass('faded');
      evt.target.connectedNodes().removeClass('faded');
      if (e) {
        setSelectedEdge(e);
        setSelected(null);
      }
    });
    cy.on('tap', (evt: cytoscape.EventObject) => {
      if (evt.target === cy) {
        cy.elements().removeClass('faded');
        setSelected(null);
        setSelectedEdge(null);
      }
    });

    return () => {
      cy.destroy();
      cyRef.current = null;
    };
  }, [map, filters, shownConfidence, nodeById, edgeById]);

  const doExport = (format: ExportFormat) => {
    setMenuOpen(false);
    const cy = cyRef.current;
    if (!cy) return;
    const base = sanitize(filename);
    if (format === 'png') {
      triggerDownload(cy.png({ full: true, scale: EXPORT_SCALE, bg: EXPORT_BG }), `${base}.png`);
      return;
    }
    if (format === 'svg') {
      const svgText = (cy as unknown as { svg: (o: object) => string }).svg({ full: true, bg: EXPORT_BG, scale: 1 });
      const blob = new Blob([svgText], { type: 'image/svg+xml;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      triggerDownload(url, `${base}.svg`);
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return;
    }
    if (format === 'drawio') {
      const blob = new Blob([cyToDrawioXml(cy)], { type: 'application/xml;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      triggerDownload(url, `${base}.drawio`);
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return;
    }
    const dataUrl = cy.png({ full: true, scale: EXPORT_SCALE, bg: EXPORT_BG });
    const img = new Image();
    img.onload = () => {
      const pdf = new jsPDF({
        orientation: img.width >= img.height ? 'landscape' : 'portrait',
        unit: 'pt',
        format: [img.width, img.height],
      });
      pdf.addImage(dataUrl, 'PNG', 0, 0, img.width, img.height);
      pdf.save(`${base}.pdf`);
    };
    img.src = dataUrl;
  };

  if (loading) {
    return <div className="seq-placeholder">Scanning workspace for services…</div>;
  }
  if (error) {
    return (
      <div className="seq-placeholder seq-error">
        <div>Could not build the service map.</div>
        <div className="detail-path">{error}</div>
      </div>
    );
  }
  if (!map) {
    return (
      <div className="seq-placeholder">
        Analyze a <strong>workspace directory</strong> (the folder that contains your services) to see the
        service-to-service map.
      </div>
    );
  }
  if (map.nodes.length === 0) {
    return <div className="seq-placeholder">No services detected in this directory.</div>;
  }

  return (
    <div className="flowchart-wrap" ref={wrapRef}>
      <div className="sm-statbar">
        <span><strong>{map.stats.services}</strong> services</span>
        <span><strong>{map.stats.topics}</strong> topics</span>
        <span><strong>{map.stats.datastores}</strong> datastores</span>
        <span><strong>{map.stats.externals}</strong> external</span>
        <span><strong>{map.stats.connections}</strong> connections</span>
      </div>

      <div className="flowchart-toolbar">
        {(['topics', 'datastores', 'externals'] as const).map((k) => (
          <button
            key={k}
            type="button"
            className={filters[k] ? 'toolbar-btn sm-toggle on' : 'toolbar-btn sm-toggle'}
            onClick={() => setFilters((f) => ({ ...f, [k]: !f[k] }))}
            title={`Show/hide ${k}`}
          >
            {filters[k] ? '✓ ' : ''}{k[0].toUpperCase() + k.slice(1)}
          </button>
        ))}
        {CONFIDENCE_LEVELS.map((c) => (
          <button
            key={c}
            type="button"
            className={shownConfidence.has(c) ? 'toolbar-btn sm-toggle on' : 'toolbar-btn sm-toggle'}
            onClick={() =>
              setShownConfidence((cur) => {
                const next = new Set(cur);
                if (next.has(c)) next.delete(c);
                else next.add(c);
                return next;
              })
            }
            title={`Show/hide ${c} connections`}
          >
            {shownConfidence.has(c) ? '✓ ' : ''}{CONFIDENCE_LABEL[c]}
          </button>
        ))}
        <div className="export-menu">
          <button type="button" className="toolbar-btn" onClick={() => setMenuOpen((v) => !v)} aria-haspopup="true" aria-expanded={menuOpen}>
            Export ▾
          </button>
          {menuOpen && (
            <div className="export-dropdown" role="menu">
              <button type="button" onClick={() => doExport('png')}>PNG</button>
              <button type="button" onClick={() => doExport('svg')}>SVG</button>
              <button type="button" onClick={() => doExport('pdf')}>PDF</button>
              <button type="button" onClick={() => doExport('drawio')}>draw.io</button>
            </div>
          )}
        </div>
      </div>

      <div className="flowchart-canvas" ref={containerRef} />

      <div className="zoom-controls">
        <button type="button" onClick={() => zoomBy(1.25)} title="Zoom in">＋</button>
        <button type="button" onClick={() => zoomBy(0.8)} title="Zoom out">－</button>
        <button type="button" onClick={fitView} title="Fit to screen">⤢</button>
        <button type="button" onClick={resetView} title="Reset zoom">1:1</button>
        <button type="button" onClick={toggleFullscreen} title="Fullscreen">⛶</button>
      </div>

      <ServiceMapLegend />

      {selected && <ServiceDetail node={selected} onClose={() => {
        setSelected(null);
        cyRef.current?.elements().removeClass('faded');
      }} />}
      {selectedEdge && (
        <EdgeDetail
          edge={selectedEdge}
          workspace={map.workspace}
          nodeById={nodeById}
          onClose={() => {
            setSelectedEdge(null);
            cyRef.current?.elements().removeClass('faded');
          }}
        />
      )}
    </div>
  );
}

/** Evidence behind one arrow, with links that open the file at the line. */
function EdgeDetail({ edge, workspace, nodeById, onClose }: {
  edge: SmEdge;
  workspace: string;
  nodeById: Map<string, SmNode>;
  onClose: () => void;
}) {
  const label = (id: string) => nodeById.get(id)?.label ?? id;
  const abs = (file: string) => `${workspace.replace(/\/+$/, '')}/${file}`;
  return (
    <div className="detail-panel">
      <div className="detail-header">
        <span className="detail-role" style={{ background: EDGE_COLORS[edge.kind] ?? '#94a3b8' }}>{edge.kind}</span>
        <button type="button" className="detail-close" onClick={onClose} aria-label="Close">✕</button>
      </div>
      <div className="detail-section">
        <div className="detail-key">Connection</div>
        <div className="detail-value">{label(edge.source)} → {label(edge.target)}</div>
      </div>
      {edge.label && (
        <div className="detail-section">
          <div className="detail-key">Label</div>
          <div className="detail-value detail-path">{edge.label}</div>
        </div>
      )}
      <div className="detail-section">
        <div className="detail-key">Confidence</div>
        <div className="detail-value">{edge.confidence ? CONFIDENCE_LABEL[edge.confidence] : 'unknown'}</div>
      </div>
      <div className="detail-section">
        <div className="detail-key">Evidence</div>
        {(edge.evidence ?? []).length === 0 && <div className="detail-value">None recorded</div>}
        {(edge.evidence ?? []).map((ev, i) => (
          <div className="detail-value detail-path" key={i} style={{ marginBottom: 8 }}>
            {ev.file ? (
              <a href={`vscode://file/${abs(ev.file)}:${ev.line}`} title={`Open ${abs(ev.file)}:${ev.line}`}>
                {ev.file}:{ev.line}
              </a>
            ) : null}
            {ev.via && <div><em>{ev.via}</em></div>}
            {ev.snippet && <code style={{ whiteSpace: 'pre-wrap' }}>{ev.snippet}</code>}
          </div>
        ))}
      </div>
    </div>
  );
}

const LEGEND: { key: string; label: string }[] = [
  { key: 'spring-boot', label: 'Spring Boot' },
  { key: 'node-lambda', label: 'Lambda (Node)' },
  { key: 'node-service', label: 'Node service' },
  { key: 'nestjs', label: 'NestJS' },
  { key: 'nextjs', label: 'Next.js' },
  { key: 'go-service', label: 'Go service' },
  { key: 'python-service', label: 'Python service' },
  { key: 'react', label: 'UI (React)' },
  { key: 'angular', label: 'UI (Angular)' },
  { key: 'container', label: 'Container job' },
  { key: 'grpc', label: 'gRPC service' },
  { key: 'kafka', label: 'Kafka topic' },
  { key: 'dynamodb', label: 'DynamoDB' },
  { key: 's3', label: 'S3 bucket' },
  { key: 'sqs', label: 'SQS queue' },
  { key: 'postgresql', label: 'PostgreSQL' },
  { key: 'cassandra', label: 'Cassandra' },
  { key: 'cloud', label: 'Cloud service' },
  { key: 'schedule', label: 'Trigger' },
  { key: 'external', label: 'External system' },
];
const LINE_LEGEND: { style: string; label: string }[] = [
  { style: 'solid', label: 'Code / declared' },
  { style: 'dashed', label: 'Config' },
  { style: 'dotted', label: 'Inferred / unresolved' },
];
function ServiceMapLegend() {
  return (
    <div className="flowchart-legend">
      {LEGEND.map(({ key, label }) => {
        const s = nodeStyle(key, key);
        return (
          <div className="legend-item" key={key}>
            <span className="legend-swatch" style={{ background: s.color }}>{s.glyph}</span>
            {label}
          </div>
        );
      })}
      {LINE_LEGEND.map(({ style, label }) => (
        <div className="legend-item" key={style}>
          <span style={{ display: 'inline-block', width: 22, borderTop: `2px ${style} #475569`, marginRight: 6 }} />
          {label}
        </div>
      ))}
    </div>
  );
}

function ServiceDetail({ node, onClose }: { node: SmNode; onClose: () => void }) {
  const meta = node.meta ?? {};
  const s = nodeStyle(node.subtype, node.type);
  const rows: [string, string][] = [];
  if (node.type === 'service' || node.type === 'ui') {
    if (meta.appName) rows.push(['App name', String(meta.appName)]);
    rows.push(['Runtime', node.subtype]);
    if (node.language) rows.push(['Language', node.language]);
    if (meta.handler) rows.push(['Lambda handler', String(meta.handler)]);
    if (meta.functionName) rows.push(['Deployed function', String(meta.functionName)]);
    if (meta.produces !== undefined) rows.push(['Topics produced', String(meta.produces)]);
    if (meta.consumes !== undefined) rows.push(['Topics consumed', String(meta.consumes)]);
    if (meta.libraryReason) rows.push(['Library because', String(meta.libraryReason)]);
    if (node.path) rows.push(['Path', node.path]);
  } else {
    if (meta.kind) rows.push(['Type', String(meta.kind)]);
    if (Array.isArray(meta.tables)) rows.push(['Tables', (meta.tables as string[]).slice(0, 15).join(', ')]);
    if (Array.isArray(meta.candidates)) rows.push(['Candidates', (meta.candidates as string[]).join(', ')]);
  }
  return (
    <div className="detail-panel">
      <div className="detail-header">
        <span className="detail-role" style={{ background: s.color }}>{s.glyph} {node.type}</span>
        <button type="button" className="detail-close" onClick={onClose} aria-label="Close">✕</button>
      </div>
      <div className="detail-section">
        <div className="detail-key">Name</div>
        <div className="detail-value">{node.label}</div>
      </div>
      {rows.map(([k, v]) => (
        <div className="detail-section" key={k}>
          <div className="detail-key">{k}</div>
          <div className="detail-value detail-path">{v}</div>
        </div>
      ))}
    </div>
  );
}
