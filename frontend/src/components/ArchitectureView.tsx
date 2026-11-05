import { useEffect, useMemo, useRef, useState } from 'react';
import cytoscape from 'cytoscape';
import type { Core, ElementDefinition, NodeSingular } from 'cytoscape';
import svg from 'cytoscape-svg';
import type { ArchEdge, ArchNode, Architecture } from '../types';
import { architectureToDrawioXml } from '../archDrawio';
import { iconFor, svgDataUri } from '../icons';

cytoscape.use(svg);

interface ArchitectureViewProps {
  arch: Architecture | null;
  loading: boolean;
  error?: string;
  filename?: string;
}

// --- layout constants (model units; the view zooms) ----------------------------
const BW = 248; // card width
const ICON = 30; // icon size
const ICON_X = 12; // icon offset from the card's left edge
const TEXT_X = 54; // text offset from the card's left edge
const TEXT_W = BW - TEXT_X - 12; // room for the name
const NAME_FONT = 12.5;
const NAME_LH = 16; // name line height
const KIND_FONT = 11;
const KIND_LH = 14; // type line height
const MAX_LINES = 3; // longer names are cut with "…" (the full name is in the detail panel)
const MIN_H = 56; // minimum card height
const PADV = 9; // minimum padding above and below the text
const GX = 22; // gap between sub-columns
const GY = 14; // gap between cards
const CGAP = 64; // gap between columns
const PADX = 20; // column padding left/right
const HEAD = 56; // column header height
const PADB = 22; // column padding bottom
const TOP = 96; // space for the title above the columns
const READABLE_ZOOM = 0.8; // the first view never zooms out further than this
const FONT_FAMILY = 'Inter, system-ui, -apple-system, sans-serif';
const NAME_CSS = `600 ${NAME_FONT}px ${FONT_FAMILY}`;
const KIND_CSS = `400 ${KIND_FONT}px ${FONT_FAMILY}`;

// Border colour by role; the icon says what the box is.
const ACCENT: Record<string, string> = {
  ui: '#7c3aed',
  api: '#2563eb',
  proc: '#16a34a',
  lambda: '#ea580c',
  job: '#475569',
  kafka: '#059669',
  queue: '#db2777',
  trigger: '#d97706',
  grpc: '#0891b2',
  db: '#0369a1',
  ext: '#64748b',
  cloud: '#b45309',
};

function role(n: ArchNode): string {
  switch (n.type) {
    case 'ui':
      return 'ui';
    case 'service':
      if (n.column === 'apis') return 'api';
      if (n.subtype === 'node-lambda') return 'lambda';
      if (n.subtype === 'container') return 'job';
      return 'proc';
    case 'datastore':
      return 'db';
    case 'external':
      return 'ext';
    default:
      return n.type in ACCENT ? n.type : 'ext';
  }
}
const accentOf = (n: ArchNode) => ACCENT[role(n)];
const kindOf = (n: ArchNode) => n.detail || n.type;

const EDGE_COLORS: Record<string, string> = {
  rest: '#4f46e5',
  soap: '#7c3aed',
  grpc: '#0891b2',
  async: '#059669',
  sends: '#db2777',
  consumes: '#db2777',
  triggers: '#d97706',
  reads: '#0284c7',
  writes: '#be123c',
  readwrite: '#0369a1',
  uses: '#94a3b8',
  inject: '#94a3b8',
  call: '#94a3b8',
};
const EDGE_NAMES: Record<string, string> = {
  rest: 'REST call',
  soap: 'SOAP call',
  grpc: 'gRPC call',
  async: 'Kafka (async)',
  sends: 'Queue send',
  consumes: 'Queue consume',
  triggers: 'Trigger',
  reads: 'Reads',
  writes: 'Writes',
  readwrite: 'Reads and writes',
  uses: 'Uses',
  inject: 'Depends on (injected)',
  call: 'Calls',
};

type Toggle = 'messaging' | 'data' | 'external' | 'cloud';
const TOGGLES: { key: Toggle; label: string }[] = [
  { key: 'messaging', label: 'Messaging' },
  { key: 'data', label: 'Data stores' },
  { key: 'external', label: 'External systems' },
  { key: 'cloud', label: 'Cloud services' },
];

// --- text measuring and wrapping ----------------------------------------------
let measureCtx: CanvasRenderingContext2D | null | undefined;
function measure(s: string, font: string): number {
  if (measureCtx === undefined) measureCtx = document.createElement('canvas').getContext('2d');
  if (!measureCtx) return s.length * 7;
  measureCtx.font = font;
  return measureCtx.measureText(s).width;
}

/**
 * Wrap a name into at most `maxLines` lines no wider than `maxW`. Lines break after
 * spaces and `- _ . / :`, and between CamelCase words, so `acme-prod-use1-DeviceState`
 * and `ProvisionAcknowledgmentConsumer` break between words; a part that is still too
 * wide is split by character.
 */
function wrapName(text: string, maxW: number, font: string, maxLines: number): string[] {
  const parts = text.split(/(?<=[\s\-_./:])|(?<=[a-z0-9])(?=[A-Z])/).filter(Boolean);
  const lines: string[] = [];
  let cur = '';
  const push = () => {
    if (cur.trim()) lines.push(cur.trimEnd());
    cur = '';
  };
  for (const part of parts) {
    if (measure(cur + part, font) <= maxW) {
      cur += part;
      continue;
    }
    push();
    if (measure(part, font) <= maxW) {
      cur = part;
      continue;
    }
    for (const ch of part) {
      if (cur && measure(cur + ch, font) > maxW) push();
      cur += ch;
    }
  }
  push();
  if (lines.length <= maxLines) return lines;
  const kept = lines.slice(0, maxLines);
  let last = kept[maxLines - 1];
  while (last.length > 1 && measure(`${last}…`, font) > maxW) last = last.slice(0, -1);
  kept[maxLines - 1] = `${last}…`;
  return kept;
}

