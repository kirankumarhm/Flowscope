/// <reference types="vite/client" />

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
