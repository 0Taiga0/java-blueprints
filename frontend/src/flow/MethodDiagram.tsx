import { useEffect, useId, useRef, useState } from 'react';
import { Background, BaseEdge, Controls, Handle, Position, ReactFlow, useNodesState,
  type EdgeProps, type NodeProps, type ReactFlowInstance } from '@xyflow/react';
import { GitBranch, Maximize2, MousePointer2, Pencil, PanelLeft, PanelRight, Search, Plus, Trash2, MoveRight, X, Diamond, Repeat2, Scan } from 'lucide-react';
import { BLOCK_WIDTH, blockHeight, layoutMethod, type DiagramEdge, type DiagramNode } from './layout';
import type { MethodFlow, SourceRange } from './types';
import { CODE_FONT_SIZE, CODE_LINE_HEIGHT, initialGraphViewport } from './geometry';
import { primaryFlow } from './visibility';
import { FlowInteractionContext, FlowDropZones, useFlowInteraction, useSemanticDrag, type DragPayload, type DropTarget } from './SemanticDrag';
import type { VisualContext, VisualApplyHandler } from './visual/types';
import '@xyflow/react/dist/style.css';
import { BlockEditor } from './BlockEditor';
import { BlockDeleteForm, BlockMoveForm } from './BlockMutationForms';
import { canHandleNodeKey, moveTargetIssue, positionLabel, type DeleteHandler, type MoveHandler } from './mutation';
import { AnalysisPanel } from '../analysis/AnalysisPanel';
import { navigateTabs } from '../analysis/tabNavigation';
import type { AnalysisNavigation, AnalysisReport } from '../analysis/types';
import { BlockPalette, BlockInsertForm, templates, type InsertHandler } from './BlockPalette';

const names: Record<string, string> = {
  start: 'НАЧАЛО', end: 'КОНЕЦ', operation: 'ДЕЙСТВИЕ', condition: 'УСЛОВИЕ / ЦИКЛ',
  switch: 'SWITCH', case: 'ВЕТКА CASE', try: 'TRY', catch: 'CATCH · ПРОВЕРКА ТИПА', finally: 'FINALLY',
  yield: 'ЗНАЧЕНИЕ SWITCH', resource: 'РЕСУРС / МОНИТОР', evaluation: 'ВЫЧИСЛЕНИЕ', 'exception-end': 'ВЫХОД ПО ИСКЛЮЧЕНИЮ',
  file: 'ФАЙЛ', class: 'КЛАСС', interface: 'ИНТЕРФЕЙС', enum: 'ENUM', record: 'RECORD', field: 'ПОЛЕ',
  method: 'МЕТОД', constructor: 'КОНСТРУКТОР', initializer: 'ИНИЦИАЛИЗАТОР', library: 'ТИП / ИМПОРТ',
  return: 'ВОЗВРАТ', throw: 'ИСКЛЮЧЕНИЕ', jump: 'ПЕРЕХОД', opaque: 'ОБЗОР',
};

const categoryNames: Record<string,string> = { input: 'ВВОД', io: 'ВВОД / ВЫВОД', collections: 'КОЛЛЕКЦИИ', variable: 'ПЕРЕМЕННАЯ', 'java.lang': 'JAVA.LANG' };

function JavaBlock({ data, selected }: NodeProps<DiagramNode>) {
  const { geometry, text, truncated, fullText } = data.presentation;
  const interaction = useFlowInteraction();
  const loop = data.insert?.role === 'loop';
  return <div className="flow-node-wrapper" data-flow-node={data.id} style={{ width: geometry.width, height: geometry.height }}><div className={'java-block nodrag nopan ' + data.kind + (loop ? ' java-block--loop' : '') + ' category-' + data.category + (selected ? ' active' : '') + (data.issueCount ? ' java-block--has-issue' : '')}
    data-truncated={truncated} data-movable={!!data.mutation?.movable}
    onPointerDown={event => { if (data.mutation?.movable) interaction?.begin(event, { kind: 'node', nodeId: data.id, label: data.code || data.label }); }}
    onPointerMove={interaction?.move} onPointerUp={interaction?.end} onPointerCancel={interaction?.cancel} onLostPointerCapture={interaction?.lostCapture}
    style={{ width: geometry.width, height: geometry.height, boxSizing: 'border-box',
      padding: geometry.paddingY + 'px ' + geometry.paddingX + 'px' }} title={fullText}>
    <Handle type="target" position={Position.Top} id="in" />
    <Handle type="target" position={Position.Left} id="back-in" style={{ top: '35%' }} />
    <div className="block-caption" style={{ fontSize: 12, lineHeight: '16px', marginBottom: geometry.captionGap }}>
      <span title={names[data.kind]}>{data.kind === 'condition' && (loop ? <Repeat2 size={14} /> : <Diamond size={13} />)}{loop ? 'ЦИКЛ' : ['operation','evaluation'].includes(data.kind) ? categoryNames[data.category] ?? names[data.kind] : data.kind === 'condition' ? 'УСЛОВИЕ' : names[data.kind]}</span>
      <span>стр. {data.range.startLine}</span>
    </div>
    <div className="block-code" style={{ fontSize: CODE_FONT_SIZE, lineHeight: CODE_LINE_HEIGHT + 'px', display: 'block', whiteSpace: 'pre',
      overflowWrap: 'normal', wordBreak: 'normal', WebkitLineClamp: 'unset' }}>{text}</div>
    {!!data.issueCount && <span className="block-issue-badge" aria-label={data.issueCount + ' замечаний анализа'} title={data.issueCount + ' замечаний анализа'}>! {data.issueCount}</span>}
    <Handle type="source" position={Position.Bottom} id="out" />
    <Handle type="source" position={Position.Bottom} id="yes" style={{ left: '35%' }} />
    <Handle type="source" position={Position.Bottom} id="no" style={{ left: '65%' }} />
    <Handle type="source" position={Position.Right} id="exception-out" />
    <Handle type="target" position={Position.Right} id="exception-in" />
    <Handle type="source" position={Position.Left} id="back-out" style={{ top: '65%' }} />
  </div><FlowDropZones block={data} /></div>;
}

