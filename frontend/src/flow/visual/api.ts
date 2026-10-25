import { RequestError, type BlockEditResult } from '../../api';
import type { VisualContext, VisualDescription, VisualSpec } from './types';

async function request<T>(path: string, body: object, signal?: AbortSignal): Promise<T> {
  try {
    const response = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal });
    const result = await response.json().catch(() => null);
    if (!response.ok) throw new RequestError(result?.message ?? 'Не удалось обработать настройки блока.', result?.diagnostics ?? []);
    if (!result || typeof result !== 'object') throw new RequestError('Сервер вернул пустые настройки блока.');
    return result as T;
  } catch (error) {
    if (error instanceof RequestError || error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new RequestError('Не удалось связаться с сервером. Проверьте, что приложение запущено.');
  }
}

export function describeVisualBlock(context: VisualContext & { nodeId: string }, position?: string, signal?: AbortSignal) {
  return request<VisualDescription>('/api/visual/describe', { ...context, ...(position ? { position } : {}) }, signal);
}

export function applyVisualBlock(context: VisualContext & { nodeId: string }, mode: 'insert' | 'edit', spec: VisualSpec, position?: string) {
  return request<BlockEditResult>('/api/visual/apply', { ...context, mode, spec, ...(position ? { position } : {}) });
}
