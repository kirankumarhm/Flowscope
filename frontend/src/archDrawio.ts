import type { Core, NodeSingular } from 'cytoscape';
import type { ArchNode } from './types';
import { drawioImage, iconFor } from './icons';
import { toHex, xmlEscape } from './drawio';

/** Where the icon and text sit inside a card, in model units (same as the web view). */
export interface CardLayout {
  iconSize: number;
  iconX: number;
  textX: number;
}

function html(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// Zero-width spaces after separators let long resource names wrap inside the card.
function breakable(s: string): string {
  return html(s).replace(/([-_./:])/g, '$1&#8203;');
}

const r = (v: number) => Math.round(v);

/**
 * Export the Architecture view as an editable draw.io file.
 *
 * Each box becomes a white card with its name and type, plus a child icon cell: AWS
 * services use draw.io's built-in AWS 4 shapes; other technologies embed their SVG logo.
 * For one application, its container, layer groups and component chips are exported too.
 * Positions come from the live Cytoscape layout. Cell order is draw.io's z-order: columns
 * and containers first, then lines, then cards and chips, so lines pass under the cards.
 * Edge labels are exported when they are shown on screen.
 */
export function architectureToDrawioXml(cy: Core, nodeById: Map<string, ArchNode>, card: CardLayout): string {
  const cells: string[] = [];
  const ids = new Map<string, string>();
  let seq = 1;
  const next = () => `c${++seq}`;
  const rect = (n: NodeSingular) => {
    const p = n.position();
    const w = n.width();
    const h = n.height();
    return { x: p.x - w / 2, y: p.y - h / 2, w, h };
  };
  const vertex = (id: string, value: string, style: string, g: { x: number; y: number; w: number; h: number }, parent = '1', extra = '') =>
    cells.push(
      `<mxCell id="${id}" value="${xmlEscape(value)}" style="${xmlEscape(style)}" vertex="1" parent="${parent}"${extra}>` +
        `<mxGeometry x="${r(g.x)}" y="${r(g.y)}" width="${r(g.w)}" height="${r(g.h)}" as="geometry"/></mxCell>`,
    );
  const iconCell = (a: ArchNode, parent: string, g: { x: number; y: number; w: number; h: number }) => {
    const icon = iconFor(a);
    const style = icon.drawio
      ? 'sketch=0;outlineConnect=0;dashed=0;html=1;aspect=fixed;strokeColor=#ffffff;' +
        `fillColor=${icon.drawio.fill};shape=mxgraph.aws4.resourceIcon;resIcon=mxgraph.aws4.${icon.drawio.resIcon};`
      : `shape=image;html=1;imageAspect=1;aspect=fixed;image=${drawioImage(icon.svg)};`;
    vertex(next(), '', style, g, parent, ' connectable="0"');
  };
  const label = (n: NodeSingular) => String(cy.getElementById(`${n.id()}__name`).data('label') ?? '');

  cy.nodes('.column').forEach((n) => {
    vertex(
      next(),
      html(String(n.data('label') ?? '')),
      'rounded=1;arcSize=2;html=1;fillColor=#f8fafc;strokeColor=#e2e8f0;verticalAlign=top;fontStyle=1;' +
        'fontSize=13;fontColor=#475569;spacingTop=14;',
      rect(n),
    );
  });

  cy.nodes('.title, .subtitle').forEach((n) => {
    const title = n.hasClass('title');
    vertex(
      next(),
      html(String(n.data('label') ?? '')),
      `text;html=1;align=left;verticalAlign=middle;fontSize=${title ? 24 : 12};fontStyle=${title ? 1 : 0};` +
        `fontColor=${title ? '#0f172a' : '#64748b'};`,
      { ...rect(n), w: Math.max(rect(n).w, title ? 600 : 900) },
    );
  });

  // One application: its container (with icon, name and type), lane titles and layer groups.
  cy.nodes('.appbox').forEach((n) => {
    const a = nodeById.get(n.id());
    if (!a) return;
    const id = next();
    ids.set(n.id(), id);
    const kind = String(cy.getElementById(`${n.id()}__kind`).data('label') ?? '');
    const value = `<b style="font-size:17px;">${html(a.label)}</b><br><span style="color:#64748b;font-size:11px;">${html(kind)}</span>`;
    vertex(
      id,
      value,
      `rounded=1;arcSize=1;html=1;fillColor=#ffffff;strokeColor=${String(n.data('stroke'))};strokeWidth=2;` +
        'align=left;verticalAlign=top;spacingLeft=58;spacingTop=12;fontColor=#0f172a;',
      rect(n),
    );
    iconCell(a, id, { x: 18, y: 22, w: 32, h: 32 });
  });
  cy.nodes('.txt.lane').forEach((n) => {
    const p = n.position();
    vertex(next(), html(String(n.data('label') ?? '')), 'text;html=1;align=left;verticalAlign=middle;fontSize=11;fontStyle=1;fontColor=#94a3b8;',
      { x: p.x, y: p.y - 9, w: 200, h: 18 });
  });
  cy.nodes('.group').forEach((n) => {
    const id = next();
    ids.set(n.id(), id);
    vertex(
      id,
      html(label(n)),
      `rounded=1;arcSize=3;html=1;fillColor=#f8fafc;strokeColor=${String(n.data('stroke'))};opacity=100;` +
        'align=left;verticalAlign=top;spacingLeft=10;spacingTop=6;fontStyle=1;fontSize=12;fontColor=#334155;',
      rect(n),
    );
  });

  // Ids for cards and chips first, so lines can reference them; their cells come after the lines.
  cy.nodes('.box, .chip').forEach((n) => {
    if (n.hasClass('chip') || nodeById.has(n.id())) ids.set(n.id(), next());
  });

  cy.edges().forEach((e) => {
    const source = ids.get(e.source().id());
    const target = ids.get(e.target().id());
    if (!source || !target) return;
    const internal = e.hasClass('internal');
    const line = e.style('line-style');
    const dash = line === 'dashed' ? 'dashed=1;' : line === 'dotted' ? 'dashed=1;dashPattern=1 4;' : '';
    const text = String(e.style('label') ?? '');
    // Leave and enter through the sides facing each other, like the web view's horizontal lines.
    const dx = e.target().position().x - e.source().position().x;
    const ports = internal
      ? ''
      : dx > 1
        ? 'exitX=1;exitY=0.5;entryX=0;entryY=0.5;'
        : dx < -1
          ? 'exitX=0;exitY=0.5;entryX=1;entryY=0.5;'
          : 'exitX=1;exitY=0.5;entryX=1;entryY=0.5;';
    const style =
      (internal
        ? 'curved=1;html=1;endArrow=block;endFill=1;endSize=4;strokeWidth=1;opacity=40;'
        : 'edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;endFill=1;strokeWidth=1.5;' +
          `${ports}exitDx=0;exitDy=0;entryDx=0;entryDy=0;`) +
      `strokeColor=${toHex(e.style('line-color'))};fontSize=10;fontColor=#334155;labelBackgroundColor=#ffffff;${dash}`;
    cells.push(
      `<mxCell id="${next()}" value="${xmlEscape(html(text))}" style="${xmlEscape(style)}" edge="1" parent="1" ` +
        `source="${source}" target="${target}"><mxGeometry relative="1" as="geometry"/></mxCell>`,
    );
  });

  cy.nodes('.box').forEach((n) => {
    const a = nodeById.get(n.id());
    const id = ids.get(n.id());
    if (!a || !id) return;
    const kind = String(cy.getElementById(`${n.id()}__kind`).data('label') ?? n.data('kind') ?? '');
    // Same line breaks as on screen (the web view wraps names between words and CamelCase parts).
    const shown = label(n) || a.label;
    const value =
      `<b>${shown.split('\n').map(breakable).join('<br>')}</b>` +
      (kind ? `<br><span style="color:#64748b;font-size:11px;">${html(kind).replace(/\n/g, '<br>')}</span>` : '');
    const style =
      'rounded=1;arcSize=12;whiteSpace=wrap;html=1;fillColor=#ffffff;' +
      `strokeColor=${String(n.data('stroke'))};strokeWidth=1.5;` +
      `align=left;verticalAlign=middle;spacingLeft=${card.textX - 4};spacingRight=8;fontSize=12;fontColor=#0f172a;` +
      (a.type === 'external' ? 'dashed=1;' : '');
    const g = rect(n);
    vertex(id, value, style, g);
    iconCell(a, id, { x: card.iconX, y: (g.h - card.iconSize) / 2, w: card.iconSize, h: card.iconSize });
  });

  cy.nodes('.chip').forEach((n) => {
    const id = ids.get(n.id());
    if (!id) return;
    const name = label(n) || nodeById.get(n.id())?.label || '';
    vertex(
      id,
      `<b>${name.split('\n').map(breakable).join('<br>')}</b>`,
      `rounded=1;arcSize=14;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=${String(n.data('stroke'))};` +
        'align=left;verticalAlign=middle;spacingLeft=8;spacingRight=6;fontSize=11;fontColor=#1e293b;',
      rect(n),
    );
  });

  return [
    '<mxfile host="flowscope">',
    '  <diagram name="Architecture">',
    '    <mxGraphModel dx="800" dy="600" grid="0" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="0" pageScale="1" math="0" shadow="0">',
    '      <root>',
    '        <mxCell id="0" />',
    '        <mxCell id="1" parent="0" />',
    ...cells.map((c) => `        ${c}`),
    '      </root>',
    '    </mxGraphModel>',
    '  </diagram>',
    '</mxfile>',
    '',
  ].join('\n');
}
