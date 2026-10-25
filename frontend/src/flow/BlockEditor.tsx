import { useState } from 'react';
import { Check, LoaderCircle, X } from 'lucide-react';
import { RequestError } from '../api';
import type { FlowBlock } from './types';
import { VisualBlockForm } from './visual/VisualBlockForm';
import type { VisualApplyHandler, VisualContext } from './visual/types';

const help: Record<string, string> = {
  condition: 'Только условие, без if и скобок: price >= 100',
  statement: 'Одно действие с ; в конце: sum += price * 2;',
  'for-init': 'Начальное значение, без ;: int i = 2',
  'for-update': 'Шаг цикла, без ;: i += 2',
  selector: 'Выражение выбора switch, без switch и case: mode',
  iterable: 'Коллекция или массив для перебора: otherPrices',
  return: 'Оператор возврата целиком: return sum * 2;',
  throw: 'Оператор исключения целиком: throw new IllegalArgumentException();',
};

type Props = {
  block: FlowBlock; busy: boolean; onClose: () => void;
  onApply: (nodeId: string, replacement: string) => Promise<void>;
  visualContext?: VisualContext; onVisualApply?: VisualApplyHandler;
};

export function BlockEditor(props: Props) {
  const [advanced, setAdvanced] = useState(false);
  const visual = !!props.visualContext && !!props.onVisualApply;
  if (visual && !advanced) return <VisualBlockForm context={props.visualContext!} block={props.block} mode="edit"
    busy={props.busy} onApply={props.onVisualApply!} onClose={props.onClose} onAdvanced={() => setAdvanced(true)} />;
  return <JavaBlockEditor {...props} onVisualBack={visual ? () => setAdvanced(false) : undefined} />;
}

function JavaBlockEditor({ block, busy, onClose, onApply, onVisualBack }: Props & { onVisualBack?: () => void }) {
  const [draft, setDraft] = useState(block.edit!.text);
  const [error, setError] = useState('');
  async function submit() {
    if (busy || !draft.trim() || draft === block.edit!.text) return;
    setError('');
    try { await onApply(block.id, draft); onClose(); }
    catch (caught) { setError(caught instanceof RequestError ? caught.message : 'Не удалось применить правку. Исходник сохранён.'); }
  }
  return <form className="block-editor nodrag nowheel" data-block-editor="true"
    onSubmit={event => { event.preventDefault(); void submit(); }}
    onKeyDown={event => {
      if (event.key === 'Escape' && !busy) onClose();
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
        event.preventDefault(); event.stopPropagation(); void submit();
      }
    }}>
    {onVisualBack && <button type="button" className="text-button visual-return-button" disabled={busy} onClick={onVisualBack}>← Визуальные настройки</button>}
    <div className="block-editor-heading"><label htmlFor="block-text">Редактор Java · строка {block.range.startLine}</label>
      <button type="button" className="icon-button" aria-label="Закрыть редактор блока" disabled={busy} onClick={onClose}><X size={15} /></button></div>
    <textarea id="block-text" autoFocus spellCheck={false} value={draft} disabled={busy}
      onChange={event => { setDraft(event.target.value); setError(''); }} aria-describedby="block-help" />
    <p id="block-help">{help[block.edit!.kind]}</p>
    {error && <div className="block-edit-error" role="alert">{error}<small>Правка не применена. Предыдущий код сохранён.</small></div>}
    <div className="block-editor-actions"><button type="button" className="button secondary" disabled={busy} onClick={onClose}>Отмена</button>
      <button type="submit" className="button primary" disabled={busy || !draft.trim() || draft === block.edit!.text}>
        {busy ? <LoaderCircle size={14} className="spin" /> : <Check size={14} />}{busy ? 'Проверяем…' : 'Применить в Java'}
      </button></div>
  </form>;
}
