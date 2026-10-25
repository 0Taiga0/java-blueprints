import type { CheckResult } from './api';

export type GraphCompilationSnapshot = {
  source: string; fileName: string; pending?: boolean; report?: CheckResult; error?: string;
};

/** Проверка именно текущего снимка, отдельно от пользовательского отчёта JUnit. */
export function GraphCompilation({ snapshot, source, fileName, onLine }: {
  snapshot: GraphCompilationSnapshot | null; source: string; fileName: string; onLine: (line: number) => void;
}) {
  if (!snapshot || snapshot.source !== source || snapshot.fileName !== fileName) return null;
  const success = snapshot.report?.status === 'compiled';
  const title = success ? '✓ Java компилируется' : snapshot.report?.status === 'compile_error'
    ? '⚠ Ошибки Java' : '⚠ Проверка не завершена';
  if (snapshot.pending) return <span className="graph-compilation pending" role="status">Проверяем Java…</span>;
  return <details className={'graph-compilation ' + (success ? 'passed' : 'failed')}>
    <summary>{title}</summary>
    <div className="graph-compilation-details">
      <strong>{snapshot.error ?? snapshot.report?.message}</strong>
      {snapshot.report?.diagnostics.map((item, index) => <div key={index}>
        <button type="button" onClick={() => onLine(item.line)}>Строка {item.line}</button><span>{item.message}</span>
      </div>)}
      <p>{success ? 'Проверена компиляция. Поведение программы проверяйте во вкладке «Тесты».'
        : 'Правка сохранена. Исправьте блок или отмените действие. При необходимости добавьте недостающие объявления.'}</p>
    </div>
  </details>;
}
