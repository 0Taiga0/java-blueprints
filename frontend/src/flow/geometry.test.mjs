import assert from 'node:assert/strict';
import { test } from 'node:test';
import { BLOCK_WIDTH, CHARACTER_WIDTH, CODE_FONT_SIZE, CODE_LINE_HEIGHT, CAPTION_LINE_HEIGHT, blockGeometry, nodePresentation, wrapNodeText, textUnits, initialGraphViewport } from './geometry.ts';

const block = (kind, text) => ({ id: 'node', kind, label: text, code: text, category: '', detail: '', targetMethodId: null,
  edit: null, insert: null, range: { startLine: 3, startColumn: 1, endLine: 3, endColumn: 10 } });

test('every kind has enough vertical and horizontal room for its declared text budget', () => {
  for (const kind of ['start','end','operation','condition','return','throw','jump','opaque','switch','case','try','catch','finally',
    'yield','resource','evaluation','exception-end','file','class','interface','enum','record','field','method','constructor','initializer','library']) {
    const value = blockGeometry(kind);
    assert.equal(value.width, BLOCK_WIDTH);
    const border = kind === 'condition' ? 0 : 2;
    assert.ok(value.paddingY * 2 + CAPTION_LINE_HEIGHT + value.captionGap + value.maxLines * CODE_LINE_HEIGHT + border <= value.height, kind);
    assert.ok(value.columns * CHARACTER_WIDTH <= value.contentWidth, kind);
  }
});
test('start uses a compact method signature and retains complete multiline source for inspector and title', () => {
  const source = 'public static java.util.Map<String, java.util.List<Integer>>\ncalculate(java.util.List<Order> orders, java.util.Map<String, Object> settings) throws java.io.IOException';
  const value = nodePresentation(block('start', source), 'calculate(List<Order>, Map<String, Object>)');
  assert.equal(value.fullText, source);
  assert.equal(value.summarized, true);
  assert.ok(value.lines.length <= 2);
  assert.ok(!value.text.includes('public static'));
  assert.equal(value.geometry.height, 100);
  for (const line of value.lines) assert.ok(textUnits(line) <= value.geometry.columns);
});
test('long conditions are visibly abbreviated and retain the entire logical expression', () => {
  const condition = 'orders != null && !orders.isEmpty() && orders.stream().allMatch(order -> order.getPrice() > 0)';
  const value = nodePresentation(block('condition', condition));
  assert.equal(value.fullText, condition);
  assert.equal(value.truncated, true);
  assert.ok(value.text.endsWith('…'));
  assert.equal(value.lines.length, 3);
  assert.equal(value.geometry.height, 124);
  for (const line of value.lines) assert.ok(textUnits(line) <= value.geometry.columns);
});
test('class, catch and return labels respect the same three-line budget', () => {
  for (const [kind, text] of [
    ['class','public class VeryLongBusinessObject implements Serializable, Comparable<VeryLongBusinessObject>'],
    ['catch','catch (IOException | IllegalArgumentException | UnsupportedOperationException failure)'],
    ['return','return results.stream().filter(value -> value.isEnabled()).sorted(customComparator).toList();'],
  ]) {
    const value = nodePresentation(block(kind, text));
    assert.equal(value.fullText, text);
    assert.equal(value.geometry.height, 124);
    assert.ok(value.lines.length <= 3);
    for (const line of value.lines) assert.ok(textUnits(line) <= value.geometry.columns);
    if (value.truncated) assert.ok(value.text.endsWith('…'));
  }
});
test('wrapping preserves complete short expressions, Unicode and logical operator pairs', () => {
  const expression = 'left > 0 && right < 10';
  const value = wrapNodeText(expression, 15, 3);
  assert.equal(value.truncated, false);
  assert.equal(value.text.replace(/\s/g,''),expression.replace(/\s/g,''));
  assert.ok(!value.text.includes('&\n&'));
  const unicode = wrapNodeText('Проверка中文😀😀😀😀😀😀😀😀', 12, 2);
  assert.ok(unicode.lines.every(line => textUnits(line) <= 12));
  assert.ok(!unicode.text.includes('\uFFFD'));
  assert.ok(unicode.text.endsWith('…'));
});
test('large graphs open near their entry at readable scale instead of fitting to tiny text', () => {
  const viewport = initialGraphViewport([
    { position: { x: 200, y: 20 }, data: { kind: 'start' } },
    { position: { x: 1200, y: 5000 }, data: { kind: 'end' } },
  ], { width: 700, height: 540 });
  assert.ok(viewport.zoom >= 0.9);
  assert.equal(viewport.y + 20 * viewport.zoom, 40);
  assert.equal(viewport.x + (200 + BLOCK_WIDTH / 2) * viewport.zoom, 350);
});
test('small diagrams remain centered and never start below readable scale', () => {
  const viewport = initialGraphViewport([{ position: { x: 10, y: 10 }, data: { kind: 'return' } }], { width: 700, height: 540 });
  assert.equal(viewport.zoom,1);
  assert.equal(viewport.x + (10 + BLOCK_WIDTH / 2),350);
});

test('large diagram opens at full size with at least 16px code when canvas has room', () => {
  const viewport = initialGraphViewport([
    { position: { x: 200, y: 20 }, data: { kind: 'start' } },
    { position: { x: 1200, y: 5000 }, data: { kind: 'end' } },
  ], { width: 700, height: 540 });
  assert.equal(viewport.zoom, 1);
  assert.ok(CODE_FONT_SIZE * viewport.zoom >= 16);
});
