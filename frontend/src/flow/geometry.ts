import type { FlowBlock } from './types';

export const BLOCK_WIDTH = 360;
export const CODE_FONT_SIZE = 16;
export const CODE_LINE_HEIGHT = 24;
export const CAPTION_LINE_HEIGHT = 16;
export const CHARACTER_WIDTH = 10; // Запас для Consolas и запасного monospace при 16px.

export type BlockGeometry = {
  width: number; height: number; maxLines: number;
  paddingX: number; paddingY: number; captionGap: number; contentWidth: number; columns: number;
};

export function blockGeometry(kind: FlowBlock['kind']): BlockGeometry {
  const terminal = kind === 'start' || kind === 'end' || kind === 'exception-end';
  const height = terminal ? 100 : 124;
  const paddingX = 16;
  const paddingY = 12;
  const contentWidth = BLOCK_WIDTH - paddingX * 2 - 2;
  return {
    width: BLOCK_WIDTH, height, maxLines: terminal ? 2 : 3,
    paddingX, paddingY, captionGap: 8,
    contentWidth, columns: Math.floor(contentWidth / CHARACTER_WIDTH),
  };
}
export const blockHeight = (kind: FlowBlock['kind']) => blockGeometry(kind).height;

// Восточноазиатские символы и emoji занимают два места даже в большинстве monospace-шрифтов.
function units(character: string): number {
  const point = character.codePointAt(0) ?? 0;
  return point >= 0x1100 && (point <= 0x115f || point >= 0x2e80 && point <= 0xa4cf
    || point >= 0xac00 && point <= 0xd7af || point >= 0xf900 && point <= 0xfaff || point >= 0x1f000) ? 2 : 1;
}
export function textUnits(text: string): number { return Array.from(text).reduce((sum, character) => sum + units(character), 0); }

export function wrapNodeText(text: string, columns: number, maxLines: number): { text: string; truncated: boolean; lines: string[] } {
  const source = text.replace(/\r\n?/g, '\n').replace(/\t/g, '    ').trim();
  const paragraphs = source.split('\n');
  const lines: string[] = [];
  let remaining = false;
  for (let paragraphIndex = 0; paragraphIndex < paragraphs.length; paragraphIndex++) {
    let characters = Array.from(paragraphs[paragraphIndex]);
    do {
      if (lines.length === maxLines) { remaining = true; break; }
      let count = 0, end = 0;
      while (end < characters.length && count + units(characters[end]) <= columns) count += units(characters[end++]);
      if (end < characters.length) {
        // Разрыв по пробелу сохраняет читаемость выражений и целостность && / ||.
        const space = characters.slice(0, end).lastIndexOf(' ');
        if (space >= Math.floor(end / 2)) end = space + 1;
        if (end > 1 && ['&', '|'].includes(characters[end - 1]) && characters[end - 1] === characters[end]) end--;
      }
      lines.push(characters.slice(0, end).join('').trimEnd());
      characters = characters.slice(end);
      if (characters.length && lines.length === maxLines) { remaining = true; break; }
    } while (characters.length);
    if (remaining || lines.length === maxLines && paragraphIndex < paragraphs.length - 1) { remaining = true; break; }
  }
  if (remaining && lines.length) {
    const last = Array.from(lines[lines.length - 1]);
    while (textUnits(last.join('')) + 1 > columns) last.pop();
    lines[lines.length - 1] = last.join('').trimEnd() + '…';
  }
  return { text: lines.join('\n'), truncated: remaining, lines };
}

export function nodePresentation(block: FlowBlock, signature?: string) {
  const geometry = blockGeometry(block.kind);
  const summaries: FlowBlock['kind'][] = ['try', 'finally', 'opaque', 'file', 'end', 'exception-end'];
  const fullText = block.code || block.label;
  const display = block.kind === 'start' && signature ? signature
    : summaries.includes(block.kind) ? block.label : fullText;
  return { ...wrapNodeText(display, geometry.columns, geometry.maxLines), fullText,
    summarized: display !== fullText, geometry };
}

export type PositionedBlock = { position: { x: number; y: number }; width?: number; height?: number; data: { kind: FlowBlock['kind'] } };
export function initialGraphViewport(nodes: PositionedBlock[], viewport: { width: number; height: number }) {
  if (!nodes.length) return { x: 0, y: 0, zoom: 1 };
  const left = Math.min(...nodes.map(node => node.position.x));
  const top = Math.min(...nodes.map(node => node.position.y));
  const right = Math.max(...nodes.map(node => node.position.x + (node.width ?? BLOCK_WIDTH)));
  const bottom = Math.max(...nodes.map(node => node.position.y + (node.height ?? blockHeight(node.data.kind))));
  const padding = 40;
  const fit = Math.min((viewport.width - padding * 2) / Math.max(1, right - left), (viewport.height - padding * 2) / Math.max(1, bottom - top), 1);
  if (fit >= 0.9) return { x: viewport.width / 2 - (left + right) / 2 * fit, y: viewport.height / 2 - (top + bottom) / 2 * fit, zoom: fit };
  // Большую схему начинаем читать с начала; полный обзор доступен отдельной кнопкой.
  const entry = nodes.find(node => node.data.kind === 'start' || node.data.kind === 'file') ?? nodes[0];
  const zoom = viewport.width < BLOCK_WIDTH + padding * 2 ? Math.max(0.75, (viewport.width - 24) / BLOCK_WIDTH) : 1;
  return { x: viewport.width / 2 - (entry.position.x + (entry.width ?? BLOCK_WIDTH) / 2) * zoom,
    y: padding - entry.position.y * zoom, zoom };
}
