import { useMemo, useState } from 'react';

export interface FunctionOption {
  id: string;
  label: string; // display text
}

interface FunctionPickerProps {
  endpoints: FunctionOption[]; // REST endpoints — the primary flow-chart roots
  functions: FunctionOption[]; // all other functions, searchable
  selectedId: string | null;
  onSelect: (id: string) => void;
}

function sortByLabel(list: FunctionOption[]): FunctionOption[] {
  return [...list].sort((a, b) => a.label.localeCompare(b.label));
}

function applyFilter(list: FunctionOption[], q: string): FunctionOption[] {
  if (q.length === 0) return list;
  return list.filter((f) => f.label.toLowerCase().includes(q));
}

export default function FunctionPicker({
  endpoints,
  functions,
  selectedId,
  onSelect,
}: FunctionPickerProps) {
  const [filter, setFilter] = useState('');
  const q = filter.trim().toLowerCase();

  const shownEndpoints = useMemo(
    () => applyFilter(sortByLabel(endpoints), q),
    [endpoints, q]
  );
  const shownFunctions = useMemo(
    () => applyFilter(sortByLabel(functions), q),
    [functions, q]
  );

  if (endpoints.length === 0 && functions.length === 0) {
    return null;
  }

  const total = endpoints.length + functions.length;
  const shown = shownEndpoints.length + shownFunctions.length;
  const rows = Math.min(Math.max(shown + 2, 4), 18);

  return (
    <div className="function-picker">
      <div className="function-picker-header">
        <label className="function-picker-label" htmlFor="fn-filter">
          Start point
        </label>
        <span className="function-picker-count">
          {shown} / {total}
        </span>
      </div>
      <input
        id="fn-filter"
        type="text"
        className="text-input"
        placeholder="Filter endpoints & functions…"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        spellCheck={false}
      />
      <select
        className="function-select"
        size={rows}
        value={selectedId ?? ''}
        onChange={(e) => onSelect(e.target.value)}
      >
        {shownEndpoints.length > 0 && (
          <optgroup label={`Entry points (${endpoints.length})`}>
            {shownEndpoints.map((f) => (
              <option key={f.id} value={f.id}>
                {f.label}
              </option>
            ))}
          </optgroup>
        )}
        {shownFunctions.length > 0 && (
          <optgroup label={`All functions (${functions.length})`}>
            {shownFunctions.map((f) => (
              <option key={f.id} value={f.id}>
                {f.label}
              </option>
            ))}
          </optgroup>
        )}
      </select>
    </div>
  );
}
