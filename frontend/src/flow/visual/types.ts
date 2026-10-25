export type VisualContext = { fileName: string; source: string; revision: string; methodId: string };
export type VisualExpr = {
  kind: 'number' | 'string' | 'char' | 'boolean' | 'null' | 'variable' | 'binary' | 'unary' | 'call';
  value?: string;
  operator?: string;
  left?: VisualExpr; right?: VisualExpr; operand?: VisualExpr;
  callId?: string; arguments?: VisualExpr[];
};
export type VisualSpec = {
  kind: 'variable' | 'action' | 'condition' | 'loop' | 'call' | 'return';
  name?: string; type?: string; value?: VisualExpr | null; finalVariable?: boolean;
  target?: string; operator?: string; condition?: VisualExpr;
  loopKind?: 'for' | 'foreach' | 'while' | 'do'; start?: VisualExpr; step?: VisualExpr; iterable?: VisualExpr;
  callId?: string; arguments?: VisualExpr[];
};
export type VisualVariable = { name: string; type: string; writable: boolean };
export type VisualMethod = { id: string; label: string; returnType: string; parameters: { name: string; type: string }[] };
export type VisualDescription = {
  revision: string; supported: boolean; reason: string; spec: VisualSpec | null;
  variables: VisualVariable[]; methods: VisualMethod[]; types: string[];
};
export type VisualApplyHandler = (nodeId: string, mode: 'insert' | 'edit', spec: VisualSpec, position?: string) => Promise<void>;
