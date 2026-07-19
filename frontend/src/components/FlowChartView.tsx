import { useEffect, useRef, useState } from 'react';
import cytoscape from 'cytoscape';
import type { Core, ElementDefinition } from 'cytoscape';
import dagre from 'cytoscape-dagre';
import svg from 'cytoscape-svg';
import expandCollapse from 'cytoscape-expand-collapse';
import { jsPDF } from 'jspdf';
import type { CFG } from '../types';
import NodeDetailPanel from './NodeDetailPanel';
import type { SelectedNode } from './NodeDetailPanel';

// Register cytoscape extensions once (dagre layout, svg export, group collapse).
cytoscape.use(dagre);
cytoscape.use(svg);
cytoscape.use(expandCollapse);

const AUTO_COLLAPSE_THRESHOLD = 40; // collapse inlined groups by default above this

interface FlowChartViewProps {
  cfg: CFG | null;
  filename?: string; // base name for exported files
  sourceRoot?: string; // analyzed absolute path (for resolving node file locations)
}

type ExportFormat = 'png' | 'jpg' | 'svg' | 'pdf';

const EXPORT_SCALE = 2; // crisp raster output
const EXPORT_BG = '#ffffff';

function triggerDownload(href: string, filename: string): void {
  const a = document.createElement('a');
  a.href = href;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
}

function sanitize(name: string): string {
  return (name || 'flowchart').replace(/[^\w.-]+/g, '_').replace(/^_+|_+$/g, '') || 'flowchart';
}

/**
 * Semantic role of a node — what it *does* — derived from its CFG kind and, for
 * call nodes, the shape of the statement. Drives color, shape, and glyph so a big
 * flow is readable at a glance.
 */
type Role =
  | 'endpoint' | 'entry' | 'exit'
  | 'branch' | 'loop' | 'return' | 'throw' | 'merge' | 'statement'
  | 'db' | 'external' | 'call';

const DB_RE =
  /(repository|\brepo\b|\.save|\.find|\.delete|\.query|\.insert|\.update|entitymanager|jdbc|\.persist|\bdao\b|criteria|jpaquery)/i;
