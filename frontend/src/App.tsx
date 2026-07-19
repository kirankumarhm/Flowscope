import { useEffect, useMemo, useRef, useState } from 'react';
import {
  analyze,
  fetchFlow,
  fetchSequence,
  fetchServiceMap,
  fetchComponentMap,
  fetchArchitecture,
} from './api';
import type { Architecture, CFG, ComponentMap, Graph, IRNode, ServiceMap } from './types';
import DirectoryInput from './components/DirectoryInput';
import StatusBar from './components/StatusBar';
import FunctionPicker from './components/FunctionPicker';
import type { FunctionOption } from './components/FunctionPicker';
import FlowChartView from './components/FlowChartView';
import SequenceDiagramView from './components/SequenceDiagramView';
import ServiceMapView from './components/ServiceMapView';
import ComponentView from './components/ComponentView';
import ArchitectureView from './components/ArchitectureView';

// node.file is already relative to the project root; keep as-is, just guard nulls.
function relativePath(file: string): string {
  return file || '';
}

// Entry points: REST endpoints (web apps) and main() methods (non-web apps).
function entryLabelOf(node: IRNode): string | null {
  const m = node.meta;
  if (!m) return null;
  if (typeof m.endpoint === 'string' && typeof m.httpMethod === 'string') {
    return `${m.httpMethod}  ${m.endpoint}`;
  }
  if (m.entryPoint === 'main') {
    return `main()  ·  ${node.file}`;
  }
  return null;
}

type View = 'flow' | 'sequence' | 'servicemap' | 'component' | 'architecture';

// Parse deep-link params once (?path=&fn=&depth=&view=).
function readInitialParams() {
  const p = new URLSearchParams(window.location.search);
  const depth = Number(p.get('depth'));
  const v = p.get('view');
  const view: View =
    v === 'sequence' || v === 'servicemap' || v === 'component' || v === 'architecture'
      ? v
      : 'flow';
  return {
    path: p.get('path') ?? '',
    fn: p.get('fn') ?? '',
    depth: Number.isFinite(depth) && depth >= 1 && depth <= 8 ? depth : 4,
    view,
  };
}

