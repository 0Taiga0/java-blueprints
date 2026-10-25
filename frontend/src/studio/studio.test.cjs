const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const test = require('node:test');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
const ts = require('typescript');

function loadTypescript(file, imports = {}) {
  const source = fs.readFileSync(file, 'utf8');
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const exports = {};
  new Function('exports', 'require', js)(exports, name => imports[name] ?? require(name));
  return exports;
}
const keyboard = loadTypescript(path.join(__dirname, '../analysis/tabNavigation.ts'));
const { StudioTabs, StudioPanel } = loadTypescript(path.join(__dirname, 'StudioTabs.tsx'), {
  '../analysis/tabNavigation': keyboard,
});
const items = [{ id: 'code', label: 'Код' }, { id: 'tests', label: 'Тесты' }, { id: 'results', label: 'Результаты' }];

function dispatch(key, current, extra = {}) {
  const result = { prevented: false, focused: -1, selected: -1 };
  const buttons = items.map((_, index) => ({
    focus: () => { result.focused = index; },
    click: () => { result.selected = index; },
  }));
  keyboard.navigateTabs({ key, target: buttons[current],
    currentTarget: { querySelectorAll: () => buttons }, preventDefault: () => { result.prevented = true; }, ...extra });
  return result;
}

test('ArrowRight wraps to the first tab and activates it', () => {
  assert.deepEqual(dispatch('ArrowRight', 2), { prevented: true, focused: 0, selected: 0 });
});
test('ArrowLeft wraps to the last tab and activates it', () => {
  assert.deepEqual(dispatch('ArrowLeft', 0), { prevented: true, focused: 2, selected: 2 });
});
test('Home and End select edge tabs', () => {
  assert.deepEqual(dispatch('Home', 1), { prevented: true, focused: 0, selected: 0 });
  assert.deepEqual(dispatch('End', 1), { prevented: true, focused: 2, selected: 2 });
});
test('Tab and modified shortcuts keep their native behavior', () => {
  assert.deepEqual(dispatch('Tab', 0), { prevented: false, focused: -1, selected: -1 });
  assert.deepEqual(dispatch('ArrowRight', 0, { ctrlKey: true }), { prevented: false, focused: -1, selected: -1 });
});
test('only the selected tab enters the keyboard tab order', () => {
  for (const active of ['code', 'tests', 'results']) {
    const html = renderToStaticMarkup(React.createElement(StudioTabs, {
      prefix: 'dock', label: 'Panels', value: active, items, onChange: () => {},
    }));
    assert.equal((html.match(/tabindex="0"/g) ?? []).length, 1);
    assert.equal((html.match(/tabindex="-1"/g) ?? []).length, 2);
    assert.match(html, new RegExp('id="dock-tab-' + active + '"[^>]*aria-selected="true"[^>]*tabindex="0"'));
    for (const item of items) assert.ok(html.includes('aria-controls="dock-panel-' + item.id + '"'));
  }
});
test('inactive panels retain their editor content but are hidden and inert', () => {
  const panel = active => renderToStaticMarkup(React.createElement(StudioPanel, {
    prefix: 'dock', id: 'tests', active,
  }, React.createElement('textarea', { defaultValue: 'unsaved Java test' })));
  const hidden = panel(false);
  assert.match(hidden, /hidden=""/);
  assert.match(hidden, /inert=""/);
  assert.match(hidden, /<textarea>unsaved Java test<\/textarea>/);
  const visible = panel(true);
  assert.doesNotMatch(visible, /hidden=""/);
  assert.doesNotMatch(visible, /inert=""/);
  assert.match(visible, /aria-labelledby="dock-tab-tests"/);
  assert.match(visible, /<textarea>unsaved Java test<\/textarea>/);
});