function JavaEdge(props: EdgeProps<DiagramEdge>) {
  const route = props.data?.route;
  if (!route) return null;
  return <BaseEdge id={props.id} path={route.path} markerEnd={props.markerEnd} style={props.style}
    label={props.label} labelX={route.labelX} labelY={route.labelY}
    labelStyle={props.labelStyle} labelBgStyle={props.labelBgStyle} labelBgPadding={[7, 4]} labelBgBorderRadius={5} />;
}

const nodeTypes = { javaBlock: JavaBlock };
const edgeTypes = { javaEdge: JavaEdge };

type InspectorTab = 'problems' | 'complexity' | 'block';

function useDiagramPanels() {
  const [leftOpen, setLeftOpen] = useState(() => window.matchMedia('(min-width: 1051px)').matches);
  const [rightOpen, setRightOpen] = useState(false);
  const [tab, setTab] = useState<InspectorTab>('block');
  const [currentOnly, setCurrentOnly] = useState(true);
  function openInspector(next: InspectorTab) {
    setTab(next); setRightOpen(true);
    if (window.matchMedia('(max-width: 1050px)').matches) setLeftOpen(false);
  }
  function toggleLeft() {
    setLeftOpen(!leftOpen);
    if (!leftOpen && window.matchMedia('(max-width: 1050px)').matches) setRightOpen(false);
  }
  function toggleRight() {
    setRightOpen(!rightOpen);
    if (!rightOpen && window.matchMedia('(max-width: 1050px)').matches) setLeftOpen(false);
  }
  useEffect(() => {
    const compact = window.matchMedia('(max-width: 1050px)');
    const narrow = window.matchMedia('(max-width: 1050px)');
    const onCompact = () => { if (compact.matches) setLeftOpen(false); };
    // Открытый инспектор сохраняем: в нём может быть незавершённая форма.
    const onNarrow = () => { if (narrow.matches) setLeftOpen(false); };
    compact.addEventListener('change', onCompact); narrow.addEventListener('change', onNarrow);
    return () => { compact.removeEventListener('change', onCompact); narrow.removeEventListener('change', onNarrow); };
  }, []);
  return { leftOpen, rightOpen, tab, setTab, currentOnly, setCurrentOnly, openInspector, toggleLeft, toggleRight, hideRight: () => setRightOpen(false) };
}

