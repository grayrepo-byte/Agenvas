export const CANVAS_ARRANGE_COLUMNS = 5;
export const CANVAS_ARRANGE_GAP = { x: 80, y: 120 };
/** The existing canvas command API accepts at most 100 commands per transaction. */
export const CANVAS_LAYOUT_BATCH_SIZE = 100;

export type ArrangeNode = {
  id: string; x: number; y: number; width: number; height: number; locked: boolean;
};
type Relation = { source: string; target: string };

/** Group connected cards, then order sources before targets. Cycles break in spatial order. */
export function arrangeCanvas(nodes: ArrangeNode[], relations: Relation[]): Map<string, { x: number; y: number }> {
  const positions = new Map<string, { x: number; y: number }>();
  const movable = nodes.filter((node) => !node.locked);
  if (!movable.length) return positions;
  const byId = new Map(movable.map((node) => [node.id, node]));
  const compare = (a: ArrangeNode, b: ArrangeNode) => a.y - b.y || a.x - b.x || a.id.localeCompare(b.id);
  const sorted = [...movable].sort(compare);
  const outgoing = new Map(sorted.map((node) => [node.id, new Set<string>()]));
  const neighbours = new Map(sorted.map((node) => [node.id, new Set<string>()]));
  const incoming = new Map(sorted.map((node) => [node.id, 0]));
  for (const { source, target } of relations) {
    if (source === target || !byId.has(source) || !byId.has(target) || outgoing.get(source)?.has(target)) continue;
    outgoing.get(source)?.add(target);
    neighbours.get(source)?.add(target);
    neighbours.get(target)?.add(source);
    incoming.set(target, (incoming.get(target) ?? 0) + 1);
  }
  const visited = new Set<string>();
  const ordered: ArrangeNode[] = [];
  for (const root of sorted) {
    if (visited.has(root.id)) continue;
    const component = new Set<string>([root.id]);
    // Set iteration includes newly added members, so each component is traversed once.
    for (const id of component) {
      visited.add(id);
      for (const neighbour of neighbours.get(id) ?? []) component.add(neighbour);
    }
    const remaining = sorted.filter((node) => component.has(node.id));
    while (remaining.length) {
      const readyIndex = remaining.findIndex((node) => incoming.get(node.id) === 0);
      // A cycle has no zero-incoming node; emit the earliest card and continue deterministically.
      const [next] = remaining.splice(Math.max(0, readyIndex), 1);
      if (!next) break;
      ordered.push(next);
      for (const target of outgoing.get(next.id) ?? []) incoming.set(target, (incoming.get(target) ?? 0) - 1);
    }
  }
  const originX = Math.min(...movable.map((node) => node.x));
  // Keep fixed cards out of the new rows, including cards whose size differs from the defaults.
  const lockedBottom = nodes.filter((node) => node.locked).map((node) => node.y + node.height + CANVAS_ARRANGE_GAP.y);
  let rowY = Math.max(Math.min(...movable.map((node) => node.y)), ...lockedBottom);
  for (let index = 0; index < ordered.length; index += CANVAS_ARRANGE_COLUMNS) {
    const row = ordered.slice(index, index + CANVAS_ARRANGE_COLUMNS);
    let x = originX;
    for (const node of row) {
      positions.set(node.id, { x, y: rowY });
      x += node.width + CANVAS_ARRANGE_GAP.x;
    }
    rowY += Math.max(...row.map((node) => node.height)) + CANVAS_ARRANGE_GAP.y;
  }
  return positions;
}