const EXTERNAL_RE =
  /(http|resttemplate|webclient|\bfeign\b|\.exchange\b|getforobject|postforobject|\.uri\(|httpclient|okhttp|\brestclient\b|\bapiclient\b|\.execute\()/i;
const VERB_RE = /\b(GET|POST|PUT|DELETE|PATCH|ANY)\b/;

function classifyRole(kind: string, label: string): Role {
  if (kind === 'entry') return VERB_RE.test(label) ? 'endpoint' : 'entry';
  if (kind === 'call') {
    if (DB_RE.test(label)) return 'db';
    if (EXTERNAL_RE.test(label)) return 'external';
    return 'call';
  }
  return (kind as Role);
}

interface RoleStyle {
  color: string; // fill
  shape: string;
  glyph: string; // small leading icon
  text?: string; // label text color (default white)
}

const ROLE_STYLES: Record<Role, RoleStyle> = {
  endpoint: { color: '#2563eb', shape: 'round-rectangle', glyph: '🔵' },
  entry: { color: '#2563eb', shape: 'round-rectangle', glyph: '▶' },
  exit: { color: '#475569', shape: 'round-rectangle', glyph: '■' },
  branch: { color: '#f59e0b', shape: 'diamond', glyph: '❓' },
  loop: { color: '#8b5cf6', shape: 'hexagon', glyph: '🔁' },
  return: { color: '#059669', shape: 'round-rectangle', glyph: '⏎' },
  throw: { color: '#dc2626', shape: 'round-rectangle', glyph: '⚠' },
  merge: { color: '#e2e8f0', shape: 'ellipse', glyph: '', text: '#475569' }, // label already carries ↵
  statement: { color: '#64748b', shape: 'rectangle', glyph: '' },
  db: { color: '#0d9488', shape: 'barrel', glyph: '🗄' },
  external: { color: '#7c3aed', shape: 'rectangle', glyph: '🌐' },
  call: { color: '#3b82f6', shape: 'rectangle', glyph: '⚙' },
};

function roleStyle(role: string): RoleStyle {
  return ROLE_STYLES[role as Role] ?? ROLE_STYLES.call;
}

// The style array is intentionally loosely typed (any[]): the exact shape/color
// literal unions and function-mapper signatures vary across @types/cytoscape
// releases (and `Stylesheet` is not re-exported in some versions). cytoscape
// accepts this JSON, including function mappers, at runtime.
const stylesheet: any[] = [
  {
    selector: 'node',
    style: {
      'background-color': (ele: cytoscape.NodeSingular) => roleStyle(ele.data('role')).color,
      shape: (ele: cytoscape.NodeSingular) => roleStyle(ele.data('role')).shape,
      color: (ele: cytoscape.NodeSingular) => roleStyle(ele.data('role')).text ?? '#ffffff',
      label: 'data(display)',
      'font-family': 'Inter, system-ui, sans-serif',
      'font-size': '12px',
      'font-weight': 500,
      'text-valign': 'center',
      'text-halign': 'center',
      'text-wrap': 'wrap',
      'text-max-width': '170px',
      width: 'label',
      height: 'label',
      padding: '12px',
      'border-width': 1,
      'border-color': 'rgba(15,23,42,0.12)',
      'corner-radius': '12px',
    },
  },
  {
    // Return-join markers are structural noise — keep them small and quiet.
    selector: 'node[role = "merge"]',
    style: { 'font-size': '10px', padding: '6px', 'border-width': 0 },
  },
  {
    // Compound container for an inlined method (expanded): labeled dashed box.
    selector: ':parent',
    style: {
      'background-color': '#eef2ff',
      'background-opacity': 0.55,
      'border-width': 1.5,
      'border-color': '#a5b4fc',
      'border-style': 'dashed',
      shape: 'round-rectangle',
      label: 'data(label)',
      'text-valign': 'top',
      'text-halign': 'center',
      'font-size': '11px',
      'font-weight': 700,
      color: '#4338ca',
      'text-margin-y': 4,
      padding: '16px',
    },
  },
  {
    // Collapsed inlined method: a single indigo pill you can click to expand.
    selector: 'node.cy-expand-collapse-collapsed-node',
    style: {
      'background-color': '#6366f1',
      'background-opacity': 1,
      'border-style': 'solid',
      'border-width': 1,
      'border-color': '#4338ca',
      shape: 'round-rectangle',
      label: 'data(label)',
      color: '#ffffff',
      'font-weight': 600,
      'text-valign': 'center',
      padding: '12px',
    },
  },
  {
    selector: 'edge',
    style: {
      width: 1.5,
      'line-color': '#94a3b8',
      'target-arrow-color': '#94a3b8',
      'target-arrow-shape': 'triangle',
      'arrow-scale': 0.9,
      'curve-style': 'taxi',
      'taxi-direction': 'downward',
      'taxi-turn': 20,
      label: 'data(label)',
      'font-family': 'Inter, system-ui, sans-serif',
      'font-size': '10px',
      'font-weight': 600,
      color: '#475569',
      'text-background-color': '#eef2ff',
      'text-background-opacity': 1,
      'text-background-padding': '3px',
      'text-background-shape': 'roundrectangle',
      'text-rotation': 'none',
    },
  },
  {
    // "yes"/"no" branch outcomes get intuitive green/red labels.
    selector: 'edge[label = "yes"]',
    style: { color: '#047857', 'text-background-color': '#ecfdf5' },
  },
  {
    selector: 'edge[label = "no"]',
    style: { color: '#b91c1c', 'text-background-color': '#fef2f2' },
  },
  {
    selector: 'node:selected',
    style: { 'border-width': 3, 'border-color': '#2563eb' },
  },
];

const ROLE_LABELS: Record<string, string> = {
  endpoint: 'Entry / endpoint',
  entry: 'Entry',
  exit: 'Exit',
  branch: 'Decision',
  loop: 'Loop',
  return: 'Return',
  throw: 'Throw / error',
  merge: 'Return point',
  statement: 'Statement',
  db: 'Database call',
  external: 'External API call',
  call: 'Service call',
};

export default function FlowChartView({
  cfg,
  filename = 'flowchart',
  sourceRoot = '',
}: FlowChartViewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const tooltipRef = useRef<HTMLDivElement | null>(null);
  const cyRef = useRef<Core | null>(null);
  // cytoscape-expand-collapse API handle (untyped extension).
  const ecRef = useRef<{ collapseAll: () => void; expandAll: () => void } | null>(null);
  // Minimap elements + cached graph bounding box.
  const minimapImgRef = useRef<HTMLImageElement | null>(null);
  const minimapRectRef = useRef<HTMLDivElement | null>(null);
  const bbRef = useRef<{ x1: number; y1: number; w: number; h: number } | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [selected, setSelected] = useState<SelectedNode | null>(null);

  const MM_W = 200;
  const MM_H = 150;

  const zoomBy = (factor: number) => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.zoom({ level: cy.zoom() * factor, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  };
  const fitView = () => cyRef.current?.fit(undefined, 30);
  const resetView = () => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.zoom(1);
    cy.center();
  };
  const toggleFullscreen = () => {
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else {
      wrapRef.current?.requestFullscreen?.();
    }
  };

  // Keyboard shortcuts: Ctrl/Cmd + = / - / 0.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!(e.ctrlKey || e.metaKey)) return;
      if (e.key === '=' || e.key === '+') {
        e.preventDefault();
        zoomBy(1.25);
      } else if (e.key === '-') {
        e.preventDefault();
        zoomBy(0.8);
      } else if (e.key === '0') {
        e.preventDefault();
        fitView();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const groups = cfg?.groups ?? [];
  const hasGroups = groups.length > 0;

  useEffect(() => {
    if (!containerRef.current || !cfg) {
      return;
    }

    const elements: ElementDefinition[] = [
      // Compound parent nodes — one per inlined method group (collapsible).
      ...groups.map((g) => ({
        data: { id: g.id, label: g.label, parent: g.parent ?? undefined, isGroup: true },
      })),
      ...cfg.nodes.map((n) => {
        const role = classifyRole(n.kind, n.title ?? n.label);
        const glyph = roleStyle(role).glyph;
        return {
          data: {
            id: n.id,
            label: n.label,
            display: glyph ? `${glyph}  ${n.label}` : n.label,
            kind: n.kind,
            role,
            parent: n.group ?? undefined, // nest under its inlined-method group
            line: n.line ?? null,
            file: n.file ?? null,
            title: n.title ?? n.label, // full text for hover
          },
        };
      }),
      ...cfg.edges.map((e) => ({
        data: {
          id: `${e.from}->${e.to}`,
          source: e.from,
          target: e.to,
          label: e.label ?? '',
        },
      })),
    ];

    const cy = cytoscape({
      container: containerRef.current,
      elements,
      style: stylesheet,
      wheelSensitivity: 0.2,
      minZoom: 0.1,
      maxZoom: 3,
    });

    cyRef.current = cy;

    const runLayout = () =>
      cy
        .layout({
          name: 'dagre',
          rankDir: 'TB',
          rankSep: 80,
          nodeSep: 60,
          fit: true,
          padding: 30,
        } as unknown as cytoscape.LayoutOptions)
        .run();

    // Collapsible inlined-method groups. For large flows we collapse BEFORE the
    // first layout, so dagre only ever positions the compact (collapsed) graph —
    // laying out the full 500+ node graph up front freezes the browser.
    const big = cfg.nodes.length > AUTO_COLLAPSE_THRESHOLD;
    if (hasGroups) {
      const api = (cy as unknown as {
        expandCollapse: (o: object) => { collapseAll: () => void; expandAll: () => void };
      }).expandCollapse({
        layoutBy: { name: 'dagre', rankDir: 'TB', rankSep: 80, nodeSep: 60, fit: false, animate: false },
        animate: false,
        fisheye: false,
        undoable: false,
        cueEnabled: true,
        expandCollapseCuePosition: 'top-left',
        expandCollapseCueSize: 14,
      });
      ecRef.current = {
        collapseAll: () => {
          api.collapseAll();
          cy.fit(undefined, 30);
        },
        expandAll: () => {
          api.expandAll();
          cy.fit(undefined, 30);
        },
      };
      if (big) {
        api.collapseAll(); // collapse first…
        runLayout(); // …then lay out only the compact graph
      } else {
        runLayout();
      }
    } else {
      runLayout();
    }

    cy.fit(undefined, 30);

    // Minimap overview + viewport rectangle (contain-letterbox mapping).
    const contentRect = () => {
      const bb = bbRef.current;
      if (!bb || !bb.w || !bb.h) return null;
      const graphAspect = bb.w / bb.h;
      const boxAspect = MM_W / MM_H;
      let drawW;
      let drawH;
      if (graphAspect > boxAspect) {
        drawW = MM_W;
        drawH = MM_W / graphAspect;
      } else {
        drawH = MM_H;
        drawW = MM_H * graphAspect;
      }
      return { drawW, drawH, offX: (MM_W - drawW) / 2, offY: (MM_H - drawH) / 2 };
    };
    const updateRect = () => {
      const rect = minimapRectRef.current;
      const bb = bbRef.current;
      const cr = contentRect();
      if (!rect || !bb || !cr) return;
      const ext = cy.extent();
      const sx = cr.drawW / bb.w;
      const sy = cr.drawH / bb.h;
      const l = Math.max(cr.offX, cr.offX + (ext.x1 - bb.x1) * sx);
      const t = Math.max(cr.offY, cr.offY + (ext.y1 - bb.y1) * sy);
      const w = Math.max(4, Math.min((ext.x2 - ext.x1) * sx, cr.offX + cr.drawW - l));
      const h = Math.max(4, Math.min((ext.y2 - ext.y1) * sy, cr.offY + cr.drawH - t));
      rect.style.left = `${l}px`;
      rect.style.top = `${t}px`;
      rect.style.width = `${w}px`;
      rect.style.height = `${h}px`;
    };
    const regenMinimap = () => {
      const img = minimapImgRef.current;
      if (!img) return;
      try {
        const bb = cy.elements().boundingBox();
        bbRef.current = { x1: bb.x1, y1: bb.y1, w: bb.w, h: bb.h };
        img.src = cy.png({ full: true, scale: 0.35, bg: '#ffffff' });
      } catch {
        /* png can fail on empty graphs; ignore */
      }
      updateRect();
    };
    cy.on('layoutstop', regenMinimap);
    cy.on('viewport', updateRect);
    regenMinimap();

    // Hover tooltip showing the full (untruncated) node text.
    const showTip = (evt: cytoscape.EventObject) => {
      const tip = tooltipRef.current;
      if (!tip) return;
      tip.textContent = String(evt.target.data('title') ?? '');
      tip.style.display = 'block';
      moveTip(evt);
    };
    const moveTip = (evt: cytoscape.EventObject) => {
      const tip = tooltipRef.current;
      const oe = evt.originalEvent as MouseEvent | undefined;
      if (!tip || !oe) return;
      tip.style.left = `${oe.clientX + 14}px`;
      tip.style.top = `${oe.clientY + 14}px`;
    };
    const hideTip = () => {
      if (tooltipRef.current) tooltipRef.current.style.display = 'none';
    };
    cy.on('mouseover', 'node', showTip);
    cy.on('mousemove', 'node', moveTip);
    cy.on('mouseout', 'node', hideTip);
    cy.on('pan zoom', hideTip);

    // Click a node → open the detail panel; click empty canvas → close it.
    const groupLabelById = new Map(groups.map((g) => [g.id, g.label]));
    cy.on('tap', 'node', (evt: cytoscape.EventObject) => {
      const d = evt.target.data();
      if (d.isGroup) return; // group containers aren't detail targets
      const role = String(d.role ?? 'call');
      setSelected({
        id: d.id,
        kind: String(d.kind ?? ''),
        role,
        roleLabel: ROLE_LABELS[role] ?? role,
        roleColor: roleStyle(role).color,
        text: String(d.title ?? d.label ?? ''),
        file: d.file ?? undefined,
        line: d.line ?? undefined,
        group: d.parent ? groupLabelById.get(d.parent) : undefined,
      });
    });
    cy.on('tap', (evt: cytoscape.EventObject) => {
      if (evt.target === cy) setSelected(null);
    });

    return () => {
      cy.destroy();
      cyRef.current = null;
      ecRef.current = null;
    };
  }, [cfg]);

  const doExport = (format: ExportFormat) => {
    setMenuOpen(false);
    const cy = cyRef.current;
    if (!cy) return;
    const base = sanitize(filename);

    if (format === 'png') {
      triggerDownload(
        cy.png({ full: true, scale: EXPORT_SCALE, bg: EXPORT_BG }),
        `${base}.png`
      );
      return;
    }
    if (format === 'jpg') {
      triggerDownload(
        cy.jpg({ full: true, scale: EXPORT_SCALE, bg: EXPORT_BG, quality: 0.95 }),
        `${base}.jpg`
      );
      return;
    }
    if (format === 'svg') {
      // cy.svg() is added by the cytoscape-svg extension (not in @types/cytoscape).
      const svgText = (cy as unknown as { svg: (o: object) => string }).svg({
        full: true,
        bg: EXPORT_BG,
        scale: 1,
      });
      const blob = new Blob([svgText], { type: 'image/svg+xml;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      triggerDownload(url, `${base}.svg`);
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return;
    }
    // PDF: render the PNG into a page sized to the image.
    const dataUrl = cy.png({ full: true, scale: EXPORT_SCALE, bg: EXPORT_BG });
    const img = new Image();
    img.onload = () => {
      const w = img.width;
      const h = img.height;
      const pdf = new jsPDF({
        orientation: w >= h ? 'landscape' : 'portrait',
        unit: 'pt',
        format: [w, h],
      });
      pdf.addImage(dataUrl, 'PNG', 0, 0, w, h);
      pdf.save(`${base}.pdf`);
    };
    img.src = dataUrl;
  };

  if (!cfg) {
    return (
      <div className="flowchart-placeholder">
        Select a function to view its flow chart.
      </div>
    );
  }

  return (
    <div className="flowchart-wrap" ref={wrapRef}>
      <div className="flowchart-toolbar">
        {hasGroups && (
          <>
            <button
              type="button"
              className="toolbar-btn"
              onClick={() => ecRef.current?.collapseAll()}
              title="Collapse all inlined method groups"
            >
              Collapse all
            </button>
            <button
              type="button"
              className="toolbar-btn"
              onClick={() => ecRef.current?.expandAll()}
              title="Expand all inlined method groups"
            >
              Expand all
            </button>
          </>
        )}
        <div className="export-menu">
          <button
            type="button"
            className="toolbar-btn"
            onClick={() => setMenuOpen((v) => !v)}
            aria-haspopup="true"
            aria-expanded={menuOpen}
          >
            Export ▾
          </button>
          {menuOpen && (
            <div className="export-dropdown" role="menu">
              <button type="button" onClick={() => doExport('png')}>PNG</button>
              <button type="button" onClick={() => doExport('jpg')}>JPG</button>
              <button type="button" onClick={() => doExport('svg')}>SVG</button>
              <button type="button" onClick={() => doExport('pdf')}>PDF</button>
            </div>
          )}
        </div>
      </div>
      <div className="flowchart-canvas" ref={containerRef} />
      <div className="flowchart-tooltip" ref={tooltipRef} />

      <div className="zoom-controls">
        <button type="button" onClick={() => zoomBy(1.25)} title="Zoom in (Ctrl +)">＋</button>
        <button type="button" onClick={() => zoomBy(0.8)} title="Zoom out (Ctrl −)">－</button>
        <button type="button" onClick={fitView} title="Fit to screen (Ctrl 0)">⤢</button>
        <button type="button" onClick={resetView} title="Reset zoom (100%)">1:1</button>
        <button type="button" onClick={toggleFullscreen} title="Toggle fullscreen">⛶</button>
      </div>

      <div className="minimap" style={{ width: MM_W, height: MM_H }}>
        <img className="minimap-img" ref={minimapImgRef} alt="diagram overview" />
        <div className="minimap-viewport" ref={minimapRectRef} />
      </div>

      <FlowLegend />

      {selected && (
        <NodeDetailPanel
          node={selected}
          sourceRoot={sourceRoot}
          onClose={() => {
            setSelected(null);
            cyRef.current?.$(':selected').unselect();
          }}
        />
      )}
    </div>
  );
}

const LEGEND: { role: Role; label: string }[] = [
  { role: 'endpoint', label: 'Entry / endpoint' },
  { role: 'branch', label: 'Decision' },
  { role: 'loop', label: 'Loop' },
  { role: 'call', label: 'Service call' },
  { role: 'db', label: 'Database' },
  { role: 'external', label: 'External API' },
  { role: 'return', label: 'Return' },
  { role: 'throw', label: 'Throw / error' },
];

function FlowLegend() {
  return (
    <div className="flowchart-legend">
      {LEGEND.map(({ role, label }) => {
        const s = roleStyle(role);
        return (
          <div className="legend-item" key={role}>
            <span className="legend-swatch" style={{ background: s.color }}>
              {s.glyph}
            </span>
            {label}
          </div>
        );
      })}
    </div>
  );
}
