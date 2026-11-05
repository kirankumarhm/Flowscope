import { useState } from 'react';
import type { KeyboardEvent } from 'react';

interface DirectoryInputProps {
  onAnalyze: (path: string) => void;
  loading: boolean;
  initialValue?: string;
}

/**
 * Trim the path and drop one pair of matching quotes around it, as added by
 * Finder's "Copy as Pathname" or a shell ('/a b/c' or "/a b/c").
 */
function unquote(raw: string): string {
  const p = raw.trim();
  if (p.length >= 2 && (p[0] === "'" || p[0] === '"') && p[p.length - 1] === p[0]) {
    return p.slice(1, -1).trim();
  }
  return p;
}

export default function DirectoryInput({ onAnalyze, loading, initialValue = '' }: DirectoryInputProps) {
  const [path, setPath] = useState(initialValue);

  const submit = () => {
    const cleaned = unquote(path);
    if (cleaned.length > 0 && !loading) {
      onAnalyze(cleaned);
    }
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      submit();
    }
  };

  return (
    <div className="directory-input">
      <input
        type="text"
        className="text-input"
        placeholder="Enter a source directory path…"
        value={path}
        onChange={(e) => setPath(e.target.value)}
        onKeyDown={handleKeyDown}
        disabled={loading}
        spellCheck={false}
      />
      <button className="button" onClick={submit} disabled={loading || unquote(path).length === 0}>
        {loading ? 'Analyzing…' : 'Analyze'}
      </button>
    </div>
  );
}
