import { useRef, useState } from 'react';
import { Check, CircleAlert, FileUp, Hammer, LoaderCircle, Play, Download, Terminal } from 'lucide-react';
import { checkProgram, type CheckMode, type CheckResult } from './api';
import { ComparisonPanel } from './ComparisonPanel';
import { TEST_EXAMPLE, TEST_EXAMPLE_NAME } from './example';
import { FEATURES_TESTS, FEATURES_TESTS_NAME } from '../features-example';
import { analysisTests } from '../analysis-example';
import { optimizationTests } from '../optimization-example';
import { VISUAL_TESTS, VISUAL_TEST_NAME } from '../visual-example';
import { StudioPanel } from '../studio/StudioTabs';
import type { CheckTab, DockTab } from '../studio/types';

type Props = {
  tests: string; testName: string; setTests: (value: string) => void; setTestName: (value: string) => void;
  baselineSource: string; originalSource: string; onBaseline: (source: string) => void;
  fileName: string; source: string; busy: boolean; onBusy: (value: boolean) => void;
  onSourceLine: (line: number) => void; activeTab: DockTab | null; tabPrefix: string; onOpenTab: (tab: CheckTab) => void;
};
type Snapshot = { source: string; fileName: string; tests: string; testName: string; mode: CheckMode; report: CheckResult };
const titles: Record<CheckResult['status'], string> = {
  compiled: 'Компиляция успешна', compile_error: 'Ошибка компиляции', compile_timeout: 'Компиляция остановлена',
  passed: 'Тесты прошли', tests_failed: 'Тесты нашли проблему', test_timeout: 'Время тестов истекло',
  no_tests: 'Нет выполненных тестов', runner_error: 'Не удалось завершить запуск',
};
const examples = {
  visual: { source: VISUAL_TESTS, file: VISUAL_TEST_NAME },
  price: { source: TEST_EXAMPLE, file: TEST_EXAMPLE_NAME },
  features: { source: FEATURES_TESTS, file: FEATURES_TESTS_NAME },
  analysis: { source: analysisTests, file: 'AnalysisExamplesTest.java' },
  optimization: { source: optimizationTests, file: 'OptimizationExamplesTest.java' },
};

