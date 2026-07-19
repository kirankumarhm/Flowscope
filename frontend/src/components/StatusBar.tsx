interface StatusBarProps {
  project?: string;
  languages?: string[];
  modules?: string[];
  functionCount?: number;
  appType?: string; // "Web application" | "Standalone application" | ...
  error?: string;
}

export default function StatusBar({
  project,
  languages,
  modules,
  functionCount,
  appType,
  error,
}: StatusBarProps) {
  if (error) {
    return <div className="error-banner">{error}</div>;
  }

  // Nothing analyzed yet.
  if (project === undefined) {
    return null;
  }

  const appBadge = appType ? <span className="status-apptype">{appType}</span> : null;
  // Show auto-detected sibling modules (beyond the primary) so it's clear what
  // was pulled in for cross-module resolution.
  const extraModules = (modules ?? []).filter((m) => m !== project);
  const modulesBadge =
    extraModules.length > 0 ? (
      <span className="status-modules" title={(modules ?? []).join(', ')}>
        + {extraModules.join(', ')}
      </span>
    ) : null;

  if (functionCount === 0) {
    return (
      <div className="status-bar">
        <div className="status-meta">
          <span className="status-project">{project}</span>
          {appBadge}
          {languages && languages.length > 0 && (
            <span className="status-langs">{languages.join(', ')}</span>
          )}
        </div>
        <div className="empty-state">
          No function bodies with control flow were detected.
        </div>
      </div>
    );
  }

  return (
    <div className="status-bar">
      <div className="status-meta">
        <span className="status-project">{project}</span>
        {modulesBadge}
        {appBadge}
        {languages && languages.length > 0 && (
          <span className="status-langs">{languages.join(', ')}</span>
        )}
        <span className="status-count">
          {functionCount} entry point{functionCount === 1 ? '' : 's'} &amp; functions
        </span>
      </div>
    </div>
  );
}
