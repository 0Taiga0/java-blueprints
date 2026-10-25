import type { MethodFlow } from './types';

export function freshName(base: string, method: MethodFlow): string {
  const words = new Set((method.nodes.map(node => (node.mutation?.text ?? '') + ' ' + node.code).join(' ').match(/[A-Za-z_$][A-Za-z0-9_$]*/g) ?? []));
  if (!words.has(base)) return base;
  for (let index = 2; ; index++) if (!words.has(base + index)) return base + index;
}
export function immediateTemplate(kind: string, method: MethodFlow, fallback: string): string {
  if (kind === 'variable') return 'int ' + freshName('value', method) + ' = 0;';
  if (kind === 'loop') {
    const name = freshName('step', method);
    return 'for (int ' + name + ' = 0; ' + name + ' < 3; ' + name + '++) {\n}';
  }
  if (kind === 'condition') return 'if (true) {\n}';
  if (kind === 'return') return method.nodes.find(node => node.kind === 'return')?.edit?.text ?? fallback;
  return fallback;
}
