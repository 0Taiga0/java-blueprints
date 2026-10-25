const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const test = require('node:test');
const ts = require('typescript');

const source = fs.readFileSync(path.join(__dirname, 'validation.ts'), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const gate = {};
new Function('exports', compiled)(gate);

const run = { status: 'passed', passed: 1, failed: 0, skipped: 0, outputTruncated: false,
  tests: [{ name: 'answer()', className: 'DemoTest', status: 'passed' }] };
const report = { status: 'matched', before: structuredClone(run), after: structuredClone(run),
  regressions: 0, fixed: 0, unresolved: 0, unchanged: 1,
  tests: [{ name: 'answer()', className: 'DemoTest', change: 'unchanged',
    before: { status: 'passed' }, after: { status: 'passed' } }] };
const current = { fileName: 'Demo.java', source: 'before', revision: 'revision-a',
  candidateId: 'candidate-1', testFileName: 'DemoTest.java', testSource: 'tests' };
const proof = { ...current, preview: { source: 'after', revision: 'revision-a',
  candidate: { id: 'candidate-1', available: true }, conversion: { fileName: 'Demo.java' } }, report };

test('complete matching snapshot enables apply', () => assert.equal(gate.canApplyOptimization(proof, current), true));
for (const key of ['fileName', 'source', 'revision', 'candidateId', 'testFileName', 'testSource']) {
  test('changed ' + key + ' blocks apply', () => assert.equal(
    gate.canApplyOptimization(proof, { ...current, [key]: 'changed' }), false));
}
for (const status of ['changed', 'baseline_failed', 'inconclusive']) {
  test(status + ' blocks apply', () => {
    const altered = structuredClone(proof); altered.report.status = status;
    assert.equal(gate.canApplyOptimization(altered, current), false);
  });
}
for (const branch of ['before', 'after']) {
  for (const field of ['skipped', 'failed', 'outputTruncated']) {
    test(branch + ' ' + field + ' blocks apply despite matched label', () => {
      const altered = structuredClone(proof);
      altered.report[branch][field] = field === 'outputTruncated' ? true : 1;
      assert.equal(gate.canApplyOptimization(altered, current), false);
    });
  }
  test(branch + ' no tests blocks apply', () => {
    const altered = structuredClone(proof);
    altered.report[branch] = { ...altered.report[branch], status: 'no_tests', passed: 0, tests: [] };
    assert.equal(gate.canApplyOptimization(altered, current), false);
  });
}
test('unavailable candidate blocks apply', () => {
  const altered = structuredClone(proof); altered.preview.candidate.available = false;
  assert.equal(gate.canApplyOptimization(altered, current), false);
});
test('different preview candidate blocks apply', () => {
  const altered = structuredClone(proof); altered.preview.candidate.id = 'different';
  assert.equal(gate.canApplyOptimization(altered, current), false);
});
test('no actual code change blocks apply', () => {
  const altered = structuredClone(proof); altered.preview.source = altered.source;
  assert.equal(gate.canApplyOptimization(altered, current), false);
});
test('missing test pair blocks apply', () => {
  const altered = structuredClone(proof); altered.report.tests[0].after = null;
  assert.equal(gate.canApplyOptimization(altered, current), false);
});
