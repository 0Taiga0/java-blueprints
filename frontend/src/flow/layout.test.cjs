const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const test = require('node:test');
const ts = require('typescript');
function load(file, imports = {}) {
  const js = ts.transpileModule(fs.readFileSync(path.join(__dirname, file), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const exports = {};
  new Function('exports', 'require', js)(exports, name => imports[name] ?? require(name));
  return exports;
}
const visibility = load('visibility.ts');
const geometry = load('geometry.ts');
const routing = load('routing.ts');
const { layoutMethod } = load('layout.ts', {
  './visibility': visibility, './geometry': geometry, './routing': routing, '@xyflow/react': { MarkerType: { ArrowClosed: 'arrowclosed' } },
  '@dagrejs/dagre': { default: require('@dagrejs/dagre') },
});
const node = (id, kind='operation') => ({ id, kind, code: id, label: id, category: '', range: { startLine: 1 }, insert: null });
const edge = (source, target, label='', kind='normal') => ({ id: source+'-'+target, source, target, label, kind });
const method = {
  id: 'test', mode: 'flow', signature: 'run()', nodes: [node('start','start'),node('loop','condition'),node('body'),node('end','end'),node('catch','catch'),node('handler')],
  edges: [edge('start','loop'),edge('loop','body','Да'),edge('body','loop'),edge('loop','end','Нет'),edge('body','catch','Возможно исключение','exception'),edge('catch','handler','Да'),edge('handler','end')],
};
test('primary view removes exception-only routes without changing the complete graph', () => {
  const before = JSON.stringify(method);
  const visible = visibility.primaryFlow(method);
  assert.deepEqual(visible.nodes.map(n=>n.id),['start','loop','body','end']);
  assert.equal(visible.edges.length,4);
  assert.equal(JSON.stringify(method),before);
  assert.equal(visibility.primaryFlow({...method,mode:'structure'}).nodes.length,6);
});
test('layout retains every semantic edge and places the loop body below its condition', () => {
  const visible = visibility.primaryFlow(method);
  const result = layoutMethod(visible);
  const positions = Object.fromEntries(result.nodes.map(n=>[n.id,n.position]));
  assert.deepEqual(new Set(result.edges.map(e=>e.id)),new Set(visible.edges.map(e=>e.id)));
  assert.ok(positions.start.y < positions.loop.y);
  assert.ok(positions.loop.y < positions.body.y);
  const back = result.edges.find(e=>e.id==='body-loop');
  assert.equal(back.data.backward,true);
  assert.ok(back.data.rail < Math.min(...result.nodes.map(n=>n.position.x)));
  assert.equal(back.sourceHandle,'out');
  assert.equal(back.targetHandle,'in');
});
test('nested loops and self loops have feedback routes; merges are not mistaken for loops', () => {
  const sample = {...method,nodes:[node('start','start'),node('outer'),node('inner'),node('body'),node('join'),node('end')],
    edges:[edge('start','outer'),edge('outer','inner'),edge('inner','body'),edge('body','inner'),edge('inner','join'),edge('join','outer'),edge('outer','end')]};
  assert.deepEqual(visibility.feedbackEdges(sample),new Set(['body-inner','join-outer']));
  assert.deepEqual(visibility.feedbackEdges({...sample,edges:[edge('start','outer'),edge('outer','outer')]}),new Set(['outer-outer']));
  assert.equal(visibility.feedbackEdges({...sample,edges:[edge('start','outer'),edge('outer','inner'),edge('outer','body'),edge('body','join'),edge('inner','join')]}).size,0);
});
test('both parallel branch labels survive even when they target the same block', () => {
  const sample = {...method,nodes:[node('start','start'),node('if','condition'),node('end','end')],edges:[edge('start','if'),{...edge('if','end','Да'),id:'yes'},{...edge('if','end','Нет'),id:'no'}]};
  const result = layoutMethod(sample);
  assert.deepEqual(result.edges.filter(e=>e.source==='if').map(e=>e.label),['Да','Нет']);
  assert.notEqual(result.edges.find(e=>e.id==='yes').sourceHandle,result.edges.find(e=>e.id==='no').sourceHandle);
  assert.ok(result.edges.every(e=>Number.isFinite(e.data.rail)));
  assert.notEqual(result.edges.find(e=>e.id==='yes').data.route.labelX,result.edges.find(e=>e.id==='no').data.route.labelX);
});

function assertCleanRoutes(result) {
  const boxes = result.nodes.map(n => ({id:n.id,x:n.position.x,y:n.position.y,width:n.width,height:n.height}));
  for (const edge of result.edges) {
    const {points,path,labelX,labelY} = edge.data.route;
    assert.ok(points.length >= 2, edge.id);
    assert.ok(Number.isFinite(labelX) && Number.isFinite(labelY), edge.id);
    assert.ok(!/[CQ]/.test(path), 'No looping Bézier curves: '+edge.id);
    points.slice(1).forEach((point,i)=>assert.ok(point.x===points[i].x || point.y===points[i].y,edge.id));
    for (const box of boxes) assert.equal(routing.routeIntersectsBox(points,box),false,edge.id+' crosses '+box.id);
  }
}

test('ordinary, loop and exception routes avoid every block interior', () => {
  assertCleanRoutes(layoutMethod(method));
  assertCleanRoutes(layoutMethod(visibility.primaryFlow(method)));
  const sample = {...method,nodes:[node('start','start'),node('outer'),node('inner'),node('body'),node('join'),node('end')],
    edges:[edge('start','outer'),edge('outer','inner'),edge('inner','body'),edge('body','inner'),edge('inner','join'),edge('join','outer'),edge('outer','end')]};
  const result = layoutMethod(sample);
  assertCleanRoutes(result);
  assert.notEqual(result.edges.find(e=>e.id==='body-inner').data.route.points[2].x,
    result.edges.find(e=>e.id==='join-outer').data.route.points[2].x);
});

test('long jumps and early returns go around intervening cards', () => {
  const sample = {...method,nodes:[node('start','start'),node('if','condition'),node('action1'),node('action2'),node('action3'),node('return','return'),node('end','end')],
    edges:[edge('start','if'),edge('if','action1','Да'),edge('if','return','Нет'),edge('action1','action2'),edge('action2','action3'),edge('action3','return'),edge('return','end')]};
  assertCleanRoutes(layoutMethod(sample));
  const branches = layoutMethod(sample).edges.filter(edge => edge.source === 'if');
  assert.equal(branches.find(edge => edge.label === 'Да').sourceHandle, 'yes');
  assert.equal(branches.find(edge => edge.label === 'Нет').sourceHandle, 'no');
  assert.notEqual(branches[0].data.route.labelX, branches[1].data.route.labelX);
});

test('self loops and both empty branches remain orthogonal and retain distinct labels', () => {
  const sample = {...method,nodes:[node('start','start'),node('if','condition'),node('end','end')],
    edges:[edge('start','if'),edge('if','if','Повтор'),{...edge('if','end','Да'),id:'yes'},{...edge('if','end','Нет'),id:'no'}]};
  assertCleanRoutes(layoutMethod(sample));
});

test('structure overview links avoid other cards after grid placement', () => {
  const sample = { ...method, mode:'structure', nodes:[node('file','file'),node('class','class'),
    ...Array.from({length:9},(_,i)=>node('method'+i,'method'))],
    edges:[edge('file','class','содержит','structure'),...Array.from({length:9},(_,i)=>edge('class','method'+i,'содержит','structure'))]};
  assertCleanRoutes(layoutMethod(sample));
});
