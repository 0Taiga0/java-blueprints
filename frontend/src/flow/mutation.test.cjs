const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const test = require('node:test');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const ts = require('typescript');

function load(file, imports = {}) {
  const js = ts.transpileModule(fs.readFileSync(path.join(__dirname, file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const exports = {};
  new Function('exports', 'require', js)(exports, name => imports[name] ?? require(name));
  return exports;
}
const mutation = load('mutation.ts');
const controllerModule = load('dragController.ts');
const drag = load('SemanticDrag.tsx', { './dragController': controllerModule });
const template = load('templateSource.ts');
const forms = load('BlockMutationForms.tsx', { './mutation': mutation });
const range = (start, end = start, startColumn = 1, endColumn = 40) => ({ startLine: start, startColumn, endLine: end, endColumn });
const block = (id, sourceRange, role = 'statement', positions = ['before', 'after']) => ({
  id, kind: role === 'if' ? 'condition' : 'operation', code: id + '();', label: id, range: sourceRange, detail: '', category: '', targetMethodId: null,
  insert: { role, range: sourceRange, positions }, edit: null,
  mutation: { range: sourceRange, text: id + '();', deleteModes: ['delete'], movable: true },
});

test('move refuses the same node and a second graph copy of the same Java range', () => {
  const source = block('if', range(3, 10), 'if');
  assert.match(mutation.moveTargetIssue(source, source), /другой/);
  assert.match(mutation.moveTargetIssue(source, block('copy', range(3, 10))), /самого себя/);
});
test('move refuses descendants but permits moving out into a containing method', () => {
  const source = block('if', range(3, 10), 'if');
  assert.match(mutation.moveTargetIssue(source, block('child', range(5))), /самого себя/);
  const start = block('start', range(1, 20), 'body', ['body-start', 'body-end']); start.mutation = null;
  assert.equal(mutation.moveTargetIssue(source, start), null);
});
test('move respects source mutability and destination capabilities', () => {
  const source = block('source', range(3)); const target = block('target', range(5));
  source.mutation.movable = false;
  assert.ok(mutation.moveTargetIssue(source, target));
  source.mutation.movable = true; target.insert = null;
  assert.ok(mutation.moveTargetIssue(source, target));
});
test('same-line range boundaries do not confuse adjacent expressions', () => {
  assert.equal(mutation.containsRange(range(2, 2, 5, 15), range(2, 2, 16, 20)), false);
  assert.equal(mutation.containsRange(range(2, 2, 5, 15), range(2, 2, 6, 14)), true);
});
test('requested branch insertion wins over default after, unsupported positions never do', () => {
  const target = block('if', range(2, 8), 'if', ['before', 'after', 'then-start', 'else-start']);
  assert.equal(mutation.defaultPosition(target, 'else-start'), 'else-start');
  assert.equal(mutation.defaultPosition(target, 'body-start'), 'after');
  assert.match(mutation.positionLabel(target, 'after'), /обеих веток/);
});
test('Delete is only handled inside a node outside editable/button controls', () => {
  const target = editable => ({ closest: selector => selector === '.react-flow__node' ? {} : editable ? {} : null });
  assert.equal(mutation.canHandleNodeKey({ key: 'Delete', target: target(false) }), true);
  assert.equal(mutation.canHandleNodeKey({ key: 'Delete', target: target(true) }), false);
  assert.equal(mutation.canHandleNodeKey({ key: 'Delete', ctrlKey: true, target: target(false) }), false);
  assert.equal(mutation.canHandleNodeKey({ key: 'Delete', target: { closest: () => null } }), false);
});

function fixture() {
  const state = { enabled: true, views: [], drops: [], cancels: 0, capture: new Set(), target: { nodeId: 'destination', position: 'then-start' } };
  let control;
  const element = {
    setPointerCapture: id => state.capture.add(id),
    hasPointerCapture: id => state.capture.has(id),
    releasePointerCapture: id => { state.capture.delete(id); control.lostCapture(); },
  };
  control = controllerModule.createDragController({
    canStart: () => state.enabled, resolve: () => state.target,
    onView: value => state.views.push(value), onDrop: (payload, destination) => state.drops.push({ payload, destination }),
    onCancel: () => state.cancels++,
  });
  const event = (x, extra = {}) => ({ button: 0, pointerId: 7, clientX: x, clientY: 10, currentTarget: element, preventDefault() {}, stopPropagation() {}, ...extra });
  const payload = { kind: 'node', nodeId: 'source', label: 'source();' };
  return { state, control, event, payload };
}
test('a click and small pointer motion do not mutate Java or suppress normal selection', () => {
  const { state, control, event, payload } = fixture();
  control.begin(event(10), payload); control.move(event(14)); control.end(event(14));
  assert.equal(state.drops.length, 0); assert.equal(state.capture.size, 0); assert.equal(control.consumeClick(), false);
});
test('node drag commits exactly the visible destination and position once on release', () => {
  const { state, control, event, payload } = fixture();
  control.begin(event(10), payload); control.move(event(50)); control.end(event(50)); control.end(event(50));
  assert.deepEqual(state.drops, [{ payload, destination: { nodeId: 'destination', position: 'then-start' } }]);
  assert.equal(state.cancels, 0); assert.equal(state.capture.size, 0);
  assert.equal(control.consumeClick(), true); assert.equal(control.consumeClick(), false);
});
test('palette and existing nodes share the same precise drop path', () => {
  const { state, control, event } = fixture();
  const payload = { kind: 'template', templateKind: 'loop', label: 'Цикл' };
  state.target = { nodeId: 'start', position: 'body-start' };
  control.begin(event(0), payload); control.move(event(20)); control.end(event(20));
  assert.deepEqual(state.drops[0], { payload, destination: state.target });
});
test('release outside a zone yields no inferred after or previous selection', () => {
  const { state, control, event, payload } = fixture();
  state.target = null; control.begin(event(0), payload); control.move(event(20)); control.end(event(20));
  assert.equal(state.drops[0].destination, null);
});
test('cancel and lost capture clear the gesture without committing', () => {
  for (const action of ['cancel', 'lostCapture']) {
    const { state, control, event, payload } = fixture();
    control.begin(event(0), payload); control.move(event(20)); control[action](); control.end(event(25));
    assert.equal(state.drops.length, 0); assert.equal(state.cancels, 1); assert.equal(state.capture.size, 0);
    assert.equal(control.hasGesture(), false);
  }
});
test('unmount releases capture without a callback into an unmounted component', () => {
  const { state, control, event, payload } = fixture();
  control.begin(event(0), payload); control.move(event(20)); control.dispose();
  assert.equal(state.drops.length, 0); assert.equal(state.cancels, 0); assert.equal(state.capture.size, 0);
});
test('busy and a second pointer cannot start another mutation gesture', () => {
  const { state, control, event, payload } = fixture();
  state.enabled = false; control.begin(event(0), payload); assert.equal(control.hasGesture(), false);
  state.enabled = true; control.begin(event(0), payload);
  control.move(event(20, { pointerId: 9 })); control.end(event(20, { pointerId: 9 }));
  assert.equal(state.drops.length, 0); assert.equal(control.hasGesture(), true);
  control.move(event(20)); control.end(event(20));
  assert.equal(state.drops.length, 1);
});
test('drop hit testing accepts only explicit enabled zones in the current canvas', () => {
  const priorDocument = global.document;
  const element = { disabled: false, dataset: { flowNode: 'n', position: 'else-start' } };
  global.document = { elementFromPoint: () => ({ closest: () => element }) };
  try {
    assert.deepEqual(drag.dropTargetAt(1, 2, { contains: candidate => candidate === element }), { nodeId: 'n', position: 'else-start' });
    assert.equal(drag.dropTargetAt(1, 2, { contains: () => false }), null);
    element.disabled = true;
    assert.equal(drag.dropTargetAt(1, 2, { contains: () => true }), null);
    element.disabled = false; delete element.dataset.position;
    assert.equal(drag.dropTargetAt(1, 2, { contains: () => true }), null);
  } finally { global.document = priorDocument; }
});
test('templates use unique loop/local names and a finite three-step loop', () => {
  const method = { nodes: [{ code: 'int step; int step2; int value;', mutation: null }] };
  const code = template.immediateTemplate('loop', method, '');
  assert.equal(code, 'for (int step3 = 0; step3 < 3; step3++) {\n}');
  assert.equal(template.immediateTemplate('variable', method, ''), 'int value2 = 0;');
  assert.equal(template.immediateTemplate('condition', method, ''), 'if (true) {\n}');
});
test('return template reuses a method return, and preserves fallback when absent', () => {
  assert.equal(template.immediateTemplate('return', { nodes: [{ kind: 'return', edit: { text: 'return 0;' } }] }, 'return;'), 'return 0;');
  assert.equal(template.immediateTemplate('return', { nodes: [] }, 'return;'), 'return;');
});
function zones(blockValue, active) {
  const value = { active, enabled: true, view: null, zoneAllowed: () => true, consumeClick: () => false, onZoneClick() {} };
  return renderToStaticMarkup(React.createElement(drag.FlowInteractionContext.Provider, { value }, React.createElement(drag.FlowDropZones, { block: blockValue })));
}
test('if ports stay discoverable at rest, all insertion directions appear during drag', () => {
  const target = block('if', range(1, 5), 'if', ['before', 'after', 'then-start', 'then-end', 'else-start', 'else-end']);
  const idle = zones(target, false); const active = zones(target, true);
  assert.ok(idle.includes('data-position="then-start"')); assert.ok(idle.includes('data-position="else-start"'));
  assert.ok(!idle.includes('data-position="before"'));
  assert.ok(active.includes('data-drop-position="before"')); assert.ok(active.includes('data-drop-position="after"'));
});
test('loop body is distinct from after loop and retains its exact API position', () => {
  const html = zones(block('loop', range(2, 7), 'loop', ['before', 'after', 'body-start', 'body-end']), true);
  assert.ok(html.includes('data-position="body-start"')); assert.ok(html.includes('После цикла'));
  assert.ok(html.includes('Внутри цикла')); assert.ok(html.includes('data-position="body-end"'));
});
test('delete preview shows the whole compound statement and explicit destructive modes', () => {
  const target = block('if', range(1, 8), 'if');
  target.mutation.text = 'if (flag) {\n  // important\n  run();\n} else {\n  fallback();\n}';
  target.mutation.deleteModes = ['delete', 'keep-then', 'keep-else'];
  const html = renderToStaticMarkup(React.createElement(forms.BlockDeleteForm, { block: target, busy: false, onDelete: async () => {}, onClose() {} }));
  assert.ok(html.includes('// important')); assert.ok(html.includes('fallback();'));
  assert.ok(html.includes('value="keep-then"')); assert.ok(html.includes('Подтвердить удаление'));
});
