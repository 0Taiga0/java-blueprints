import { useState } from 'react';
import { ArrowRight, Check, CircleAlert, Download, GitCompareArrows, LoaderCircle } from 'lucide-react';
import { compareProgram, type ComparisonResult, type CheckResult } from './api';

type Props = {
  fileName: string; source: string; baselineSource: string; originalSource: string;
  testName: string; tests: string; busy: boolean;
  onBusy: (busy: boolean) => void; onBaseline: (source: string) => void;
};
type Snapshot = {fileName: string; source: string; baselineSource: string; testName: string; tests: string; at: string; report: ComparisonResult};
const verdicts = {matched: 'Совпало на тестах', changed: 'Найдены расхождения', baseline_failed: 'Ошибка уже была в исходнике', inconclusive: 'Сравнение не завершено'};
const changes = {unchanged: 'Без расхождений', regression: 'Новая ошибка', fixed: 'Исправлено', both_failed: 'Ошибка в обеих версиях',
  skipped: 'Пропуск', missing: 'Нет пары', ambiguous: 'Неоднозначные имена'};
const runs: Record<CheckResult['status'], string> = {compiled: 'Скомпилировано', passed: 'Тесты прошли', tests_failed: 'Ошибка тестов',
  compile_error: 'Не компилируется', compile_timeout: 'Компиляция остановлена', test_timeout: 'Лимит времени',
  no_tests: 'Нет выполненных тестов', runner_error: 'Ошибка запуска'};

function TestOutcome({test}: {test: CheckResult['tests'][number] | null}) {
  if (!test) return <span className="outcome missing">Нет результата</span>;
  return <div><span className={'outcome ' + test.status}>{test.status === 'passed' ? '✓ Пройден' : test.status === 'failed' ? '× Ошибка' : '— Пропущен'}</span>
    {test.message && <details className="comparison-problem">
      <summary>{test.message.split('\n')[0].replace('expected:', 'Ожидали:').replace('but was:', 'Получили:') || 'Подробности'}</summary>
      <pre>{test.message}</pre>
    </details>}</div>;
}

function RunCard({title, report}: {title: string; report: CheckResult}) {
  return <div className={'comparison-run ' + (report.status === 'passed' && !report.skipped && !report.outputTruncated ? 'passed' : 'attention')}>
    <span className="comparison-label">{title}</span><strong>{runs[report.status]}</strong>
    <p><b>{report.passed}</b> прошли · <b>{report.failed}</b> с ошибкой · <b>{report.skipped}</b> пропущены</p>
    <small>{(report.durationMs / 1000).toFixed(2)} с · компиляция + тесты</small>
    <details className="process-output"><summary>Диагностика и вывод этой версии</summary><p>{report.message}</p>
      {report.diagnostics.map((d, i) => <p key={i}>{d.file === 'tests' ? 'Тесты' : 'Java-код'} · строка {d.line}:{d.column} — {d.message}</p>)}
      <pre>{report.output || 'Вывод пуст.'}</pre>
      {report.outputTruncated && <p>Отчёт или вывод сокращён.</p>}
    </details>
  </div>;
}