interface Card {
  lines: string[];
  kind: string;
  kindLines: string[];
  textH: number;
  h: number;
}
interface Topic {
  name: string;
  direction: 'in' | 'out' | 'both';
}
const topicsOf = (n: ArchNode): Topic[] => (Array.isArray(n.meta?.topics) ? (n.meta!.topics as Topic[]) : []);
const DIRECTION: Record<string, string> = { in: 'in', out: 'out', both: 'in/out' };

function cardOf(n: ArchNode): Card {
  const lines = wrapName(n.label, TEXT_W, NAME_CSS, MAX_LINES);
  const kind = kindOf(n);
  // One Kafka box lists every topic the application uses, with its direction.
  const topicLines = topicsOf(n).flatMap((t) => wrapName(`${DIRECTION[t.direction] ?? t.direction} · ${t.name}`, TEXT_W, KIND_CSS, 2));
  const kindLines = kind ? [...wrapName(kind, TEXT_W, KIND_CSS, 2), ...topicLines] : [];
  const textH = lines.length * NAME_LH + (kindLines.length ? 2 + kindLines.length * KIND_LH : 0);
  return { lines, kind, kindLines, textH, h: Math.max(MIN_H, textH + 2 * PADV) };
}

/** Rows per column that make the diagram's shape closest to the screen's. */
function chooseRows(counts: number[], cardH: number, aspect: number): number {
  const max = Math.max(1, ...counts);
  let best = max;
  let bestScore = Infinity;
  for (let rows = max; rows >= Math.min(max, 4); rows--) {
    const width =
      counts.reduce((sum, n) => {
        const k = Math.max(1, Math.ceil(n / rows));
        return sum + k * BW + (k - 1) * GX + 2 * PADX;
      }, 0) +
      (counts.length - 1) * CGAP;
    const height = TOP + HEAD + rows * cardH + (rows - 1) * GY + PADB;
    const score = Math.abs(Math.log(width / height) - Math.log(aspect));
    if (score < bestScore - 1e-9) {
      bestScore = score;
      best = rows;
    }
  }
  return best;
}

function textWidth(s: string, size: number): number {
  return Math.max(40, s.length * size * 0.58);
}

function buildElements(arch: Architecture, shown: Set<string>, aspect: number): ElementDefinition[] {
  const byId = new Map(arch.nodes.map((n) => [n.id, n]));
  const columns = arch.columns
    .filter((c) => shown.has(c.id))
    .map((c) => ({ col: c, nodes: c.nodes.map((id) => byId.get(id)).filter((n): n is ArchNode => !!n) }))
    .filter((c) => c.nodes.length > 0);
  const cards = new Map<string, Card>();
  columns.forEach((c) => c.nodes.forEach((n) => cards.set(n.id, cardOf(n))));
  const avgH = cards.size ? Array.from(cards.values()).reduce((s, c) => s + c.h, 0) / cards.size : MIN_H;
  const rows = chooseRows(
    columns.map((c) => c.nodes.length),
    avgH,
    aspect,
  );

  // Split each column into sub-columns of `rows` cards; all columns share the tallest height.
  const subs = columns.map((c) => {
    const groups: ArchNode[][] = [];
    for (let i = 0; i < c.nodes.length; i += rows) groups.push(c.nodes.slice(i, i + rows));
    return groups;
  });
  const stackH = (g: ArchNode[]) => g.reduce((s, n) => s + cards.get(n.id)!.h, 0) + (g.length - 1) * GY;
  const inner = Math.max(MIN_H, ...subs.flat().map(stackH));
  const colHeight = HEAD + inner + PADB;

  const elements: ElementDefinition[] = [];
  let x = 0;
  columns.forEach(({ col }, ci) => {
    const groups = subs[ci];
    const width = groups.length * BW + (groups.length - 1) * GX + 2 * PADX;
    elements.push({
      data: { id: `col:${col.id}`, label: col.title.toUpperCase(), w: width, h: colHeight },
      position: { x: x + width / 2, y: TOP + colHeight / 2 },
      classes: 'column',
      grabbable: false,
      selectable: false,
    });
    groups.forEach((g, gi) => {
      let y = TOP + HEAD + (inner - stackH(g)) / 2;
      const left = x + PADX + gi * (BW + GX);
      for (const n of g) {
        const card = cards.get(n.id)!;
        const textTop = y + (card.h - card.textH) / 2;
        elements.push({
          data: {
            id: n.id,
            w: BW,
            h: card.h,
            stroke: accentOf(n),
            icon: svgDataUri(iconFor(n).svg),
            kind: card.kind,
          },
          position: { x: left + BW / 2, y: y + card.h / 2 },
          classes: `box ${n.type}${n.id === arch.focus ? ' focus' : ''}`,
          grabbable: false,
        });
        // Name and type are separate text nodes so the name can be bold and the type grey.
        elements.push({
          data: { id: `${n.id}__name`, owner: n.id, label: card.lines.join('\n'), w: 1, h: 1 },
          position: { x: left + TEXT_X - 0.5, y: textTop + (card.lines.length * NAME_LH) / 2 },
          classes: 'txt name',
          grabbable: false,
          selectable: false,
        });
        if (card.kindLines.length) {
          elements.push({
            data: { id: `${n.id}__kind`, owner: n.id, label: card.kindLines.join('\n'), w: 1, h: 1 },
            position: {
              x: left + TEXT_X - 0.5,
              y: textTop + card.lines.length * NAME_LH + 2 + (card.kindLines.length * KIND_LH) / 2,
            },
            classes: 'txt kind',
            grabbable: false,
            selectable: false,
          });
        }
        y += card.h + GY;
      }
    });
    x += width + CGAP;
  });

  const title = arch.project;
  const subtitle =
    `System architecture generated from source by FlowScope · ${arch.stats.services} services · ` +
    `${arch.stats.connections} connections`;
  const tw = textWidth(title, 26);
  const sw = textWidth(subtitle, 13);
  elements.push(
    {
      data: { id: '__title', label: title, w: tw, h: 34 },
      position: { x: tw / 2, y: 26 },
      classes: 'title',
      grabbable: false,
      selectable: false,
    },
    {
      data: { id: '__subtitle', label: subtitle, w: sw, h: 20 },
      position: { x: sw / 2, y: 60 },
      classes: 'subtitle',
      grabbable: false,
      selectable: false,
    },
  );
  const colOf = new Map(arch.nodes.map((n) => [n.id, n.column]));
  for (const e of arch.edges) {
    if (!cards.has(e.source) || !cards.has(e.target)) continue;
    const same = colOf.get(e.source) === colOf.get(e.target);
    elements.push({
      data: {
        id: e.id,
        source: e.source,
        target: e.target,
        kind: e.kind,
        label: e.label,
        confidence: e.confidence ?? 'code',
      },
      classes: `${e.kind}${same ? ' same-col' : ''}`,
    });
  }
  return elements;
}

