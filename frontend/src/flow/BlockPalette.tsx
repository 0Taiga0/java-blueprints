import { useId, useRef, useState } from 'react';
import { Braces, Diamond, Repeat2, FunctionSquare, Plus, CornerUpLeft, SkipForward, CircleStop, Search, GripVertical, X } from 'lucide-react';
import type { FlowBlock } from './types';
import { defaultPosition, positionLabel } from './mutation';
import { useFlowInteraction } from './SemanticDrag';
import { VisualBlockForm } from './visual/VisualBlockForm';
import type { VisualApplyHandler, VisualContext } from './visual/types';

export const templates = [
  { kind: 'variable', name: 'Переменная', icon: Braces, code: 'int value = 0;', hint: 'Дайте переменной свободное имя и начальное значение.' },
  { kind: 'condition', name: 'Условие', icon: Diamond, code: 'if (true) {\n}', hint: 'После добавления наполните ветки «Да» и «Нет» через кнопку «+ Блок».' },
  { kind: 'loop', name: 'Цикл', icon: Repeat2, code: 'for (int i = 0; i < 3; i++) {\n}', hint: 'После добавления наполните тело цикла через кнопку «+ Блок».' },
  { kind: 'call', name: 'Вызов метода', icon: FunctionSquare, code: 'System.out.println("Новый блок");', hint: 'Вызовите существующий метод. Этот пример выводит сообщение.' },
  { kind: 'operation', name: 'Действие', icon: Plus, code: 'System.out.println("Новый блок");', hint: 'Напишите действие с доступными в этом месте переменными.' },
  { kind: 'return', name: 'Возврат', icon: CornerUpLeft, code: 'return;', hint: 'Завершает метод. Если метод возвращает результат, укажите значение: например, return sum;.' },
  { kind: 'break', name: 'Выход из цикла', icon: CircleStop, code: 'break;', hint: 'Завершает ближайший цикл. Добавляйте только внутри цикла, обычно в ветке условия.' },
  { kind: 'continue', name: 'Следующий шаг', icon: SkipForward, code: 'continue;', hint: 'Переходит к следующей итерации ближайшего цикла.' },
];

export function BlockPalette({ disabled, onChoose }: { disabled: boolean; onChoose: (kind: string) => void }) {
  const [search, setSearch] = useState('');
  const interaction = useFlowInteraction();
  return <aside className="block-palette" aria-label="Палитра новых блоков">
    <div className="rail-heading">БЛОКИ <span>{templates.length}</span></div>
    <label className="palette-search"><Search size={14} /><input aria-label="Поиск блоков" placeholder="Поиск блоков…" value={search} onChange={event => setSearch(event.target.value)} /></label>
    <div className="palette-items">{templates.filter(template => template.name.toLowerCase().includes(search.toLowerCase())).map(template =>
      <button key={template.kind} className={'palette-item ' + template.kind} disabled={disabled}
        onPointerDown={event => interaction?.begin(event, { kind: 'template', templateKind: template.kind, label: template.name })}
        onPointerMove={interaction?.move} onPointerUp={interaction?.end}
        onPointerCancel={interaction?.cancel} onLostPointerCapture={interaction?.lostCapture}
        onClick={() => { if (interaction?.consumeClick()) return; onChoose(template.kind); }}
        title={'Перетащите в нужную зону: ' + template.name}>
        <span className="palette-icon"><template.icon size={16} /></span><span>{template.name}</span><GripVertical size={13} />
      </button>
    )}</div>
    <details className="palette-guide"><summary>Как добавить блок</summary><p>Перетащите блок в подсвеченное место и настройте его поля. Двойной щелчок по готовому блоку открывает его настройки.</p></details>
  </aside>;
}

export type InsertHandler = (nodeId: string, position: string, kind: string, code: string) => Promise<void>;

type InsertProps = {
  block: FlowBlock; kind: string; busy: boolean; onInsert: InsertHandler; onClose: () => void; returnCode?: string; initialPosition?: string;
  visualContext?: VisualContext; onVisualApply?: VisualApplyHandler;
};

export function BlockInsertForm(props: InsertProps) {
  const [advanced, setAdvanced] = useState(false);
  const visual = !!props.visualContext && !!props.onVisualApply && !['break', 'continue'].includes(props.kind);
  if (visual && !advanced) return <VisualBlockForm context={props.visualContext!} block={props.block} mode="insert" kind={props.kind}
    initialPosition={props.initialPosition} busy={props.busy} onApply={props.onVisualApply!} onClose={props.onClose} onAdvanced={() => setAdvanced(true)} />;
  return <JavaBlockInsertForm {...props} onVisualBack={visual ? () => setAdvanced(false) : undefined} />;
}

