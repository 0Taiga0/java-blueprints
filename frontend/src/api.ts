import type { AnalysisReport } from './analysis/types';
import type { MethodFlow } from './flow/types';
export type Diagnostic = { message: string; line?: number; column?: number };
export type ApiError = { message: string; diagnostics: Diagnostic[] };
export type Conversion = {
  revision: string;
  fileName: string;
  generatedSource: string;
  languageLevel: string;
  reparsed: boolean;
  structureEquivalent: boolean;
  statistics: {
    types: number; methods: number; constructors: number;
    sourceLines: number; generatedLines: number; astNodes: number;
  };
  methods: MethodFlow[];
  structure: MethodFlow;
  analysis: AnalysisReport;
  declarations: { kind: string; name: string; line: number }[];
};

export class RequestError extends Error {
  diagnostics: Diagnostic[];
  constructor(message: string, diagnostics: Diagnostic[] = []) {
    super(message);
    this.diagnostics = diagnostics;
  }
}

async function send<T = Conversion>(path: string, init: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(path, { ...init, signal: controller.signal });
    const body = await response.json().catch(() => null);
    if (!response.ok) {
      throw new RequestError(body?.message ?? 'Сервер не смог обработать запрос.', body?.diagnostics ?? []);
    }
    if (!body || (typeof body.generatedSource !== 'string' && typeof body.conversion?.generatedSource !== 'string')) {
      throw new RequestError('Сервер вернул неожиданный ответ.');
    }
    return body as T;
  } catch (error) {
    if (error instanceof RequestError) throw error;
    throw new RequestError(
      error instanceof DOMException && error.name === 'AbortError'
        ? 'Сервер не ответил за 20 секунд. Попробуйте ещё раз.'
        : 'Не удалось связаться с сервером. Проверьте, что сервер приложения запущен.',
    );
  } finally { clearTimeout(timer); }
}

export function convertSource(fileName: string, source: string) {
  return send('/api/roundtrip', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName, source }),
  });
}

export function uploadSource(file: File) {
  const data = new FormData();
  data.append('file', file);
  return send('/api/roundtrip/file', { method: 'POST', body: data });
}

export type BlockEditResult = {
  source: string; conversion: Conversion; selectedMethodId: string; selectedNodeId: string;
};
export function editBlockSource(fileName: string, source: string, revision: string,
  methodId: string, nodeId: string, replacement: string) {
  return send<BlockEditResult>('/api/flow/edit', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName, source, revision, methodId, nodeId, replacement }),
  });
}

export function insertBlockSource(fileName: string, source: string, revision: string,
  methodId: string, nodeId: string, position: string, kind: string, code: string) {
  return send<BlockEditResult>('/api/flow/insert', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName, source, revision, methodId, nodeId, position, kind, code }),
  });
}

export function deleteBlockSource(fileName: string, source: string, revision: string,
  methodId: string, nodeId: string, mode: string) {
  return send<BlockEditResult>('/api/flow/delete', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName, source, revision, methodId, nodeId, mode }),
  });
}

export function moveBlockSource(fileName: string, source: string, revision: string,
  methodId: string, nodeId: string, targetNodeId: string, position: string) {
  return send<BlockEditResult>('/api/flow/move', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fileName, source, revision, methodId, nodeId, targetNodeId, position }),
  });
}
