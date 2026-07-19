import { useMemo, useRef, useState, useEffect } from 'react';
import cytoscape from 'cytoscape';
import type { Core, ElementDefinition } from 'cytoscape';
import dagre from 'cytoscape-dagre';
import svg from 'cytoscape-svg';
import { jsPDF } from 'jspdf';
import type { ComponentMap, CmpNode } from '../types';

cytoscape.use(dagre);
cytoscape.use(svg);

interface ComponentViewProps {
  map: ComponentMap | null;
  loading: boolean;
  error?: string;
  filename?: string;
}

type ExportFormat = 'png' | 'svg' | 'pdf';
const EXPORT_SCALE = 2;
const EXPORT_BG = '#ffffff';

// Architectural layers, in left-to-right flow order.
const LAYERS = ['controller', 'service', 'repository', 'client', 'component', 'config'] as const;
type Layer = (typeof LAYERS)[number];

interface Style {
  color: string;
  shape: string;
  glyph: string;
}
const LAYER_STYLES: Record<Layer, Style> = {
  controller: { color: '#2563eb', shape: 'round-rectangle', glyph: '🔵' },
  service: { color: '#10b981', shape: 'round-rectangle', glyph: '⚙' },
  repository: { color: '#f59e0b', shape: 'barrel', glyph: '🗄' },
  client: { color: '#db2777', shape: 'round-rectangle', glyph: '🌐' },
  component: { color: '#8b5cf6', shape: 'round-rectangle', glyph: '🧩' },
  config: { color: '#64748b', shape: 'round-rectangle', glyph: '🔧' },
};
function layerStyle(layer: string): Style {
  return LAYER_STYLES[layer as Layer] ?? LAYER_STYLES.component;
}
const LAYER_LABELS: Record<Layer, string> = {
  controller: 'Controller',
  service: 'Service',
  repository: 'Repository',
  client: 'Client',
  component: 'Component',
  config: 'Config',
};

const stylesheet: any[] = [
  {
    selector: 'node',
    style: {
      'background-color': (e: cytoscape.NodeSingular) => layerStyle(e.data('layer')).color,
      shape: (e: cytoscape.NodeSingular) => layerStyle(e.data('layer')).shape,
      label: 'data(label)',
      color: '#ffffff',
      'font-family': 'Inter, system-ui, sans-serif',
      'font-size': '13px',
      'font-weight': 600,
      'text-valign': 'center',
      'text-halign': 'center',
      'text-wrap': 'wrap',
      'text-max-width': '170px',
      width: 'label',
      height: 'label',
      padding: '16px',
      'border-width': 1,
      'border-color': 'rgba(15,23,42,0.15)',
    },
  },
  {
    selector: 'node[layer = "config"]',
    style: { 'border-style': 'dashed', 'border-color': '#94a3b8' },
  },
  {
    selector: 'edge',
    style: {
      width: (e: cytoscape.EdgeSingular) => Math.min(5, 1.2 + (Number(e.data('weight')) || 1) * 0.4),
      'line-color': '#cbd5e1',
      'target-arrow-color': '#cbd5e1',
      'target-arrow-shape': 'triangle',
      'arrow-scale': 0.9,
      // Strictly orthogonal (horizontal/vertical) routing. Per-edge turn distance
      // puts parallel edges in their own vertical lanes to reduce overlap.
      'curve-style': 'taxi',
      'taxi-direction': 'horizontal',
      'taxi-turn': 'data(turn)',
      'taxi-turn-min-distance': 4,
      opacity: 0.8,
    },
  },
  {
    // Injection is the structural wiring — draw it solid & a touch darker.
    selector: 'edge[kind = "inject"]',
    style: { 'line-color': '#94a3b8', 'target-arrow-color': '#94a3b8' },
  },
  {
    // Calls without injection — dashed to distinguish from DI wiring.
    selector: 'edge[kind = "call"]',
    style: { 'line-style': 'dashed' },
  },
  { selector: 'node:selected', style: { 'border-width': 3, 'border-color': '#1d4ed8' } },
  { selector: '.faded', style: { opacity: 0.1 } },
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
  return (name || 'components').replace(/[^\w.-]+/g, '_').replace(/^_+|_+$/g, '') || 'components';
}

