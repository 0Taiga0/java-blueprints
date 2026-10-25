export type RoutePoint = { x: number; y: number };
export type RouteBox = { id: string; x: number; y: number; width: number; height: number };
export type OrthogonalRoute = { points: RoutePoint[]; path: string; labelX: number; labelY: number };

function compact(points: RoutePoint[]): RoutePoint[] {
  const result: RoutePoint[] = [];
  for (const point of points) {
    const previous = result.at(-1);
    if (previous?.x === point.x && previous.y === point.y) continue;
    const before = result.at(-2);
    if (before && previous && ((before.x === previous.x && previous.x === point.x)
      || (before.y === previous.y && previous.y === point.y))) result.pop();
    result.push(point);
  }
  return result;
}

/** Only interior intersections count: endpoints intentionally touch the card border. */
export function routeIntersectsBox(points: RoutePoint[], box: RouteBox, clearance = 0): boolean {
  const left = box.x - clearance, right = box.x + box.width + clearance;
  const top = box.y - clearance, bottom = box.y + box.height + clearance;
  return points.slice(1).some((point, index) => {
    const previous = points[index];
    if (point.x === previous.x) return point.x > left && point.x < right
      && Math.max(point.y, previous.y) > top && Math.min(point.y, previous.y) < bottom;
    if (point.y === previous.y) return point.y > top && point.y < bottom
      && Math.max(point.x, previous.x) > left && Math.min(point.x, previous.x) < right;
    return true; // A diagonal is never a valid orthogonal route.
  });
}

function length(points: RoutePoint[]) {
  return points.slice(1).reduce((total, point, index) => total
    + Math.abs(point.x - points[index].x) + Math.abs(point.y - points[index].y), 0);
}

export function orthogonalRoute(options: {
  source: RouteBox; target: RouteBox; boxes: RouteBox[];
  sourceHandle: string; targetHandle: string; backward: boolean;
  exception: boolean; rail: number; lane: number; branch: boolean;
}): OrthogonalRoute {
  const { source, target, boxes, sourceHandle, targetHandle, backward, exception, rail, lane, branch } = options;
  const sourceRight = sourceHandle === 'exception-out';
  const targetRight = targetHandle === 'exception-in';
  const start = { x: source.x + source.width * (sourceRight ? 1 : sourceHandle === 'yes' ? 0.35 : sourceHandle === 'no' ? 0.65 : 0.5),
    y: source.y + source.height * (sourceRight ? 0.5 : 1) };
  const end = { x: target.x + target.width * (targetRight ? 1 : 0.5),
    y: target.y + target.height * (targetRight ? 0.5 : 0) };
  const gap = target.y - source.y - source.height;
  const stub = !backward && gap > 0 ? Math.min(28, gap / 3) : 28;
  const exit = { x: start.x + (sourceRight ? 28 : 0), y: source.y + source.height + stub };
  const entry = { x: end.x + (targetRight ? 28 : 0), y: target.y - stub };
  const prefix = sourceRight ? [start, { x: exit.x, y: start.y }, exit] : [start, exit];
  const suffix = targetRight ? [entry, { x: entry.x, y: end.y }, end] : [entry, end];
  const safe = (points: RoutePoint[]) => !boxes.some(box => routeIntersectsBox(points, box));
  const viaRail = (x: number) => compact([...prefix, { x, y: exit.y }, { x, y: entry.y }, ...suffix]);
  let points: RoutePoint[] | undefined;

  if (!backward && !sourceRight && !targetRight) {
    // Each branch turns in the empty band between rows. This preserves clear,
    // short paths for adjacent blocks without crossing intermediate cards.
    const middleY = (exit.y + entry.y) / 2;
    const direct = compact([start, { x: start.x, y: middleY }, { x: end.x, y: middleY }, end]);
    if (safe(direct)) points = direct;
  }
  if (!points) {
    const left = Math.min(...boxes.map(box => box.x)) - 48 - lane * 24;
    const right = Math.max(...boxes.map(box => box.x + box.width)) + 48 + lane * 24;
    // Loop returns use their own outside lane; exceptional routes use the other
    // side. Long forward jumps try nearer free corridors before going outside.
    const corridors = backward ? [exception ? right : rail, exception ? left : right]
      : [exit.x, entry.x, (exit.x + entry.x) / 2,
        ...boxes.flatMap(box => [box.x - 28, box.x + box.width + 28]), left, right];
    const candidates = [...new Set(corridors)].map(viaRail);
    if (!backward) candidates.sort((a, b) => length(a) - length(b));
    points = candidates.find(safe);
    // Dagre leaves a free band before/after each rank. The outside route also
    // handles self loops and two branches that immediately join again.
    points ??= viaRail(exception ? right : left);
  }
  let labelX = start.x, labelY = start.y + 19;
  if (backward) {
    const segment = points.slice(1).map((point, index) => ({ start: points![index], end: point }))
      .filter(segment => segment.start.x === segment.end.x)
      .sort((a, b) => Math.abs(b.start.y - b.end.y) - Math.abs(a.start.y - a.end.y))[0];
    if (segment) { labelX = segment.start.x; labelY = (segment.start.y + segment.end.y) / 2; }
  } else if (!branch) {
    const segment = points.slice(1).map((point, index) => ({ start: points![index], end: point }))
      .sort((a, b) => length([b.start, b.end]) - length([a.start, a.end]))[0];
    if (segment) { labelX = (segment.start.x + segment.end.x) / 2; labelY = (segment.start.y + segment.end.y) / 2; }
  }
  return { points, path: points.map((point, index) => `${index ? 'L' : 'M'} ${point.x} ${point.y}`).join(' '), labelX, labelY };
}
