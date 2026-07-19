import { useState } from 'react';
import type { KeyboardEvent } from 'react';

interface DirectoryInputProps {
  onAnalyze: (path: string) => void;
  loading: boolean;
  initialValue?: string;
}

export default function DirectoryInput({ onAnalyze, loading, initialValue = '' }: DirectoryInputProps) {
  const [path, setPath] = useState(initialValue);

  const submit = () => {
    const trimmed = path.trim();
    if (trimmed.length > 0 && !loading) {
      onAnalyze(trimmed);
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
      <button className="button" onClick={submit} disabled={loading || path.trim().length === 0}>
        {loading ? 'Analyzing…' : 'Analyze'}
      </button>
    </div>
  );
}
