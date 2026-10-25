import { RequestError } from '../api';

export type CheckMode = 'compile' | 'tests';
export type CheckResult = {
  status: 'compiled' | 'compile_error' | 'compile_timeout' | 'passed' | 'tests_failed' | 'test_timeout' | 'no_tests' | 'runner_error';
  message: string; durationMs: number;
  diagnostics: {file: 'source' | 'tests'; line: number; column: number; message: string}[];
  tests: {name: string; className: string; status: 'passed' | 'failed' | 'skipped'; message: string; seconds: number}[];
  passed: number; failed: number; skipped: number; output: string; outputTruncated: boolean;
};

export async function checkProgram(mode: CheckMode, fileName: string, source: string,
  testFileName: string, testSource: string): Promise<CheckResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 35000);
  try {
    const response = await fetch('/api/check/' + mode, {
      method: 'POST', headers: {'Content-Type': 'application/json', 'X-Blueprints-Local': '1'},
      body: JSON.stringify({fileName, source, testFileName, testSource}), signal: controller.signal,
    });
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new RequestError(body?.message ?? 'Не удалось выполнить проверку.');
    if (!body || typeof body.status !== 'string' || !Array.isArray(body.tests))
      throw new RequestError('Сервер вернул неожиданный отчёт.');
    return body as CheckResult;
  } catch (error) {
    if (error instanceof RequestError) throw error;
    throw new RequestError(error instanceof DOMException && error.name === 'AbortError'
      ? 'Сервер не ответил за 35 секунд. Процесс проверки ограничен по времени; повторите запуск позже.'
      : 'Не удалось связаться с сервером. Проверьте его запуск.');
  } finally { clearTimeout(timer); }
}

export type ComparisonResult = {
  status: 'matched' | 'changed' | 'baseline_failed' | 'inconclusive';
  message: string; reasons: string[];
  before: CheckResult; after: CheckResult;
  tests: {name: string; className: string;
    change: 'unchanged' | 'regression' | 'fixed' | 'both_failed' | 'skipped' | 'missing' | 'ambiguous';
    before: CheckResult['tests'][number] | null; after: CheckResult['tests'][number] | null}[];
  unchanged: number; regressions: number; fixed: number; unresolved: number;
};

export async function compareProgram(fileName: string, baselineSource: string, source: string,
  testFileName: string, testSource: string): Promise<ComparisonResult> {
  const controller = new AbortController();
  // Два последовательных запуска: каждый до 15 с на javac и до 8 с на JUnit.
  const timer = setTimeout(() => controller.abort(), 65000);
  try {
    const response = await fetch('/api/check/compare', {
      method: 'POST', headers: {'Content-Type': 'application/json', 'X-Blueprints-Local': '1'},
      body: JSON.stringify({fileName, baselineSource, source, testFileName, testSource}), signal: controller.signal,
    });
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new RequestError(body?.message ?? 'Не удалось сравнить версии.');
    if (!body || !['matched', 'changed', 'baseline_failed', 'inconclusive'].includes(body.status) ||
        !body.before || !body.after || !Array.isArray(body.tests))
      throw new RequestError('Сервер вернул неожиданный отчёт сравнения.');
    return body as ComparisonResult;
  } catch (error) {
    if (error instanceof RequestError) throw error;
    throw new RequestError(error instanceof DOMException && error.name === 'AbortError'
      ? 'Сервер не ответил за 65 секунд. Дождитесь завершения ограниченных по времени процессов и повторите проверку.'
      : 'Не удалось связаться с сервером. Проверьте его запуск.');
  } finally { clearTimeout(timer); }
}