// --- application scope: one application with its layers ------------------------
const CW = 212; // component chip width
const CHIP_FONT = 11.5;
const CHIP_LH = 15;
const CHIP_CSS = `600 ${CHIP_FONT}px ${FONT_FAMILY}`;
const CGX = 10; // gap between chip columns
const CGY = 8; // gap between chips
const GPAD = 12; // group padding
const GHEAD = 34; // group title height
const GGAP = 16; // gap between groups in a lane
const LHEAD = 28; // lane title height
const LGAP = 24; // gap between lanes
const APAD = 18; // application padding
const AHEAD = 74; // application header height (icon, name, type)

const LAYER_COLOR: Record<string, string> = {
  controller: '#2563eb',
  component: '#0891b2',
  service: '#16a34a',
  repository: '#0369a1',
  client: '#7c3aed',
};

interface Chip {
  id: string;
  lines: string[];
}
interface GroupBox {
  id: string;
  title: string;
  color: string;
  chips: Chip[];
  chipH: number;
}

function groupSize(g: GroupBox, rows: number) {
  const n = g.chips.length;
  const k = Math.max(1, Math.ceil(n / rows));
  const r = Math.max(1, Math.min(n, rows));
  return { k, r, w: 2 * GPAD + k * CW + (k - 1) * CGX, h: GHEAD + r * g.chipH + (r - 1) * CGY + GPAD };
}

/** Fill sub-columns top to bottom, starting a new one when the next card would pass `maxH`. */
function pack(ids: string[], cards: Map<string, Card>, maxH: number): string[][] {
  const subs: string[][] = [];
  let cur: string[] = [];
  let h = 0;
  for (const id of ids) {
    const ch = cards.get(id)!.h;
    if (cur.length && h + GY + ch > maxH) {
      subs.push(cur);
      cur = [];
      h = 0;
    }
    h += (cur.length ? GY : 0) + ch;
    cur.push(id);
  }
  if (cur.length) subs.push(cur);
  return subs;
}

