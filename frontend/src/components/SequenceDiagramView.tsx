import { useEffect, useRef, useState } from 'react';
import mermaid from 'mermaid';

mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
  sequence: { useMaxWidth: false, showSequenceNumbers: false },
});

let renderSeq = 0;

interface SequenceDiagramViewProps {
  mermaidText: string | null;
  loading?: boolean;
  error?: string;
}

export default function SequenceDiagramView({ mermaidText, loading, error }: SequenceDiagramViewProps) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const [renderError, setRenderError] = useState<string | undefined>(undefined);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setRenderError(undefined);
    const host = hostRef.current;
    if (!host || !mermaidText) {
      if (host) host.innerHTML = '';
      return;
    }
    const id = `seq-${renderSeq++}`;
    mermaid
      .render(id, mermaidText)
      .then(({ svg }) => {
        if (!cancelled && hostRef.current) hostRef.current.innerHTML = svg;
      })
      .catch((e: unknown) => {
        if (!cancelled) setRenderError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelled = true;
    };
  }, [mermaidText]);

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

  if (error) {
    return <div className="seq-placeholder seq-error">{error}</div>;
  }

  return (
    <div className="seq-wrap">
      <div className="seq-toolbar">
        <button
          type="button"
          className="toolbar-btn"
          onClick={copyMermaid}
          disabled={!mermaidText}
        >
          {copied ? 'Copied!' : 'Copy Mermaid'}
        </button>
      </div>
      {/* Loading is a non-destructive overlay so the diagram host is never
          unmounted mid-render (which would blank it when the text is unchanged). */}
      {loading && <div className="seq-loading">Building sequence…</div>}
      {renderError ? (
        <div className="seq-placeholder seq-error">
          Could not render diagram: {renderError}
          <pre className="seq-source">{mermaidText}</pre>
        </div>
      ) : mermaidText ? (
        <div className="seq-scroll">
          <div className="seq-host" ref={hostRef} />
        </div>
      ) : (
        !loading && (
          <div className="seq-placeholder">Select an entry point to trace its sequence.</div>
        )
      )}
    </div>
  );
}
