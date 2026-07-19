import { useState } from 'react';

export interface SelectedNode {
  id: string;
  kind: string;
  role: string;
  roleLabel: string;
  roleColor: string;
  text: string; // full (untruncated) code/label
  file?: string; // module-relative path
  line?: number;
  group?: string; // enclosing inlined-method group label
}

interface NodeDetailPanelProps {
  node: SelectedNode;
  sourceRoot: string; // absolute path that was analyzed (to resolve absolute file paths)
  onClose: () => void;
}

/** Parent directory of the analyzed root — module-relative paths hang off it. */
function parentDir(root: string): string {
  return root.replace(/[\\/]+$/, '').replace(/[\\/][^\\/]+$/, '');
}

export default function NodeDetailPanel({ node, sourceRoot, onClose }: NodeDetailPanelProps) {
  const [copied, setCopied] = useState(false);

  const absPath = node.file && sourceRoot ? `${parentDir(sourceRoot)}/${node.file}` : node.file ?? '';
  const withLine = node.line ? `${absPath}:${node.line}` : absPath;
  const vscodeUrl = absPath ? `vscode://file/${absPath}${node.line ? `:${node.line}` : ''}` : undefined;

  const copyPath = async () => {
    try {
      await navigator.clipboard.writeText(withLine);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* clipboard may be unavailable */
    }
  };

  return (
    <div className="detail-panel">
      <div className="detail-header">
        <span className="detail-role" style={{ background: node.roleColor }}>
          {node.roleLabel}
        </span>
        <button type="button" className="detail-close" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>

      <div className="detail-section">
        <div className="detail-key">Code</div>
        <pre className="detail-code">{node.text}</pre>
      </div>

      {node.group && (
        <div className="detail-section">
          <div className="detail-key">In method</div>
          <div className="detail-value">{node.group}</div>
        </div>
      )}

      {node.file && (
        <div className="detail-section">
          <div className="detail-key">Location</div>
          <div className="detail-value detail-path">
            {node.file}
            {node.line ? `:${node.line}` : ''}
          </div>
          <div className="detail-actions">
            <button type="button" className="toolbar-btn" onClick={copyPath}>
              {copied ? 'Copied!' : 'Copy path'}
            </button>
            {vscodeUrl && (
              <a className="toolbar-btn detail-link" href={vscodeUrl}>
                Open in VS Code
              </a>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
