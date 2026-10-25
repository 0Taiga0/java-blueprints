import { useEffect, useId, useRef, useState } from 'react';
import { MoveRight, Trash2, X } from 'lucide-react';
import type { FlowBlock } from './types';
import { defaultPosition, deleteModes, moveTargetIssue, positionLabel, type DeleteHandler, type DeleteMode, type MoveHandler } from './mutation';

function Fragment({ block, label }: { block: FlowBlock; label: string }) {
  return <div className="mutation-fragment"><small>{label} · строки {block.mutation?.range.startLine ?? block.range.startLine}–{block.mutation?.range.endLine ?? block.range.endLine}</small>
    <pre tabIndex={0} aria-label={label}><code>{block.mutation?.text ?? block.code ?? block.label}</code></pre></div>;
}

export function BlockDeleteForm({ block, busy, onDelete, onClose }: {
  block: FlowBlock; busy: boolean; onDelete: DeleteHandler; onClose: () => void;
}) {
  const choices = (block.mutation?.deleteModes ?? []).filter((mode): mode is DeleteMode => mode in deleteModes);
  const [mode, setMode] = useState<DeleteMode>(choices[0] ?? 'delete');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const submitting = useRef(false);
  const id = useId();
  const locked = busy || pending;
  async function apply() {
    if (locked || submitting.current || !choices.includes(mode)) return;
    submitting.current = true; setPending(true); setError('');
    try { await onDelete(block.id, mode); onClose(); }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Не удалось удалить блок.'); }
    finally { submitting.current = false; setPending(false); }
  }
  return <form className="mutation-form mutation-delete" data-block-editor onSubmit={event => { event.preventDefault(); void apply(); }}
    onKeyDown={event => {
      if (event.key === 'Escape' && !locked) { event.preventDefault(); event.stopPropagation(); onClose(); }
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); }
    }}>
    <div className="insert-title"><Trash2 size={15} /><strong>Удаление блока</strong>
      <button className="icon-button" type="button" disabled={locked} onClick={onClose} aria-label="Отменить удаление"><X size={16} /></button></div>
    <Fragment block={block} label="Затронутый Java-фрагмент" />
    <label htmlFor={id}>Что удалить</label>
    <select id={id} value={mode} disabled={locked} autoFocus onChange={event => { setMode(event.target.value as DeleteMode); setError(''); }}>
      {choices.map(choice => <option key={choice} value={choice}>{deleteModes[choice].label}</option>)}
    </select>
    <p className="mutation-explanation">{deleteModes[mode].description}</p>
    <p className="mutation-note">Комментарии сохраняются. После изменения проверьте программу тестами.</p>
    {error && <p className="insert-error" role="alert">{error}</p>}
    <div className="mutation-actions">
      <button type="button" className="button secondary" disabled={locked} onClick={onClose}>Отмена</button>
      <button type="submit" className="button mutation-danger" disabled={locked || !choices.length}><Trash2 size={14} />{locked ? 'Удаляем…' : 'Подтвердить удаление'}</button>
    </div>
    <p className="insert-footnote">Изменение можно отменить кнопкой ↶ наверху.</p>
  </form>;
}

export function BlockMoveForm({ source, target, busy, onMove, onClose, onPickTarget }: {
  source: FlowBlock; target: FlowBlock | null; busy: boolean; onMove: MoveHandler; onClose: () => void; onPickTarget: () => void;
}) {
  const [position, setPosition] = useState(target ? defaultPosition(target) : '');
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const submitting = useRef(false);
  const id = useId();
  const locked = busy || pending;
  const choices = target?.insert?.positions ?? [];
  const issue = target ? moveTargetIssue(source, target) : null;
  useEffect(() => { setPosition(target ? defaultPosition(target) : ''); setError(''); }, [target?.id]);
  async function apply() {
    if (locked || submitting.current || !target || moveTargetIssue(source, target) || !choices.includes(position)) return;
    submitting.current = true; setPending(true); setError('');
    try { await onMove(source.id, target.id, position); onClose(); }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Не удалось переместить блок.'); }
    finally { submitting.current = false; setPending(false); }
  }
  return <form className="mutation-form mutation-move" data-block-editor onSubmit={event => { event.preventDefault(); void apply(); }}
    onKeyDown={event => {
      if (event.key === 'Escape' && !locked) { event.preventDefault(); event.stopPropagation(); onClose(); }
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); }
    }}>
    <div className="insert-title"><MoveRight size={15} /><strong>Перенос логики</strong>
      <button className="icon-button" type="button" disabled={locked} onClick={onClose} aria-label="Отменить перенос"><X size={16} /></button></div>
    <Fragment block={source} label="Переносимый Java-фрагмент" />
    {target ? <div className="mutation-destination"><small>Место назначения · строка {target.range.startLine}</small>
      <code>{target.code || target.label}</code></div> : <p className="mutation-explanation">Щёлкните на схеме блок, рядом с которым или внутри которого нужно разместить этот фрагмент.</p>}
    <button className="button secondary" type="button" disabled={locked} onClick={onPickTarget}>{target ? 'Выбрать другое место на схеме' : 'Выбрать место на схеме'}</button>
    {target && <><label htmlFor={id}>Куда перенести</label><select id={id} value={position} disabled={locked || !!issue} onChange={event => { setPosition(event.target.value); setError(''); }}>
      {choices.map(choice => <option key={choice} value={choice}>{positionLabel(target, choice)}</option>)}
    </select></>}
    <p className="mutation-note">Изменится порядок выполнения Java-кода. Проверьте доступность переменных и запустите тесты.</p>
    {(error || issue) && <p className="insert-error" role="alert">{error || issue}</p>}
    <div className="mutation-actions">
      <button type="button" className="button secondary" disabled={locked} onClick={onClose}>Отмена</button>
      <button type="submit" className="button primary" disabled={locked || !target || !!issue || !choices.includes(position)}><MoveRight size={14} />{locked ? 'Переносим…' : 'Переместить в программе'}</button>
    </div>
    <p className="insert-footnote">Изменение можно отменить кнопкой ↶ наверху.</p>
  </form>;
}
