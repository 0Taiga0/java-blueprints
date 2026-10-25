import { useCallback, useEffect, useId, useRef, useState } from 'react';
import Editor from '@monaco-editor/react';
import { ArrowDownToLine, Braces, Check, CircleAlert, Copy, FileCode2, HelpCircle, LoaderCircle, Play, RefreshCw, RotateCcw, Undo2, Redo2, Sparkles, Upload, X } from 'lucide-react';
import { monaco } from './monaco';
import { convertSource, uploadSource, editBlockSource, insertBlockSource, deleteBlockSource, moveBlockSource, RequestError, type BlockEditResult, type Conversion, type ApiError } from './api';
import { FEATURES_EXAMPLE, FEATURES_NAME } from './features-example';
import { EXAMPLE, EXAMPLE_NAME } from './example';
import { CheckPanel } from './check/CheckPanel';
import { checkProgram } from './check/api';
import { GraphCompilation, type GraphCompilationSnapshot } from './check/GraphCompilation';
import { MethodDiagram } from './flow/MethodDiagram';
import { analysisExample } from './analysis-example';
import { optimizationExample } from './optimization-example';
import { TEST_EXAMPLE, TEST_EXAMPLE_NAME } from './check/example';
import { OptimizationDialog } from './optimization/OptimizationDialog';
import { canApplyOptimization } from './optimization/validation';
import type { VerificationSnapshot } from './optimization/types';
import type { Finding } from './analysis/types';
import type { SourceRange } from './flow/types';
import { StudioTabs, StudioPanel } from './studio/StudioTabs';
import { StudioHelp } from './studio/StudioHelp';
import type { DockSize, DockTab } from './studio/types';
import { editorText } from './studio/sourceText';
import { applyVisualBlock } from './flow/visual/api';
import type { VisualSpec } from './flow/visual/types';
import { VISUAL_NAME, VISUAL_EXAMPLE, VISUAL_TEST_NAME, VISUAL_TESTS } from './visual-example';

type Snapshot = { source: string; result: Conversion; methodId: string; selectedNodeId: string; compilation?: GraphCompilationSnapshot | null };
const MAX_BYTES = 1024 * 1024;
const editorOptions: monaco.editor.IStandaloneEditorConstructionOptions = {
  minimap: { enabled: false }, fontSize: 14, lineHeight: 22,
  fontFamily: "'Cascadia Code', Consolas, monospace", padding: { top: 18, bottom: 18 },
  scrollBeyondLastLine: false, renderLineHighlight: 'line', tabSize: 4,
  automaticLayout: true, overviewRulerLanes: 0, hideCursorInOverviewRuler: true,
  wordWrap: 'on', scrollbar: { verticalScrollbarSize: 7, horizontalScrollbarSize: 7 },
};

