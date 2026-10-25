export type DragPayload = { kind: 'template'; templateKind: string; label: string } | { kind: 'node'; nodeId: string; label: string };
export type DropTarget = { nodeId: string; position: string };
export type DragView = { payload: DragPayload; x: number; y: number; target: DropTarget | null };
type CaptureElement = Pick<HTMLElement, 'setPointerCapture' | 'hasPointerCapture' | 'releasePointerCapture'>;
export type DragPointer = { button: number; pointerId: number; clientX: number; clientY: number; currentTarget: CaptureElement; preventDefault: () => void; stopPropagation: () => void };
type Gesture = { payload: DragPayload; x: number; y: number; moved: boolean; pointerId: number; element: CaptureElement };

export function createDragController(callbacks: {
  canStart: () => boolean; resolve: (x: number, y: number) => DropTarget | null;
  onView: (view: DragView | null) => void; onDrop: (payload: DragPayload, target: DropTarget | null) => void; onCancel: () => void;
}) {
  let gesture: Gesture | null = null;
  let suppressed = false;
  function release(current: Gesture | null) {
    if (current?.element.hasPointerCapture(current.pointerId)) current.element.releasePointerCapture(current.pointerId);
  }
  function cancel() {
    const current = gesture; gesture = null;
    if (current?.moved) suppressed = true;
    release(current); callbacks.onView(null);
    if (current?.moved) callbacks.onCancel();
  }
  return {
    hasGesture: () => !!gesture,
    begin(event: DragPointer, payload: DragPayload) {
      if (event.button !== 0 || gesture || !callbacks.canStart()) return;
      event.stopPropagation(); suppressed = false;
      gesture = { payload, x: event.clientX, y: event.clientY, moved: false, pointerId: event.pointerId, element: event.currentTarget };
      event.currentTarget.setPointerCapture(event.pointerId);
    },
    move(event: DragPointer) {
      const current = gesture;
      if (!current || current.pointerId !== event.pointerId) return;
      if (!current.moved && Math.hypot(event.clientX - current.x, event.clientY - current.y) < 6) return;
      current.moved = true; event.preventDefault(); event.stopPropagation();
      callbacks.onView({ payload: current.payload, x: event.clientX, y: event.clientY, target: callbacks.resolve(event.clientX, event.clientY) });
    },
    end(event: DragPointer) {
      const current = gesture;
      if (!current || current.pointerId !== event.pointerId) return;
      const target = current.moved ? callbacks.resolve(event.clientX, event.clientY) : null;
      gesture = null; release(current); callbacks.onView(null);
      if (!current.moved) return;
      event.preventDefault(); event.stopPropagation(); suppressed = true;
      callbacks.onDrop(current.payload, target);
    },
    cancel,
    lostCapture() { if (gesture) cancel(); },
    consumeClick() { const previous = suppressed; suppressed = false; return previous; },
    dispose() { const current = gesture; gesture = null; release(current); },
  };
}
