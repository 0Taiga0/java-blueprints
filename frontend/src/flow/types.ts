export type SourceRange = {
  startLine: number; startColumn: number; endLine: number; endColumn: number;
};
export type FlowBlock = {
  id: string;
  issueCount?: number;
  kind: 'start' | 'end' | 'operation' | 'condition' | 'return' | 'throw' | 'jump' | 'opaque' | 'switch' | 'case' | 'try' | 'catch' | 'finally' | 'yield' | 'resource' | 'evaluation' | 'exception-end' | 'file' | 'class' | 'interface' | 'enum' | 'record' | 'field' | 'method' | 'constructor' | 'initializer' | 'library';
  label: string;
  code: string; detail: string; category: string; targetMethodId: string | null;
  range: SourceRange;
  insert: { role: string; range: SourceRange; positions: string[] } | null;
  mutation: { range: SourceRange; text: string; deleteModes: string[]; movable: boolean } | null;
  edit: { kind: string; text: string; range: SourceRange } | null;
};
export type MethodFlow = {
  id: string; owner: string; signature: string; kind: 'method' | 'constructor' | 'initializer' | 'structure';
  range: SourceRange; mode: 'flow' | 'overview' | 'no-body' | 'structure'; warnings: string[];
  nodes: FlowBlock[];
  edges: { id: string; source: string; target: string; label: string; kind: string }[];
};