function MethodCanvas({ method, onNavigate, onReveal, busy, onEdit, onInsert, onDelete, onMove, onInteractionLock, selectedNodeId, onNodeSelected, onOpenMethod, structureTypes, focusedType, onFocusType, analysis, onAnalysisNavigate, focusToken, panels, visualContext, onVisualApply }: {
  method: MethodFlow; onNavigate: (range: SourceRange) => void; onReveal: (range: SourceRange) => void;
  busy: boolean; onEdit: (nodeId: string, replacement: string) => Promise<void>; onInsert: InsertHandler; onDelete: DeleteHandler; onMove: MoveHandler; onInteractionLock: (locked: boolean) => void; selectedNodeId: string; onNodeSelected: (id: string) => void; onOpenMethod: (id: string) => void;
  structureTypes: MethodFlow['nodes']; focusedType: string; onFocusType: (id: string) => void;
  analysis: AnalysisReport; onAnalysisNavigate: AnalysisNavigation; focusToken: number; panels: ReturnType<typeof useDiagramPanels>;
  visualContext?: VisualContext; onVisualApply?: VisualApplyHandler;
}) {
  const [layout] = useState(() => {
    const result = layoutMethod(method);
    result.nodes.forEach(n => {
      n.selected = n.id === selectedNodeId;
      const issues = analysis.findings.filter(finding => method.mode === 'structure'
        ? finding.methodId === n.data.targetMethodId
        : finding.methodId === method.id && finding.nodeIds.includes(n.id));
      n.data = { ...n.data, issueCount: issues.length };
    });
    return result;
  });
  const [nodes, setNodes, onNodesChange] = useNodesState<DiagramNode>(layout.nodes);
  const [selected, setSelected] = useState<DiagramNode | null>(() => layout.nodes.find(n => n.id === selectedNodeId) ?? null);
  const [editing, setEditing] = useState<DiagramNode | null>(null);
  const [adding, setAdding] = useState<{ kind: string; node: DiagramNode; position?: string } | null>(null);
  const [deleting, setDeleting] = useState<DiagramNode | null>(null);
  const [moving, setMoving] = useState<{ source: DiagramNode; target: DiagramNode | null } | null>(null);
  const [dropPendingState, setDropPendingState] = useState(false);
  const dropPending = useRef(false);
  const [hint, setHint] = useState('');
  const [search, setSearch] = useState('');
  const [searchIndex, setSearchIndex] = useState(0);
  const [zoom, setZoom] = useState(1);
  const inspectorId = useId();
  const canvasRef = useRef<HTMLDivElement>(null);
  const instance = useRef<ReactFlowInstance<DiagramNode, DiagramEdge> | null>(null);
  const localSelectionRef = useRef<string | null>(null);
  const lastFocusToken = useRef(focusToken);
  const locked = busy || dropPendingState || !!editing || !!adding || !!deleting || !!moving;
  const navigationLocked = busy || dropPendingState || !!editing || !!adding || !!deleting;
  const drag = useSemanticDrag({
    canStart: () => !locked && !dropPending.current && method.mode === 'flow',
    canvas: () => canvasRef.current,
    onDrop: (payload, target) => { void performDrop(payload, target); },
    onCancel: () => setHint('Перетаскивание отменено. Программа не изменилась.'),
  });
  useEffect(() => { onInteractionLock(locked || drag.active); return () => onInteractionLock(false); }, [locked, drag.active, onInteractionLock]);
  async function performDrop(payload: DragPayload, target: DropTarget | null) {
    if (locked || dropPending.current) return;
    const anchor = nodes.find(node => node.id === target?.nodeId);
    if (!target || !anchor?.data.insert?.positions.includes(target.position)) {
      setHint('Отпустите блок в зоне «До», «После», «Да», «Нет» или «Внутрь». Вне зоны программа не меняется.'); return;
    }
    const source = payload.kind === 'node' ? nodes.find(node => node.id === payload.nodeId) : null;
    if (payload.kind === 'node') {
      const issue = source ? moveTargetIssue(source.data, anchor.data) : 'Переносимый блок больше не найден.';
      if (issue) { setHint(issue); return; }
    }
    const template = payload.kind === 'template' ? templates.find(item => item.kind === payload.templateKind) : null;
    if (payload.kind === 'template' && !template) return;
    if (template && !source) {
      setHint(''); setSelected(anchor); setAdding({ node: anchor, kind: template.kind, position: target.position });
      panels.openInspector('block');
      return;
    }
    dropPending.current = true; setDropPendingState(true); setHint(''); panels.hideRight();
    try {
      if (source) await onMove(source.id, anchor.id, target.position);
    } catch (error) { setHint(error instanceof Error ? error.message : 'Не удалось изменить программу. Попробуйте другое место.'); }
    finally { dropPending.current = false; setDropPendingState(false); }
  }
  function zoneAllowed(block: MethodFlow['nodes'][number]) {
    if (!block.insert?.positions.length || busy || dropPendingState) return false;
    const payload = drag.view?.payload;
    if (payload?.kind !== 'node') return !locked;
    const source = nodes.find(node => node.id === payload.nodeId);
    return !!source && !moveTargetIssue(source.data, block);
  }
  const displayedNodes = nodes.map(node => ({ ...node,
    selected: moving ? node.id === moving.source.id : node.selected,
    className: [moving?.source.id === node.id || (drag.view?.payload.kind === 'node' && drag.view.payload.nodeId === node.id) ? 'graph-move-source' : '',
      moving?.target?.id === node.id ? 'graph-move-target' : '', drag.view?.target?.nodeId === node.id ? 'graph-insert-target' : ''].filter(Boolean).join(' '),
  }));
  const matches = search.trim() ? nodes.filter(node => (node.data.code + ' ' + node.data.label).toLocaleLowerCase().includes(search.trim().toLocaleLowerCase())) : [];
  function focusNode(id: string) {
    const target = instance.current?.getNode(id) ?? layout.nodes.find(node => node.id === id);
    if (target) void instance.current?.setCenter(
      target.position.x + BLOCK_WIDTH / 2, target.position.y + blockHeight(target.data.kind) / 2,
      { zoom: Math.max(1, Math.min(instance.current?.getZoom() ?? 1, 1.1)), duration: 200 },
    );
  }
  // Внешняя навигация срабатывает и при повторном выборе той же проблемы.
  useEffect(() => {
    const externalFocus = lastFocusToken.current !== focusToken || localSelectionRef.current !== selectedNodeId;
    lastFocusToken.current = focusToken; localSelectionRef.current = null;
    setSelected(layout.nodes.find(node => node.id === selectedNodeId) ?? null);
    setNodes(current => current.map(node => ({ ...node, selected: node.id === selectedNodeId })));
    // Щелчок только выделяет блок. Поиск, мутация и внешняя навигация фокусируют явно.
    const frame = requestAnimationFrame(() => { if (selectedNodeId && externalFocus) focusNode(selectedNodeId); });
    return () => cancelAnimationFrame(frame);
  }, [selectedNodeId, focusToken, layout, setNodes]);
  function select(node: DiagramNode) {
    if (navigationLocked) return;
    if (moving) {
      const issue = moveTargetIssue(moving.source.data, node.data);
      if (issue) { setHint(issue); setMoving(current => current ? { ...current, target: null } : current); return; }
      setHint(''); setMoving(current => current ? { ...current, target: node } : current);
      panels.openInspector('block'); onNavigate(node.data.range);
      return;
    }
    localSelectionRef.current = node.id;
    setHint(''); setSelected(node); panels.setTab('block'); if (method.mode === 'structure') panels.openInspector('block'); onNodeSelected(node.id);
    setNodes(current => current.map(item => ({ ...item, selected: item.id === node.id })));
    onNavigate(node.data.range);
  }
  function choose(kind: string, dropped?: DiagramNode, position?: string) {
    if (locked || !templates.some(t => t.kind === kind)) return;
    panels.openInspector('block');
    const anchor = dropped ?? selected ?? nodes.find(n => n.data.kind === 'start');
    if (!anchor?.data.insert?.positions.length) {
      setHint('Выберите начало метода, действие или условие. Части заголовка for не подходят для вставки.');
      return;
    }
    select(anchor); setAdding({ kind, node: anchor, position });
  }
  function focusGraphNode(id: string) {
    requestAnimationFrame(() => {
      const element = [...(canvasRef.current?.querySelectorAll<HTMLElement>('.react-flow__node') ?? [])].find(node => node.dataset.id === id);
      element?.focus();
    });
  }
  function beginDelete(node: DiagramNode) {
    if (locked || !node.data.mutation?.deleteModes.length) return;
    select(node); panels.openInspector('block'); setDeleting(node);
  }
  function beginMove(node: DiagramNode) {
    if (locked || !node.data.mutation?.movable) return;
    select(node); panels.openInspector('block'); setMoving({ source: node, target: null });
    if (window.matchMedia('(max-width: 800px)').matches) panels.hideRight();
  }
  function closeDelete() { if (!busy && deleting) { const id = deleting.id; setDeleting(null); setHint(''); focusGraphNode(id); } }
  function closeMove() { if (!busy && moving) { const id = moving.source.id; setMoving(null); setHint(''); focusGraphNode(id); } }
  function pickMoveTarget() {
    if (window.matchMedia('(max-width: 800px)').matches) panels.hideRight();
    if (moving) focusGraphNode(moving.target?.id ?? moving.source.id);
  }
  const visibleIssues = analysis.findings.filter(finding => method.mode === 'structure' || finding.methodId === method.id).length;
  return <FlowInteractionContext.Provider value={{ ...drag, enabled: !locked && method.mode === 'flow', zoneAllowed,
    onZoneClick: (block, position) => choose('operation', nodes.find(node => node.id === block.id), position) }}>
    <div className="graph-shell" data-drag-active={drag.active}>
    <div className="studio-graph-controls">
      <button type="button" className="text-button" aria-expanded={panels.leftOpen} aria-controls={inspectorId + '-palette'} disabled={locked}
        onClick={panels.toggleLeft}><PanelLeft size={15} />{method.mode === 'structure' ? 'Объявления' : 'Блоки'}</button>
      <form className="graph-search" onSubmit={event => {
        event.preventDefault();
        if (!matches.length || navigationLocked) return;
        const match = matches[searchIndex % matches.length];
        select(match); focusNode(match.id); setSearchIndex(index => index + 1);
      }}>
        <Search size={14} /><input aria-label="Найти блок на схеме" placeholder="Найти блок…" value={search} disabled={navigationLocked}
          onChange={event => { setSearch(event.target.value); setSearchIndex(0); }} />
        <button type="submit" disabled={navigationLocked || !matches.length}>Найти</button>
        {search.trim() && <small className="graph-search-count" role="status">{matches.length ? matches.length + ' найдено' : 'Нет совпадений'}</small>}
      </form>
      <button type="button" className="text-button" onClick={() => void instance.current?.fitView({ padding: 0.16, maxZoom: 1, duration: 200 })}>
        <Maximize2 size={14} />Вся схема</button>
      <button type="button" className="text-button graph-readable" title="Читаемый масштаб: выбранный блок или начало метода" onClick={() => {
        const target = selected ?? nodes.find(node => node.data.kind === 'start') ?? nodes[0];
        if (target) void instance.current?.setCenter(target.position.x + BLOCK_WIDTH / 2, target.position.y + blockHeight(target.data.kind) / 2, { zoom: 1, duration: 200 });
      }}><Scan size={14} />Читать · {Math.round(zoom * 100)}%</button>
      <button type="button" className="text-button" aria-expanded={panels.rightOpen} aria-controls={inspectorId + '-inspector'} disabled={busy || !!editing || !!adding || !!deleting}
        onClick={panels.toggleRight}><PanelRight size={15} />Анализ / блок</button>
    </div>
    {drag.active && <div className="graph-drag-banner" role="status">{drag.view?.payload.kind === 'template' ? 'Выберите место — затем настройте новый блок.' : 'Отпустите в зоне, чтобы перенести блок.'} Escape — отмена.</div>}
    {drag.view && <div className="palette-drag-preview" style={{ left: drag.view.x + 14, top: drag.view.y + 14, pointerEvents: 'none' }}>
      {drag.view.payload.label}<small>{drag.view.target ? positionLabel(nodes.find(node => node.id === drag.view!.target!.nodeId)!.data, drag.view.target.position) : 'Наведите на зону вставки'}</small></div>}
    {moving && <div className="graph-move-banner" role="status"><MoveRight size={16} /><span><strong>Перенос логики</strong> · {moving.target ? 'Назначение: строка ' + moving.target.data.range.startLine + '. Выберите позицию и подтвердите справа.' : 'Выберите место на схеме. Переносимый блок отмечен пунктиром.'}</span>
      <button className="text-button" type="button" disabled={busy} onClick={closeMove}><X size={14} />Отмена</button></div>}
    {hint && <div className="graph-action-hint" role="status">{hint}</div>}
    <div className="graph-workbench" data-left-open={panels.leftOpen} data-right-open={panels.rightOpen}>
      <div className="studio-left-panel" id={inspectorId + '-palette'} hidden={!panels.leftOpen}>
        {method.mode === 'structure' ? <aside className="structure-rail">
          <div className="rail-heading">ОБЪЯВЛЕНИЯ</div>
          <button className="text-button" disabled={locked} onClick={() => onFocusType('')}>← Все классы</button>
          <div className="structure-list">{structureTypes.map(n => <button key={n.id} className={n.id === focusedType ? 'current' : ''} disabled={locked}
            onClick={() => onFocusType(n.id)} title={n.code || n.label}>{n.label}</button>)}</div>
          {focusedType && <div className="structure-members">{nodes.filter(n => ['field','method','constructor','initializer'].includes(n.data.kind)).map(n =>
            <button key={n.id} disabled={locked} onClick={() => { select(n); focusNode(n.id); }} title={n.data.code || n.data.label}>{n.data.label}</button>)}</div>}
          <details className="structure-legend"><summary>Как читать структуру</summary><p>Линии обозначают состав, наследование, throws и типы. Выберите метод и откройте его логику справа.</p></details>
        </aside> : <BlockPalette disabled={locked || method.mode !== 'flow'} onChoose={choose} />}
      </div>
      <div className="diagram-center">
        <div className="diagram-canvas" ref={canvasRef}>
          <ReactFlow<DiagramNode, DiagramEdge> nodes={displayedNodes} edges={layout.edges.map(edge => ({ ...edge,
            className: selected && edge.source !== selected.id && edge.target !== selected.id ? 'edge-muted' : '',
          }))}
            nodeTypes={nodeTypes} edgeTypes={edgeTypes} onNodesChange={onNodesChange}
            onPaneClick={() => {
              if (locked || drag.active) return;
              localSelectionRef.current = ''; setSelected(null); onNodeSelected('');
              setNodes(current => current.map(node => ({ ...node, selected: false })));
            }}
            onNodeClick={(_, node) => { if (!drag.consumeClick()) select(node); }}
            onNodeDoubleClick={(_, node) => { if (!locked && node.data.edit) { select(node); setEditing(node); } }}
            onKeyDown={event => {
              if (!canHandleNodeKey(event)) return;
              const id = (event.target as HTMLElement).closest<HTMLElement>('.react-flow__node')?.dataset.id;
              const node = nodes.find(item => item.id === id);
              if (node) {
                event.preventDefault(); event.stopPropagation();
                if (event.key === 'Delete') { beginDelete(node); return; }
                select(node);
                if (!moving && !navigationLocked) { panels.openInspector('block'); requestAnimationFrame(() => document.getElementById(inspectorId + '-full-code')?.focus()); }
              }
            }}
            onInit={flow => {
              instance.current = flow;
              requestAnimationFrame(() => {
                if (layout.nodes.some(node => node.id === selectedNodeId)) focusNode(selectedNodeId);
                else void flow.setViewport(initialGraphViewport(layout.nodes, {
                  width: canvasRef.current?.clientWidth || 640, height: canvasRef.current?.clientHeight || 540,
                }));
              });
            }}
            onMove={(_, viewport) => setZoom(viewport.zoom)}
            minZoom={0.03} maxZoom={1.8} nodesConnectable={false} edgesReconnectable={false}
            nodesDraggable={false} panOnDrag={!drag.active} deleteKeyCode={null} panOnScroll={false} zoomOnDoubleClick={false}
            colorMode="dark" aria-label="Блок-схема выбранного метода">
            <Background color="#253142" gap={24} size={0.65} />
            <Controls showInteractive={false} showFitView={false} />
          </ReactFlow>
          {editing && <div className="block-editor-overlay"><BlockEditor key={editing.id} block={editing.data} busy={busy} onClose={() => setEditing(null)} onApply={onEdit} visualContext={visualContext} onVisualApply={onVisualApply} /></div>}
          <span className="diagram-count">{method.nodes.length} блоков · {method.edges.length} связей</span>
        </div>

      </div>
      <aside className="block-inspector" id={inspectorId + '-inspector'} hidden={!panels.rightOpen} aria-label="Анализ и настройки блока">
        <div className="inspector-tabs" role="tablist" aria-label="Панель схемы" onKeyDown={navigateTabs}>
          {(['problems', 'complexity', 'block'] as const).map(tab => <button key={tab} className="inspector-tab" role="tab"
            id={inspectorId + '-' + tab} aria-controls={inspectorId + '-panel'} tabIndex={panels.tab === tab ? 0 : -1}
            aria-selected={panels.tab === tab} disabled={locked} onClick={() => panels.setTab(tab)}>
            {tab === 'problems' ? <>Проблемы <span className="analysis-count">{visibleIssues}</span></> : tab === 'complexity' ? 'Big O' : 'Блок'}
          </button>)}
        </div>
        <div role="tabpanel" id={inspectorId + '-panel'} aria-labelledby={inspectorId + '-' + panels.tab}>
          <div hidden={panels.tab === 'block'}>
            <AnalysisPanel report={analysis} methodId={method.id} disabled={locked} onNavigate={onAnalysisNavigate}
              embedded activeTab={panels.tab === 'complexity' ? 'complexity' : 'problems'} currentOnly={panels.currentOnly} onCurrentOnlyChange={panels.setCurrentOnly} />
          </div>
          <div hidden={panels.tab !== 'block'} className="inspector-block-content">
            <div className="rail-heading">{adding ? 'ДОБАВЛЕНИЕ' : deleting ? 'УДАЛЕНИЕ' : moving ? 'ПЕРЕНОС ЛОГИКИ' : 'ВЫБРАННЫЙ БЛОК'}</div>
            {selected && !adding && !deleting && !moving ? <>
              <div className="selected-summary"><small>{names[selected.data.kind]} · СТРОКА {selected.data.range.startLine}</small>
                <code tabIndex={0} id={inspectorId + '-full-code'} aria-label="Полный код выбранного блока">{selected.data.code || selected.data.label}</code></div>
              {selected.data.detail && <details className="node-detail"><summary>Описание блока</summary><p>{selected.data.detail}</p></details>}
              <div className="inspector-actions">
                {selected.data.targetMethodId && <button className="button primary" disabled={locked} onClick={() => onOpenMethod(selected.data.targetMethodId!)}>Открыть логику →</button>}
                <button className="button secondary" onClick={() => onReveal(selected.data.range)}>К коду ↓</button>
                {selected.data.edit && <button className="button secondary" disabled={locked} onClick={() => setEditing(selected)}><Pencil size={13} />Изменить блок</button>}
                {!!selected.data.insert?.positions.length && <button className="button secondary" disabled={locked} onClick={() => choose('operation', selected)}><Plus size={14} />Блок</button>}
                {!!selected.data.mutation?.deleteModes.length && <button className="button secondary" disabled={locked} onClick={() => beginDelete(selected)}><Trash2 size={14} />Удалить</button>}
                {selected.data.mutation?.movable && <button className="button secondary" disabled={locked} onClick={() => beginMove(selected)}><MoveRight size={14} />Переместить</button>}
              </div>
              {!!selected.data.insert?.positions.length && <div className="insert-quick-actions" aria-label="Быстрое добавление">
                {['then-start', 'else-start', 'body-start'].filter(position => selected.data.insert!.positions.includes(position)).map(position =>
                  <button className="text-button" type="button" disabled={locked} key={position} title={positionLabel(selected.data, position)} onClick={() => choose('operation', selected, position)}>
                    <Plus size={12} />{position === 'then-start' ? 'В «Да»' : position === 'else-start' ? 'В «Нет»' : 'В тело'}</button>)}
              </div>}
              {method.mode === 'flow' && <p className="mutation-note">Перетащите блок в нужную зону — порядок Java-кода изменится сразу. Двойной щелчок открывает параметры.</p>}
            </> : !adding && !deleting && !moving && <div className="inspector-empty"><MousePointer2 size={24} /><p>Выберите блок, чтобы увидеть полный код и действия.</p>
              {method.mode === 'flow' && <button className="button secondary" disabled={locked} onClick={() => choose('operation')}><Plus size={14} />Добавить блок в метод</button>}</div>}
            {adding && <BlockInsertForm key={adding.node.id + adding.kind} block={adding.node.data} kind={adding.kind} busy={busy} initialPosition={adding.position}
              visualContext={visualContext} onVisualApply={onVisualApply}
              onInsert={onInsert} onClose={() => { if (!busy) { const id = adding.node.id; setAdding(null); focusGraphNode(id); } }} returnCode={method.nodes.find(n => n.kind === 'return')?.edit?.text} />}
            {deleting && <BlockDeleteForm block={deleting.data} busy={busy} onDelete={onDelete} onClose={closeDelete} />}
            {moving && <BlockMoveForm source={moving.source.data} target={moving.target?.data ?? null} busy={busy} onMove={onMove} onClose={closeMove} onPickTarget={pickMoveTarget} />}
          </div>
        </div>
      </aside>
    </div>
  </div></FlowInteractionContext.Provider>;
}