export default function App() {
  const initial = useMemo(readInitialParams, []);

  const [graph, setGraph] = useState<Graph | null>(null);
  const [analyzedPath, setAnalyzedPath] = useState<string>('');
  const [error, setError] = useState<string | undefined>(undefined);
  const [loading, setLoading] = useState(false);

  const [selectedFunctionId, setSelectedFunctionId] = useState<string | null>(null);
  const [depth, setDepth] = useState<number>(initial.depth);
  const [view, setView] = useState<View>(initial.view);
  const [flowCfg, setFlowCfg] = useState<CFG | null>(null);
  const [flowLoading, setFlowLoading] = useState(false);
  const [flowError, setFlowError] = useState<string | undefined>(undefined);

  const [seqText, setSeqText] = useState<string | null>(null);
  const [seqLoading, setSeqLoading] = useState(false);
  const [seqError, setSeqError] = useState<string | undefined>(undefined);

  const [serviceMap, setServiceMap] = useState<ServiceMap | null>(null);
  const [smLoading, setSmLoading] = useState(false);
  const [smError, setSmError] = useState<string | undefined>(undefined);

  const [componentMap, setComponentMap] = useState<ComponentMap | null>(null);
  const [cmpLoading, setCmpLoading] = useState(false);
  const [cmpError, setCmpError] = useState<string | undefined>(undefined);

  const [architecture, setArchitecture] = useState<Architecture | null>(null);
  const [archLoading, setArchLoading] = useState(false);
  const [archError, setArchError] = useState<string | undefined>(undefined);

  // A pending function id from the deep link, applied on the first analyze.
  const pendingFn = useRef<string>(initial.fn);

  const handleAnalyze = async (path: string) => {
    setLoading(true);
    setError(undefined);
    // Set the path up front so the Service Map (workspace-wide, independent of the
    // per-app graph) can load even if the single-app analysis below is slow.
    setAnalyzedPath(path);
    try {
      const result = await analyze(path);
      setGraph(result);
      // Deep-linked function wins on first load; otherwise first entry point.
      const wanted = pendingFn.current;
      pendingFn.current = '';
      const exists = wanted && (result.cfgs[wanted] || result.nodes.some((n) => n.id === wanted));
      const firstEntry = result.nodes.find((n) => entryLabelOf(n));
      const firstId = firstEntry?.id ?? Object.keys(result.cfgs)[0] ?? null;
      setSelectedFunctionId(exists ? wanted : firstId);
    } catch (err) {
      // Retain previous graph/selection on error; just surface the message.
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  };

  // On first load, auto-analyze if a path was deep-linked.
  useEffect(() => {
    if (initial.path) {
      handleAnalyze(initial.path);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Keep the URL in sync so any view is shareable/bookmarkable.
  useEffect(() => {
    if (!analyzedPath) return;
    const p = new URLSearchParams();
    p.set('path', analyzedPath);
    if (selectedFunctionId) p.set('fn', selectedFunctionId);
    p.set('depth', String(depth));
    p.set('view', view);
    window.history.replaceState(null, '', `?${p.toString()}`);
  }, [analyzedPath, selectedFunctionId, depth, view]);

  // Fetch the sequence diagram when the Sequence view is active.
  useEffect(() => {
    if (view !== 'sequence' || !analyzedPath || !selectedFunctionId) {
      return;
    }
    let cancelled = false;
    setSeqLoading(true);
    setSeqError(undefined);
    fetchSequence(analyzedPath, selectedFunctionId, depth)
      .then((s) => {
        if (!cancelled) setSeqText(s.mermaidText);
      })
      .catch((err) => {
        if (!cancelled) {
          setSeqError(err instanceof Error ? err.message : String(err));
          setSeqText(null);
        }
      })
      .finally(() => {
        if (!cancelled) setSeqLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [view, analyzedPath, selectedFunctionId, depth]);

  // Fetch the whole-workspace service map when that view is active. It is
  // project-wide (independent of the selected function) and cached server-side.
  useEffect(() => {
    if (view !== 'servicemap' || !analyzedPath) {
      return;
    }
    let cancelled = false;
    setSmLoading(true);
    setSmError(undefined);
    fetchServiceMap(analyzedPath)
      .then((m) => {
        if (!cancelled) setServiceMap(m);
      })
      .catch((err) => {
        if (!cancelled) {
          setSmError(err instanceof Error ? err.message : String(err));
          setServiceMap(null);
        }
      })
      .finally(() => {
        if (!cancelled) setSmLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [view, analyzedPath]);

  // Fetch the intra-app component map when that view is active (per-app, cached).
  useEffect(() => {
    if (view !== 'component' || !analyzedPath) {
      return;
    }
    let cancelled = false;
    setCmpLoading(true);
    setCmpError(undefined);
    fetchComponentMap(analyzedPath)
      .then((m) => {
        if (!cancelled) setComponentMap(m);
      })
      .catch((err) => {
        if (!cancelled) {
          setCmpError(err instanceof Error ? err.message : String(err));
          setComponentMap(null);
        }
      })
      .finally(() => {
        if (!cancelled) setCmpLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [view, analyzedPath]);

  // Fetch the high-level architecture view when that view is active (per-app, cached).
  useEffect(() => {
    if (view !== 'architecture' || !analyzedPath) {
      return;
    }
    let cancelled = false;
    setArchLoading(true);
    setArchError(undefined);
    fetchArchitecture(analyzedPath)
      .then((a) => {
        if (!cancelled) setArchitecture(a);
      })
      .catch((err) => {
        if (!cancelled) {
          setArchError(err instanceof Error ? err.message : String(err));
          setArchitecture(null);
        }
      })
      .finally(() => {
        if (!cancelled) setArchLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [view, analyzedPath]);

  // Entry points (REST endpoints + main methods) — the primary flow-chart roots.
  const endpoints: FunctionOption[] = useMemo(() => {
    if (!graph) return [];
    return graph.nodes
      .map((n) => {
        const label = entryLabelOf(n);
        return label ? { id: n.id, label } : null;
      })
      .filter((x): x is FunctionOption => x !== null);
  }, [graph]);

  const endpointIds = useMemo(
    () => new Set(endpoints.map((e) => e.id)),
    [endpoints]
  );

  // Step 1: identify web vs standalone from the detected entry points.
  const appType = useMemo(() => {
    if (!graph) return undefined;
    const hasEndpoint = graph.nodes.some(
      (n) => n.meta && typeof n.meta.endpoint === 'string'
    );
    if (hasEndpoint) return 'Web application';
    const hasMain = graph.nodes.some((n) => n.meta && n.meta.entryPoint === 'main');
    if (hasMain) return 'Standalone application';
    return 'Library / no entry point';
  }, [graph]);

  // Every function with a CFG (excluding endpoints, which are listed above).
  const functions: FunctionOption[] = useMemo(() => {
    if (!graph) return [];
    const nodeById = new Map(graph.nodes.map((n) => [n.id, n]));
    return Object.values(graph.cfgs)
      .filter((cfg) => !endpointIds.has(cfg.functionId))
      .map((cfg) => {
        const node = nodeById.get(cfg.functionId);
        if (node) {
          return {
            id: cfg.functionId,
            label: `${node.name} (${relativePath(node.file)}:${node.startLine})`,
          };
        }
        return { id: cfg.functionId, label: cfg.functionId };
      });
  }, [graph, endpointIds]);

  // Fetch the inter-procedural flow whenever the selection changes.
  useEffect(() => {
    if (!analyzedPath || !selectedFunctionId) {
      setFlowCfg(null);
      return;
    }
    let cancelled = false;
    setFlowLoading(true);
    setFlowError(undefined);
    fetchFlow(analyzedPath, selectedFunctionId, depth)
      .then((cfg) => {
        if (!cancelled) setFlowCfg(cfg);
      })
      .catch((err) => {
        if (!cancelled) {
          setFlowError(err instanceof Error ? err.message : String(err));
          setFlowCfg(null); // fall back to the intra-procedural CFG below
        }
      })
      .finally(() => {
        if (!cancelled) setFlowLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [analyzedPath, selectedFunctionId, depth]);

  // Prefer the inline flow; fall back to the per-function CFG from /api/analyze.
  const displayedCfg: CFG | null =
    flowCfg ??
    (graph && selectedFunctionId ? graph.cfgs[selectedFunctionId] ?? null : null);

  const totalRoots = endpoints.length + functions.length;

  // Base name for exported files, derived from the selected entry/function.
  const exportName = useMemo(() => {
    if (!graph || !selectedFunctionId) return 'flowchart';
    const node = graph.nodes.find((n) => n.id === selectedFunctionId);
    const label =
      node && node.meta && typeof node.meta.endpoint === 'string'
        ? `${node.meta.httpMethod}_${node.meta.endpoint}`
        : node?.name ?? 'flowchart';
    return `${graph.project}_${label}`;
  }, [graph, selectedFunctionId]);

  return (
    <div className="app">
      <header className="app-header">
        <div className="app-title">
          <span className="app-logo">FlowScope</span>
          <span className="app-subtitle">Web</span>
        </div>
        <DirectoryInput onAnalyze={handleAnalyze} loading={loading} initialValue={initial.path} />
      </header>

      <StatusBar
        project={graph?.project}
        languages={graph?.languages}
        modules={graph?.modules}
        functionCount={graph ? totalRoots : undefined}
        appType={appType}
        error={error}
      />

      <div className="app-body">
        <aside className="app-sidebar">
          {view === 'servicemap' ? (
            <div className="sm-sidebar-note">
              <div className="function-picker-label">Service Map</div>
              <p>
                Whole-workspace view. Point the directory above at the folder that
                <strong> contains your services</strong> to map how they talk to each
                other — Kafka topics, REST calls, datastores, and external systems.
              </p>
            </div>
          ) : view === 'component' ? (
            <div className="sm-sidebar-note">
              <div className="function-picker-label">Component</div>
              <p>
                Whole-app view of the analyzed service — its Spring beans
                (controllers, services, repositories, …) and how they depend on each
                other via injection and calls. Use the layer toggles above the canvas
                to focus.
              </p>
            </div>
          ) : view === 'architecture' ? (
            <div className="sm-sidebar-note">
              <div className="function-picker-label">Architecture</div>
              <p>
                High-level view of the analyzed service — its layers (controllers →
                services → repositories → clients) as aggregate boxes, plus the
                datastores, Kafka topics, and external systems it depends on.
              </p>
            </div>
          ) : (
            <>
              <FunctionPicker
                endpoints={endpoints}
                functions={functions}
                selectedId={selectedFunctionId}
                onSelect={setSelectedFunctionId}
              />
              {graph && (
                <div className="depth-control">
                  <label htmlFor="depth-range">
                    Call depth <span className="depth-value">{depth}</span>
                  </label>
                  <input
                    id="depth-range"
                    type="range"
                    min={1}
                    max={8}
                    value={depth}
                    onChange={(e) => setDepth(Number(e.target.value))}
                  />
                  <span className="depth-hint">How many call levels to inline</span>
                </div>
              )}
            </>
          )}
        </aside>
        <main className="app-main">
          <div className="view-tabs">
            <button
              type="button"
              className={view === 'flow' ? 'view-tab active' : 'view-tab'}
              onClick={() => setView('flow')}
            >
              Flow Chart
            </button>
            <button
              type="button"
              className={view === 'sequence' ? 'view-tab active' : 'view-tab'}
              onClick={() => setView('sequence')}
            >
              Sequence
            </button>
            <button
              type="button"
              className={view === 'component' ? 'view-tab active' : 'view-tab'}
              onClick={() => setView('component')}
            >
              Component
            </button>
            <button
              type="button"
              className={view === 'architecture' ? 'view-tab active' : 'view-tab'}
              onClick={() => setView('architecture')}
            >
              Architecture
            </button>
            <button
              type="button"
              className={view === 'servicemap' ? 'view-tab active' : 'view-tab'}
              onClick={() => setView('servicemap')}
            >
              Service Map
            </button>
          </div>
          <div className="view-body">
            {view === 'flow' && flowLoading && <div className="flow-status">Building flow…</div>}
            {view === 'flow' && flowError && (
              <div className="flow-status flow-status-warn">
                Inline flow unavailable ({flowError}); showing single-method view.
              </div>
            )}
            {view === 'flow' && (
              <FlowChartView cfg={displayedCfg} filename={exportName} sourceRoot={analyzedPath} />
            )}
            {view === 'sequence' && (
              <SequenceDiagramView mermaidText={seqText} loading={seqLoading} error={seqError} />
            )}
            {view === 'component' && (
              <ComponentView
                map={componentMap}
                loading={cmpLoading}
                error={cmpError}
                filename={graph ? `${graph.project}_components` : 'components'}
              />
            )}
            {view === 'architecture' && (
              <ArchitectureView
                arch={architecture}
                loading={archLoading}
                error={archError}
                filename={graph ? `${graph.project}_architecture` : 'architecture'}
              />
            )}
            {view === 'servicemap' && (
              <ServiceMapView
                map={serviceMap}
                loading={smLoading}
                error={smError}
                filename={graph ? `${graph.project}_service-map` : 'service-map'}
              />
            )}
          </div>
        </main>
      </div>
    </div>
  );
}
