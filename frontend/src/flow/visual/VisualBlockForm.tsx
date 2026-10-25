import { useEffect, useId, useRef, useState } from 'react';
import { Check, Code2, LoaderCircle, X } from 'lucide-react';
import type { FlowBlock } from '../types';
import { defaultPosition, positionLabel } from '../mutation';
import { describeVisualBlock } from './api';
import type { VisualApplyHandler, VisualContext, VisualDescription, VisualSpec } from './types';
import { defaultSpec, kindLabels, specIssue } from './model';
import { SpecInputs } from './SpecInputs';
import './visual-editor.css';

export function VisualBlockForm({ context, block, mode, kind = 'variable', initialPosition, busy, onApply, onClose, onAdvanced }: {
  context: VisualContext; block: FlowBlock; mode: 'insert' | 'edit'; kind?: string; initialPosition?: string;
  busy: boolean; onApply: VisualApplyHandler; onClose: () => void; onAdvanced: () => void;
}) {
  const id = useId();
  const [position, setPosition] = useState(defaultPosition(block, initialPosition));
  const [description, setDescription] = useState<VisualDescription | null>(null);
  const [spec, setSpec] = useState<VisualSpec | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const submitting = useRef(false);
  const insertionDraft = useRef<{ identity: string; spec: VisualSpec | null }>({ identity: '', spec: null });
  const insertPosition = mode === 'insert' ? position : undefined;
  useEffect(() => {
    const controller = new AbortController();
    let current = true;
    const identity = JSON.stringify([context.fileName, context.revision, context.methodId, block.id, mode, kind]);
    if (insertionDraft.current.identity !== identity) insertionDraft.current = { identity, spec: null };
    setLoading(true); setDescription(null); setSpec(null); setError('');
    const timer = setTimeout(() => { if (current) { controller.abort(); setError('Сервер не ответил. Попробуйте ещё раз.'); setLoading(false); } }, 20000);
    describeVisualBlock({ ...context, nodeId: block.id }, insertPosition, controller.signal).then(result => {
      if (!current) return;
      if (result.revision !== context.revision || !Array.isArray(result.variables) || !Array.isArray(result.methods) || !Array.isArray(result.types)) throw new Error('Настройки не соответствуют текущей версии. Обновите схему.');
      setDescription(result);
      const next = mode === 'edit' ? result.spec : insertionDraft.current.spec ?? defaultSpec(kind, result);
      setSpec(next);
      if (mode === 'insert') insertionDraft.current.spec = next;
    }).catch(caught => { if (current && !controller.signal.aborted) setError(caught instanceof Error ? caught.message : 'Не удалось загрузить настройки.'); })
      .finally(() => { clearTimeout(timer); if (current) setLoading(false); });
    return () => { current = false; clearTimeout(timer); controller.abort(); };
  }, [context.fileName, context.source, context.revision, context.methodId, block.id, insertPosition, mode, kind, retry]);
  const locked = busy || pending;
  function changeSpec(next: VisualSpec) {
    setSpec(next); setError('');
    if (mode === 'insert') insertionDraft.current.spec = next;
  }
  const issue = specIssue(spec, description);
  const supported = mode === 'insert' || description?.supported;
  const valid = !loading && !!description && supported && !!spec && !issue && (mode !== 'insert' || (block.insert?.positions ?? []).includes(position));
  async function submit() {
    if (!valid || !spec || locked || submitting.current) return;
    submitting.current = true; setPending(true); setError('');
    try { await onApply(block.id, mode, spec, insertPosition); onClose(); }
    catch (caught) { setError(caught instanceof Error ? caught.message : 'Не удалось применить настройки.'); }
    finally { submitting.current = false; setPending(false); }
  }
  return <form className={'visual-block-form nodrag nowheel ' + (mode === 'insert' ? 'visual-insert-form' : 'visual-edit-form')} data-block-editor="true"
    aria-label={mode === 'insert' ? 'Визуальное добавление блока' : 'Визуальное редактирование блока'}
    onSubmit={event => { event.preventDefault(); void submit(); }}
    onKeyDown={event => {
      if (event.key === 'Escape' && !locked) { event.preventDefault(); event.stopPropagation(); onClose(); }
      if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); void submit(); }
    }}>
    <div className="visual-form-heading"><div><small>{mode === 'insert' ? 'НОВЫЙ БЛОК' : 'НАСТРОЙКИ БЛОКА'}</small>
      <strong>{spec ? kindLabels[spec.kind] : 'Визуальный редактор'}</strong></div>
      <button type="button" className="icon-button" onClick={onClose} disabled={locked} aria-label="Закрыть настройки блока"><X size={18} /></button>
    </div>
    <div className="visual-form-body">
      {mode === 'insert' && <>
        <div className="visual-field"><label htmlFor={id + '-position'}>Куда вставить</label>
          <select id={id + '-position'} value={position} disabled={locked} onChange={event => setPosition(event.target.value)}>
            {(block.insert?.positions ?? []).map(choice => <option key={choice} value={choice}>{positionLabel(block, choice)}</option>)}
          </select>
        </div>
        {description && spec && <div className="visual-field"><label htmlFor={id + '-kind'}>Тип блока</label>
          <select id={id + '-kind'} value={spec.kind} disabled={locked || loading} onChange={event => changeSpec(defaultSpec(event.target.value, description))}>
            {Object.entries(kindLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </div>}
      </>}
      {loading && <div className="visual-loading" role="status"><LoaderCircle size={18} className="spin" />Читаем параметры и доступные переменные…</div>}
      {!loading && description && !supported && <div className="visual-unsupported"><strong>Для этого фрагмента пока доступен редактор Java</strong>
        <p>{description.reason || 'Сложная конструкция сохранена без изменений.'}</p></div>}
      {!loading && supported && description && spec && <SpecInputs spec={spec} description={description} disabled={locked}
        editing={mode === 'edit'} onChange={changeSpec} />}
      {issue && !loading && spec && supported && <p className="visual-validation">{issue}</p>}
      {error && <div className="visual-error" role="alert">{error}
        {!description && <button type="button" className="text-button" disabled={locked} onClick={() => setRetry(value => value + 1)}>Повторить загрузку</button>}
      </div>}
    </div>
    <div className="visual-form-footer">
      <button type="submit" className="button primary" disabled={locked || !valid}>
        {locked ? <LoaderCircle size={15} className="spin" /> : <Check size={15} />}{locked ? 'Применяем…' : mode === 'insert' ? 'Добавить блок' : 'Применить изменения'}
      </button>
      <button type="button" className="text-button visual-advanced" disabled={locked} onClick={onAdvanced}><Code2 size={14} />Редактор Java</button>
    </div>
  </form>;
}
