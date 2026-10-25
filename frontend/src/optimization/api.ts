import { RequestError } from '../api';
import type { Candidate, OptimizationInput, OptimizationOptions, OptimizationPreview } from './types';

const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string');
const textFields = (value: Record<string, unknown>, names: string[]) => names.every(name => typeof value[name] === 'string');
function candidate(value: unknown): value is Candidate {
  if (!record(value) || !textFields(value, ['id', 'rule', 'title', 'description', 'methodId', 'reason']) ||
      typeof value.available !== 'boolean' || !strings(value.nodeIds) || !record(value.range)) return false;
  return ['startLine', 'startColumn', 'endLine', 'endColumn'].every(key =>
    Number.isInteger(value.range && (value.range as Record<string, unknown>)[key]) && Number((value.range as Record<string, unknown>)[key]) > 0);
}
function options(value: unknown): value is OptimizationOptions {
  return record(value) && typeof value.revision === 'string' && strings(value.limitations) &&
    Array.isArray(value.candidates) && value.candidates.every(candidate);
}
function preview(value: unknown): value is OptimizationPreview {
  if (!record(value) || !candidate(value.candidate) || !strings(value.notes) ||
      !textFields(value, ['revision', 'source', 'selectedMethodId', 'selectedNodeId', 'summary', 'beforeFragment', 'afterFragment']) ||
      !record(value.conversion)) return false;
  const converted = value.conversion;
  return textFields(converted, ['revision', 'fileName', 'generatedSource', 'languageLevel']) &&
    typeof converted.reparsed === 'boolean' && typeof converted.structureEquivalent === 'boolean' &&
    Array.isArray(converted.methods) && Array.isArray(converted.declarations) && record(converted.statistics) &&
    record(converted.structure) && record(converted.analysis) && Array.isArray(converted.analysis.findings) &&
    Array.isArray(converted.analysis.methods) && strings(converted.analysis.limitations) &&
    typeof converted.analysis.truncated === 'boolean';
}
async function request<T>(path: string, payload: object, valid: (value: unknown) => value is T, signal?: AbortSignal): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  const timer = setTimeout(abort, 20000);
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) controller.abort();
  try {
    const response = await fetch('/api/optimization/' + path, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload), signal: controller.signal,
    });
    const body: unknown = await response.json().catch(() => null);
    if (!response.ok) throw new RequestError(record(body) && typeof body.message === 'string' ? body.message : 'Не удалось подготовить изменение.');
    if (!valid(body)) throw new RequestError('Сервер вернул неполный ответ оптимизации. Изменения не применены.');
    return body;
  } catch (error) {
    if (error instanceof RequestError) throw error;
    throw new RequestError(error instanceof DOMException && error.name === 'AbortError'
      ? 'Запрос отменён или сервер не ответил за 20 секунд. Повторите попытку.'
      : 'Не удалось связаться с сервером. Проверьте его запуск.');
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abort);
  }
}
export function getOptimizationOptions(input: OptimizationInput, signal?: AbortSignal) {
  return request('options', input, options, signal);
}
export function previewOptimization(input: OptimizationInput, candidateId: string) {
  return request('preview', { ...input, candidateId }, preview);
}
