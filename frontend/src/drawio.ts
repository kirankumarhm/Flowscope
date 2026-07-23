import type { Core } from 'cytoscape';

/**
 * Convert a live Cytoscape graph into a draw.io (diagrams.net) `.drawio` file.
 *
 * A `.drawio` file is an `mxGraphModel` XML document. Unlike our PNG/SVG/PDF
 * exports (which flatten the diagram to pixels/paths), this produces *editable*
 * shapes: every node becomes an `mxCell` vertex and every edge an `mxCell` edge,
 * carrying position, size, fill, and stroke over from what's currently rendered.
 *
 * Styles are read from the resolved Cytoscape style (`ele.style(...)`) rather
 * than re-derived, so this stays consistent with each view's stylesheet without
 * duplicating its color/shape logic. The mapping to draw.io's shape vocabulary
 * is best-effort — barrels/hexagons and emoji glyphs won't be pixel-identical.
 */

// Cytoscape shape name -> draw.io (mxGraph) style fragment.
const SHAPE_STYLE: Record<string, string> = {
  'round-rectangle': 'rounded=1;',
  roundrectangle: 'rounded=1;',
  rectangle: 'rounded=0;',
  ellipse: 'ellipse;',
  diamond: 'rhombus;',
  hexagon: 'shape=hexagon;perimeter=hexagonPerimeter2;',
  barrel: 'shape=cylinder3;boundedLbl=1;backgroundOutline=1;',
};

function xmlEscape(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// Normalize any CSS color Cytoscape reports (rgb/rgba/#rgb/#rrggbb) to #rrggbb.
function toHex(color: unknown): string {
  const c = String(color ?? '').trim();
  if (!c) return '#ffffff';
  if (c.startsWith('#')) {
    if (c.length === 4) return `#${c[1]}${c[1]}${c[2]}${c[2]}${c[3]}${c[3]}`;
    return c.slice(0, 7).toLowerCase();
  }
  const m = c.match(/rgba?\(([^)]+)\)/i);
  if (m) {
    const parts = m[1].split(',').map((v) => Math.round(parseFloat(v)));
    const h = (n: number) => Math.max(0, Math.min(255, n || 0)).toString(16).padStart(2, '0');
    return `#${h(parts[0])}${h(parts[1])}${h(parts[2])}`;
  }
  return '#ffffff';
}

export function cyToDrawioXml(cy: Core): string {
  // Stable, reference-safe ids so source/target always match a vertex id.
  const idMap = new Map<string, string>();
  cy.nodes().forEach((n, i) => {
    idMap.set(n.id(), `node-${i}`);
  });

  const cells: string[] = [];

  // Parents (group containers) first so their children stack on top of them.
  const ordered = cy.nodes().filter((n) => n.isParent()).union(cy.nodes().filter((n) => !n.isParent()));

  ordered.forEach((n) => {
    const p = n.position();
    const w = Math.round(n.width());
    const h = Math.round(n.height());
    const x = Math.round(p.x - w / 2);
    const y = Math.round(p.y - h / 2);

    const shape = String(n.style('shape') || 'rectangle');
    const shapeStyle = SHAPE_STYLE[shape] ?? 'rounded=1;';
    const fill = toHex(n.style('background-color'));
    const stroke = toHex(n.style('border-color'));
    const font = toHex(n.style('color'));
    const dashed = n.style('border-style') === 'dashed' ? 'dashed=1;dashPattern=3 3;' : '';
    const label = String(n.style('label') ?? n.data('display') ?? n.data('label') ?? '');

    const style = `${shapeStyle}whiteSpace=wrap;html=1;fillColor=${fill};strokeColor=${stroke};fontColor=${font};${dashed}`;
    cells.push(
      `        <mxCell id="${idMap.get(n.id())}" value="${xmlEscape(label)}" style="${xmlEscape(style)}" vertex="1" parent="1">`,
      `          <mxGeometry x="${x}" y="${y}" width="${w}" height="${h}" as="geometry" />`,
      `        </mxCell>`,
    );
  });

  cy.edges().forEach((e, i) => {
    const source = idMap.get(e.source().id());
    const target = idMap.get(e.target().id());
    if (!source || !target) return; // skip dangling edges (e.g. hidden endpoints)

    const stroke = toHex(e.style('line-color'));
    const dashed = e.style('line-style') === 'dashed' ? 'dashed=1;' : '';
    const label = String(e.style('label') ?? e.data('label') ?? '');

    const style = `edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;strokeColor=${stroke};${dashed}`;
    cells.push(
      `        <mxCell id="edge-${i}" value="${xmlEscape(label)}" style="${xmlEscape(style)}" edge="1" parent="1" source="${source}" target="${target}">`,
      `          <mxGeometry relative="1" as="geometry" />`,
      `        </mxCell>`,
    );
  });

  return [
    '<mxfile host="flowscope">',
    '  <diagram name="FlowScope">',
    '    <mxGraphModel dx="800" dy="600" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" math="0" shadow="0">',
    '      <root>',
    '        <mxCell id="0" />',
    '        <mxCell id="1" parent="0" />',
    ...cells,
    '      </root>',
    '    </mxGraphModel>',
    '  </diagram>',
    '</mxfile>',
    '',
  ].join('\n');
}
