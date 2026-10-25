import type { VisualDescription, VisualExpr, VisualMethod, VisualSpec } from './types';

export const kindLabels: Record<VisualSpec['kind'], string> = {
  variable: 'Переменная', action: 'Действие', condition: 'Условие', loop: 'Цикл', call: 'Вызов метода', return: 'Возврат результата',
};
export const typeLabels: Record<string, string> = {
  int: 'Целое число — int', long: 'Большое целое — long', double: 'Дробное число — double', float: 'Дробное число — float',
  short: 'Короткое целое — short', byte: 'Малое целое — byte', boolean: 'Да / нет — boolean', char: 'Символ — char', String: 'Текст — String',
};
export const binaryOperators: [string, string][] = [
  ['+', '＋ Сложить'], ['-', '− Вычесть'], ['*', '× Умножить'], ['/', '÷ Разделить'], ['%', '% Остаток'],
  ['==', '= Равно'], ['!=', '≠ Не равно'], ['<', '< Меньше'], ['<=', '≤ Не больше'], ['>', '> Больше'], ['>=', '≥ Не меньше'],
  ['&&', 'И — оба условия'], ['||', 'ИЛИ — одно из условий'], ['&', 'Побитовое И'], ['|', 'Побитовое ИЛИ'], ['^', 'Исключающее ИЛИ'],
];
export const assignmentOperators: [string, string][] = [
  ['=', 'Присвоить'], ['+=', 'Увеличить на'], ['-=', 'Уменьшить на'], ['*=', 'Умножить на'], ['/=', 'Разделить на'], ['%=', 'Остаток от деления'], ['++', 'Увеличить на 1'], ['--', 'Уменьшить на 1'],
];
export const number = (value: number | string): VisualExpr => ({ kind: 'number', value: String(value) });
export const variable = (value: string): VisualExpr => ({ kind: 'variable', value });
export function isNumeric(type: string): boolean { return /^(byte|short|int|long|float|double|Byte|Short|Integer|Long|Float|Double)$/.test(type.replace(/^java\.lang\./, '')); }
export function defaultValue(type = 'int'): VisualExpr {
  const simple = type.replace(/^java\.lang\./, '');
  if (simple === 'boolean' || simple === 'Boolean') return { kind: 'boolean', value: 'false' };
  if (simple === 'String') return { kind: 'string', value: '' };
  if (simple === 'char' || simple === 'Character') return { kind: 'char', value: 'a' };
  if (isNumeric(simple)) return number(0);
  return { kind: 'null' };
}
export function freshName(base: string, description: VisualDescription): string {
  const used = new Set(description.variables.map(item => item.name));
  if (!used.has(base)) return base;
  let index = 2; while (used.has(base + index)) index++;
  return base + index;
}
export function callArguments(method?: VisualMethod): VisualExpr[] {
  return method?.parameters.map(parameter => defaultValue(parameter.type)) ?? [];
}
export function defaultLoopCondition(description: VisualDescription): VisualExpr {
  const boolean = description.variables.find(item => item.type === 'boolean');
  if (boolean) return variable(boolean.name);
  const numeric = description.variables.find(item => isNumeric(item.type));
  if (numeric) return { kind: 'binary', operator: '>', left: variable(numeric.name), right: number(0) };
  // A method call is not a Java compile-time constant: an empty while body remains reachable.
  const absolute = description.methods.find(method => /Math\.abs/.test(method.label) && method.returnType === 'int' && method.parameters.length === 1);
  if (absolute) return { kind: 'binary', operator: '>', left: { kind: 'call', callId: absolute.id, arguments: [number(0)] }, right: number(0) };
  return { kind: 'boolean', value: 'false' };
}
export function defaultExpression(kind: VisualExpr['kind'], description: VisualDescription, expectedType = 'int'): VisualExpr {
  if (kind === 'number') return number(0);
  if (kind === 'variable') return variable(description.variables.find(item => item.type === expectedType)?.name ?? description.variables[0]?.name ?? '');
  if (kind === 'binary') return { kind, operator: expectedType === 'boolean' ? '>' : '+', left: number(0), right: number(0) };
  if (kind === 'unary') return { kind, operator: expectedType === 'boolean' ? '!' : '-', operand: defaultValue(expectedType) };
  if (kind === 'call') {
    const method = description.methods.find(item => item.returnType === expectedType) ?? description.methods.find(item => item.returnType !== 'void');
    return { kind, callId: method?.id ?? '', arguments: callArguments(method) };
  }
  if (kind === 'boolean') return { kind, value: 'true' };
  if (kind === 'null') return { kind };
  return { kind, value: kind === 'char' ? 'a' : '' };
}
export function defaultSpec(kind: string, description: VisualDescription): VisualSpec {
  if (kind === 'variable') return { kind, name: freshName('value', description), type: 'int', value: number(0), finalVariable: false };
  if (kind === 'condition') {
    const item = description.variables.find(item => isNumeric(item.type));
    const boolean = description.variables.find(item => item.type === 'boolean');
    return { kind, condition: item ? { kind: 'binary', operator: '>', left: variable(item.name), right: number(0) } : boolean ? variable(boolean.name) : { kind: 'boolean', value: 'true' } };
  }
  if (kind === 'loop') {
    const name = freshName('i', description);
    return { kind, loopKind: 'for', name, type: 'int', start: number(0), condition: { kind: 'binary', operator: '<', left: variable(name), right: number(3) }, operator: '+=', step: number(1) };
  }
  if (kind === 'call') {
    const method = description.methods.find(item => /println/.test(item.label) && item.parameters.length === 1 && /String/.test(item.parameters[0].type)) ?? description.methods[0];
    return { kind, callId: method?.id ?? '', arguments: callArguments(method) };
  }
  if (kind === 'return') return { kind, value: null };
  const target = description.variables.find(item => item.writable);
  return { kind: 'action', target: target?.name ?? '', operator: '=', value: defaultValue(target?.type) };
}
export function withLoopVariable(description: VisualDescription, spec: VisualSpec): VisualDescription {
  if (spec.kind !== 'loop' || !['for', 'foreach'].includes(spec.loopKind ?? '') || !spec.name?.trim()) return description;
  return { ...description, variables: [{ name: spec.name, type: spec.type ?? 'int', writable: true }, ...description.variables.filter(item => item.name !== spec.name)] };
}
export function renameExpression(expression: VisualExpr | undefined, before: string, after: string): VisualExpr | undefined {
  if (!expression) return expression;
  if (expression.kind === 'variable' && expression.value === before) return { ...expression, value: after };
  return { ...expression,
    ...(expression.left ? { left: renameExpression(expression.left, before, after) } : {}),
    ...(expression.right ? { right: renameExpression(expression.right, before, after) } : {}),
    ...(expression.operand ? { operand: renameExpression(expression.operand, before, after) } : {}),
    ...(expression.arguments ? { arguments: expression.arguments.map(item => renameExpression(item, before, after)!) } : {}),
  };
}
export function renameLoop(spec: VisualSpec, name: string): VisualSpec {
  return { ...spec, name, condition: renameExpression(spec.condition, spec.name ?? '', name) };
}
export function expressionIssue(expression: VisualExpr | undefined | null, description: VisualDescription): string | null {
  if (!expression) return 'Выберите значение.';
  switch (expression.kind) {
    case 'number': return /^[+-]?(?:(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?[fFdDlL]?|0[xX][0-9a-fA-F]+[lL]?|0[bB][01]+[lL]?)$/.test((expression.value ?? '').replaceAll('_', '')) ? null : 'Введите число.';
    case 'char': return (expression.value ?? '').length === 1 ? null : 'Для символа нужен один знак.';
    case 'boolean': return ['true', 'false'].includes(expression.value ?? '') ? null : 'Выберите «Да» или «Нет».';
    case 'variable': return description.variables.some(item => item.name === expression.value) ? null : 'Выберите доступную переменную.';
    case 'binary': return !binaryOperators.some(([operator]) => operator === expression.operator) ? 'Выберите операцию.' : expressionIssue(expression.left, description) ?? expressionIssue(expression.right, description);
    case 'unary': return !['!', '-', '+', '~'].includes(expression.operator ?? '') ? 'Выберите операцию.' : expressionIssue(expression.operand, description);
    case 'call': return callIssue(expression.callId, expression.arguments, description);
    case 'null': case 'string': return null;
    default: return 'Выберите поддерживаемый вид значения.';
  }
}
export function callIssue(id: string | undefined, args: VisualExpr[] | undefined, description: VisualDescription): string | null {
  const method = description.methods.find(item => item.id === id);
  if (!method) return 'Выберите метод.';
  if ((args?.length ?? 0) !== method.parameters.length) return 'Заполните аргументы метода.';
  for (const expression of args ?? []) { const issue = expressionIssue(expression, description); if (issue) return issue; }
  return null;
}
export function specIssue(spec: VisualSpec | null, description: VisualDescription | null): string | null {
  if (!spec || !description) return 'Настройки загружаются.';
  const identifier = (name?: string) => name && /^[\p{L}_$][\p{L}\p{N}_$]*$/u.test(name) ? null : 'Имя должно начинаться с буквы, _ или $ и не содержать пробелов.';
  if (spec.kind === 'variable') return identifier(spec.name) ?? (!description.types.includes(spec.type ?? '') ? 'Выберите тип.' : spec.value ? expressionIssue(spec.value, description) : null);
  if (spec.kind === 'action') return !description.variables.some(item => item.name === spec.target && item.writable) ? 'Выберите изменяемую переменную.' : !['++', '--'].includes(spec.operator ?? '') ? expressionIssue(spec.value, description) : null;
  if (spec.kind === 'condition') return expressionIssue(spec.condition, description);
  if (spec.kind === 'return') return spec.value ? expressionIssue(spec.value, description) : null;
  if (spec.kind === 'call') return callIssue(spec.callId, spec.arguments, description);
  if (spec.kind === 'loop') {
    if (spec.loopKind === 'for' || spec.loopKind === 'foreach') {
      const issue = identifier(spec.name) ?? (!description.types.includes(spec.type ?? '') ? 'Выберите тип.' : null);
      if (issue) return issue;
    }
    if (spec.loopKind === 'foreach') return expressionIssue(spec.iterable, description);
    const scope = withLoopVariable(description, spec);
    if (spec.loopKind === 'for') return expressionIssue(spec.start, description) ?? expressionIssue(spec.condition, scope) ?? expressionIssue(spec.step, scope);
    return expressionIssue(spec.condition, description);
  }
  return null;
}
