const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('typescript');
const React = require('react');
const { renderToStaticMarkup } = require('react-dom/server');
function load(file) {
  const js = ts.transpileModule(fs.readFileSync(path.join(__dirname, file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  const exports = {}; new Function('exports', 'require', js)(exports, require); return exports;
}
const { editorText } = load('sourceText.ts');
const { GraphCompilation } = load('../check/GraphCompilation.tsx');
test('Windows CRLF/BOM input matches Monaco content without changing Java characters', () => {
  const file = '\uFEFFclass A {\r\n String name = "Ёж";\r\n}\r\n';
  assert.equal(editorText(file), editorText(file.slice(1).replaceAll('\r\n', '\n')));
  assert.equal(editorText('String x="\uFEFF";'), 'String x="\uFEFF";');
  assert.notEqual(editorText('return 1;\r\n'), editorText('return 2;\n'));
});
const report = status => ({ status, message: status, diagnostics: [] });
const render = (snapshot, source = 'new', fileName = 'A.java') => renderToStaticMarkup(
  React.createElement(GraphCompilation, { snapshot, source, fileName, onLine: () => {} }));
test('a successful compilation of a different revision or file is never shown as current', () => {
  assert.equal(render({ source: 'old', fileName: 'A.java', report: report('compiled') }), '');
  assert.equal(render({ source: 'new', fileName: 'B.java', report: report('compiled') }), '');
  assert.match(render({ source: 'new', fileName: 'A.java', report: report('compiled') }), /Java компилируется/);
});
test('transport failure and timeout are distinguished from a Java compiler error', () => {
  const base = { source: 'new', fileName: 'A.java' };
  assert.match(render({ ...base, error: 'Offline' }), /Проверка не завершена/);
  assert.match(render({ ...base, report: report('compile_timeout') }), /Проверка не завершена/);
  assert.match(render({ ...base, report: report('compile_error') }), /Ошибки Java/);
});
