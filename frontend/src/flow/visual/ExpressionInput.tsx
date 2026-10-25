import { useId } from 'react';
import type { VisualDescription, VisualExpr } from './types';
import { binaryOperators, callArguments, defaultExpression, defaultValue, typeLabels } from './model';

const expressionLabels: [VisualExpr['kind'], string][] = [
  ['number', 'Число'], ['string', 'Текст'], ['boolean', 'Да / нет'], ['variable', 'Переменная'],
  ['binary', 'Операция из двух значений'], ['unary', 'Операция над значением'], ['call', 'Результат метода'], ['char', 'Символ'], ['null', 'Пустая ссылка (null)'],
];

export function CallInputs({ callId, args, description, disabled, onChange, expression = false, path = 'Вызов', depth = 0 }: {
  callId?: string; args?: VisualExpr[]; description: VisualDescription; disabled: boolean;
  onChange: (id: string, args: VisualExpr[]) => void; expression?: boolean; path?: string; depth?: number;
}) {
  const id = useId();
  const methods = description.methods.filter(item => !expression || item.returnType !== 'void');
  const selected = methods.find(item => item.id === callId);
  return <div className="visual-call-inputs">
    <label htmlFor={id + '-method'}>Метод</label>
    <select id={id + '-method'} aria-label={path + ': метод'} value={callId ?? ''} disabled={disabled || !methods.length}
      onChange={event => { const method = methods.find(item => item.id === event.target.value); onChange(event.target.value, callArguments(method)); }}>
      <option value="" disabled>Выберите метод</option>
      {methods.map(method => <option value={method.id} key={method.id}>{method.label}</option>)}
    </select>
    {!methods.length && <p className="visual-note">В этом месте нет доступных методов для выбора.</p>}
    {selected?.parameters.map((parameter, index) => <ExpressionInput key={selected.id + '-' + index}
      label={'Аргумент: ' + parameter.name} path={path + ': аргумент ' + parameter.name} expectedType={parameter.type}
      value={args?.[index] ?? defaultValue(parameter.type)} description={description} disabled={disabled} depth={depth + 1}
      onChange={value => { const next = [...(args ?? callArguments(selected))]; next[index] = value; onChange(selected.id, next); }} />)}
    {selected && <small className="visual-type-hint">{selected.returnType === 'void' ? 'Выполняет действие' : 'Возвращает: ' + (typeLabels[selected.returnType] ?? selected.returnType)}</small>}
  </div>;
}

export function ExpressionInput({ value, label, path = label, description, disabled, expectedType = 'int', onChange, depth = 0 }: {
  value: VisualExpr; label: string; path?: string; description: VisualDescription; disabled: boolean;
  expectedType?: string; onChange: (value: VisualExpr) => void; depth?: number;
}) {
  const id = useId();
  const types = expressionLabels.filter(([kind]) =>
    (kind !== 'variable' || description.variables.length > 0 || value.kind === kind)
    && (kind !== 'call' || description.methods.some(method => method.returnType !== 'void') || value.kind === kind)
    && (depth < 12 || !['binary', 'unary', 'call'].includes(kind) || value.kind === kind));
  const setValue = (text: string) => onChange({ ...value, value: text });
  const numericOperation = !['&&', '||'].includes(value.operator ?? '');
  return <fieldset className={'visual-expression depth-' + Math.min(depth, 3)}>
    <legend>{label}</legend>
    <div className="visual-expression-top">
      <select aria-label={path + ': вид значения'} value={value.kind} disabled={disabled}
        onChange={event => onChange(defaultExpression(event.target.value as VisualExpr['kind'], description, expectedType))}>
        {types.map(([kind, title]) => <option key={kind} value={kind}>{title}</option>)}
      </select>
      {value.kind === 'number' && <input aria-label={path + ': число'} inputMode="decimal" value={value.value ?? ''} disabled={disabled} spellCheck={false}
        onChange={event => setValue(event.target.value)} placeholder="0" />}
      {(value.kind === 'string' || value.kind === 'char') && <input aria-label={path + (value.kind === 'char' ? ': символ' : ': текст')}
        value={value.value ?? ''} disabled={disabled} maxLength={value.kind === 'char' ? 1 : 20000} spellCheck={false}
        onChange={event => setValue(event.target.value)} placeholder={value.kind === 'char' ? 'а' : 'Введите текст'} />}
      {value.kind === 'boolean' && <select aria-label={path + ': да или нет'} value={value.value ?? 'false'} disabled={disabled} onChange={event => setValue(event.target.value)}>
        <option value="true">Да</option><option value="false">Нет</option>
      </select>}
      {value.kind === 'variable' && <select aria-label={path + ': переменная'} value={value.value ?? ''} disabled={disabled}
        onChange={event => setValue(event.target.value)}>
        <option value="" disabled>Выберите переменную</option>
        {description.variables.map(item => <option key={item.name} value={item.name}>{item.name} · {typeLabels[item.type] ?? item.type}</option>)}
      </select>}
    </div>
    {value.kind === 'binary' && <div className="visual-expression-pair">
      <ExpressionInput label="Слева" path={path + ': слева'} value={value.left ?? defaultValue(numericOperation ? 'int' : 'boolean')}
        description={description} disabled={disabled} expectedType={numericOperation ? 'int' : 'boolean'} depth={depth + 1}
        onChange={left => onChange({ ...value, left })} />
      <label className="visual-operator-label" htmlFor={id + '-operator'}>Операция</label>
      <select id={id + '-operator'} aria-label={path + ': операция'} value={value.operator ?? '+'} disabled={disabled}
        onChange={event => onChange({ ...value, operator: event.target.value })}>
        {binaryOperators.map(([operator, title]) => <option key={operator} value={operator}>{title}</option>)}
      </select>
      <ExpressionInput label="Справа" path={path + ': справа'} value={value.right ?? defaultValue(numericOperation ? 'int' : 'boolean')}
        description={description} disabled={disabled} expectedType={numericOperation ? 'int' : 'boolean'} depth={depth + 1}
        onChange={right => onChange({ ...value, right })} />
    </div>}
    {value.kind === 'unary' && <div className="visual-expression-pair">
      <label htmlFor={id + '-unary'}>Операция</label>
      <select id={id + '-unary'} aria-label={path + ': операция'} value={value.operator ?? '-'} disabled={disabled}
        onChange={event => onChange({ ...value, operator: event.target.value })}>
        <option value="!">НЕ — противоположное условие</option><option value="-">Сменить знак</option>
        <option value="+">Сохранить знак</option><option value="~">Инвертировать биты</option>
      </select>
      <ExpressionInput label="Значение" path={path + ': значение'} value={value.operand ?? defaultValue(expectedType)} description={description}
        disabled={disabled} expectedType={value.operator === '!' ? 'boolean' : 'int'} depth={depth + 1} onChange={operand => onChange({ ...value, operand })} />
    </div>}
    {value.kind === 'call' && <CallInputs description={description} disabled={disabled} expression path={path} depth={depth}
      callId={value.callId} args={value.arguments} onChange={(callId, args) => onChange({ ...value, callId, arguments: args })} />}
  </fieldset>;
}
