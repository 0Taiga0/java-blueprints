import { useEffect, useId, useRef, useState } from 'react';
import { OptimizationDiff } from './OptimizationDiff';
import { Check, CircleAlert, GitCompareArrows, LoaderCircle, Sparkles, X } from 'lucide-react';
import { ComparisonReport } from '../check/ComparisonPanel';
import { compareProgram } from '../check/api';
import { getOptimizationOptions, previewOptimization } from './api';
import { canApplyOptimization, completeMatch, sameSnapshot } from './validation';
import type { OptimizationInput, OptimizationOptions, OptimizationPreview, VerificationSnapshot } from './types';

type Props = OptimizationInput & {
  testFileName: string; testSource: string; busy: boolean;
  onBusy: (value: boolean) => void; onClose: () => void; onEditTests: () => void;
  onApply: (snapshot: VerificationSnapshot) => void;
};

export function OptimizationDialog({ fileName, source, revision, testFileName, testSource, busy, onBusy, onClose, onEditTests, onApply }: Props) {
  const [catalog, setCatalog] = useState<OptimizationOptions | null>(null);
  const [candidateId, setCandidateId] = useState('');
  const [preview, setPreview] = useState<OptimizationPreview | null>(null);
  const [snapshot, setSnapshot] = useState<VerificationSnapshot | null>(null);
  const [phase, setPhase] = useState<'options' | 'preview' | 'compare' | null>('options');
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const [wide, setWide] = useState(() => window.matchMedia('(min-width: 900px)').matches);
  const dialog = useRef<HTMLDialogElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const diffSection = useRef<HTMLDivElement>(null);
  const requestId = useRef(0);
  const actionLock = useRef(false);
  const restoreFocus = useRef(true);
  const testEpoch = useRef(0);
  const prefix = useId();
  const current = { fileName, source, revision, testFileName, testSource, candidateId };
  const currentRef = useRef(current);
  currentRef.current = current;
  const selected = catalog?.candidates.find(candidate => candidate.id === candidateId);
  const locked = busy || phase !== null;
  const stale = !!snapshot && !sameSnapshot(snapshot, current);
  const canApply = !locked && !!preview && snapshot?.preview === preview && canApplyOptimization(snapshot, current);
  const currentStep = canApply ? 3 : preview ? 2 : candidateId ? 1 : 0;
  const disabledReason = locked ? 'Дождитесь завершения запроса.'
    : selected && !selected.available ? selected.reason
    : !preview ? 'Выберите предложение и посмотрите изменения.'
    : !testFileName.trim() || !testSource.trim() ? 'Добавьте тесты в редактор тестов, затем проверьте обе версии.'
    : stale ? 'Код или тесты изменились. Нужна новая проверка.'
    : !snapshot ? 'Проверьте обе версии одним набором тестов. До этого применение недоступно.'
    : !canApply ? 'Проверка не подтвердила полное совпадение. Исправьте код или тесты и повторите сравнение.'
    : 'Можно применить именно тот код, который показан и проверен ниже.';

  useEffect(() => {
    const element = dialog.current;
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    element?.showModal();
    title.current?.focus();
    return () => {
      element?.close();
      document.body.style.overflow = overflow;
      if (restoreFocus.current && previous?.isConnected) previous.focus();
    };
  }, []);

  useEffect(() => {
    const query = window.matchMedia('(min-width: 900px)');
    const update = () => setWide(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);

  useEffect(() => {
    // Даже если пользователь вернул прежний текст, проверка должна быть новой.
    testEpoch.current += 1;
    setSnapshot(null);
  }, [testFileName, testSource]);

  useEffect(() => {
    const id = ++requestId.current;
    const controller = new AbortController();
    actionLock.current = true;
    setPhase('options'); onBusy(true); setError(''); setCatalog(null);
    setCandidateId(''); setPreview(null); setSnapshot(null);
    getOptimizationOptions({ fileName, source, revision }, controller.signal).then(response => {
      if (id !== requestId.current) return;
      if (response.revision !== revision) throw new Error('Список предложений относится к другой версии кода. Откройте оптимизацию заново.');
      setCatalog(response);
      setCandidateId(response.candidates.find(candidate => candidate.available)?.id ?? response.candidates[0]?.id ?? '');
    }).catch(caught => {
      if (id === requestId.current) setError(caught instanceof Error ? caught.message : 'Не удалось получить предложения.');
    }).finally(() => {
      if (id === requestId.current) { actionLock.current = false; setPhase(null); onBusy(false); }
    });
    return () => {
      controller.abort();
      if (requestId.current === id) requestId.current += 1;
    };
  }, [fileName, source, revision, retry, onBusy]);

  function close() {
    if (!actionLock.current && !locked) onClose();
  }

  function choose(id: string) {
    if (actionLock.current || locked) return;
    setCandidateId(id); setPreview(null); setSnapshot(null); setError('');
  }

  async function showPreview() {
    if (actionLock.current || locked || !selected?.available) return;
    const id = ++requestId.current;
    actionLock.current = true;
    setPhase('preview'); onBusy(true); setError(''); setPreview(null); setSnapshot(null);
    try {
      const response = await previewOptimization({ fileName, source, revision }, selected.id);
      if (id !== requestId.current) return;
      if (response.revision !== revision || response.candidate.id !== selected.id ||
          response.conversion.fileName !== fileName || !response.candidate.available || response.source === source) {
        throw new Error('Предпросмотр не соответствует выбранному предложению. Изменения не применены.');
      }
      setPreview(response);
      requestAnimationFrame(() => diffSection.current?.scrollIntoView({ block: 'nearest', behavior: 'auto' }));
    } catch (caught) {
      if (id === requestId.current) setError(caught instanceof Error ? caught.message : 'Не удалось показать изменение.');
    } finally {
      if (id === requestId.current) { actionLock.current = false; setPhase(null); onBusy(false); }
    }
  }

  async function checkPreview() {
    if (actionLock.current || locked || !preview || preview.candidate.id !== candidateId || !testFileName.trim() || !testSource.trim()) return;
    const id = ++requestId.current;
    const epoch = testEpoch.current;
    const candidate = preview;
    const captured = { fileName, source, revision, candidateId, testFileName, testSource, preview: candidate };
    actionLock.current = true;
    setPhase('compare'); onBusy(true); setError(''); setSnapshot(null);
    try {
      const response = await compareProgram(fileName, source, candidate.source, testFileName, testSource);
      if (id !== requestId.current) return;
      const report = response.status === 'matched' && !completeMatch(response)
        ? { ...response, status: 'inconclusive' as const, message: 'Отчёт неполный: применять изменение пока нельзя.',
          reasons: [...response.reasons, 'Нужны прошедшие попарно совпавшие тесты без пропусков и сокращённого отчёта.'] }
        : response;
      const completed = { ...captured, report };
      if (epoch !== testEpoch.current || !sameSnapshot(completed, currentRef.current)) {
        setError('Код, предложение или тесты изменились во время проверки. Запустите её заново.');
        return;
      }
      setSnapshot(completed);
    } catch (caught) {
      if (id === requestId.current) setError(caught instanceof Error ? caught.message : 'Не удалось сравнить версии.');
    } finally {
      if (id === requestId.current) { actionLock.current = false; setPhase(null); onBusy(false); }
    }
  }

  function apply() {
    // Повторная проверка в обработчике: состояние кнопки не является разрешением.
    if (actionLock.current || locked || !snapshot || snapshot.preview !== preview || !canApplyOptimization(snapshot, currentRef.current)) return;
    actionLock.current = true;
    onApply(snapshot);
    actionLock.current = false;
  }

  return <dialog ref={dialog} className="optimization-dialog" aria-labelledby={prefix + '-title'}
    aria-describedby={prefix + '-intro'} data-optimization-panel
    onCancel={event => { event.preventDefault(); close(); }}>
    <header className="optimization-header">
      <div><h2 ref={title} tabIndex={-1} id={prefix + '-title'} className="optimization-title"><Sparkles size={23} />Оптимизация</h2>
        <p id={prefix + '-intro'}>Выберите правку. Применение доступно после проверки обеих версий тестами.</p></div>
      <button className="optimization-close" aria-label="Закрыть оптимизацию" disabled={locked} onClick={close}><X size={20} /></button>
    </header>
    <div className="optimization-body" aria-busy={locked}>
      <ol className="optimization-steps" aria-label="Шаги оптимизации">
        {['Выбор', 'Изменения', 'Проверка', 'Применение'].map((label, index) => <li key={label}
          data-state={index === currentStep ? 'current' : index < currentStep ? 'done' : 'pending'}
          aria-current={index === currentStep ? 'step' : undefined}><span className="optimization-step-number">{index + 1}</span>{label}</li>)}
      </ol>
      {phase === 'options' && <div className="optimization-status optimization-status--running" role="status"><LoaderCircle className="spin" size={19} />Ищем доступные изменения…</div>}
      {catalog && <>
        <div className="optimization-summary"><strong>{fileName}</strong><span>{catalog.candidates.filter(candidate => candidate.available).length} доступных предложений</span></div>
        {catalog.candidates.length ? <div className="optimization-catalog" aria-label="Предложения оптимизации">
          {catalog.candidates.map(candidate => <button key={candidate.id} type="button"
            className={'optimization-candidate' + (!candidate.available ? ' optimization-candidate--unavailable' : '')}
            aria-pressed={candidate.id === candidateId} disabled={locked}
            onClick={() => choose(candidate.id)}>
            <span className="optimization-candidate-title">{candidate.title}</span>
            <span>Строка {candidate.range.startLine} · {candidate.available ? 'Доступно' : 'Вручную'}</span>
          </button>)}
        </div> : <div className="optimization-status optimization-status--incomplete">Для этого кода нет предложений из поддерживаемого набора. Подробности — в панели проблем.</div>}
        {selected && <section className="optimization-selected" aria-live="polite"><strong>{selected.title} · строка {selected.range.startLine}</strong>
          <p>{selected.reason}</p><details><summary>Как изменится код</summary><p>{selected.description}</p></details></section>}
        {catalog.limitations.length > 0 && <details className="optimization-limitations"><summary>Что умеет эта версия</summary><ul>{catalog.limitations.map((limitation, index) => <li key={index}>{limitation}</li>)}</ul></details>}
      </>}
      {phase === 'preview' && <div className="optimization-status optimization-status--running" role="status"><LoaderCircle className="spin" size={19} />Готовим изменение. Исходник остаётся прежним…</div>}
      {preview && <div ref={diffSection}>
        <div className="optimization-summary"><strong>{preview.summary}</strong></div>
        <div className={'optimization-diff-labels' + (wide ? '' : ' optimization-diff-labels--inline')}>
          {wide ? <><span>ДО · текущий код</span><span>ПОСЛЕ · предложение</span></>
            : <span>Сравнение в одной колонке: удалённые строки — «до», добавленные — «после».</span>}
        </div>
        <div className="optimization-diff" aria-label="Полное сравнение исходного и предложенного кода">
          <OptimizationDiff original={source} modified={preview.source} sideBySide={wide} />
        </div>
        {preview.notes.length > 0 && <details className="optimization-limitations"><summary>Подробности преобразования</summary><p>Сравниваем текущий код с предложением. Сохранённый эталон остаётся прежним.</p><ul className="optimization-notes">{preview.notes.map((note, index) => <li key={index}>{note}</li>)}</ul></details>}
      </div>}
      <div className="optimization-tests"><div><strong>Тесты: {testFileName || 'файл не выбран'}</strong>
        <p>Один набор для обеих версий.</p></div>
        <button className="button secondary" disabled={locked} onClick={() => { restoreFocus.current = false; onEditTests(); }}>Изменить тесты</button></div>
      {phase === 'compare' && <div className="optimization-status optimization-status--running" role="status"><LoaderCircle className="spin" size={20} /><span>Проверяем «до» и «после»… У каждой версии своя компиляция и запуск Java.</span></div>}
      {snapshot && <section className="optimization-report" aria-label="Проверка предложения" aria-live="polite">
        <ComparisonReport report={snapshot.report} stale={stale} />
      </section>}
      {error && <div className="optimization-error" role="alert"><CircleAlert size={18} /><span>{error}</span>
        {!catalog && !locked && <button className="button secondary" onClick={() => setRetry(value => value + 1)}>Повторить запрос</button>}</div>}
      <details className="optimization-limitations"><summary>Что подтверждает проверка</summary><p>Проверяются только заданные тестами случаи. Совпадение не доказывает эквивалентность на всех входах. Эти правки упрощают код; ускорение и уменьшение Big O не гарантируются.</p></details>
    </div>
    <footer className="optimization-footer">
      <p className="optimization-disabled-reason" role="status">{disabledReason}</p>
      <div className="optimization-actions">
        <button className="button secondary" disabled={locked} onClick={close}>Отмена</button>
        {!preview ? <button className="button secondary" disabled={locked || !selected?.available} onClick={() => void showPreview()}>
          {phase === 'preview' ? <LoaderCircle className="spin" size={16} /> : <Sparkles size={16} />}Показать изменения</button>
          : <button className="button secondary" disabled={locked || !testFileName.trim() || !testSource.trim()} onClick={() => void checkPreview()}>
            {phase === 'compare' ? <LoaderCircle className="spin" size={16} /> : <GitCompareArrows size={16} />}Проверить обе версии</button>}
        <button className="button primary" disabled={!canApply} onClick={apply}><Check size={16} />Применить</button>
      </div>
    </footer>
  </dialog>;
}