function buildAppElements(arch: Architecture, shown: Set<string>, aspect: number): ElementDefinition[] {
  const byId = new Map(arch.nodes.map((n) => [n.id, n]));
  const appId = arch.app?.id ?? arch.focus ?? '';
  const appNode = byId.get(appId);
  const lanes = (arch.app?.lanes ?? []).map((l) => ({
    id: l.id,
    title: l.title,
    groups: l.groups.map((g): GroupBox => {
      const chips = g.nodes.map((id) => ({ id, lines: wrapName(byId.get(id)?.label ?? id, CW - 20, CHIP_CSS, 2) }));
      const maxLines = Math.max(1, ...chips.map((c) => c.lines.length));
      return { id: g.id, title: g.title, color: LAYER_COLOR[byId.get(g.id)?.subtype ?? ''] ?? '#64748b', chips, chipH: 14 + maxLines * CHIP_LH };
    }),
  }));
  const outer = arch.columns
    .filter((c) => c.id !== 'app' && (c.id === 'connected' || shown.has(c.id)))
    .map((c) => ({ col: c, ids: c.nodes.filter((id) => byId.has(id)) }))
    .filter((c) => c.ids.length > 0);
  // Outer columns that come before the application in the column order.
  const appPos = arch.columns.findIndex((c) => c.id === 'app');
  const appIndex = outer.filter((o) => arch.columns.findIndex((c) => c.id === o.col.id) < appPos).length;
  // Without an inside view (e.g. a Lambda), the application is an ordinary card in its own column.
  const hasInside = lanes.length > 0;
  if (!hasInside && appNode) {
    outer.splice(appIndex, 0, { col: { id: 'app', title: 'Application', nodes: [appId] }, ids: [appId] });
  }
  const cards = new Map<string, Card>();
  outer.forEach((c) => c.ids.forEach((id) => cards.set(id, cardOf(byId.get(id)!))));
  const tallest = Math.max(MIN_H, ...Array.from(cards.values()).map((c) => c.h));
  const avgH = cards.size ? Array.from(cards.values()).reduce((t, c) => t + c.h, 0) / cards.size : MIN_H;

  // Try row counts (component chips, or cards when there is no inside view) and keep the
  // one whose overall shape is closest to the screen's.
  const maxRows = hasInside
    ? Math.max(1, ...lanes.flatMap((l) => l.groups.map((g) => g.chips.length)))
    : Math.max(1, ...outer.map((c) => c.ids.length));
  const plan = (rows: number) => {
    const laneDims = lanes.map((l) => {
      const gs = l.groups.map((g) => groupSize(g, rows));
      return { w: Math.max(...gs.map((g) => g.w)), h: LHEAD + gs.reduce((s, g) => s + g.h, 0) + (gs.length - 1) * GGAP, gs };
    });
    const appW = hasInside ? 2 * APAD + laneDims.reduce((s, l) => s + l.w, 0) + (laneDims.length - 1) * LGAP : 0;
    const appH = hasInside ? AHEAD + Math.max(MIN_H, ...laneDims.map((l) => l.h)) + APAD : 0;
    const maxH = Math.max(tallest, hasInside ? appH - HEAD - PADB : rows * (avgH + GY) - GY);
    const cols = outer.map((c) => {
      const subs = pack(c.ids, cards, maxH);
      const stackH = (g: string[]) => g.reduce((s, id) => s + cards.get(id)!.h, 0) + (g.length - 1) * GY;
      return { ...c, subs, w: subs.length * BW + (subs.length - 1) * GX + 2 * PADX, h: HEAD + Math.max(...subs.map(stackH)) + PADB, stackH };
    });
    const colHeight = Math.max(appH, ...cols.map((c) => c.h));
    const width = cols.reduce((s, c) => s + c.w, 0) + appW + (cols.length - (hasInside ? 0 : 1)) * CGAP;
    return { rows, laneDims, appW, colHeight, cols, score: Math.abs(Math.log(width / (TOP + colHeight)) - Math.log(aspect)) };
  };
  let best = plan(maxRows);
  for (let rows = maxRows - 1; rows >= Math.min(maxRows, hasInside ? 4 : 2); rows--) {
    const p = plan(rows);
    if (p.score < best.score - 1e-9) best = p;
  }

  const elements: ElementDefinition[] = [];
  const present = new Set<string>();
  const text = (id: string, owner: string, label: string, x: number, y: number, cls: string) =>
    elements.push({ data: { id, owner, label, w: 1, h: 1 }, position: { x: x - 0.5, y }, classes: `txt ${cls}`, grabbable: false, selectable: false });
  const colHeight = best.colHeight;
  let x = 0;
  const slots = hasInside ? [...best.cols.slice(0, appIndex), null, ...best.cols.slice(appIndex)] : best.cols;
  for (const c of slots) {
    if (c === null) {
      // The application: header, then lanes of layer groups with their component chips.
      const left = x;
      const w = best.appW;
      if (appNode) {
        present.add(appId);
        elements.push({
          data: { id: appId, w, h: colHeight, stroke: accentOf(appNode), icon: svgDataUri(iconFor(appNode).svg), kind: kindOf(appNode) },
          position: { x: left + w / 2, y: TOP + colHeight / 2 },
          classes: 'appbox',
          grabbable: false,
        });
        const count = (arch.app?.lanes ?? []).reduce((s, l) => s + l.groups.reduce((t, g) => t + g.nodes.length, 0), 0);
        text(`${appId}__name`, appId, appNode.label, left + 60, TOP + 28, 'apptitle');
        text(`${appId}__kind`, appId, count ? `${kindOf(appNode)} · ${count} components` : kindOf(appNode), left + 60, TOP + 48, 'kind');
      }
      let lx = left + APAD;
      lanes.forEach((lane, li) => {
        const dims = best.laneDims[li];
        text(`lane:${lane.id}`, appId, lane.title.toUpperCase(), lx, TOP + AHEAD + 8, 'lane');
        let gy = TOP + AHEAD + LHEAD;
        lane.groups.forEach((g, gi) => {
          const gs = dims.gs[gi];
          present.add(g.id);
          elements.push({
            data: { id: g.id, w: gs.w, h: gs.h, stroke: g.color },
            position: { x: lx + gs.w / 2, y: gy + gs.h / 2 },
            classes: 'group',
            grabbable: false,
          });
          text(`${g.id}__name`, g.id, `${g.title} · ${g.chips.length}`, lx + GPAD, gy + GHEAD / 2 + 2, 'gtitle');
          g.chips.forEach((chip, i) => {
            const col = Math.floor(i / gs.r);
            const row = i % gs.r;
            const cx = lx + GPAD + col * (CW + CGX);
            const cy = gy + GHEAD + row * (g.chipH + CGY);
            present.add(chip.id);
            elements.push({
              data: { id: chip.id, w: CW, h: g.chipH, stroke: g.color },
              position: { x: cx + CW / 2, y: cy + g.chipH / 2 },
              classes: 'chip',
              grabbable: false,
            });
            text(`${chip.id}__name`, chip.id, chip.lines.join('\n'), cx + 10, cy + g.chipH / 2, 'chipname');
          });
          gy += gs.h + GGAP;
        });
        lx += dims.w + LGAP;
      });
      x += w + CGAP;
      continue;
    }
    const inner = colHeight - HEAD - PADB;
    elements.push({
      data: { id: `col:${c.col.id}`, label: c.col.title.toUpperCase(), w: c.w, h: colHeight },
      position: { x: x + c.w / 2, y: TOP + colHeight / 2 },
      classes: 'column',
      grabbable: false,
      selectable: false,
    });
    c.subs.forEach((sub, si) => {
      let y = TOP + HEAD + (inner - c.stackH(sub)) / 2;
      const left = x + PADX + si * (BW + GX);
      for (const id of sub) {
        const n = byId.get(id)!;
        const card = cards.get(id)!;
        const textTop = y + (card.h - card.textH) / 2;
        present.add(id);
        elements.push({
          data: { id, w: BW, h: card.h, stroke: accentOf(n), icon: svgDataUri(iconFor(n).svg), kind: card.kind },
          position: { x: left + BW / 2, y: y + card.h / 2 },
          classes: `box ${n.type}${id === appId ? ' focus' : ''}`,
          grabbable: false,
        });
        text(`${id}__name`, id, card.lines.join('\n'), left + TEXT_X, textTop + (card.lines.length * NAME_LH) / 2, 'name');
        if (card.kindLines.length) {
          text(`${id}__kind`, id, card.kindLines.join('\n'), left + TEXT_X,
            textTop + card.lines.length * NAME_LH + 2 + (card.kindLines.length * KIND_LH) / 2, 'kind');
        }
        y += card.h + GY;
      }
    });
    x += c.w + CGAP;
  }

  const components = (arch.app?.lanes ?? []).reduce((s, l) => s + l.groups.reduce((t, g) => t + g.nodes.length, 0), 0);
  const title = arch.project;
  const subtitle =
    `Application architecture generated from source by FlowScope · ` +
    `${components ? `${components} components · ` : ''}${arch.stats.connections} connections`;
  const tw = textWidth(title, 26);
  const sw = textWidth(subtitle, 13);
  elements.push(
    { data: { id: '__title', label: title, w: tw, h: 34 }, position: { x: tw / 2, y: 26 }, classes: 'title', grabbable: false, selectable: false },
    { data: { id: '__subtitle', label: subtitle, w: sw, h: 20 }, position: { x: sw / 2, y: 60 }, classes: 'subtitle', grabbable: false, selectable: false },
  );
  for (const e of arch.edges) {
    if (!present.has(e.source) || !present.has(e.target)) continue;
    const internal = e.kind === 'inject' || e.kind === 'call';
    elements.push({
      data: { id: e.id, source: e.source, target: e.target, kind: e.kind, label: e.label ?? '', confidence: e.confidence ?? 'code' },
      classes: internal ? `${e.kind} internal` : e.kind,
    });
  }
  return elements;
}