type Props = {
  revision: string; selectedNodeId: string; busy: boolean; fileName: string;
  visualContext?: VisualContext; onVisualApply?: VisualApplyHandler;
  onInteractionLockChange?: (locked: boolean) => void;
  analysis: AnalysisReport; onAnalysisNavigate: AnalysisNavigation; focusToken: number;
  onEdit: (nodeId: string, replacement: string) => Promise<void>; onInsert: InsertHandler; onDelete: DeleteHandler; onMove: MoveHandler;
  methods: MethodFlow[]; selectedId: string; onSelect: (id: string) => void;
  onNavigate: (range: SourceRange) => void; onReveal: (range: SourceRange) => void; onOpenMethod: (id: string) => void;
};

export function MethodDiagram({ methods, selectedId, onSelect, onNavigate, onReveal, revision, selectedNodeId, busy, onEdit, onInsert, onDelete, onMove, fileName, onOpenMethod, analysis, onAnalysisNavigate, focusToken, onInteractionLockChange, visualContext, onVisualApply }: Props) {
  const method = methods.find(item => item.id === selectedId);
  const [showExceptions, setShowExceptions] = useState(false);
  const [showLibraries, setShowLibraries] = useState(false);
  const [focusedType, setFocusedType] = useState('');
  const panels = useDiagramPanels();
  const [interactionLocked, setInteractionLocked] = useState(false);
  useEffect(() => { onInteractionLockChange?.(interactionLocked); return () => onInteractionLockChange?.(false); }, [interactionLocked, onInteractionLockChange]);
  const selectionKey = revision + ':' + selectedId;
  const [localSelection, setLocalSelection] = useState({ key: selectionKey, id: selectedNodeId });
  useEffect(() => { setLocalSelection({ key: selectionKey, id: selectedNodeId }); }, [selectionKey, selectedNodeId, focusToken]);
  const currentNodeId = localSelection.key === selectionKey ? localSelection.id : selectedNodeId;
  const navigateFinding: AnalysisNavigation = (finding, destination) => { panels.openInspector('problems'); onAnalysisNavigate(finding, destination); };
  // Замечание должно быть видно даже при ранее выключенных исключениях.
  useEffect(() => { if (focusToken > 0) {
    if (method?.nodes.some(node => node.id === selectedNodeId) && !primaryFlow(method).nodes.some(node => node.id === selectedNodeId)) setShowExceptions(true);
    panels.openInspector('problems');
  } }, [focusToken]);
  const structureTypes = method?.nodes.filter(n => ['class','interface','enum','record'].includes(n.kind)) ?? [];
  let visibleMethod = method;
  if (method?.mode === 'structure') {
    const focus = structureTypes.some(n => n.id === focusedType) ? focusedType : '';
    const ids = new Set(focus ? [focus] : method.nodes.filter(n => n.kind === 'file' || structureTypes.some(t => t.id === n.id)).map(n => n.id));
    if (focus) method.edges.filter(e => e.source === focus && (e.label === 'содержит' || e.label === 'компонент' || e.label === 'константа')).forEach(e => ids.add(e.target));
    const owners = new Set(ids);
    method.edges.filter(e => owners.has(e.source) && e.label !== 'содержит').forEach(e => {
      const target = method.nodes.find(n => n.id === e.target);
      if (target && (target.kind !== 'library' || showLibraries)) ids.add(target.id);
    });
    visibleMethod = {...method, nodes:method.nodes.filter(n => ids.has(n.id)),edges:method.edges.filter(e => ids.has(e.source) && ids.has(e.target))};
  } else if (method?.mode === 'flow' && !showExceptions) {
    visibleMethod = primaryFlow(method);
  }

  return <div className="method-diagram">
    <div className="graph-header-row">
    <div className="method-picker">
      <label htmlFor="method-select" title={fileName}><GitBranch size={15} />{method?.mode === 'structure' ? 'Структура' : 'Метод'}</label>
      <select id="method-select" title={method?.signature} value={selectedId} onChange={event => onSelect(event.target.value)} disabled={busy || interactionLocked || !methods.length}>
        {!methods.length && <option value="">Методы не найдены</option>}
        {methods.map(item => <option key={item.id} value={item.id}>
          {item.owner} · {item.signature} · стр. {item.range.startLine}{item.mode === 'no-body' ? ' · без тела' : ''}
        </option>)}
      </select>
    </div>
    {method && <div className="graph-options">
      {method.mode === 'structure' ? <label title="Внешние типы и импорты"><input type="checkbox" disabled={busy || interactionLocked} checked={showLibraries} onChange={e => setShowLibraries(e.target.checked)} />Импорты</label>
        : method.edges.some(edge => edge.kind === 'exception') && <label title="Показать дополнительные маршруты ошибок и обработчики catch. Java-код не изменяется."><input type="checkbox" disabled={busy || interactionLocked} checked={showExceptions} onChange={e => setShowExceptions(e.target.checked)} />Пути исключений</label>}
    </div>}
    {method && <details className="graph-legend"><summary title="Легенда и ограничения схемы">Легенда{method.warnings.length ? ' · ' + method.warnings.length : ''}</summary><div className="graph-legend-content">
      <p>{method.mode === 'structure' ? 'Линии показывают состав и зависимости, а не порядок исполнения.' : 'Сплошная линия — обычный переход; красный пунктир — возможное исключение. Условие выбирает ветку, возвратная стрелка повторяет цикл.'}</p>
      {method.mode !== 'overview' && method.mode !== 'no-body' && method.warnings.map((warning, index) => <p key={index}>{warning}</p>)}
      <p>Выберите блок или найдите его по тексту. Enter открывает полный код справа, двойной щелчок — редактор доступного блока. Палитра и «+ Блок» добавляют действия в Java-код. Delete на выбранном блоке открывает предпросмотр удаления. Перетаскивайте блоки в зоны до/после, Да/Нет или тело: это меняет Java-код. Фон схемы можно перемещать мышью. Кнопка «Вся схема» показывает общий обзор; для чтения приближайте отдельный участок.</p>
    </div></details>}
    </div>
    {(method?.mode === 'overview' || method?.mode === 'no-body') && method.warnings.map((warning, index) => <div className="diagram-warning" key={index}>{warning}</div>)}
    {visibleMethod && visibleMethod.nodes.length > 0
      ? <MethodCanvas key={revision + visibleMethod.id + showExceptions + showLibraries + focusedType} method={visibleMethod} structureTypes={structureTypes} focusedType={focusedType} onFocusType={setFocusedType} onOpenMethod={onOpenMethod} onNavigate={onNavigate} onReveal={onReveal} busy={busy} onEdit={onEdit} onInsert={onInsert} onDelete={onDelete} onMove={onMove} onInteractionLock={setInteractionLocked} selectedNodeId={currentNodeId} onNodeSelected={id => setLocalSelection({ key: selectionKey, id })} analysis={analysis} onAnalysisNavigate={navigateFinding} focusToken={focusToken} panels={panels} visualContext={visualContext ? { ...visualContext, methodId: method!.id } : undefined} onVisualApply={onVisualApply} />
      : <div className="analysis-empty-workbench"><div className="diagram-empty"><GitBranch size={32} /><strong>{method ? 'У метода нет тела' : 'В этом файле нет методов'}</strong>
          <p>Объявление без тела доступно в «Структуре файла». Для блок-схемы выберите метод или конструктор с телом.</p></div>
          <aside className="block-inspector" aria-label="Анализ Java-кода"><AnalysisPanel report={analysis} methodId={method?.id ?? ''} disabled={busy} onNavigate={navigateFinding} currentOnly={panels.currentOnly} onCurrentOnlyChange={panels.setCurrentOnly} /></aside></div>}
  </div>;
}
