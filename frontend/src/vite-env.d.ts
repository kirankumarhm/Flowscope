/// <reference types="vite/client" />

// Custom Vite env vars (exposed on import.meta.env, must be VITE_-prefixed).
interface ImportMetaEnv {
  // Backend REST API base URL for production builds; empty/undefined in dev
  // (requests stay relative and go through the Vite proxy).
  readonly VITE_API_BASE_URL?: string;
}

// cytoscape-dagre ships without bundled type declarations and there is no
// @types/cytoscape-dagre package in use, so declare the module here.
// The extension is a registration function accepted by cytoscape.use().
declare module 'cytoscape-dagre' {
  const cytoscapeDagre: (cy: unknown) => void;
  export default cytoscapeDagre;
}

// cytoscape-svg likewise ships no types; it registers a cy.svg() method.
declare module 'cytoscape-svg' {
  const cytoscapeSvg: (cy: unknown) => void;
  export default cytoscapeSvg;
}

// cytoscape-expand-collapse: registers cy.expandCollapse(options) with
// collapseAll/expandAll/collapse/expand APIs.
declare module 'cytoscape-expand-collapse' {
  const cytoscapeExpandCollapse: (cy: unknown) => void;
  export default cytoscapeExpandCollapse;
}