const stylesheet: any[] = [
  {
    selector: 'node',
    style: {
      width: 'data(w)',
      height: 'data(h)',
      'z-index-compare': 'manual',
      'z-index': 10,
      'font-family': FONT_FAMILY,
    },
  },
  {
    selector: 'node.box',
    style: {
      shape: 'round-rectangle',
      'background-color': '#ffffff',
      'border-color': 'data(stroke)',
      'border-width': 1.6,
      'background-image': 'data(icon)',
      'background-width': ICON,
      'background-height': ICON,
      'background-position-x': ICON_X,
      'background-position-y': '50%',
      'background-fit': 'none',
      'background-clip': 'none',
      'background-image-smoothing': 'yes',
    },
  },
  { selector: 'node.external', style: { 'border-style': 'dashed' } },
  {
    selector: 'node.txt',
    style: {
      'background-opacity': 0,
      'border-width': 0,
      label: 'data(label)',
      'text-halign': 'right',
      'text-valign': 'center',
      'text-justification': 'left',
      'text-wrap': 'wrap',
      'text-max-width': '2000px',
      'z-index': 12,
      events: 'no',
    },
  },
  {
    selector: 'node.txt.name',
    style: { 'font-size': NAME_FONT, 'font-weight': 600, 'line-height': NAME_LH / NAME_FONT, color: '#0f172a' },
  },
  { selector: 'node.txt.kind', style: { 'font-size': KIND_FONT, 'line-height': KIND_LH / KIND_FONT, color: '#64748b' } },
  { selector: 'node.txt.apptitle', style: { 'font-size': 17, 'font-weight': 700, color: '#0f172a' } },
  { selector: 'node.txt.lane', style: { 'font-size': 11, 'font-weight': 700, color: '#94a3b8' } },
  { selector: 'node.txt.gtitle', style: { 'font-size': 12, 'font-weight': 700, color: '#334155' } },
  {
    selector: 'node.txt.chipname',
    style: { 'font-size': CHIP_FONT, 'font-weight': 600, 'line-height': CHIP_LH / CHIP_FONT, color: '#1e293b' },
  },
  {
    selector: 'node.appbox',
    style: {
      shape: 'round-rectangle',
      'background-color': '#ffffff',
      'border-color': 'data(stroke)',
      'border-width': 2,
      'background-image': 'data(icon)',
      'background-width': 32,
      'background-height': 32,
      'background-position-x': 18,
      'background-position-y': 22,
      'background-fit': 'none',
      'background-clip': 'none',
      'z-index': 1,
    },
  },
  {
    selector: 'node.group',
    style: {
      shape: 'round-rectangle',
      'background-color': '#f8fafc',
      'border-color': 'data(stroke)',
      'border-width': 1.2,
      'border-opacity': 0.55,
      'z-index': 2,
    },
  },
  {
    selector: 'node.chip',
    style: {
      shape: 'round-rectangle',
      'background-color': '#ffffff',
      'border-color': 'data(stroke)',
      'border-width': 1.2,
      'z-index': 10,
    },
  },
  {
    selector: 'node.column',
    style: {
      shape: 'round-rectangle',
      'background-color': '#f8fafc',
      'border-color': '#e2e8f0',
      'border-width': 1,
      label: 'data(label)',
      color: '#475569',
      'font-size': 13,
      'font-weight': 700,
      'text-valign': 'top',
      'text-halign': 'center',
      'text-margin-y': 32,
      'z-index': 0,
      events: 'no',
    },
  },
  {
    selector: 'node.title, node.subtitle',
    style: {
      shape: 'rectangle',
      'background-opacity': 0,
      'border-width': 0,
      label: 'data(label)',
      'text-valign': 'center',
      'text-halign': 'center',
      events: 'no',
    },
  },
  { selector: 'node.title', style: { 'font-size': 26, 'font-weight': 700, color: '#0f172a' } },
  { selector: 'node.subtitle', style: { 'font-size': 13, color: '#64748b' } },
  {
    selector: 'node.focus',
    style: {
      'border-width': 3.5,
      'border-color': '#f59e0b',
      'underlay-color': '#fbbf24',
      'underlay-opacity': 0.35,
      'underlay-padding': 7,
      'underlay-shape': 'round-rectangle',
    },
  },
  {
    selector: 'edge',
    style: {
      width: 1.6,
      'line-color': (e: cytoscape.EdgeSingular) => EDGE_COLORS[e.data('kind') as string] ?? '#94a3b8',
      'target-arrow-color': (e: cytoscape.EdgeSingular) => EDGE_COLORS[e.data('kind') as string] ?? '#94a3b8',
      'target-arrow-shape': 'triangle',
      'arrow-scale': 0.9,
      'curve-style': 'taxi',
      'taxi-direction': 'horizontal',
      'taxi-turn': '50%',
      'taxi-turn-min-distance': 12,
      opacity: 0.7,
      'z-index': 5,
      'font-size': 10,
      'font-weight': 600,
      color: '#334155',
      'text-background-color': '#ffffff',
      'text-background-opacity': 0.92,
      'text-background-padding': '2px',
      'text-rotation': 'none',
    },
  },
  {
    selector: 'edge.same-col',
    style: {
      'curve-style': 'unbundled-bezier',
      'control-point-distances': 90,
      'control-point-weights': 0.5,
    },
  },
  { selector: 'edge.async', style: { 'line-style': 'dashed', 'line-dash-pattern': [7, 5] } },
  // Dependencies between components inside one application: thin and quiet until selected.
  { selector: 'edge.internal', style: { width: 1, opacity: 0.3, 'curve-style': 'bezier', 'arrow-scale': 0.6, 'z-index': 6 } },
  { selector: 'edge[confidence = "inferred"], edge[confidence = "unresolved"]', style: { 'line-style': 'dotted' } },
  { selector: 'edge.show-label, edge.labels-on', style: { label: 'data(label)' } },
  { selector: 'edge.hl', style: { width: 2.8, opacity: 1, 'z-index': 8 } },
  // Faded cards stay opaque so lines behind them stay hidden; only their content fades.
  { selector: 'node.box.faded, node.chip.faded, node.group.faded', style: { 'border-opacity': 0.3, 'background-image-opacity': 0.3 } },
  { selector: 'node.txt.faded', style: { 'text-opacity': 0.3 } },
  { selector: 'edge.faded', style: { opacity: 0.15 } },
  { selector: 'node.box:selected, node.chip:selected, node.group:selected', style: { 'border-width': 3, 'border-color': '#1d4ed8', 'border-opacity': 1 } },
];