export default function App() {
  const [source, setSource] = useState(EXAMPLE);
  const [fileName, setFileName] = useState(EXAMPLE_NAME);
  const [initialSource, setInitialSource] = useState(EXAMPLE);
  const [baselineSource, setBaselineSource] = useState(EXAMPLE);
  const [uploadedFile, setUploadedFile] = useState<File | null>(null);
  const [result, setResult] = useState<Conversion | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const [graphLocked, setGraphLocked] = useState(false);
  const actionLock = useRef(false);
  const [compilation, setCompilation] = useState<GraphCompilationSnapshot | null>(null);
  const [testSource, setTestSource] = useState(TEST_EXAMPLE);
  const [testFileName, setTestFileName] = useState(TEST_EXAMPLE_NAME);
  const [optimizationOpen, setOptimizationOpen] = useState(false);
  const optimizationApplied = useRef(false);
  const [dragging, setDragging] = useState(false);
  const [health, setHealth] = useState<'checking' | 'online' | 'offline'>('checking');
  const [helpOpen, setHelpOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const [rightTab, setRightTab] = useState<'diagram' | 'structure'>('diagram');
  const [dockTab, setDockTab] = useState<DockTab>('code');
  const [dockOpen, setDockOpen] = useState(false);
  const [codeView, setCodeView] = useState<'source' | 'ast'>('source');
  const [dockSize, setDockSize] = useState<DockSize>('normal');
  const [mobileView, setMobileView] = useState<'graph' | 'dock'>('graph');
  const [documentEpoch, setDocumentEpoch] = useState(0);
  const instanceId = useId();
  const sourceModelPath = 'inmemory://java-blueprints/' + encodeURIComponent(instanceId) + '/' + documentEpoch + '/source.java';
  const astModelPath = 'inmemory://java-blueprints/' + encodeURIComponent(instanceId) + '/generated.java';
  const [methodId, setMethodId] = useState('');
  const [selectedNodeId, setSelectedNodeId] = useState('');
  const [analysisFocusToken, setAnalysisFocusToken] = useState(0);
  const [editNotice, setEditNotice] = useState('');
  const [history, setHistory] = useState<Snapshot[]>([]);
  const [future, setFuture] = useState<Snapshot[]>([]);
  const sourceRef = useRef(source);
  sourceRef.current = source;
  const [selectedLine, setSelectedLine] = useState<number | null>(null);
  const pendingNavigation = useRef<SourceRange | null>(null);
  const highlightRef = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const astEditorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const pendingFocus = useRef(false);
  const requestVersion = useRef(0);
  const sourceBytes = new TextEncoder().encode(source).length;
  const changed = source !== initialSource;

  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    fetch('/api/health', { signal: controller.signal }).then(async response => {
      const body = await response.json();
      setHealth(response.ok && body.status === 'ok' ? 'online' : 'offline');
    }).catch(() => setHealth('offline')).finally(() => clearTimeout(timer));
    return () => { clearTimeout(timer); controller.abort(); };
  }, []);

  useEffect(() => {
    const model = editorRef.current?.getModel();
    if (model) monaco.editor.setModelMarkers(model, 'javaparser', (error?.diagnostics ?? [])
      .filter(item => item.line != null).map(item => ({
        severity: monaco.MarkerSeverity.Error, message: item.message,
        startLineNumber: item.line!, endLineNumber: item.line!,
        startColumn: item.column ?? 1, endColumn: (item.column ?? 1) + 1,
      })));
  }, [error]);

  useEffect(() => {
    editorRef.current?.setPosition({ lineNumber: 1, column: 1 });
    editorRef.current?.revealLine(1);
  }, [initialSource, fileName]);

  useEffect(() => { if (error) setMobileView('graph'); }, [error]);

  function openDock(tab: DockTab) {
    setDockTab(tab); setDockOpen(true); setMobileView('dock');
  }

  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      if (dockOpen && dockTab === 'code') (codeView === 'source' ? editorRef : astEditorRef).current?.layout();
    });
    return () => cancelAnimationFrame(frame);
  }, [dockTab, codeView, dockSize, mobileView, dockOpen]);


  function invalidate() {
    requestVersion.current += 1;
    setCompilation(null);
    setOptimizationOpen(false);
    setHistory([]); setFuture([]); setEditNotice(''); setSelectedNodeId(''); pendingNavigation.current = null; pendingFocus.current = false;
    setResult(null); setError(null); setCopied(false); setMethodId(''); setSelectedLine(null); highlightRef.current?.clear();
  }

  async function checkCurrentCode(name: string, text: string, version: number) {
    setCompilation({ source: text, fileName: name, pending: true });
    try {
      // javac проверяет типы и имена; пользовательская программа здесь не выполняется.
      const report = await checkProgram('compile', name, text, '', '');
      if (version === requestVersion.current) setCompilation({ source: text, fileName: name, report });
    } catch (caught) {
      if (version === requestVersion.current) setCompilation({ source: text, fileName: name,
        error: caught instanceof Error ? caught.message : 'Не удалось проверить компиляцию.' });
    }
  }

  async function selectFile(file: File) {
    if (busy || graphLocked || actionLock.current) return;
    if (!file.name.toLowerCase().endsWith('.java')) {
      setError({ message: 'Нужен файл с расширением .java.', diagnostics: [] }); return;
    }
    if (file.size > MAX_BYTES) {
      setError({ message: 'Файл больше 1 МБ. Выберите файл меньшего размера.', diagnostics: [] }); return;
    }
    actionLock.current = true; setBusy(true);
    let text: string;
    try { text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()); }
    catch {
      setError({ message: 'Не удалось прочитать файл. Сохраните его в кодировке UTF-8.', diagnostics: [] });
      actionLock.current = false; setBusy(false); return;
    }
    invalidate(); setDocumentEpoch(value => value + 1);
    const version = requestVersion.current;
    sourceRef.current = text;
    setSource(text); setInitialSource(text); setBaselineSource(text); setFileName(file.name); setUploadedFile(file);
    setCodeView('source'); setDockTab('code'); setDockOpen(false); setRightTab('diagram'); setMobileView('graph');
    try {
      const response = await uploadSource(file);
      if (version !== requestVersion.current) return;
      setResult(response); setHealth('online');
      setMethodId((response.methods.find(item => item.mode === 'flow') ?? response.methods[0])?.id ?? '');
      await checkCurrentCode(file.name, text, version);
    } catch (caught) {
      if (version !== requestVersion.current) return;
      const failure = caught instanceof RequestError ? caught : new RequestError('Не удалось построить схему.');
      setError({ message: failure.message, diagnostics: failure.diagnostics }); openDock('code');
    } finally { actionLock.current = false; if (version === requestVersion.current) setBusy(false); }
  }

  const convert = useCallback(async () => {
    if (busy || graphLocked || actionLock.current) return;
    if (!source.trim() || sourceBytes > MAX_BYTES) {
      setError({ message: sourceBytes > MAX_BYTES ? 'Размер кода превышает 1 МБ.' : 'Добавьте Java-код перед преобразованием.', diagnostics: [] }); return;
    }
    actionLock.current = true;
    const version = ++requestVersion.current;
    setCompilation(null);
    // Повторное построение того же исходника не является новой правкой.
    // Ручные изменения уже сбрасывают устаревший граф в invalidate().
    setBusy(true); setError(null); setCopied(false);
    try {
      // Загруженный файл отправляем multipart; правки редактора передаём JSON.
      const response = uploadedFile && source === initialSource
        ? await uploadSource(uploadedFile) : await convertSource(fileName, source);
      if (version === requestVersion.current) {
        setResult(response); setHealth('online'); setRightTab('diagram');
        setMethodId(current => response.methods.some(item => item.id === current) ? current
          : (response.methods.find(item => item.mode === 'flow') ?? response.methods[0])?.id ?? '');
        await checkCurrentCode(fileName, source, version);
      }
    } catch (caught) {
      if (version !== requestVersion.current) return;
      const failure = caught instanceof RequestError ? caught : new RequestError('Не удалось преобразовать код.');
      setError({ message: failure.message, diagnostics: failure.diagnostics });
    } finally { actionLock.current = false; if (version === requestVersion.current) setBusy(false); }
  }, [busy, graphLocked, source, sourceBytes, uploadedFile, initialSource, fileName]);

  useEffect(() => {
    const handleKey = (event: KeyboardEvent) => {
      if (!optimizationOpen && !helpOpen && (event.ctrlKey || event.metaKey) && event.key === 'Enter' && !(event.target as HTMLElement).closest('[data-block-editor], [data-check-panel], [data-optimization-panel], [data-studio-help]')) {
        event.preventDefault(); void convert();
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [convert, optimizationOpen, helpOpen]);

  function loadExample() {
    setDocumentEpoch(value => value + 1);
    openDock('code'); setCodeView('source');
    invalidate(); setSource(EXAMPLE); setInitialSource(EXAMPLE); setBaselineSource(EXAMPLE); setFileName(EXAMPLE_NAME); setUploadedFile(null);
  }

  function loadFeatures() {
    setDocumentEpoch(value => value + 1);
    openDock('code'); setCodeView('source');
    invalidate(); setSource(FEATURES_EXAMPLE); setInitialSource(FEATURES_EXAMPLE); setBaselineSource(FEATURES_EXAMPLE); setFileName(FEATURES_NAME); setUploadedFile(null);
  }

  function loadAnalysisExample() {
    setDocumentEpoch(value => value + 1);
    openDock('code'); setCodeView('source');
    invalidate(); setSource(analysisExample); setInitialSource(analysisExample); setBaselineSource(analysisExample);
    setFileName('AnalysisExamples.java'); setUploadedFile(null);
  }

  function loadOptimizationExample() {
    setDocumentEpoch(value => value + 1);
    openDock('code'); setCodeView('source');
    invalidate(); setSource(optimizationExample); setInitialSource(optimizationExample); setBaselineSource(optimizationExample);
    setFileName('OptimizationExamples.java'); setUploadedFile(null);
  }

  function openOptimization() {
    if (!result || busy) return;
    optimizationApplied.current = false;
    setOptimizationOpen(true);
  }

  function loadVisualExample() {
    setDocumentEpoch(value => value + 1);
    openDock('code'); setCodeView('source');
    invalidate(); setSource(VISUAL_EXAMPLE); setInitialSource(VISUAL_EXAMPLE); setBaselineSource(VISUAL_EXAMPLE);
    setFileName(VISUAL_NAME); setUploadedFile(null); setTestSource(VISUAL_TESTS); setTestFileName(VISUAL_TEST_NAME);
  }

  function editOptimizationTests() {
    if (busy) return;
    setOptimizationOpen(false); openDock('tests');
    requestAnimationFrame(() => {
      const tests = document.getElementById('test-source');
      tests?.scrollIntoView({ block: 'center', behavior: 'auto' });
      tests?.focus();
    });
  }

  function applyOptimization(snapshot: VerificationSnapshot) {
    if (!result || busy || optimizationApplied.current) return;
    const current = {
      fileName, source: sourceRef.current, revision: result.revision, testFileName, testSource,
      candidateId: snapshot.preview.candidate.id,
    };
    if (!canApplyOptimization(snapshot, current)) {
      setError({ message: 'Снимок оптимизации устарел или проверка не завершена. Повторите сравнение перед применением.', diagnostics: [] });
      return;
    }
    optimizationApplied.current = true;
    requestVersion.current += 1;
    const previous = { source, result, methodId, selectedNodeId, compilation };
    const proposal = snapshot.preview;
    setCompilation(null);
    sourceRef.current = proposal.source;
    setSource(proposal.source); setResult(proposal.conversion);
    setMethodId(proposal.selectedMethodId); setSelectedNodeId(proposal.selectedNodeId); setRightTab('diagram');
    setUploadedFile(null); setCopied(false); setError(null); setHealth('online');
    setHistory(items => [...items, previous].slice(-20)); setFuture([]);
    setEditNotice('Проверенное изменение применено. Его можно отменить одной кнопкой. Сохранённая версия «до» не менялась.');
    highlightRef.current?.clear(); setSelectedLine(null);
    pendingNavigation.current = proposal.conversion.methods.find(method => method.id === proposal.selectedMethodId)
      ?.nodes.find(node => node.id === proposal.selectedNodeId)?.range ?? null;
    setOptimizationOpen(false);
  }

  function download(kind: 'source' | 'ast' = 'ast') {
    const text = kind === 'source' ? source : result?.generatedSource;
    if (text == null) return;
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url; link.download = fileName; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  async function copyCode() {
    const text = codeView === 'source' ? source : result?.generatedSource;
    if (text == null) return;
    try { await navigator.clipboard.writeText(text); setCopied(true); }
    catch { setError({ message: 'Браузер не разрешил копирование. Скачайте файл или выделите код вручную.', diagnostics: [] }); }
  }

  // React и Monaco обновляют текст не в один момент. Ждём нужный текст модели,
  // затем подсвечиваем диапазон: иначе setValue сбросит только что поставленный курсор.
  function flushNavigation() {
    const pending = pendingNavigation.current;
    const editor = editorRef.current;
    if (!pending || !editor || editorText(editor.getValue()) !== editorText(sourceRef.current)) return;
    pendingNavigation.current = null;
    const reveal = pendingFocus.current; pendingFocus.current = false;
    requestAnimationFrame(() => goToRange(pending, reveal));
  }

  useEffect(() => { flushNavigation(); }, [source, result]);

  function goToRange(range: SourceRange, reveal = true) {
    setDockTab('code'); setCodeView('source');
    if (reveal) { setDockOpen(true); setMobileView('dock'); }
    const editor = editorRef.current;
    if (!editor || editorText(editor.getValue()) !== editorText(sourceRef.current)) {
      pendingNavigation.current = range; pendingFocus.current = reveal; return;
    }
    requestAnimationFrame(() => {
      editor.layout();
      const selection = new monaco.Range(range.startLine, range.startColumn, range.endLine, range.endColumn + 1);
      highlightRef.current?.set([{ range: selection, options: {
        isWholeLine: true, className: 'source-flow-highlight', linesDecorationsClassName: 'source-flow-gutter',
      } }]);
      setSelectedLine(range.startLine);
      editor.revealLineInCenter(range.startLine); editor.setSelection(selection);
      if (reveal) editor.focus();
    });
  }

  function goToLine(line: number) {
    goToRange({ startLine: line, startColumn: 1, endLine: line, endColumn: 1 });
  }

  function selectMethod(id: string) {
    setMethodId(id); setSelectedNodeId('');
    const method = result?.methods.find(item => item.id === id);
    if (method) goToRange(method.range, false);
  }


  function navigateAnalysis(finding: Finding, destination: 'code' | 'block') {
    if (!result || busy) return;
    const target = result.methods.find(method => method.id === finding.methodId);
    if (!target) return;
    setRightTab('diagram'); setMethodId(target.id);
    setSelectedNodeId(finding.nodeIds.find(id => target.nodes.some(node => node.id === id)) ?? '');
    setAnalysisFocusToken(value => value + 1);
    goToRange(finding.range, destination === 'code');
    if (destination === 'block') setMobileView('graph');
  }

  async function mutateGraph(nodeId: string, perform: (current: Conversion) => Promise<BlockEditResult>, notice: string) {
    if (!result || busy || actionLock.current) throw new RequestError('Дождитесь завершения текущей операции.');
    actionLock.current = true;
    const version = ++requestVersion.current;
    const previous = { source, result, methodId, selectedNodeId: nodeId, compilation };
    setBusy(true);
    try {
      const response = await perform(result);
      if (version !== requestVersion.current) throw new RequestError('Исходник изменился. Повторите преобразование.');
      sourceRef.current = response.source;
      setSource(response.source); setResult(response.conversion);
      setMethodId(response.selectedMethodId); setSelectedNodeId(response.selectedNodeId);
      setUploadedFile(null); setCopied(false); setError(null); setHealth('online');
      setHistory(items => [...items, previous].slice(-20)); setFuture([]); setEditNotice(notice);
      highlightRef.current?.clear(); setSelectedLine(null);
      pendingNavigation.current = response.conversion.methods.find(m => m.id === response.selectedMethodId)
        ?.nodes.find(n => n.id === response.selectedNodeId)?.range ?? null;
      await checkCurrentCode(fileName, response.source, version);
    } finally { actionLock.current = false; if (version === requestVersion.current) setBusy(false); }
  }

  function applyBlockEdit(nodeId: string, replacement: string) {
    return mutateGraph(nodeId, current => editBlockSource(fileName, source, current.revision, methodId, nodeId, replacement),
      'Блок изменён. Java-код и схема обновлены.');
  }
  function applyBlockInsert(nodeId: string, position: string, kind: string, code: string) {
    return mutateGraph(nodeId, current => insertBlockSource(fileName, source, current.revision, methodId, nodeId, position, kind, code),
      'Новый блок добавлен в Java-код. Проверьте программу тестами.');
  }

  function applyVisualEdit(nodeId: string, mode: 'insert' | 'edit', spec: VisualSpec, position?: string) {
    return mutateGraph(nodeId, current => applyVisualBlock({ fileName, source, revision: current.revision, methodId, nodeId }, mode, spec, position),
      mode === 'insert' ? 'Блок добавлен. Java-код собран из настроек.' : 'Настройки сохранены. Java-код и схема обновлены.');
  }

  function applyBlockDelete(nodeId: string, mode: string) {
    return mutateGraph(nodeId, current => deleteBlockSource(fileName, source, current.revision, methodId, nodeId, mode),
      'Блок удалён. Код и схема обновлены; доступна отмена.');
  }
  function applyBlockMove(nodeId: string, targetNodeId: string, position: string) {
    return mutateGraph(nodeId, current => moveBlockSource(fileName, source, current.revision, methodId, nodeId, targetNodeId, position),
      'Логика перемещена. Порядок действий в Java изменён.');
  }

  function moveHistory(redo: boolean) {
    const stack = redo ? future : history;
    const snapshot = stack.at(-1);
    if (!snapshot || !result || busy) return;
    const current = { source, result, methodId, selectedNodeId, compilation };
    if (redo) { setFuture(stack.slice(0, -1)); setHistory(items => [...items, current].slice(-20)); }
    else { setHistory(stack.slice(0, -1)); setFuture(items => [...items, current]); }
    requestVersion.current += 1;
    setCompilation(snapshot.compilation ?? null);
    sourceRef.current = snapshot.source;
    setSource(snapshot.source); setResult(snapshot.result); setMethodId(snapshot.methodId);
    setSelectedNodeId(snapshot.selectedNodeId); setUploadedFile(null); setCopied(false); setError(null);
    pendingNavigation.current = snapshot.result.methods.find(m => m.id === snapshot.methodId)?.nodes.find(n => n.id === snapshot.selectedNodeId)?.range ?? null;
    setEditNotice(redo ? 'Правка повторена.' : 'Правка отменена.');
    highlightRef.current?.clear(); setSelectedLine(null);
  }

  return (
    <div className="studio-app" data-workspace="scratch" data-dock-open={dockOpen} data-dock-size={dockSize} data-mobile-view={mobileView}
      onDragOver={event => { if (!busy && event.dataTransfer.types.includes('Files')) { event.preventDefault(); setDragging(true); } }}
      onDragLeave={event => { if (!event.currentTarget.contains(event.relatedTarget as Node)) setDragging(false); }}
      onDrop={event => { const file = event.dataTransfer.files[0]; if (!file) return; event.preventDefault(); setDragging(false); void selectFile(file); }}>
      <header className="studio-header">
        <div className="studio-brand" aria-label="Java Blueprints"><Braces size={21} /><strong>Java Blueprints</strong></div>
        <div className="studio-file" title={fileName}><FileCode2 size={16} /><span>{fileName}</span>{changed && <span className="edited-dot" title="Есть изменения" />}</div>
        <StudioTabs prefix={instanceId + '-graph'} label="Представление графа" className="studio-workspace-switch" disabled={busy || graphLocked}
          value={rightTab} items={[{ id: 'diagram', label: 'Схема' }, { id: 'structure', label: 'Структура' }]}
          panelId={instanceId + '-graph-content'} onChange={value => { setRightTab(value); setMobileView('graph'); }} />
        <div className="studio-toolbar">
          <input ref={inputRef} type="file" accept=".java" hidden onChange={event => { const file = event.target.files?.[0]; if (file) void selectFile(file); event.target.value = ''; }} />
          <button className="button secondary" disabled={busy || graphLocked} onClick={() => inputRef.current?.click()} title="Импорт .java · UTF-8 · до 1 МБ"><Upload size={15} />Импорт</button>
          <select className="studio-example-select" aria-label="Примеры Java" value="" disabled={busy || graphLocked} onChange={event => {
            const loaders: Record<string, () => void> = { visual: loadVisualExample, price: loadExample, features: loadFeatures, analysis: loadAnalysisExample, optimization: loadOptimizationExample };
            loaders[event.target.value]?.();
          }}><option value="" disabled>Примеры</option><option value="visual">Визуальное редактирование</option><option value="price">Покупки · PriceCalculator</option><option value="features">Конструкции Java</option><option value="analysis">Анализ и Big O</option><option value="optimization">Оптимизация</option></select>
          <select className="studio-example-select" aria-label="Экспорт Java" value="" disabled={busy} onChange={event => download(event.target.value === 'source' ? 'source' : 'ast')}>
            <option value="" disabled>Экспорт</option><option value="source">Исходный код</option><option value="ast" disabled={!result}>Код из AST</option></select>
          <div className="studio-history-controls">
            <button className="icon-button" aria-label="Отменить правку" title="Отменить правку" disabled={busy || graphLocked || !history.length} onClick={() => moveHistory(false)}><Undo2 size={17} /></button>
            <button className="icon-button" aria-label="Повторить правку" title="Повторить правку" disabled={busy || graphLocked || !future.length} onClick={() => moveHistory(true)}><Redo2 size={17} /></button>
          </div>
          <button className="button primary" onClick={() => void convert()} disabled={busy || graphLocked || !source.trim()} title="Обновить схему · Ctrl + Enter">
            {busy ? <LoaderCircle size={15} className="spin" /> : <RefreshCw size={15} />}Обновить схему</button>
          <button className="button secondary optimization-entry" disabled={!result || busy || graphLocked} onClick={openOptimization}><Sparkles size={15} />Оптимизировать</button>
          <button className="button secondary" onClick={() => openDock('tests')}><Play size={14} />Тесты</button>
          <button className="icon-button" aria-label="Открыть справку" title="Справка" onClick={() => setHelpOpen(true)}><HelpCircle size={18} /></button>
        </div>
        <div className="studio-mobile-switch" aria-label="Область экрана">
          <button aria-pressed={mobileView === 'graph'} aria-controls="studio-graph" onClick={() => setMobileView('graph')}>Схема</button>
          <button aria-pressed={mobileView === 'dock'} aria-controls="studio-dock" onClick={() => { setMobileView('dock'); setDockOpen(true); }}>Панель</button>
        </div>
      </header>
      <main className="studio-main">
        <section className="studio-graph" id="studio-graph" aria-label="Граф Java"
          onPointerDownCapture={() => setMobileView('graph')} onFocusCapture={() => setMobileView('graph')}>
          {error && <div className="studio-error" role="alert"><CircleAlert size={18} /><div><strong>{error.message}</strong>
            {error.diagnostics.length > 0 && <ul>{error.diagnostics.map((item, index) => <li key={index}>
              {item.line && <button onClick={() => goToLine(item.line!)}>Строка {item.line}{item.column ? ':' + item.column : ''}</button>}<span>{item.message}</span></li>)}</ul>}</div>
            <button className="icon-button" aria-label="Закрыть сообщение об ошибке" onClick={() => setError(null)}><X size={16} /></button></div>}
          <div className="studio-graph-content" id={instanceId + '-graph-content'} role="tabpanel" aria-labelledby={instanceId + '-graph-tab-' + rightTab} aria-busy={busy}>
            {result ? <MethodDiagram methods={rightTab === 'structure' ? [result.structure] : result.methods}
              selectedId={rightTab === 'structure' ? 'structure' : methodId} onOpenMethod={id => { selectMethod(id); setRightTab('diagram'); }}
              onSelect={selectMethod} fileName={fileName} onNavigate={range => goToRange(range, false)} onReveal={goToRange}
              onInteractionLockChange={setGraphLocked} onInsert={applyBlockInsert} onDelete={applyBlockDelete} onMove={applyBlockMove} revision={result.revision} selectedNodeId={selectedNodeId} busy={busy} onEdit={applyBlockEdit}
              analysis={result.analysis} onAnalysisNavigate={navigateAnalysis} focusToken={analysisFocusToken}
              visualContext={{ fileName, source, revision: result.revision, methodId }} onVisualApply={applyVisualEdit} />
              : <div className="studio-empty">{busy ? <LoaderCircle size={24} className="spin" /> : <Braces size={26} />}
                <strong>{busy ? 'Ожидаем завершения операции…' : 'Схема не построена'}</strong>
                <p>Импортируйте файл или измените код, затем нажмите «Обновить схему».</p>
                <button className="button secondary" onClick={() => openDock('code')}>Открыть код</button></div>}
          </div>
        </section>
        <section className="studio-dock" id="studio-dock" aria-label="Код и проверка">
          <div className="studio-dock-bar">
            <StudioTabs prefix={instanceId + '-dock'} label="Рабочие панели" className="studio-dock-tabs" value={dockTab}
              items={[{ id: 'code', label: 'Код' }, { id: 'tests', label: 'Тесты' }, { id: 'results', label: 'Результаты' }, { id: 'console', label: 'Консоль' }, { id: 'comparison', label: 'Сравнение' }, { id: 'info', label: 'Сведения' }]}
              onChange={openDock} />
            <div className="studio-dock-controls"><button type="button" className="icon-button" aria-label={dockOpen ? 'Свернуть нижнюю панель' : 'Развернуть нижнюю панель'} aria-expanded={dockOpen} onClick={() => { setDockOpen(!dockOpen); setMobileView(dockOpen ? 'graph' : 'dock'); }}>{dockOpen ? '⌄' : '⌃'}</button><select hidden={!dockOpen} aria-label="Размер нижней панели" value={dockSize} onChange={event => setDockSize(event.target.value as DockSize)}>
              <option value="compact">Компактная</option><option value="normal">Обычная</option><option value="large">Большая</option></select></div>
          </div>
          <StudioPanel prefix={instanceId + '-dock'} id="code" active={dockOpen && dockTab === 'code'} className="studio-code-panel">
            <div className="studio-code-toolbar">
              <StudioTabs prefix={instanceId + '-code'} label="Версия кода" className="studio-code-tabs" value={codeView}
                items={[{ id: 'source', label: 'Исходник' }, { id: 'ast', label: 'Код из AST' }]} onChange={value => { setCodeView(value); setCopied(false); }} />
              <span className="studio-code-caption">{codeView === 'source' ? fileName : result ? 'Восстановленный код · только чтение' : 'Обновите схему для просмотра AST'}</span>
              <button className="icon-button" aria-label={copied ? 'Код скопирован' : 'Копировать код'} title={copied ? 'Скопировано' : 'Копировать код'} disabled={codeView === 'ast' && !result} onClick={() => void copyCode()}>
                {copied ? <Check size={15} /> : <Copy size={15} />}</button>
              <button className="icon-button" aria-label="Скачать выбранный код" title="Скачать выбранный код" disabled={codeView === 'ast' && !result} onClick={() => download(codeView)}><ArrowDownToLine size={16} /></button>
              <button className="icon-button" aria-label="Вернуть загруженный исходник" title="Вернуть загруженный исходник" disabled={!changed || busy || graphLocked} onClick={() => { setDocumentEpoch(value => value + 1); invalidate(); setSource(initialSource); setCodeView('source'); }}><RotateCcw size={16} /></button>
            </div>
            <div className="studio-code-editors">
              <div className="studio-code-editor" hidden={codeView !== 'source'} inert={codeView !== 'source'} role="tabpanel"
                id={instanceId + '-code-panel-source'} aria-labelledby={instanceId + '-code-tab-source'}>
                <Editor key={documentEpoch} height="100%" path={sourceModelPath} language="java" theme="blueprints-dark" value={source}
                  loading={<div className="studio-empty">Загружаем редактор…</div>} options={{ ...editorOptions, readOnly: busy || graphLocked }}
                  onMount={editor => { editorRef.current = editor; highlightRef.current = editor.createDecorationsCollection(); editor.onDidChangeModelContent(flushNavigation); flushNavigation(); }}
                  onChange={value => { const next = value ?? ''; if (editorText(next) === editorText(sourceRef.current)) return; sourceRef.current = next; invalidate(); setSource(next); }} />
              </div>
              <div className="studio-code-editor" hidden={codeView !== 'ast'} inert={codeView !== 'ast'} role="tabpanel"
                id={instanceId + '-code-panel-ast'} aria-labelledby={instanceId + '-code-tab-ast'}>
                <Editor height="100%" path={astModelPath} language="java" theme="blueprints-dark" value={result?.generatedSource ?? ''}
                  onMount={editor => { astEditorRef.current = editor; }} options={{ ...editorOptions, readOnly: true, domReadOnly: true }} />
              </div>
            </div>
          </StudioPanel>
          <CheckPanel tests={testSource} testName={testFileName} setTests={setTestSource} setTestName={setTestFileName}
            baselineSource={baselineSource} originalSource={initialSource} onBaseline={setBaselineSource} fileName={fileName} source={source}
            busy={busy || graphLocked} onBusy={setBusy} onSourceLine={goToLine} activeTab={dockOpen ? dockTab : null} tabPrefix={instanceId + '-dock'} onOpenTab={openDock} />
          <StudioPanel prefix={instanceId + '-dock'} id="info" active={dockOpen && dockTab === 'info'} className="studio-info-panel">
            {result ? <div className="studio-info-content">
              <div className={'studio-validation' + (!result.structureEquivalent ? ' warning' : '')}>
                {result.structureEquivalent ? <Check size={18} /> : <CircleAlert size={18} />}
                <span>{result.structureEquivalent ? 'Структура AST совпадает после восстановления' : 'Структура AST требует проверки'}</span></div>
              <dl className="studio-info-stats"><div><dt>Типы</dt><dd>{result.statistics.types}</dd></div>
                <div><dt>Методы</dt><dd>{result.statistics.methods}</dd></div><div><dt>Узлы AST</dt><dd>{result.statistics.astNodes}</dd></div>
                <div><dt>Строки</dt><dd>{result.statistics.sourceLines}</dd></div></dl>
              <div className="studio-declarations">{result.declarations.map((item, index) => <button key={index} onClick={() => goToLine(item.line)}>
                <span>{item.name}</span><small>{item.kind} · стр. {item.line}</small></button>)}</div>
              <details><summary>Проверка структуры</summary><p>Java-код повторно разобран. Сравнение дерева выполняется без учёта комментариев; поведение проверяется тестами.</p></details>
            </div> : <div className="studio-empty">Сведения появятся после обновления схемы.</div>}
          </StudioPanel>
        </section>
      </main>
      <footer className="studio-statusbar">
        <span className={'server-status ' + health}><i />{health === 'online' ? 'Сервер подключён' : health === 'offline' ? 'Сервер недоступен' : 'Проверяем сервер'}</span>
        <GraphCompilation snapshot={compilation} source={source} fileName={fileName} onLine={goToLine} />
        <span className="studio-notice" role="status" title={editNotice}>{busy ? 'Выполняется операция…' : editNotice || 'Java 21 · UTF-8'}</span>
        <span>{selectedLine ? 'Строка ' + selectedLine + ' · ' : ''}{sourceBytes < 1024 ? sourceBytes + ' Б' : (sourceBytes / 1024).toFixed(1) + ' КБ'}</span>
      </footer>
      {dragging && <div className="studio-drop-overlay">Отпустите Java-файл для импорта</div>}
      {helpOpen && <StudioHelp onClose={() => setHelpOpen(false)} />}
      {optimizationOpen && result && <OptimizationDialog fileName={fileName} source={source} revision={result.revision}
        testFileName={testFileName} testSource={testSource} busy={busy} onBusy={setBusy}
        onClose={() => { if (!busy) setOptimizationOpen(false); }} onEditTests={editOptimizationTests} onApply={applyOptimization} />}
    </div>
  );
}