export default function ComponentView({ map, loading, error, filename = 'components' }: ComponentViewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const cyRef = useRef<Core | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [selected, setSelected] = useState<CmpNode | null>(null);
  const [hidden, setHidden] = useState<Set<string>>(new Set());
  const [hideIsolated, setHideIsolated] = useState(true);
  const [hubsOnly, setHubsOnly] = useState(true);
  const [focus, setFocus] = useState('');

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
  const toggleLayer = (layer: string) =>
    setHidden((h) => {
      const next = new Set(h);
      if (next.has(layer)) next.delete(layer);
      else next.add(layer);
      return next;
    });

  const nodeById = useMemo(() => {
    const m = new Map<string, CmpNode>();
    map?.components.forEach((n) => m.set(n.id, n));
    return m;
  }, [map]);

  // Apply all filters: layer toggles, hide-isolated, and focus search (a match
  // plus its direct neighbours). Shared by the canvas and the stat bar.
  const visible = useMemo(() => {
    if (!map) return [];
    const layerVisible = map.components.filter((n) => !hidden.has(n.layer));
    const q = focus.trim().toLowerCase();
    if (q) {
      const matchIds = new Set(
        layerVisible.filter((n) => n.name.toLowerCase().includes(q)).map((n) => n.id)
      );
      const keep = new Set(matchIds);
      for (const e of map.dependencies) {
        if (matchIds.has(e.source)) keep.add(e.target);
        if (matchIds.has(e.target)) keep.add(e.source);
      }
      return layerVisible.filter((n) => keep.has(n.id));
    }
    // Default to a clean "hubs only" view: well-connected components (3+ links).
    if (hubsOnly) {
      return layerVisible.filter((n) => n.fanIn + n.fanOut >= 3);
    }
    if (hideIsolated) {
      return layerVisible.filter((n) => n.fanIn + n.fanOut > 0);
    }
    return layerVisible;
  }, [map, hidden, hideIsolated, hubsOnly, focus]);

  useEffect(() => {
    if (!containerRef.current || !map) return;

    const visibleIds = new Set(visible.map((n) => n.id));

    const elements: ElementDefinition[] = [
      ...visible.map((n) => {
        const s = layerStyle(n.layer);
        return {
          data: {
            id: n.id,
            label: `${s.glyph}  ${n.name}`,
            layer: n.layer,
          },
        };
      }),
      ...map.dependencies
        .filter((e) => visibleIds.has(e.source) && visibleIds.has(e.target))
        .map((e, i) => ({
          data: {
            id: e.id,
            source: e.source,
            target: e.target,
            kind: e.kind,
            weight: e.weight,
            // Stagger bend points across ~10 lanes so parallel edges don't stack.
            turn: 14 + (i % 10) * 14,
          },
        })),
    ];

    const cy = cytoscape({
      container: containerRef.current,
      elements,
      style: stylesheet,
      wheelSensitivity: 0.2,
      minZoom: 0.03,
      maxZoom: 3,
    });
    cyRef.current = cy;

    cy.layout({
      name: 'dagre',
      rankDir: 'LR',
      rankSep: 160,
      nodeSep: 50,
      edgeSep: 18,
      fit: true,
      padding: 40,
    } as unknown as cytoscape.LayoutOptions).run();
    cy.fit(undefined, 40);

    cy.on('tap', 'node', (evt: cytoscape.EventObject) => {
      const id = evt.target.id();
      cy.elements().addClass('faded');
      evt.target.closedNeighborhood().removeClass('faded');
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
  }, [map, visible, nodeById]);

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

  if (loading) return <div className="seq-placeholder">Building component map…</div>;
  if (error) {
    return (
      <div className="seq-placeholder seq-error">
        <div>Could not build the component map.</div>
        <div className="detail-path">{error}</div>
      </div>
    );
  }
  if (!map) {
    return <div className="seq-placeholder">Analyze a single service to see its component structure.</div>;
  }
  if (map.components.length === 0) {
    return <div className="seq-placeholder">No Spring components detected in this service.</div>;
  }

  return (
    <div className="flowchart-wrap" ref={wrapRef}>
      <div className="sm-statbar">
        <span><strong>{visible.length}</strong> shown</span>
        <span>of {map.stats.components} components</span>
        <span><strong>{map.stats.dependencies}</strong> dependencies</span>
      </div>

      <div className="flowchart-toolbar">
        <input
          type="search"
          className="sm-search"
          placeholder="Focus a component…"
          value={focus}
          onChange={(e) => setFocus(e.target.value)}
          title="Show only components matching this name and their direct neighbours"
        />
        <button
          type="button"
          className={hubsOnly ? 'toolbar-btn sm-toggle on' : 'toolbar-btn sm-toggle'}
          onClick={() => setHubsOnly((v) => !v)}
          title="Show only well-connected components (3+ dependencies) for a clean overview"
        >
          {hubsOnly ? '✓ ' : ''}Hubs only
        </button>
        <button
          type="button"
          className={hideIsolated ? 'toolbar-btn sm-toggle on' : 'toolbar-btn sm-toggle'}
          onClick={() => setHideIsolated((v) => !v)}
          title="Hide components with no dependencies (isolated config/util classes)"
        >
          {hideIsolated ? '✓ ' : ''}Hide isolated
        </button>
        {LAYERS.filter((l) => map.stats.byLayer[l]).map((l) => (
          <button
            key={l}
            type="button"
            className={hidden.has(l) ? 'toolbar-btn sm-toggle' : 'toolbar-btn sm-toggle on'}
            onClick={() => toggleLayer(l)}
            title={`Show/hide ${LAYER_LABELS[l]}`}
          >
            {hidden.has(l) ? '' : '✓ '}{LAYER_LABELS[l]} ({map.stats.byLayer[l]})
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

      <ComponentLegend />

      {selected && (
        <ComponentDetail
          node={selected}
          onClose={() => {
            setSelected(null);
            cyRef.current?.elements().removeClass('faded');
          }}
        />
      )}
    </div>
  );
}

function ComponentLegend() {
  return (
    <div className="flowchart-legend">
      {LAYERS.map((l) => {
        const s = LAYER_STYLES[l];
        return (
          <div className="legend-item" key={l}>
            <span className="legend-swatch" style={{ background: s.color }}>{s.glyph}</span>
            {LAYER_LABELS[l]}
          </div>
        );
      })}
    </div>
  );
}

function ComponentDetail({ node, onClose }: { readonly node: CmpNode; readonly onClose: () => void }) {
  const s = layerStyle(node.layer);
  return (
    <div className="detail-panel">
      <div className="detail-header">
        <span className="detail-role" style={{ background: s.color }}>{s.glyph} {node.layer}</span>
        <button type="button" className="detail-close" onClick={onClose} aria-label="Close">✕</button>
      </div>
      <div className="detail-section">
        <div className="detail-key">Class</div>
        <div className="detail-value">{node.name}</div>
      </div>
      {node.pkg && (
        <div className="detail-section">
          <div className="detail-key">Package</div>
          <div className="detail-value detail-path">{node.pkg}</div>
        </div>
      )}
      <div className="detail-section">
        <div className="detail-key">Dependencies</div>
        <div className="detail-value">{node.fanOut} out · {node.fanIn} in · {node.methods} methods</div>
      </div>
      {node.endpoints.length > 0 && (
        <div className="detail-section">
          <div className="detail-key">Endpoints ({node.endpoints.length})</div>
          <div className="detail-value detail-path">{node.endpoints.join('\n')}</div>
        </div>
      )}
      {node.file && (
        <div className="detail-section">
          <div className="detail-key">Location</div>
          <div className="detail-value detail-path">{node.file}:{node.startLine}</div>
        </div>
      )}
    </div>
  );
}
