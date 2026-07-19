import { useEffect, useMemo, useRef, useState } from 'react';
import mermaid from 'mermaid';
import type { Architecture, ArchNode } from '../types';

mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
  flowchart: { useMaxWidth: false, htmlLabels: true, nodeSpacing: 45, rankSpacing: 70 },
  sequence: { useMaxWidth: false, showSequenceNumbers: false },
});

let renderSeq = 0;

interface ArchitectureViewProps {
  arch: Architecture | null;
  loading: boolean;
  error?: string;
  filename?: string;
}

const GLYPH: Record<string, string> = {
  controller: '🔵',
  service: '⚙',
  repository: '🗄',
  client: '🌐',
  component: '🧩',
  config: '🔧',
};

// classDef colours per node kind (fill / border / text).
const CLASS_DEFS = [
  'classDef controller fill:#2563eb,stroke:#1e40af,color:#fff;',
  'classDef service fill:#10b981,stroke:#047857,color:#fff;',
  'classDef repository fill:#f59e0b,stroke:#b45309,color:#fff;',
  'classDef client fill:#db2777,stroke:#9d174d,color:#fff;',
  'classDef component fill:#8b5cf6,stroke:#6d28d9,color:#fff;',
  'classDef config fill:#64748b,stroke:#475569,color:#fff;',
  'classDef datastore fill:#ccfbf1,stroke:#0d9488,color:#134e4a;',
  'classDef topic fill:#d1fae5,stroke:#059669,color:#064e3b;',
  'classDef external fill:#f1f5f9,stroke:#94a3b8,color:#334155,stroke-dasharray:4 3;',
];