// Elements that fade when something else is selected.
const FADEABLE = 'node.box, node.chip, node.group, edge';

/** Fit the whole diagram, unless that makes the text too small to read; then show its top-left corner. */
function initialView(cy: Core): void {
  cy.fit(undefined, 24);
  if (cy.zoom() >= READABLE_ZOOM) return;
  cy.zoom(READABLE_ZOOM);
  const bb = cy.elements().boundingBox();
  cy.pan({ x: 24 - bb.x1 * READABLE_ZOOM, y: 24 - bb.y1 * READABLE_ZOOM });
}

function triggerDownload(href: string, name: string): void {
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
}
function sanitize(name: string): string {
  return (name || 'architecture').replace(/[^\w.-]+/g, '_').replace(/^_+|_+$/g, '') || 'architecture';
}

export default function ArchitectureView({ arch, loading, error, filename = 'architecture' }: ArchitectureViewProps) {
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const cyRef = useRef<Core | null>(null);
  const [shown, setShown] = useState<Record<Toggle, boolean>>({ messaging: true, data: true, external: true, cloud: true });
  const [labels, setLabels] = useState(false);
  const [selected, setSelected] = useState<ArchNode | null>(null);
  const [selectedEdge, setSelectedEdge] = useState<ArchEdge | null>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const [aspect, setAspect] = useState(1.6);

  const nodeById = useMemo(() => new Map((arch?.nodes ?? []).map((n) => [n.id, n])), [arch]);
  const edgeById = useMemo(() => new Map((arch?.edges ?? []).map((e) => [e.id, e])), [arch]);

  // Re-lay out when the panel's shape changes enough to matter.
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => {
      const w = el.clientWidth;
      const h = el.clientHeight;
      if (w > 0 && h > 0) {
        cyRef.current?.resize();
        const next = Math.round((w / h) * 10) / 10;
        setAspect((cur) => (Math.abs(cur - next) >= 0.1 ? next : cur));
      }
    });
    ro.observe(el);
    return () => ro.disconnect();
    // The canvas only exists once loading is over, so re-run when that changes.
  }, [arch, loading, error]);

  useEffect(() => {
    if (!containerRef.current || !arch) return;
    const visibleCols = new Set(['frontends', 'apis', 'processing', 'connected', 'app']);
    (Object.keys(shown) as Toggle[]).forEach((k) => shown[k] && visibleCols.add(k));
    const application = arch.scope === 'application';
    const cy = cytoscape({
      container: containerRef.current,
      elements: application ? buildAppElements(arch, visibleCols, aspect) : buildElements(arch, visibleCols, aspect),
      style: stylesheet,
      layout: { name: 'preset' },
      wheelSensitivity: 0.2,
      minZoom: 0.05,
      maxZoom: 3,
      autoungrabify: true,
    });
    cyRef.current = cy;

    // Text nodes fade with the card they belong to.
    const syncText = () =>
      cy.nodes('.txt').forEach((t) => {
        if (cy.getElementById(t.data('owner') as string).hasClass('faded')) t.addClass('faded');
        else t.removeClass('faded');
      });
    const clear = () => cy.elements().removeClass('faded hl show-label');
    const highlight = (center: NodeSingular) => {
      clear();
      const keep = center.closedNeighborhood();
      cy.elements(FADEABLE).not(keep).addClass('faded');
      center.connectedEdges().addClass('hl show-label');
      syncText();
    };
    const restore = clear;
    initialView(cy);

    cy.on('tap', 'node.box, node.chip, node.group, node.appbox', (evt) => {
      const n = nodeById.get(evt.target.id());
      highlight(evt.target as NodeSingular);
      setSelected(n ?? null);
      setSelectedEdge(null);
    });
    cy.on('tap', 'edge', (evt) => {
      clear();
      const edge = evt.target;
      cy.elements(FADEABLE).not(edge.union(edge.connectedNodes())).addClass('faded');
      edge.addClass('hl show-label');
      syncText();
      setSelectedEdge(edgeById.get(edge.id()) ?? null);
      setSelected(null);
    });
    cy.on('tap', (evt) => {
      if (evt.target === cy) {
        setSelected(null);
        setSelectedEdge(null);
        restore();
      }
    });
    return () => {
      cy.destroy();
      cyRef.current = null;
    };
  }, [arch, loading, error, shown, aspect, nodeById, edgeById]);

  useEffect(() => {
    const cy = cyRef.current;
    if (!cy) return;
    if (labels) cy.edges().addClass('labels-on');
    else cy.edges().removeClass('labels-on');
  }, [labels, arch, shown, aspect]);

  const zoomBy = (f: number) => {
    const cy = cyRef.current;
    if (!cy) return;
    cy.zoom({ level: cy.zoom() * f, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } });
  };
  const fit = () => cyRef.current?.fit(undefined, 24);
  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else wrapRef.current?.requestFullscreen?.();
  };

  const doExport = (format: 'png' | 'svg' | 'drawio') => {
    setMenuOpen(false);
    const cy = cyRef.current;
    if (!cy || !arch) return;
    const base = sanitize(filename);
    if (format === 'png') {
      triggerDownload(cy.png({ full: true, scale: 2, bg: '#ffffff' }), `${base}.png`);
      return;
    }
    const text =
      format === 'svg'
        ? (cy as unknown as { svg: (o: object) => string }).svg({ full: true, bg: '#ffffff', scale: 1 })
        : architectureToDrawioXml(cy, nodeById, { iconSize: ICON, iconX: ICON_X, textX: TEXT_X });
    const type = format === 'svg' ? 'image/svg+xml;charset=utf-8' : 'application/xml;charset=utf-8';
    const url = URL.createObjectURL(new Blob([text], { type }));
    triggerDownload(url, `${base}.${format === 'svg' ? 'svg' : 'drawio'}`);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  };

  if (loading) return <div className="seq-placeholder">Building the architecture…</div>;
  if (error) {
    return (
      <div className="seq-placeholder seq-error">
        <div>Could not build the architecture view.</div>
        <div className="detail-path">{error}</div>
      </div>
    );
  }
  if (!arch) {
    return (
      <div className="seq-placeholder">
        Analyze a folder with several services to see the whole project, or one service&apos;s folder to see
        that application&apos;s architecture.
      </div>
    );
  }
  if (arch.nodes.length === 0) return <div className="seq-placeholder">No services detected in this project.</div>;

  const usedKinds = Array.from(new Set(arch.edges.map((e) => e.kind)));

  return (
    <div className="flowchart-wrap arch-wrap" ref={wrapRef}>
      <div className="flowchart-toolbar">
        {TOGGLES.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            className={shown[key] ? 'toolbar-btn sm-toggle on' : 'toolbar-btn sm-toggle'}
            onClick={() => setShown((s) => ({ ...s, [key]: !s[key] }))}
            title={`Show/hide ${label.toLowerCase()}`}
          >
            {shown[key] ? '✓ ' : ''}
            {label}
          </button>
        ))}
        <button
          type="button"
          className={labels ? 'toolbar-btn sm-toggle on' : 'toolbar-btn sm-toggle'}
          onClick={() => setLabels((v) => !v)}
          title="Show the label of every connection"
        >
          {labels ? '✓ ' : ''}Labels
        </button>
        <div className="export-menu">
          <button type="button" className="toolbar-btn" onClick={() => setMenuOpen((v) => !v)} aria-haspopup="true" aria-expanded={menuOpen}>
            Export ▾
          </button>
          {menuOpen && (
            <div className="export-dropdown" role="menu">
              <button type="button" onClick={() => doExport('png')}>PNG</button>
              <button type="button" onClick={() => doExport('svg')}>SVG</button>
              <button type="button" onClick={() => doExport('drawio')}>draw.io</button>
            </div>
          )}
        </div>
      </div>

      <div className="flowchart-canvas" ref={containerRef} />

      <div className="zoom-controls">
        <button type="button" onClick={() => zoomBy(1.25)} title="Zoom in">＋</button>
        <button type="button" onClick={() => zoomBy(0.8)} title="Zoom out">－</button>
        <button type="button" onClick={fit} title="Fit the whole project on screen">⤢</button>
        <button type="button" onClick={toggleFullscreen} title="Fullscreen">⛶</button>
      </div>

      <div className="arch-footer">
        <div className="arch-legend">
          {usedKinds.map((k) => (
            <span className="arch-legend-item" key={k}>
              <span
                className="arch-legend-line"
                style={{ borderTop: `2.5px ${k === 'async' ? 'dashed' : 'solid'} ${EDGE_COLORS[k] ?? '#94a3b8'}` }}
              />
              {EDGE_NAMES[k] ?? k}
            </span>
          ))}
          <span className="arch-legend-item">
            <span className="arch-legend-line" style={{ borderTop: '2.5px dotted #64748b' }} />
            Inferred
          </span>
        </div>
        <div className="arch-notes" title={arch.stats.notes.join('\n')}>
          {arch.stats.notes[0]}
          {arch.stats.notes.length > 1 && <span className="arch-notes-more"> · {arch.stats.notes.length - 1} more notes</span>}
        </div>
      </div>

      {selected && (
        <NodeDetail
          node={selected}
          arch={arch}
          nodeById={nodeById}
          onClose={() => {
            setSelected(null);
            cyRef.current?.elements().removeClass('faded hl show-label');
          }}
        />
      )}
      {selectedEdge && (
        <EdgeDetail
          edge={selectedEdge}
          workspace={arch.workspace}
          nodeById={nodeById}
          onClose={() => {
            setSelectedEdge(null);
            cyRef.current?.elements().removeClass('faded hl show-label');
          }}
        />
      )}
    </div>
  );
}

