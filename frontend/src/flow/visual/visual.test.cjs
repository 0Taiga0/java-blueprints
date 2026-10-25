const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
function load(file, imports = {}) {
  const js = ts.transpileModule(fs.readFileSync(path.join(__dirname, file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const exports = {};
  new Function('exports', 'require', js)(exports, name => imports[name] ?? require(name));
  return exports;
}
const model = load('model.ts');
const expression = load('ExpressionInput.tsx', { './model': model });
const { SpecInputs } = load('SpecInputs.tsx', { './model': model, './ExpressionInput': expression });
const description = {
  revision: 'r1', supported: true, reason: '', spec: null,
  variables: [
    { name: 'count', type: 'int', writable: true },
    { name: 'i', type: 'int', writable: true },
    { name: 'i2', type: 'int', writable: true },
    { name: 'fixed', type: 'int', writable: false },
    { name: 'prices', type: 'List<Integer>', writable: true },
  ],
  methods: [
    { id: 'println-string', label: 'System.out.println(String)', returnType: 'void', parameters: [{ name: 'text', type: 'String' }] },
    { id: 'abs-int', label: 'Math.abs(int)', returnType: 'int', parameters: [{ name: 'value', type: 'int' }] },
  ],
  types: ['int', 'long', 'double', 'boolean', 'char', 'String', 'Integer', 'Object', 'List<Integer>'],
};

test('new variable and condition defaults are structured values without Java text', () => {
  const variable = model.defaultSpec('variable', description);
  assert.deepEqual(variable, { kind: 'variable', name: 'value', type: 'int', value: { kind: 'number', value: '0' }, finalVariable: false });
  const condition = model.defaultSpec('condition', description);
  assert.deepEqual(condition.condition, { kind: 'binary', operator: '>', left: { kind: 'variable', value: 'count' }, right: { kind: 'number', value: '0' } });
  assert.equal(model.specIssue(variable, description), null);
  assert.equal(model.specIssue(condition, description), null);
});

test('new for counter avoids existing variable names and is visible only inside header', () => {
  const loop = model.defaultSpec('loop', description);
  assert.equal(loop.name, 'i3');
  assert.equal(model.specIssue(loop, description), null);
  assert.equal(description.variables.some(v => v.name === 'i3'), false);
  assert.equal(model.withLoopVariable(description, loop).variables[0].name, 'i3');
});

test('renaming loop counter rewrites matching structured references but not text literals', () => {
  const loop = model.defaultSpec('loop', description);
  const changed = model.renameLoop(loop, 'step');
  assert.equal(changed.condition.left.value, 'step');
  assert.equal(loop.condition.left.value, 'i3');
  const text = { kind: 'string', value: 'i3' };
  assert.deepEqual(model.renameExpression(text, 'i3', 'step'), text);
});

test('default values respect booleans, strings, characters and nullable objects', () => {
  assert.deepEqual(model.defaultValue('boolean'), { kind: 'boolean', value: 'false' });
  assert.deepEqual(model.defaultValue('String'), { kind: 'string', value: '' });
  assert.deepEqual(model.defaultValue('char'), { kind: 'char', value: 'a' });
  assert.deepEqual(model.defaultValue('Object'), { kind: 'null' });
  assert.deepEqual(model.defaultValue('Integer'), { kind: 'number', value: '0' });
});

test('unknown variable, readonly target and missing method are rejected before submit', () => {
  assert.match(model.expressionIssue({ kind: 'variable', value: 'outside' }, description), /переменную/);
  assert.match(model.specIssue({ kind: 'action', target: 'fixed', operator: '++' }, description), /изменяемую/);
  assert.match(model.specIssue({ kind: 'call', callId: 'missing', arguments: [] }, description), /метод/);
  assert.match(model.specIssue({ kind: 'variable', name: 'bad name', type: 'int', value: model.number(0) }, description), /Имя/);
});

test('nested expression validation reaches deep operands and never accepts raw Java kind', () => {
  const expression = { kind: 'binary', operator: '&&', left: { kind: 'boolean', value: 'true' }, right: { kind: 'unary', operator: '!', operand: { kind: 'variable', value: 'outside' } } };
  assert.match(model.expressionIssue(expression, description), /переменную/);
  assert.match(model.expressionIssue({ kind: 'number', value: '1; System.exit(0)' }, description), /число/);
  assert.match(model.expressionIssue({ kind: 'char', value: 'ab' }, description), /один знак/);
  assert.match(model.expressionIssue({ kind: 'raw', value: 'count + 1' }, description), /поддерживаемый/);
});

test('while defaults use variables or a nonconstant method expression', () => {
  assert.equal(model.defaultLoopCondition(description).left.value, 'count');
  const noVariables = { ...description, variables: [] };
  const condition = model.defaultLoopCondition(noVariables);
  assert.equal(condition.left.kind, 'call');
  assert.equal(condition.left.callId, 'abs-int');
  assert.equal(model.expressionIssue(condition, noVariables), null);
});

test('method selections create arguments of declared types and expression calls exclude void', () => {
  assert.deepEqual(model.defaultSpec('call', description), { kind: 'call', callId: 'println-string', arguments: [{ kind: 'string', value: '' }] });
  assert.deepEqual(model.defaultExpression('call', description, 'int'), { kind: 'call', callId: 'abs-int', arguments: [{ kind: 'number', value: '0' }] });
  assert.match(model.callIssue('abs-int', [], description), /аргументы/);
  assert.equal(model.callIssue('abs-int', [model.number(-5)], description), null);
});

test('null initializers and empty-string literals remain distinct', () => {
  const empty = { kind: 'variable', name: 'message', type: 'String', value: { kind: 'string', value: '' } };
  assert.equal(model.specIssue(empty, description), null);
  assert.equal(model.specIssue({ ...empty, value: null }, description), null);
  assert.notDeepEqual(empty, { ...empty, value: null });
});

test('imported variable renders prefilled semantic fields without textarea', () => {
  const html = renderToStaticMarkup(React.createElement(SpecInputs, { spec: { kind: 'variable', name: 'count', type: 'int', value: model.number(42) }, description, disabled: false, editing: true, onChange() {} }));
  assert.match(html, /Имя переменной/);
  assert.match(html, /value="count"/);
  assert.match(html, /value="42"/);
  assert.match(html, /Начальное значение: число/);
  assert.doesNotMatch(html, /<textarea/);
  assert.doesNotMatch(html, /int count/);
});

test('imported foreach displays existing collection and protects loop kind', () => {
  const html = renderToStaticMarkup(React.createElement(SpecInputs, { spec: { kind: 'loop', loopKind: 'foreach', name: 'price', type: 'Integer', iterable: model.variable('prices') }, description, disabled: false, editing: true, onChange() {} }));
  assert.match(html, /value="price"/);
  assert.match(html, /value="prices" selected/);
  assert.match(html, /Коллекция или массив: переменная/);
  assert.match(html, /<select[^>]*disabled=""[^>]*>.*?foreach/s);
  assert.doesNotMatch(html, /<textarea/);
});

test('actions offer writable targets only', () => {
  const html = renderToStaticMarkup(React.createElement(SpecInputs, { spec: { kind: 'action', target: 'count', operator: '++' }, description, disabled: false, editing: true, onChange() {} }));
  assert.match(html, /value="count" selected/);
  assert.doesNotMatch(html, /value="fixed"/);
  assert.doesNotMatch(html, /Значение действия/);
});

test('text arguments escape markup and never require Java quotes', () => {
  const html = renderToStaticMarkup(React.createElement(SpecInputs, { spec: { kind: 'call', callId: 'println-string', arguments: [{ kind: 'string', value: '<Привет "мир">' }] }, description, disabled: false, editing: true, onChange() {} }));
  assert.match(html, /Вызов: аргумент text: текст/);
  assert.match(html, /&lt;Привет &quot;мир&quot;&gt;/);
  assert.doesNotMatch(html, /<Привет/);
});

test('visual API sends structured apply and stale-context fields unchanged', async () => {
  class RequestError extends Error { constructor(message, diagnostics) { super(message); this.diagnostics = diagnostics; } }
  const api = load('api.ts', { '../../api': { RequestError } });
  const previousFetch = global.fetch;
  let captured;
  global.fetch = async (url, init) => { captured = { url, init }; return { ok: true, json: async () => ({ source: 'result' }) }; };
  try {
    const context = { fileName: 'Demo.java', source: 'original', revision: 'r1', methodId: 'm1', nodeId: 'n1' };
    const spec = model.defaultSpec('variable', description);
    await api.applyVisualBlock(context, 'insert', spec, 'then-start');
    assert.equal(captured.url, '/api/visual/apply');
    assert.deepEqual(JSON.parse(captured.init.body), { ...context, mode: 'insert', spec, position: 'then-start' });
    await api.describeVisualBlock(context);
    assert.equal(captured.url, '/api/visual/describe');
    assert.deepEqual(JSON.parse(captured.init.body), context);
  } finally { global.fetch = previousFetch; }
});
