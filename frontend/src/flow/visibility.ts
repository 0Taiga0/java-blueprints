import type { MethodFlow } from './types';

// A display filter only: the source and the server's complete graph remain intact.
export function primaryFlow(method: MethodFlow): MethodFlow {
  if (method.mode !== 'flow') return method;
  const edges = method.edges.filter(edge => edge.kind !== 'exception');
  const reachable = new Set(method.nodes.filter(node => node.kind === 'start').map(node => node.id));
  const outgoing = new Map<string, string[]>();
  for (const edge of edges) outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge.target]);
  const queue = [...reachable];
  for (let index = 0; index < queue.length; index++) {
    for (const target of outgoing.get(queue[index]) ?? []) {
      if (!reachable.has(target)) { reachable.add(target); queue.push(target); }
    }
  }
  return { ...method, nodes: method.nodes.filter(node => reachable.has(node.id)),
    edges: edges.filter(edge => reachable.has(edge.source) && reachable.has(edge.target)) };
}

// Remove only DFS feedback edges from the layout input. They remain in the drawing.
export function feedbackEdges(method: MethodFlow): Set<string> {
  const feedback = new Set<string>();
  const state = new Map<string, number>();
  const outgoing = new Map<string, MethodFlow['edges']>();
  for (const edge of method.edges) {
    if (edge.kind === 'exception') continue;
    outgoing.set(edge.source, [...(outgoing.get(edge.source) ?? []), edge]);
  }
  function visit(id: string) {
    state.set(id, 1);
    for (const edge of outgoing.get(id) ?? []) {
      if (state.get(edge.target) === 1) feedback.add(edge.id);
      else if (!state.has(edge.target)) visit(edge.target);
    }
    state.set(id, 2);
  }
  for (const node of [...method.nodes.filter(node => node.kind === 'start'), ...method.nodes]) {
    if (!state.has(node.id)) visit(node.id);
  }
  return feedback;
}
