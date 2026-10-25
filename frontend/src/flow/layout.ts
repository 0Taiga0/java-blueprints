import dagre from '@dagrejs/dagre';
import { MarkerType, type Edge, type Node } from '@xyflow/react';
import type { FlowBlock, MethodFlow } from './types';
import { BLOCK_WIDTH, blockHeight, nodePresentation } from './geometry';
import { feedbackEdges } from './visibility';
import { orthogonalRoute, type OrthogonalRoute, type RouteBox } from './routing';
export { BLOCK_WIDTH, blockHeight } from './geometry';

export type DiagramNode = Node<FlowBlock & { presentation: ReturnType<typeof nodePresentation> } & Record<string, unknown>, 'javaBlock'>;
export type DiagramEdge = Edge<{ backward: boolean; lane: number; exception: boolean; structure: boolean; rail: number; route: OrthogonalRoute }>;
// Dagre расставляет прямоугольники. Смысл стрелок уже определён сервером.
export function layoutMethod(method: MethodFlow): { nodes: DiagramNode[]; edges: DiagramEdge[] } {
  const graph = new dagre.graphlib.Graph({ multigraph: true }).setDefaultEdgeLabel(() => ({}));
  const feedback = method.mode === 'flow' ? feedbackEdges(method) : new Set<string>();
  graph.setGraph({ rankdir: 'TB', nodesep: 140, ranksep: 90, marginx: 60, marginy: 35 });
  method.nodes.forEach(node => graph.setNode(node.id, { width: BLOCK_WIDTH, height: blockHeight(node.kind) }));
  method.edges.filter(edge => !feedback.has(edge.id)).forEach(edge => graph.setEdge(edge.source, edge.target, {weight: edge.kind === 'exception' ? 0.2 : 2}, edge.id));
  dagre.layout(graph);
  if (method.mode === 'structure') {
    // Обзор типов и карточки состава: длинный горизонтальный ряд становится сеткой.
    const types = method.nodes.filter(n => ['class','interface','enum','record'].includes(n.kind));
    const members = method.nodes.filter(n => ['field','method','constructor','initializer'].includes(n.kind));
    const library = method.nodes.filter(n => n.kind === 'library');
    const root = method.nodes.find(n => n.kind === 'file');
    const place = (id:string,x:number,y:number) => { const point=graph.node(id); point.x=x+BLOCK_WIDTH/2; point.y=y+blockHeight(method.nodes.find(n=>n.id===id)!.kind)/2; };
    if (!members.length) {
      if (root) place(root.id,175,20);
      types.forEach((n,i)=>place(n.id,(i%2)*400,190+Math.floor(i/2)*190));
      library.forEach((n,i)=>place(n.id,880+(i%2)*400,20+Math.floor(i/2)*180));
    } else {
      const owner=types.reduce((a,b)=>method.edges.filter(e=>e.source===a.id&&e.label==='содержит').length>=method.edges.filter(e=>e.source===b.id&&e.label==='содержит').length?a:b);
      place(owner.id,400,30);
      members.forEach((n,i)=>place(n.id,(i%3)*400,240+Math.floor(i/3)*175));
      [...types.filter(n=>n.id!==owner.id),...library].forEach((n,i)=>place(n.id,1320+(Math.floor(i/9))*400,30+(i%9)*180));
      if (root) place(root.id,0,30);
    }
  }
  const nodes: DiagramNode[] = method.nodes.map(block => {
    const point = graph.node(block.id);
    return {
      id: block.id, type: 'javaBlock', data: { ...block, presentation: nodePresentation(block, method.signature) },
      width: BLOCK_WIDTH, height: blockHeight(block.kind),
      position: { x: point.x - BLOCK_WIDTH / 2, y: point.y - blockHeight(block.kind) / 2 },
      ariaLabel: (block.code || block.label) + ', строка ' + block.range.startLine + '. Enter: открыть полный код и действия блока.',
    };
  });
  let lane = 0;
  const leftBoundary = Math.min(...nodes.map(node => node.position.x));
  const boxes: RouteBox[] = nodes.map(node => ({ id: node.id, x: node.position.x, y: node.position.y,
    width: BLOCK_WIDTH, height: blockHeight(node.data.kind) }));
  const boxesById = new Map(boxes.map(box => [box.id, box]));
  const edges: DiagramEdge[] = method.edges.map(edge => {
    const backward = feedback.has(edge.id) || graph.node(edge.target).y <= graph.node(edge.source).y;
    const exception = edge.kind === 'exception';
    const structure = edge.kind === 'structure';
    const color = exception ? '#d88383' : edge.kind === 'structure' ? '#a891d2' : edge.label === 'Нет' || edge.label === 'Конец' ? '#e5a46c' : '#68b6df';
    const branch = ['Да', 'Есть элемент', 'Нет', 'Конец'].includes(edge.label);
    // Branch meaning determines its port even when Dagre places both destinations
    // on the same side. Sharing a port also stacks the Да/Нет labels.
    const branchHandle = edge.label === 'Да' || edge.label === 'Есть элемент' ? 'yes' : 'no';
    const edgeLane = backward ? lane++ : 0;
    const sourceHandle = exception ? 'exception-out' : backward ? 'out' : branch ? branchHandle : 'out';
    const targetHandle = exception ? 'exception-in' : 'in';
    const rail = leftBoundary - 48 - edgeLane * 24;
    const route = orthogonalRoute({ source: boxesById.get(edge.source)!, target: boxesById.get(edge.target)!, boxes,
      sourceHandle, targetHandle, backward, exception, rail, lane: edgeLane, branch });
    return {
      ...edge, label: edge.label || (backward && !exception && !structure ? 'Повтор' : ''),
      type: 'javaEdge', sourceHandle, targetHandle,
      data: { backward, exception, structure, lane: edgeLane, rail, route },
      markerEnd: { type: MarkerType.ArrowClosed, color, width: 18, height: 18 },
      style: { stroke: color, strokeWidth: exception ? 1.5 : 2, opacity: exception ? 0.65 : 1, strokeDasharray: exception ? '5 7' : undefined,
        strokeLinejoin: 'round', strokeLinecap: 'round' },
      labelStyle: { fill: color, fontSize: 14, fontWeight: 600 },
      labelBgStyle: { fill: '#111c28', fillOpacity: 0.96 },
    };
  });
  return { nodes, edges };
}
