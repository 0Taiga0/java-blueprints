import { createContext, useContext, useEffect, useRef, useState } from 'react';
import type { FlowBlock } from './types';

import { createDragController, type DragPayload, type DropTarget, type DragView } from './dragController';
export type { DragPayload, DropTarget } from './dragController';

export function dropTargetAt(x: number, y: number, root: HTMLElement | null): DropTarget | null {
  const element = document.elementFromPoint(x, y)?.closest<HTMLButtonElement>('[data-flow-node][data-position]');
  if (!element || element.disabled || !root?.contains(element)) return null;
  const nodeId = element.dataset.flowNode;
  const position = element.dataset.position;
  return nodeId && position ? { nodeId, position } : null;
}

export function useSemanticDrag({ canStart, canvas, onDrop, onCancel }: {
  canStart: () => boolean; canvas: () => HTMLElement | null;
  onDrop: (payload: DragPayload, target: DropTarget | null) => void; onCancel: () => void;
}) {
  const [view, setView] = useState<DragView | null>(null);
  const latest = useRef({ canStart, canvas, onDrop, onCancel });
  latest.current = { canStart, canvas, onDrop, onCancel };
  const controllerRef = useRef<ReturnType<typeof createDragController> | null>(null);
  if (!controllerRef.current) controllerRef.current = createDragController({
    canStart: () => latest.current.canStart(),
    resolve: (x, y) => dropTargetAt(x, y, latest.current.canvas()),
    onView: setView, onDrop: (payload, target) => latest.current.onDrop(payload, target), onCancel: () => latest.current.onCancel(),
  });
  const controller = controllerRef.current;
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape' && controller.hasGesture()) { event.preventDefault(); controller.cancel(); } };
    const onBlur = () => controller.cancel();
    window.addEventListener('keydown', onKey); window.addEventListener('blur', onBlur);
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('blur', onBlur); controller.dispose(); };
  }, [controller]);
  return { view, active: !!view, ...controller };
}

export type FlowInteraction = ReturnType<typeof useSemanticDrag> & {
  enabled: boolean; zoneAllowed: (block: FlowBlock) => boolean; onZoneClick: (block: FlowBlock, position: string) => void;
};
export const FlowInteractionContext = createContext<FlowInteraction | null>(null);
export function useFlowInteraction() { return useContext(FlowInteractionContext); }

export const dropPositionNames: Record<string, string> = {
  before: '+ До блока', after: '+ После блока',
  'then-start': '+ В ветку Да', 'else-start': '+ В ветку Нет',
  'body-start': '+ Внутрь', 'body-end': '+ В конец тела',
};

export function FlowDropZones({ block }: { block: FlowBlock }) {
  const interaction = useFlowInteraction();
  const choices = block.insert?.positions ?? [];
  if (!interaction || !choices.length) return null;
  const visible = choices.filter(position => interaction.active
    ? ['before', 'after', 'then-start', 'else-start', 'body-start', 'body-end'].includes(position)
    : ['then-start', 'else-start', 'body-start'].includes(position));
  const allowed = interaction.zoneAllowed(block);
  return <div className="flow-drop-zones" data-drag-active={interaction.active}>
    {visible.map(position => {
      const active = interaction.view?.target?.nodeId === block.id && interaction.view.target.position === position;
      const label = position === 'after' && block.insert?.role === 'loop' ? '+ После цикла'
        : position === 'after' && block.insert?.role === 'if' ? '+ После веток'
        : position === 'body-start' && block.insert?.role === 'loop' ? '+ Внутри цикла'
        : position === 'body-start' && block.kind === 'start' ? '+ В начало метода'
        : dropPositionNames[position];
      return <button key={position} type="button" className={'flow-drop-zone nodrag nopan' + (active ? ' flow-drop-zone--active' : '')}
        data-flow-node={block.id} data-position={position} data-drop-position={position}
        disabled={!allowed || (!interaction.active && !interaction.enabled)}
        title={label} aria-label={label + ', строка ' + block.range.startLine}
        onPointerDown={event => { event.stopPropagation(); interaction.consumeClick(); }}
        onClick={event => { event.stopPropagation(); if (interaction.consumeClick() || interaction.active) return; interaction.onZoneClick(block, position); }}>
        {position === 'then-start' ? '+ Да' : position === 'else-start' ? '+ Нет' : position === 'body-start' ? (block.insert?.role === 'loop' ? '+ Тело цикла' : '+ Внутрь') : position === 'body-end' ? '+ В конец' : label}
      </button>;
    })}
  </div>;
}
