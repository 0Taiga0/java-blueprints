import type { FlowBlock, SourceRange } from './types';

export type DeleteMode = 'delete' | 'keep-then' | 'keep-else' | 'keep-body';
export type DeleteHandler = (nodeId: string, mode: string) => Promise<void>;
export type MoveHandler = (nodeId: string, targetNodeId: string, position: string) => Promise<void>;

export const deleteModes: Record<DeleteMode, { label: string; description: string }> = {
  delete: { label: 'Удалить целиком', description: 'Весь показанный фрагмент исчезнет из программы.' },
  'keep-then': { label: 'Оставить ветку «Да»', description: 'Условие и ветка «Нет» исчезнут. Ветка «Да» будет выполняться без этой проверки.' },
  'keep-else': { label: 'Оставить ветку «Нет»', description: 'Условие и ветка «Да» исчезнут. Ветка «Нет» будет выполняться без этой проверки.' },
  'keep-body': { label: 'Оставить тело цикла', description: 'Цикл исчезнет. Его тело выполнится один раз; проверка, начальные значения и шаг цикла не сохраняются.' },
};

const positions: Record<string, string> = {
  before: 'Перед выбранным блоком', after: 'После выбранного блока',
  'body-start': 'В начало тела', 'body-end': 'В конец тела',
  'then-start': '«Да» — в начало ветки', 'then-end': '«Да» — в конец ветки',
  'else-start': '«Нет» — в начало ветки', 'else-end': '«Нет» — в конец ветки',
};
export function positionLabel(block: FlowBlock, position: string): string {
  if (position === 'after' && block.insert?.role === 'if') return 'После обеих веток';
  if (position === 'after' && block.insert?.role === 'loop') return 'После цикла';
  if (position.startsWith('body-')) {
    const role = block.insert?.role;
    const suffix = role === 'body' ? ' метода / блока' : role === 'loop' ? ' цикла' : role === 'case' ? ' ветки case' : role === 'try' ? ' try' : '';
    return (positions[position] ?? position) + suffix;
  }
  return positions[position] ?? position;
}

function beforeOrEqual(line: number, column: number, otherLine: number, otherColumn: number) {
  return line < otherLine || (line === otherLine && column <= otherColumn);
}
export function containsRange(outer: SourceRange, inner: SourceRange): boolean {
  return beforeOrEqual(outer.startLine, outer.startColumn, inner.startLine, inner.startColumn)
    && beforeOrEqual(inner.endLine, inner.endColumn, outer.endLine, outer.endColumn);
}

export function moveTargetIssue(source: FlowBlock, target: FlowBlock): string | null {
  if (!source.mutation?.movable) return 'Этот блок нельзя переместить.';
  if (source.id === target.id) return 'Выберите другой блок для нового места.';
  if (!target.insert?.positions.length) return 'У этого блока нет места для вставки. Выберите действие, условие или начало тела.';
  // Нельзя поместить составной оператор внутрь себя или в свою копию finally.
  // Предок допустим: например, действие можно вынести из if в начало метода.
  if (containsRange(source.mutation.range, target.insert.range)) return 'Нельзя перенести блок внутрь самого себя. Выберите место вне переносимого фрагмента.';
  return null;
}

export function defaultPosition(block: FlowBlock, preferred?: string): string {
  const choices = block.insert?.positions ?? [];
  return preferred && choices.includes(preferred) ? preferred : choices.includes('after') ? 'after' : choices[0] ?? '';
}

export function canHandleNodeKey(event: { key: string; ctrlKey?: boolean; altKey?: boolean; metaKey?: boolean; target: EventTarget | null }): boolean {
  if (!['Delete', 'Enter'].includes(event.key) || event.ctrlKey || event.altKey || event.metaKey) return false;
  const target = event.target as HTMLElement | null;
  if (!target?.closest || target.closest('input, textarea, select, button, a, [contenteditable="true"], [role="textbox"]')) return false;
  return !!target.closest('.react-flow__node');
}