function esc(s: string): string {
  return s.replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/** Build a Mermaid flowchart (grouped subgraphs) from the architecture data. */
function toMermaid(arch: Architecture, filters: Filters): string {
  const show = (n: ArchNode) =>
    n.type === 'layer' ||
    (n.type === 'datastore' && filters.datastore) ||
    (n.type === 'topic' && filters.topic) ||
    (n.type === 'external' && filters.external);

  const nodes = arch.nodes.filter(show);
  const idOf = new Map<string, string>();
  let d = 0;
  let t = 0;
  let e = 0;
  for (const n of nodes) {
    if (n.type === 'layer') idOf.set(n.id, `L_${n.subtype}`);
    else if (n.type === 'datastore') idOf.set(n.id, `DS${d++}`);
    else if (n.type === 'topic') idOf.set(n.id, `T${t++}`);
    else idOf.set(n.id, `E${e++}`);
  }

  const lines: string[] = ['flowchart LR'];

  const layers = nodes.filter((n) => n.type === 'layer');
  lines.push(`  subgraph APP["${esc(arch.project)}"]`);
  for (const l of layers) {
    lines.push(`    ${idOf.get(l.id)}["${GLYPH[l.subtype] ?? ''} ${esc(l.label)}<br/><small>${l.count} classes</small>"]`);
  }
  lines.push('  end');

  const group = (title: string, type: string, wrap: (id: string, label: string) => string) => {
    const ns = nodes.filter((n) => n.type === type);
    if (ns.length === 0) return;
    lines.push(`  subgraph ${type.toUpperCase()}["${title}"]`);
    for (const n of ns) lines.push(`    ${wrap(idOf.get(n.id) as string, esc(n.label))}`);
    lines.push('  end');
  };
  group('Data Stores', 'datastore', (id, label) => `${id}[("${label}")]`);
  group('Kafka Topics', 'topic', (id, label) => `${id}{{"${label}"}}`);
  group('External Systems', 'external', (id, label) => `${id}["${label}"]`);

  for (const edge of arch.edges) {
    const s = idOf.get(edge.source);
    const tg = idOf.get(edge.target);
    if (!s || !tg) continue;
    if (edge.kind === 'depends' && edge.weight > 1) {
      lines.push(`  ${s} -->|"×${edge.weight}"| ${tg}`);
    } else {
      lines.push(`  ${s} --> ${tg}`);
    }
  }

  lines.push(...CLASS_DEFS.map((c) => `  ${c}`));
  for (const n of nodes) {
    lines.push(`  class ${idOf.get(n.id)} ${n.type === 'layer' ? n.subtype : n.type};`);
  }
  return lines.join('\n');
}

interface Filters {
  datastore: boolean;
  topic: boolean;
  external: boolean;
}

function triggerDownload(href: string, name: string): void {
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
}
function sanitize(name: string): string {
  return (name || 'architecture').replace(/[^\w.-]+/g, '_').replace(/^_+|_+$/g, '') || 'architecture';
}

export default function ArchitectureView({ arch, loading, error, filename = 'architecture' }: ArchitectureViewProps) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const [renderError, setRenderError] = useState<string | undefined>(undefined);
  const [filters, setFilters] = useState<Filters>({ datastore: true, topic: true, external: true });
  const [zoom, setZoom] = useState(1);
  const [copied, setCopied] = useState(false);

  const mermaidText = useMemo(() => (arch ? toMermaid(arch, filters) : null), [arch, filters]);

  useEffect(() => {
    let cancelled = false;
    setRenderError(undefined);
    const host = hostRef.current;
    if (!host || !mermaidText) {
      if (host) host.innerHTML = '';
      return;
    }
    const id = `arch-${renderSeq++}`;
    mermaid
      .render(id, mermaidText)
      .then(({ svg }) => {
        if (!cancelled && hostRef.current) hostRef.current.innerHTML = svg;
      })
      .catch((err: unknown) => {
        if (!cancelled) setRenderError(err instanceof Error ? err.message : String(err));
      });
    return () => {
      cancelled = true;
    };
  }, [mermaidText]);

  const currentSvg = (): string | null => {
    const svgEl = hostRef.current?.querySelector('svg');
    return svgEl ? new XMLSerializer().serializeToString(svgEl) : null;
  };

  const downloadSvg = () => {
    const svg = currentSvg();
    if (!svg) return;
    const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }));
    triggerDownload(url, `${sanitize(filename)}.svg`);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  const downloadPng = () => {
    const svgEl = hostRef.current?.querySelector('svg');
    if (!svgEl) return;
    const svg = new XMLSerializer().serializeToString(svgEl);
    const rect = svgEl.getBoundingClientRect();
    const scale = 2;
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, rect.width * scale);
      canvas.height = Math.max(1, rect.height * scale);
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      triggerDownload(canvas.toDataURL('image/png'), `${sanitize(filename)}.png`);
    };
    img.src = `data:image/svg+xml;base64,${btoa(unescape(encodeURIComponent(svg)))}`;
  };

  const copyMermaid = async () => {
    if (!mermaidText) return;
    try {
      await navigator.clipboard.writeText(mermaidText);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard unavailable */
    }
  };

  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else wrapRef.current?.requestFullscreen?.();
  };

  if (loading) return <div className="seq-placeholder">Building architecture view…</div>;
  if (error) {
    return (
      <div className="seq-placeholder seq-error">
        <div>Could not build the architecture view.</div>
        <div className="detail-path">{error}</div>
      </div>
    );
  }
  if (!arch) return <div className="seq-placeholder">Analyze a single service to see its architecture.</div>;
  if (arch.nodes.length === 0) return <div className="seq-placeholder">No architectural layers detected.</div>;

  return (
    <div className="flowchart-wrap" ref={wrapRef}>
      <div className="sm-statbar">
        <span><strong>{arch.stats.layers}</strong> layers</span>
        <span><strong>{arch.stats.datastores}</strong> datastores</span>
        <span><strong>{arch.stats.topics}</strong> topics</span>
        <span><strong>{arch.stats.externals}</strong> external</span>
      </div>

      <div className="flowchart-toolbar">
        {(['datastore', 'topic', 'external'] as const).map((k) => (
          <button
            key={k}
            type="button"
            className={filters[k] ? 'toolbar-btn sm-toggle on' : 'toolbar-btn sm-toggle'}
            onClick={() => setFilters((f) => ({ ...f, [k]: !f[k] }))}
          >
            {filters[k] ? '✓ ' : ''}{k[0].toUpperCase() + k.slice(1)}s
          </button>
        ))}
        <button type="button" className="toolbar-btn" onClick={copyMermaid}>{copied ? 'Copied!' : 'Copy Mermaid'}</button>
        <button type="button" className="toolbar-btn" onClick={downloadSvg}>SVG</button>
        <button type="button" className="toolbar-btn" onClick={downloadPng}>PNG</button>
      </div>

      {renderError ? (
        <div className="seq-placeholder seq-error">
          Could not render diagram: {renderError}
          <pre className="seq-source">{mermaidText}</pre>
        </div>
      ) : (
        <div className="seq-scroll">
          <div
            className="seq-host"
            ref={hostRef}
            style={{ transform: `scale(${zoom})`, transformOrigin: 'top left' }}
          />
        </div>
      )}

      <div className="zoom-controls">
        <button type="button" onClick={() => setZoom((z) => Math.min(3, z * 1.25))} title="Zoom in">＋</button>
        <button type="button" onClick={() => setZoom((z) => Math.max(0.2, z * 0.8))} title="Zoom out">－</button>
        <button type="button" onClick={() => setZoom(1)} title="Reset zoom">1:1</button>
        <button type="button" onClick={toggleFullscreen} title="Fullscreen">⛶</button>
      </div>
    </div>
  );
}