export function ComparisonReport({report, stale = false, onDownload}: {report: ComparisonResult; stale?: boolean; onDownload?: () => void}) {
  return <>
        {stale && <div className="stale-report"><CircleAlert size={17} />Отчёт устарел: код, версия «до» или тесты изменились. Запустите сравнение заново.</div>}
        <div className={'comparison-verdict ' + (stale ? 'stale' : report.status)}>
          {report.status === 'matched' && !stale ? <Check size={25} /> : <CircleAlert size={25} />}
          <div><strong>{verdicts[report.status]}{stale ? ' · прежние версии' : ''}</strong><p>{report.message}</p></div>
          {onDownload && <button className="text-button" onClick={onDownload}><Download size={15} />Скачать отчёт{stale ? ' прежней проверки' : ''}</button>}
        </div>
        {report.reasons.length > 0 && <ul className="comparison-reasons">{report.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul>}
        <div className={'comparison-detail' + (stale ? ' is-stale' : '')}>
          <div className="comparison-runs"><RunCard title="ДО ИЗМЕНЕНИЙ" report={report.before} /><RunCard title="ПОСЛЕ ИЗМЕНЕНИЙ" report={report.after} /></div>
          <div className="comparison-counts"><span><b>{report.unchanged}</b> без расхождений</span><span><b>{report.regressions}</b> новых ошибок</span><span><b>{report.fixed}</b> исправлено</span><span><b>{report.unresolved}</b> требуют проверки</span></div>
          {report.tests.length > 0 ? <div className="comparison-table-scroll"><table className="comparison-table">
            <caption>Результаты одного набора тестов на обеих версиях</caption>
            <thead><tr><th scope="col">Тест</th><th scope="col">До</th><th scope="col">После</th><th scope="col">Что изменилось</th></tr></thead>
            <tbody>{report.tests.map((test, index) => <tr key={index} className={test.change}>
              <th scope="row">{test.name}<small>{test.className}</small></th><td><TestOutcome test={test.before} /></td><td><TestOutcome test={test.after} /></td>
              <td><span className={'change-badge ' + test.change}>{changes[test.change]}</span></td>
            </tr>)}</tbody></table></div> : <p className="comparison-hint">Построчного отчёта тестов нет. Откройте диагностику обеих версий выше.</p>}
        </div>
  </>;
}

export function ComparisonPanel({fileName, source, baselineSource, originalSource, testName, tests, busy, onBusy, onBaseline}: Props) {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');
  const stale = !!snapshot && (snapshot.fileName !== fileName || snapshot.source !== source || snapshot.baselineSource !== baselineSource ||
    snapshot.testName !== testName || snapshot.tests !== tests);
  const report = snapshot?.report;

  async function compare() {
    if (busy) return;
    onBusy(true); setRunning(true); setError(''); setSnapshot(null);
    try {
      const response = await compareProgram(fileName, baselineSource, source, testName, tests);
      setSnapshot({fileName, source, baselineSource, testName, tests, at: new Date().toISOString(), report: response});
    } catch (caught) { setError(caught instanceof Error ? caught.message : 'Не удалось сравнить версии.'); }
    finally { setRunning(false); onBusy(false); }
  }

  function downloadReport() {
    if (!snapshot) return;
    // Скачивается именно проверенный снимок, даже если редактор уже изменился.
    const url = URL.createObjectURL(new Blob([JSON.stringify({format: 'java-blueprints-comparison-v1', ...snapshot}, null, 2)], {type: 'application/json;charset=utf-8'}));
    const link = document.createElement('a'); link.href = url; link.download = 'comparison-' + snapshot.fileName.replace(/\.java$/i, '') + '.json'; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return <section className="comparison-panel" aria-label="Сравнение до и после">
    <div className="comparison-heading"><div><h2><GitCompareArrows size={20} /> Сравнение до и после</h2>
      <p>Один набор тестов: {testName}</p></div>
      <button className="button primary" disabled={busy || !source.trim() || !baselineSource.trim() || !tests.trim() || !testName.trim()}
        onClick={() => void compare()}>{running ? <LoaderCircle size={17} className="spin" /> : <GitCompareArrows size={17} />}Сравнить до и после</button>
    </div>
    <div className="comparison-reference">
      <div><span className="comparison-label">ДО · СОХРАНЁННАЯ ВЕРСИЯ</span><strong>{fileName}</strong>
        <p>{baselineSource === originalSource ? 'Код при загрузке файла или примера.' : 'Код, который вы зафиксировали вручную.'}</p></div>
      <ArrowRight size={22} className="comparison-arrow" />
      <div><span className="comparison-label">ПОСЛЕ · ТЕКУЩИЙ КОД</span><strong>{baselineSource === source ? 'Код пока не изменён' : 'Есть изменения для проверки'}</strong>
        <p>Включает правки редактора и блок-схемы.</p></div>
    </div>
    <div className="baseline-tools">
      <details><summary>Посмотреть код «до»</summary><pre className="baseline-source">{baselineSource}</pre></details>
      <button className="text-button" disabled={busy || !source.trim() || baselineSource === source} onClick={() => onBaseline(source)}>Сделать текущий код версией «до»</button>
      {baselineSource !== originalSource && <button className="text-button" disabled={busy} onClick={() => onBaseline(originalSource)}>Вернуть загруженную версию «до»</button>}
    </div>

    <div className="comparison-results" aria-live="polite" aria-busy={running}>
      {error && <div className="check-error" role="alert"><CircleAlert size={17} />{error}</div>}
      {running && <div className="comparison-loading"><LoaderCircle size={22} className="spin" /><div><strong>Проверяем обе версии…</strong><p>Два отдельных запуска одного набора тестов.</p></div></div>}
      {report && !running && <>
        <ComparisonReport report={report} stale={stale} onDownload={downloadReport} />
      </>}
    </div>
    <details className="comparison-scope"><summary>Границы проверки</summary><p>Совпадение подтверждается только заданными тестами. Для вывода консоли нужна assert-проверка вывода. Время включает компиляцию и JUnit и не показывает ускорение программы. Версия «до» хранится до перезагрузки; импорт другого файла задаёт её заново.</p></details>
  </section>;
}
