import { useId, useState } from 'react';
import { AlertTriangle, ArrowDownToLine, GitBranch, Info } from 'lucide-react';
import type { AnalysisNavigation, AnalysisReport } from './types';
import { navigateTabs } from './tabNavigation';

export type AnalysisTab = 'problems' | 'complexity';

type Props = {
  report: AnalysisReport; methodId: string; disabled: boolean;
  onNavigate: AnalysisNavigation;
  activeTab?: AnalysisTab; embedded?: boolean;
  currentOnly?: boolean; onCurrentOnlyChange?: (value: boolean) => void;
};

export function AnalysisPanel({ report, methodId, disabled, onNavigate, activeTab, embedded = false, currentOnly: controlledCurrentOnly, onCurrentOnlyChange }: Props) {
  const [localTab, setTab] = useState<AnalysisTab>('problems');
  const tab = activeTab ?? localTab;
  const [localCurrentOnly, setLocalCurrentOnly] = useState(true);
  const currentOnly = controlledCurrentOnly ?? localCurrentOnly;
  const setCurrentOnly = (value: boolean) => { setLocalCurrentOnly(value); onCurrentOnlyChange?.(value); };
  const [metricMethodId, setMetricMethodId] = useState('');
  const prefix = useId();
  const current = report.methods.find(method => method.methodId === methodId);
  const method = current ?? report.methods.find(item => item.methodId === metricMethodId) ?? report.methods[0];
  const findings = currentOnly && current
    ? report.findings.filter(finding => finding.methodId === methodId)
    : report.findings;
  const warnings = findings.filter(finding => finding.severity === 'warning').length;
  const complexity = method?.complexity;

  return <section className="analysis-panel" aria-label="Анализ Java-кода">
    {!embedded && <div className="analysis-tabs" role="tablist" aria-label="Раздел анализа" onKeyDown={navigateTabs}>
      <button id={prefix + '-problems'} className="analysis-tab" role="tab" aria-selected={tab === 'problems'} tabIndex={tab === 'problems' ? 0 : -1}
        aria-controls={prefix + '-content'} onClick={() => setTab('problems')}>Проблемы <span className="analysis-count">{findings.length}</span></button>
      <button id={prefix + '-complexity'} className="analysis-tab" role="tab" aria-selected={tab === 'complexity'} tabIndex={tab === 'complexity' ? 0 : -1}
        aria-controls={prefix + '-content'} onClick={() => setTab('complexity')}>Big O</button>
    </div>}
    <div id={prefix + '-content'} role={embedded ? undefined : "tabpanel"} aria-labelledby={embedded ? undefined : prefix + '-' + tab}>
      {tab === 'problems' ? <>
        <div className="analysis-summary"><strong>{findings.length}</strong><span>мест для проверки<br /><small>{warnings} предупреждений · {findings.length - warnings} подсказок</small></span></div>
        <label className="analysis-filter"><input type="checkbox" checked={currentOnly && !!current} disabled={!current}
          onChange={event => setCurrentOnly(event.target.checked)} />Только выбранный метод</label>
        {!current && <p className="analysis-explanation">Показаны замечания для всего файла.</p>}
        {findings.length ? <div className="analysis-list">{findings.map(finding => <article key={finding.id}
          className={'analysis-finding analysis-finding--' + finding.severity} data-finding-id={finding.id}>
          <div className="analysis-finding-heading">
            <span className="analysis-severity">{finding.severity === 'warning' ? <AlertTriangle size={12} /> : <Info size={12} />}
              {finding.severity === 'warning' ? 'Проверить' : 'Подсказка'}</span>
            <span className="analysis-location">стр. {finding.range.startLine}</span>
          </div>
          <h3>{finding.title}</h3>
          {(!currentOnly || !current) && <p className="analysis-method-name">{report.methods.find(item => item.methodId === finding.methodId)?.signature}</p>}
          <details className="analysis-finding-details"><summary>Причина и рекомендация</summary>
            <p className="analysis-explanation">{finding.message}</p>
            <div className="analysis-recommendation"><strong>Что проверить</strong><p>{finding.recommendation}</p></div>
          </details>
          <div className="analysis-actions">
            <button disabled={disabled} onClick={() => onNavigate(finding, 'code')} aria-label={finding.title + ': к строке ' + finding.range.startLine}><ArrowDownToLine size={13} />К коду</button>
            <button disabled={disabled || !finding.nodeIds.length} onClick={() => onNavigate(finding, 'block')} aria-label={finding.title + ': к блоку на схеме'}
              title={finding.nodeIds.length ? 'Выделить связанный блок' : 'Для этого замечания нет блока'}><GitBranch size={13} />К блоку</button>
          </div>
        </article>)}</div> : <div className="analysis-empty"><strong>Эти проверки не нашли замечаний</strong><p>Это ещё не означает, что ошибок нет. Проверьте поведение программы тестами.</p></div>}
      </> : <div className="analysis-complexity">
        {!current && report.methods.length > 0 && <label className="analysis-method-select">Метод для оценки
          <select value={method?.methodId ?? ''} onChange={event => setMetricMethodId(event.target.value)}>
            {report.methods.map(item => <option key={item.methodId} value={item.methodId}>{item.signature} · стр. {item.range.startLine}</option>)}
          </select></label>}
        {complexity && method ? <>
          <p className="analysis-method-name">{method.signature} · стр. {method.range.startLine}</p>
          <div className={'analysis-metric' + (complexity.loopEstimate ? '' : ' analysis-metric--unknown')}>
            <small>По обходам</small><strong className="analysis-formula">{complexity.loopEstimate ?? 'Не определено'}</strong>
          </div>
          <div className={'analysis-metric' + (complexity.methodEstimate ? '' : ' analysis-metric--unknown')}>
            <small>Метод целиком</small><strong className="analysis-formula">{complexity.methodEstimate ?? 'Не определено'}</strong>
          </div>
          <details className="analysis-details"><summary>Как получена оценка</summary><p className="analysis-explanation">{complexity.explanation}</p></details>
          <div className="analysis-details"><strong>Размер метода</strong><p>{method.statementCount} инструкций · вложенность {method.maxNesting}</p></div>
          {complexity.variables.length > 0 && <details className="analysis-details"><summary>Что обозначают буквы</summary><dl>
            {complexity.variables.map(variable => <div key={variable.symbol}><dt>{variable.symbol}</dt><dd>{variable.meaning}</dd></div>)}
          </dl></details>}
          {complexity.unknownReasons.length > 0 && <details className="analysis-details analysis-unknown-reasons" open={!complexity.methodEstimate}><summary>Что мешает полной оценке ({complexity.unknownReasons.length})</summary><ul>{complexity.unknownReasons.map((reason, index) => <li key={index}>{reason}</li>)}</ul></details>}
          {complexity.assumptions.length > 0 && <details className="analysis-details"><summary>Условия этой оценки</summary><ul>{complexity.assumptions.map((assumption, index) => <li key={index}>{assumption}</li>)}</ul></details>}
          <details className="analysis-limitations"><summary>Что означает Big O</summary><p>Например, два вложенных обхода по n элементам дают до n × n шагов. Оценка не показывает секунды и не обещает ускорение после правок.</p></details>
        </> : <div className="analysis-empty"><strong>Нет метода для оценки</strong><p>Добавьте метод или конструктор с телом и преобразуйте код.</p></div>}
      </div>}
    </div>
    {report.truncated && <p className="analysis-limitations" role="status">Анализ сокращён из-за размера или сложности файла. Показаны только найденные замечания.</p>}
    {report.limitations.length > 0 && <details className="analysis-limitations"><summary>Границы анализа</summary><ul>{report.limitations.map((limitation, index) => <li key={index}>{limitation}</li>)}</ul></details>}
  </section>;
}