function JavaBlockInsertForm({ block, kind, busy, onInsert, onClose, returnCode, initialPosition, onVisualBack }: InsertProps & { onVisualBack?: () => void }) {
  const [currentKind, setKind] = useState(kind);
  const template = templates.find(item => item.kind === currentKind)!;
  const choices = block.insert?.positions ?? [];
  const [position, setPosition] = useState(defaultPosition(block, initialPosition));
  const [code, setCode] = useState(kind === 'return' && returnCode ? returnCode : template.code);
  const [simple, setSimple] = useState(kind === 'condition' || kind === 'loop');
  const [condition, setCondition] = useState('true');
  const [initial, setInitial] = useState('int i = 0');
  const [loopCondition, setLoopCondition] = useState('i < 3');
  const [update, setUpdate] = useState('i++');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const submitting = useRef(false);
  const id = useId();
  const locked = busy || pending;
  const proposed = simple ? currentKind === 'condition' ? 'if (' + condition + ') {\n}' : 'for (' + initial + '; ' + loopCondition + '; ' + update + ') {\n}' : code;
  const valid = !!proposed.trim() && choices.includes(position) && !(simple && currentKind === 'condition' && !condition.trim());
  function selectKind(next: string) {
    setKind(next); setCode(next === 'return' && returnCode ? returnCode : templates.find(item => item.kind === next)!.code);
    setSimple(next === 'condition' || next === 'loop'); setError('');
  }
  async function apply() {
    if (!valid || locked || submitting.current) return;
    submitting.current = true; setPending(true); setError('');
    try { await onInsert(block.id, position, currentKind, proposed); onClose(); }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Не удалось добавить блок.'); }
    finally { submitting.current = false; setPending(false); }
  }
  return <form className="insert-form" data-block-editor onSubmit={event => { event.preventDefault(); void apply(); }}
    onKeyDown={event => {
      if (event.key === 'Escape' && !locked) { event.preventDefault(); event.stopPropagation(); onClose(); }
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); void apply(); }
    }}>
    {onVisualBack && <button type="button" className="text-button visual-return-button" disabled={locked} onClick={onVisualBack}>← Визуальные настройки</button>}
    <div className="insert-title"><span className={'type-dot ' + currentKind} /><strong>Добавить Java-код</strong>
      <button type="button" className="icon-button" disabled={locked} onClick={onClose} aria-label="Отменить добавление"><X size={16} /></button>
    </div>
    <div className="mutation-destination"><small>Опорный блок · строка {block.range.startLine}</small><code>{block.code || block.label}</code></div>
    <label htmlFor={id + '-position'}>Куда вставить</label>
    <select id={id + '-position'} value={position} onChange={event => setPosition(event.target.value)} disabled={locked} autoFocus>
      {choices.map(choice => <option key={choice} value={choice}>{positionLabel(block, choice)}</option>)}
    </select>
    <label htmlFor={id + '-kind'}>Тип нового блока</label>
    <select id={id + '-kind'} value={currentKind} onChange={event => selectKind(event.target.value)} disabled={locked}>
      {templates.map(item => <option key={item.kind} value={item.kind}>{item.name}</option>)}
    </select>
    {simple ? <>
      {currentKind === 'condition' ? <><label htmlFor={id + '-condition'}>Условие для ветки «Да»</label>
        <input id={id + '-condition'} className="insert-expression" value={condition} disabled={locked} spellCheck={false} onChange={event => setCondition(event.target.value)} /></>
        : <><label htmlFor={id + '-initial'}>Начальные значения</label>
          <input id={id + '-initial'} className="insert-expression" value={initial} disabled={locked} spellCheck={false} onChange={event => setInitial(event.target.value)} />
          <label htmlFor={id + '-condition'}>Повторять, пока</label>
          <input id={id + '-condition'} className="insert-expression" value={loopCondition} disabled={locked} spellCheck={false} onChange={event => setLoopCondition(event.target.value)} />
          <label htmlFor={id + '-update'}>После каждого повторения</label>
          <input id={id + '-update'} className="insert-expression" value={update} disabled={locked} spellCheck={false} onChange={event => setUpdate(event.target.value)} /></>}
      <details className="insert-preview"><summary>Получится Java-код</summary><pre><code>{proposed}</code></pre></details>
      <button type="button" className="text-button" disabled={locked} onClick={() => { setCode(proposed); setSimple(false); }}>Редактировать весь Java-код</button>
    </> : <><label htmlFor={id + '-code'}>Java-код нового блока</label>
      <textarea id={id + '-code'} spellCheck={false} value={code} onChange={event => setCode(event.target.value)} disabled={locked} rows={7} /></>}
    <p className="insert-hint">{template.hint}</p>
    {error && <p className="insert-error" role="alert">{error}</p>}
    <button className="button primary insert-submit" disabled={locked || !valid} type="submit"><Plus size={15} />{locked ? 'Добавляем…' : 'Добавить в программу'}</button>
    <p className="insert-footnote">Код и стрелки обновятся вместе. Правку можно отменить.</p>
  </form>;
}
