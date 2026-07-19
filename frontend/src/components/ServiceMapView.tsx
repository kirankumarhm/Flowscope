import { useEffect, useMemo, useRef, useState } from 'react';
import cytoscape from 'cytoscape';
import type { Core, ElementDefinition } from 'cytoscape';
import dagre from 'cytoscape-dagre';
import svg from 'cytoscape-svg';
import { jsPDF } from 'jspdf';
import type { ServiceMap, SmNode } from '../types';

cytoscape.use(dagre);
cytoscape.use(svg);

interface ServiceMapViewProps {
  map: ServiceMap | null;
  loading: boolean;
  error?: string;
  filename?: string;
}

type ExportFormat = 'png' | 'svg' | 'pdf';
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
  react: { color: '#8b5cf6', shape: 'round-rectangle', glyph: '⚛' },
  library: { color: '#94a3b8', shape: 'round-rectangle', glyph: '⚙' },
  kafka: { color: '#10b981', shape: 'hexagon', glyph: '📨' },
  dynamodb: { color: '#0d9488', shape: 'barrel', glyph: '🗄' },
  s3: { color: '#ca8a04', shape: 'barrel', glyph: '🪣' },
  sqs: { color: '#db2777', shape: 'barrel', glyph: '📥' },
  external: { color: '#64748b', shape: 'round-rectangle', glyph: '🌐' },
};
function nodeStyle(subtype: string, type: string): Style {
  return NODE_STYLES[subtype] ?? NODE_STYLES[type] ?? NODE_STYLES.library;
}

const EDGE_COLORS: Record<string, string> = {
  produces: '#10b981',
  consumes: '#2563eb',
  rest: '#6366f1',
  writes: '#e11d48',
  reads: '#0d9488',
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
    selector: 'edge[kind = "rest"]',
    style: { 'line-style': 'dashed' },
  },
  {
    selector: 'node:selected',
    style: { 'border-width': 3, 'border-color': '#1d4ed8' },
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
  const [filters, setFilters] = useState<Filters>({ topics: true, datastores: true, externals: true });

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

  useEffect(() => {
    if (!containerRef.current || !map) return;

    const hidden = (n: SmNode): boolean =>
      (!filters.topics && n.type === 'topic') ||
      (!filters.datastores && n.type === 'datastore') ||
      (!filters.externals && n.type === 'external');

    const visibleNodes = map.nodes.filter((n) => !hidden(n));
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
          },
        };
      }),
      ...map.edges
        .filter((e) => visibleIds.has(e.source) && visibleIds.has(e.target))
        .map((e) => ({
          data: {
            id: e.id,
            source: e.source,
            target: e.target,
            kind: e.kind,
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
      if (n) setSelected(n);
    });
    cy.on('tap', (evt: cytoscape.EventObject) => {
      if (evt.target === cy) {
        cy.elements().removeClass('faded');
        setSelected(null);
      }
    });

    return () => {
      cy.destroy();
      cyRef.current = null;
    };
  }, [map, filters, nodeById]);

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
        <div className="export-menu">
          <button type="button" className="toolbar-btn" onClick={() => setMenuOpen((v) => !v)} aria-haspopup="true" aria-expanded={menuOpen}>
            Export ▾
          </button>
          {menuOpen && (
            <div className="export-dropdown" role="menu">
              <button type="button" onClick={() => doExport('png')}>PNG</button>
              <button type="button" onClick={() => doExport('svg')}>SVG</button>
              <button type="button" onClick={() => doExport('pdf')}>PDF</button>
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
    </div>
  );
}

const LEGEND: { key: string; label: string }[] = [
  { key: 'spring-boot', label: 'Spring Boot' },
  { key: 'node-lambda', label: 'Lambda (Node)' },
  { key: 'node-service', label: 'Node service' },
  { key: 'react', label: 'UI (React)' },
  { key: 'kafka', label: 'Kafka topic' },
  { key: 'dynamodb', label: 'DynamoDB' },
  { key: 's3', label: 'S3 bucket' },
  { key: 'sqs', label: 'SQS queue' },
  { key: 'external', label: 'External system' },
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
    if (meta.produces !== undefined) rows.push(['Topics produced', String(meta.produces)]);
    if (meta.consumes !== undefined) rows.push(['Topics consumed', String(meta.consumes)]);
    if (node.path) rows.push(['Path', node.path]);
  } else {
    if (meta.kind) rows.push(['Type', String(meta.kind)]);
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