export function CheckPanel({ tests, testName, setTests, setTestName, baselineSource, originalSource, onBaseline,
  fileName, source, busy, onBusy, onSourceLine, activeTab, tabPrefix, onOpenTab }: Props) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [attempt, setAttempt] = useState<Omit<Snapshot, 'report'> | null>(null);
  const [error, setError] = useState('');
  const [running, setRunning] = useState<CheckMode | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const textarea = useRef<HTMLTextAreaElement>(null);
  const lock = useRef(false);
  const report = snapshot?.report;
  const context = snapshot ?? attempt;
  const stale = !!context && (context.source !== source || context.fileName !== fileName ||
    (context.mode === 'tests' && (context.tests !== tests || context.testName !== testName)));
  const success = report?.status === 'compiled' || report?.status === 'passed';
  const incomplete = report?.status === 'no_tests' || (report?.skipped ?? 0) > 0 || !!report?.outputTruncated;

  async function run(mode: CheckMode) {
    if (busy || lock.current) return;
    lock.current = true;
    onBusy(true); setRunning(mode); setError(''); setSnapshot(null); setAttempt({ source, fileName, tests, testName, mode }); onOpenTab('results');
    try {
      const response = await checkProgram(mode, fileName, source, testName, tests);
      setSnapshot({ source, fileName, tests, testName, mode, report: response });
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Не удалось выполнить проверку.'); }
    finally { lock.current = false; setRunning(null); onBusy(false); }
  }

  async function loadTests(file: File) {
    if (busy || lock.current) return;
    if (!file.name.endsWith('.java') || file.size > 128 * 1024) {
      setError('Выберите файл тестов .java размером до 128 КБ.'); onOpenTab('results'); return;
    }
    lock.current = true; onBusy(true);
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
      setTests(text); setTestName(file.name); setError('');
    } catch { setError('Не удалось прочитать тесты. Нужен файл в кодировке UTF-8.'); onOpenTab('results'); }
    finally { lock.current = false; onBusy(false); }
  }

  function downloadTests() {
    const url = URL.createObjectURL(new Blob([tests], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a'); link.href = url; link.download = testName; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function showDiagnostic(file: 'source' | 'tests', line: number) {
    if (stale) return;
    if (file === 'source') { onSourceLine(line); return; }
    onOpenTab('tests');
    requestAnimationFrame(() => {
      const editor = textarea.current;
      if (!editor) return;
      const lines = tests.replace(/\r\n/g, '\n').split('\n');
      const start = lines.slice(0, line - 1).reduce((n, value) => n + value.length + 1, 0);
      editor.focus(); editor.setSelectionRange(start, start + (lines[line - 1]?.length ?? 0));
      editor.scrollTop = Math.max(0, (line - 4) * 21);
    });
  }

  const actions = <div className="check-actions">
    <button className="button secondary" disabled={busy || !source.trim()} onClick={() => void run('compile')}>
      {running === 'compile' ? <LoaderCircle size={15} className="spin" /> : <Hammer size={15} />}Компилировать</button>
    <button className="button primary" disabled={busy || !source.trim() || !tests.trim() || !testName.trim()} onClick={() => void run('tests')}>
      {running === 'tests' ? <LoaderCircle size={15} className="spin" /> : <Play size={15} />}Запустить тесты</button>
  </div>;

  return <>
    <StudioPanel prefix={tabPrefix} id="tests" active={activeTab === 'tests'} className="studio-tests-panel">
      <div className="studio-check-toolbar" data-check-panel>
        <label className="studio-test-filename" htmlFor="test-file-name">Файл тестов
          <input id="test-file-name" value={testName} disabled={busy} maxLength={160} onChange={event => setTestName(event.target.value)} /></label>
        <select className="studio-example-select" aria-label="Примеры тестов" value="" disabled={busy}
          onChange={event => { const example = examples[event.target.value as keyof typeof examples]; if (example) { setTests(example.source); setTestName(example.file); setError(''); } }}>
          <option value="" disabled>Примеры тестов</option><option value="visual">VisualWorkshop</option><option value="price">PriceCalculator</option>
          <option value="features">JavaFeatures</option><option value="analysis">AnalysisExamples</option><option value="optimization">OptimizationExamples</option>
        </select>
        <input ref={input} type="file" accept=".java" hidden onChange={event => { const file = event.target.files?.[0]; if (file) void loadTests(file); event.target.value = ''; }} />
        <button className="button secondary" disabled={busy} onClick={() => input.current?.click()}><FileUp size={14} />Загрузить</button>
        <button className="icon-button" title="Скачать тесты" aria-label="Скачать тесты" disabled={!tests.trim()} onClick={downloadTests}><Download size={16} /></button>
        {actions}
      </div>
      <div className="studio-test-editor" data-check-panel><label className="sr-only" htmlFor="test-source">Код тестов JUnit 5</label>
        <textarea id="test-source" ref={textarea} spellCheck={false} value={tests} disabled={busy} onChange={event => setTests(event.target.value)} /></div>
      <p className="studio-local-note">Локальный запуск: используйте доверенный код — доступ к файлам и сети не изолирован.</p>
    </StudioPanel>

    <StudioPanel prefix={tabPrefix} id="results" active={activeTab === 'results'} className="studio-results-panel">
      <div className="studio-check-toolbar" data-check-panel><strong>{fileName}</strong>{actions}
        <button className="button secondary" onClick={() => onOpenTab('tests')}>К тестам</button>
        <button className="button secondary" onClick={() => onOpenTab('console')}>Консоль</button></div>
      <div className="studio-check-content check-results" aria-live="polite" aria-busy={!!running} data-check-panel>
        {running ? <div className="studio-empty"><LoaderCircle className="spin" size={21} /><strong>{running === 'compile' ? 'Компилируем Java…' : 'Запускаем JUnit…'}</strong></div> : <>
          {error && <div className="check-error" role="alert"><CircleAlert size={17} />{error}{stale && <span> · Ошибка предыдущей проверки</span>}</div>}
          {!report ? <div className="studio-empty">Проверка ещё не запускалась.</div> : <>
            {stale && <div className="stale-report"><CircleAlert size={16} />Код или тесты изменились. Повторите проверку.</div>}
            <div className={'check-summary ' + (stale ? 'stale' : incomplete ? 'incomplete' : success ? 'passed' : 'failed')}>
              {success && !stale && !incomplete ? <Check size={20} /> : <CircleAlert size={20} />}
              <div><strong>{titles[report.status]}{stale ? ' · прежняя версия' : ''}</strong><p>{report.message}</p>
                <small>{(report.durationMs / 1000).toFixed(2)} с · {snapshot?.mode === 'tests' ? 'Компиляция + JUnit 5' : 'javac · Java 21'}</small></div>
            </div>
            {snapshot?.mode === 'tests' && <div className="test-counts"><span><b>{report.passed}</b> прошли</span><span><b>{report.failed}</b> с ошибкой</span><span><b>{report.skipped}</b> пропущены</span></div>}
            {report.diagnostics.length > 0 && <ul className="compile-diagnostics">{report.diagnostics.map((diagnostic, index) => <li key={index}>
              <button disabled={stale} onClick={() => showDiagnostic(diagnostic.file, diagnostic.line)}>{diagnostic.file === 'tests' ? 'Тесты' : 'Исходник'} · строка {diagnostic.line}</button><span>{diagnostic.message}</span></li>)}</ul>}
            <div className="test-cases">{report.tests.map((item, index) => <details key={index} className={'test-case ' + item.status} open={item.status === 'failed'}>
              <summary><span className="test-state">{item.status === 'passed' ? '✓' : item.status === 'failed' ? '×' : '—'}</span><span>{item.name}<small>{item.className}</small></span><em>{item.status === 'passed' ? 'Пройден' : item.status === 'failed' ? 'Ошибка' : 'Пропущен'}</em></summary>
              {item.message && <div className="test-problem"><pre>{item.message}</pre></div>}</details>)}</div>
            {report.outputTruncated && <p className="stale-report">Отчёт или вывод сокращён. Подробности во вкладке «Консоль».</p>}
          </>}
        </>}
      </div>
    </StudioPanel>

    <StudioPanel prefix={tabPrefix} id="console" active={activeTab === 'console'} className="studio-console-panel">
      <div className="studio-check-toolbar"><strong><Terminal size={15} /> Вывод компилятора и тестов</strong>
        <button className="button secondary" onClick={() => onOpenTab('results')}>Результаты</button></div>
      <div className="studio-check-content">
        {stale && (report || error) && <p className="stale-report">Вывод относится к предыдущей проверке.</p>}
        {error && <p className="check-error">{error}</p>}
        <pre className="studio-console-output">{report
          ? report.message + '\n\n' + report.diagnostics.map(item => (item.file === 'tests' ? snapshot?.testName : snapshot?.fileName) + ':' + item.line + ':' + item.column + ' — ' + item.message).join('\n') + '\n' + (report.output || 'Нет вывода.')
          : running ? 'Проверка выполняется…' : 'Вывод появится после компиляции или тестов.'}</pre>
        {report?.outputTruncated && <p className="stale-report">Отчёт или вывод сокращён.</p>}
      </div>
    </StudioPanel>

    <StudioPanel prefix={tabPrefix} id="comparison" active={activeTab === 'comparison'} className="studio-comparison-panel">
      <div className="studio-check-content" data-check-panel>
        <ComparisonPanel fileName={fileName} source={source} baselineSource={baselineSource} originalSource={originalSource}
          tests={tests} testName={testName} busy={busy} onBusy={onBusy} onBaseline={onBaseline} />
      </div>
    </StudioPanel>
  </>;
}
