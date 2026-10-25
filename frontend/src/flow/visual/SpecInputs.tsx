import { useId } from 'react';
import type { VisualDescription, VisualSpec } from './types';
import { assignmentOperators, defaultLoopCondition, defaultSpec, defaultValue, isNumeric, number, renameLoop, typeLabels, variable, withLoopVariable } from './model';
import { CallInputs, ExpressionInput } from './ExpressionInput';

export function SpecInputs({ spec, description, disabled, onChange, editing }: {
  spec: VisualSpec; description: VisualDescription; disabled: boolean; onChange: (spec: VisualSpec) => void; editing: boolean;
}) {
  const id = useId();
  const update = (values: Partial<VisualSpec>) => onChange({ ...spec, ...values });
  const typeInput = (label: string, loop = false) => <div className="visual-field">
    <label htmlFor={id + '-type'}>{label}</label>
    <select id={id + '-type'} value={spec.type ?? 'int'} disabled={disabled}
      onChange={event => update({ type: event.target.value, ...(!loop && spec.value && ['number', 'string', 'char', 'boolean', 'null'].includes(spec.value.kind) ? { value: defaultValue(event.target.value) } : {}) })}>
      {description.types.filter(type => !loop || spec.loopKind !== 'for' || isNumeric(type)).map(type => <option key={type} value={type}>{typeLabels[type] ?? type}</option>)}
    </select>
  </div>;
  const nameInput = (label: string, loop = false) => <div className="visual-field">
    <label htmlFor={id + '-name'}>{label}</label>
    <input id={id + '-name'} value={spec.name ?? ''} spellCheck={false} autoComplete="off" disabled={disabled}
      onChange={event => loop ? onChange(renameLoop(spec, event.target.value)) : update({ name: event.target.value })} />
  </div>;
  if (spec.kind === 'variable') return <>
    <div className="visual-field-grid">{nameInput('Имя переменной')}{typeInput('Тип данных')}</div>
    <label className="visual-checkbox"><input type="checkbox" checked={spec.value != null} disabled={disabled}
      onChange={event => update({ value: event.target.checked ? defaultValue(spec.type) : null })} />Задать начальное значение</label>
    {spec.value && <ExpressionInput label="Начальное значение" value={spec.value} expectedType={spec.type} description={description} disabled={disabled} onChange={value => update({ value })} />}
    <label className="visual-checkbox"><input type="checkbox" checked={spec.finalVariable ?? false} disabled={disabled}
      onChange={event => update({ finalVariable: event.target.checked })} />Запретить повторное присваивание (final)</label>
    {editing && <p className="visual-note">Если переменная используется дальше, сохраните её имя.</p>}
  </>;
  if (spec.kind === 'action') {
    const target = description.variables.find(item => item.name === spec.target);
    return <>
      <div className="visual-field"><label htmlFor={id + '-target'}>Изменяемая переменная</label>
        <select id={id + '-target'} value={spec.target ?? ''} disabled={disabled}
          onChange={event => { const selected = description.variables.find(item => item.name === event.target.value); update({ target: event.target.value, value: defaultValue(selected?.type) }); }}>
          <option value="" disabled>Выберите переменную</option>
          {description.variables.filter(item => item.writable).map(item => <option key={item.name} value={item.name}>{item.name} · {typeLabels[item.type] ?? item.type}</option>)}
        </select>
      </div>
      {!description.variables.some(item => item.writable) && <p className="visual-note">Сначала добавьте переменную перед этим местом.</p>}
      <div className="visual-field"><label htmlFor={id + '-operation'}>Действие</label>
        <select id={id + '-operation'} value={spec.operator ?? '='} disabled={disabled} onChange={event => update({ operator: event.target.value })}>
          {assignmentOperators.map(([operator, label]) => <option key={operator} value={operator}>{label}</option>)}
        </select>
      </div>
      {!['++', '--'].includes(spec.operator ?? '') && <ExpressionInput label="Значение действия" value={spec.value ?? defaultValue(target?.type)} expectedType={target?.type}
        description={description} disabled={disabled} onChange={value => update({ value })} />}
    </>;
  }
  if (spec.kind === 'condition') return <>
    <ExpressionInput label="Условие" value={spec.condition ?? { kind: 'boolean', value: 'true' }} expectedType="boolean"
      description={description} disabled={disabled} onChange={condition => update({ condition })} />
    <p className="visual-note">Если условие верно, выполняется ветка «Да». Действия добавляются в ветки перетаскиванием.</p>
  </>;
  if (spec.kind === 'call') return <CallInputs callId={spec.callId} args={spec.arguments} description={description} disabled={disabled}
    onChange={(callId, args) => update({ callId, arguments: args })} />;
  if (spec.kind === 'return') return <>
    <label className="visual-checkbox"><input type="checkbox" checked={spec.value != null} disabled={disabled}
      onChange={event => update({ value: event.target.checked ? defaultValue() : null })} />Вернуть значение</label>
    {spec.value && <ExpressionInput label="Результат" value={spec.value} description={description} disabled={disabled} onChange={value => update({ value })} />}
  </>;
  const scope = withLoopVariable(description, spec);
  function changeLoopKind(loopKind: VisualSpec['loopKind']) {
    if (loopKind === 'for') onChange(defaultSpec('loop', description));
    else if (loopKind === 'foreach') {
      const iterable = description.variables.find(item => /\[\]|List|Set|Collection|Iterable/.test(item.type));
      const elementType = iterable?.type.endsWith('[]') ? iterable.type.slice(0, -2) : iterable?.type.match(/<\s*([\w.]+)\s*>/)?.[1] ?? 'int';
      update({ loopKind, name: 'item', type: description.types.includes(elementType) ? elementType : 'int', iterable: variable(iterable?.name ?? '') });
    } else update({ loopKind, condition: loopKind === 'while' ? defaultLoopCondition(description) : { kind: 'boolean', value: 'false' } });
  }
  return <>
    <div className="visual-field"><label htmlFor={id + '-loop'}>Вид цикла</label>
      <select id={id + '-loop'} value={spec.loopKind ?? 'for'} disabled={disabled || editing} onChange={event => changeLoopKind(event.target.value as VisualSpec['loopKind'])}>
        <option value="for">Со счётчиком (for)</option><option value="foreach">Для каждого элемента (for each)</option>
        <option value="while">Пока условие верно (while)</option><option value="do">Сначала действие, затем проверка (do while)</option>
      </select>
    </div>
    {['for', 'foreach'].includes(spec.loopKind ?? '') && <div className="visual-field-grid">{nameInput(spec.loopKind === 'for' ? 'Имя счётчика' : 'Имя элемента', true)}{typeInput('Тип данных', true)}</div>}
    {spec.loopKind === 'for' && <ExpressionInput label="Начало счётчика" value={spec.start ?? number(0)} expectedType={spec.type}
      description={description} disabled={disabled} onChange={start => update({ start })} />}
    {spec.loopKind === 'foreach' ? <ExpressionInput label="Коллекция или массив" value={spec.iterable ?? variable('')} expectedType="Iterable"
      description={description} disabled={disabled} onChange={iterable => update({ iterable })} /> : <ExpressionInput label="Повторять, пока" value={spec.condition ?? { kind: 'boolean', value: 'false' }}
      expectedType="boolean" description={scope} disabled={disabled} onChange={condition => update({ condition })} />}
    {spec.loopKind === 'for' && <>
      <div className="visual-field"><label htmlFor={id + '-step'}>После каждого повторения</label>
        <select id={id + '-step'} value={spec.operator ?? '+='} disabled={disabled} onChange={event => update({ operator: event.target.value })}>
          <option value="+=">Увеличить счётчик</option><option value="-=">Уменьшить счётчик</option>
        </select>
      </div>
      <ExpressionInput label="Размер шага" value={spec.step ?? number(1)} expectedType={spec.type} description={scope} disabled={disabled} onChange={step => update({ step })} />
    </>}
    <p className="visual-note">{editing ? 'Меняются настройки цикла. Действия внутри сохраняются.' : 'После добавления перетащите действия в тело цикла.'}</p>
  </>;
}