function NodeDetail({ node, arch, nodeById, onClose }: {
  node: ArchNode;
  arch: Architecture;
  nodeById: Map<string, ArchNode>;
  onClose: () => void;
}) {
  const meta = node.meta ?? {};
  const out = arch.edges.filter((e) => e.source === node.id);
  const inc = arch.edges.filter((e) => e.target === node.id);
  const name = (id: string) => nodeById.get(id)?.label ?? id;
  const handlers = Array.isArray(meta.handlers) ? (meta.handlers as string[]) : [];
  const inside = node.type === 'group' || node.type === 'component';
  const color = inside ? LAYER_COLOR[node.subtype] ?? '#64748b' : accentOf(node);
  const members = arch.app?.lanes.flatMap((l) => l.groups).find((g) => g.id === node.id)?.nodes ?? [];
  const endpoints = Array.isArray(meta.endpoints) ? (meta.endpoints as string[]) : [];
  const topics = topicsOf(node);
  const file = typeof meta.file === 'string' ? meta.file : null;
  const abs = file ? `${arch.workspace.replace(/\/+$/, '')}/${file}` : '';
  return (
    <div className="detail-panel">
      <div className="detail-header">
        <span className="detail-role" style={{ background: color }}>{kindOf(node)}</span>
        <button type="button" className="detail-close" onClick={onClose} aria-label="Close">✕</button>
      </div>
      <div className="detail-section">
        <div className="detail-key">Name</div>
        <div className="detail-value">{node.label}</div>
      </div>
      {typeof meta.path === 'string' && (
        <div className="detail-section">
          <div className="detail-key">Path</div>
          <div className="detail-value detail-path">{meta.path}</div>
        </div>
      )}
      {file && (
        <div className="detail-section">
          <div className="detail-key">Source</div>
          <div className="detail-value detail-path">
            <a href={`vscode://file/${abs}:${meta.line ?? 1}`} title={`Open ${abs}`}>
              {file}:{String(meta.line ?? 1)}
            </a>
          </div>
        </div>
      )}
      {endpoints.length > 0 && (
        <div className="detail-section">
          <div className="detail-key">Endpoints ({endpoints.length})</div>
          {endpoints.map((ep) => (
            <div className="detail-value detail-path" key={ep}>{ep}</div>
          ))}
        </div>
      )}
      {members.length > 0 && (
        <div className="detail-section">
          <div className="detail-key">Components ({members.length})</div>
          <div className="detail-value detail-path">{members.map(name).join(', ')}</div>
        </div>
      )}
      {topics.length > 0 && (
        <div className="detail-section">
          <div className="detail-key">Kafka topics ({topics.length})</div>
          {topics.map((t) => (
            <div className="detail-value detail-path" key={t.name}>
              {DIRECTION[t.direction] ?? t.direction} · {t.name}
            </div>
          ))}
        </div>
      )}
      {typeof meta.httpRoutes === 'number' && (
        <div className="detail-section">
          <div className="detail-key">HTTP routes served</div>
          <div className="detail-value">{meta.httpRoutes}</div>
        </div>
      )}
      {handlers.length > 0 && (
        <div className="detail-section">
          <div className="detail-key">Lambda handlers</div>
          <div className="detail-value detail-path">{handlers.join(', ')}</div>
        </div>
      )}
      {typeof meta.note === 'string' && (
        <div className="detail-section">
          <div className="detail-key">Note</div>
          <div className="detail-value">{meta.note}</div>
        </div>
      )}
      {out.length > 0 && (
        <div className="detail-section">
          <div className="detail-key">Outgoing ({out.length})</div>
          {out.map((e) => (
            <div className="detail-value" key={e.id}>
              {EDGE_NAMES[e.kind] ?? e.kind} → {name(e.target)}
              {e.items && e.items.length > 1 ? `: ${e.items.join(', ')}` : e.label && e.kind === 'async' ? `: ${e.label}` : ''}
            </div>
          ))}
        </div>
      )}
      {inc.length > 0 && (
        <div className="detail-section">
          <div className="detail-key">Incoming ({inc.length})</div>
          {inc.map((e) => (
            <div className="detail-value" key={e.id}>
              {name(e.source)} → {EDGE_NAMES[e.kind] ?? e.kind}
              {e.items && e.items.length > 1 ? `: ${e.items.join(', ')}` : e.label && e.kind === 'async' ? `: ${e.label}` : ''}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function EdgeDetail({ edge, workspace, nodeById, onClose }: {
  edge: ArchEdge;
  workspace: string;
  nodeById: Map<string, ArchNode>;
  onClose: () => void;
}) {
  const name = (id: string) => nodeById.get(id)?.label ?? id;
  const abs = (file: string) => `${workspace.replace(/\/+$/, '')}/${file}`;
  return (
    <div className="detail-panel">
      <div className="detail-header">
        <span className="detail-role" style={{ background: EDGE_COLORS[edge.kind] ?? '#94a3b8' }}>
          {EDGE_NAMES[edge.kind] ?? edge.kind}
        </span>
        <button type="button" className="detail-close" onClick={onClose} aria-label="Close">✕</button>
      </div>
      <div className="detail-section">
        <div className="detail-key">Connection</div>
        <div className="detail-value">{name(edge.source)} → {name(edge.target)}</div>
      </div>
      {edge.items && edge.items.length > 0 && (
        <div className="detail-section">
          <div className="detail-key">{edge.kind === 'async' ? 'Kafka topics' : 'Names'}</div>
          <div className="detail-value detail-path">{edge.items.join(', ')}</div>
        </div>
      )}
      <div className="detail-section">
        <div className="detail-key">Confidence</div>
        <div className="detail-value">{edge.confidence ?? 'unknown'}</div>
      </div>
      <div className="detail-section">
        <div className="detail-key">Evidence</div>
        {(edge.evidence ?? []).length === 0 && <div className="detail-value">None recorded</div>}
        {(edge.evidence ?? []).map((ev, i) => (
          <div className="detail-value detail-path" key={i} style={{ marginBottom: 8 }}>
            {ev.file && (
              <a href={`vscode://file/${abs(ev.file)}:${ev.line}`} title={`Open ${abs(ev.file)}:${ev.line}`}>
                {ev.file}:{ev.line}
              </a>
            )}
            {ev.via && <div><em>{ev.via}</em></div>}
            {ev.snippet && <code style={{ whiteSpace: 'pre-wrap' }}>{ev.snippet}</code>}
          </div>
        ))}
      </div>
    </div>
  );
}
