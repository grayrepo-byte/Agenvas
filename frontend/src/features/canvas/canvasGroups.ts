import type { Edge } from "@xyflow/react";
import type { CSSProperties } from "react";

export const CANVAS_GROUP_NODE_TYPE = "canvasGroup" as const;
export const CANVAS_GROUP_INPUT_HANDLE = "group-input";
export const CANVAS_GROUP_OUTPUT_HANDLE = "group-output";
const GROUP_NODE_PREFIX = "canvas-group:";
export const CANVAS_GROUP_LAYOUT = {
  padding: 20,
  headerHeight: 48,
  // Member titles and the portalled action toolbar sit above the card's rectangle.
  memberChromeSpace: 72,
  minWidth: 280,
  collapsedWidth: 280,
  collapsedHeight: 64,
} as const;

export type CanvasGroupModel = {
  id: string;
  projectId: string;
  name: string;
  collapsed: boolean;
  version: number;
};
export type CanvasGroupMemberLayout = {
  id: string;
  groupId?: string | null;
  x: number;
  y: number;
  width: number;
  height: number;
};
export type CanvasGroupProjection = {
  group: CanvasGroupModel;
  id: string;
  position: { x: number; y: number };
  width: number;
  height: number;
  memberIds: string[];
};
export type CanvasGroupMemberPresentation = {
  groupId: string;
  collapsed: boolean;
  selectable: boolean;
  draggable: boolean;
  focusable: boolean;
  style: CSSProperties;
};

export function canvasGroupNodeId(groupId: string): string {
  return `${GROUP_NODE_PREFIX}${groupId}`;
}

export function canvasGroupIdFromNodeId(nodeId: string): string | null {
  return nodeId.startsWith(GROUP_NODE_PREFIX) && nodeId.length > GROUP_NODE_PREFIX.length
    ? nodeId.slice(GROUP_NODE_PREFIX.length) : null;
}

/** Absolute member coordinates remain the persisted layout. Callers overlay unsaved layout
 * drafts before projecting, so a group follows an in-progress drag without relative positions. */
export function projectCanvasGroups(groups: readonly CanvasGroupModel[], items: readonly CanvasGroupMemberLayout[]) {
  const groupedItems = new Map<string, CanvasGroupMemberLayout[]>();
  for (const item of items) {
    if (!item.groupId) continue;
    const members = groupedItems.get(item.groupId) ?? [];
    members.push(item);
    groupedItems.set(item.groupId, members);
  }
  const projected: CanvasGroupProjection[] = [];
  const members = new Map<string, CanvasGroupMemberPresentation>();
  const collapsedNodeByMemberId = new Map<string, string>();
  for (const group of groups) {
    const groupItems = groupedItems.get(group.id);
    // A stale group record with no cards must not create an unmovable phantom node.
    if (!groupItems?.length) continue;
    let left = Infinity; let top = Infinity; let right = -Infinity; let bottom = -Infinity;
    for (const item of groupItems) {
      left = Math.min(left, item.x); top = Math.min(top, item.y);
      right = Math.max(right, item.x + item.width); bottom = Math.max(bottom, item.y + item.height);
    }
    const id = canvasGroupNodeId(group.id);
    projected.push({
      group, id,
      position: { x: left - CANVAS_GROUP_LAYOUT.padding,
        y: top - CANVAS_GROUP_LAYOUT.headerHeight - CANVAS_GROUP_LAYOUT.padding - CANVAS_GROUP_LAYOUT.memberChromeSpace },
      width: group.collapsed ? CANVAS_GROUP_LAYOUT.collapsedWidth
        : Math.max(CANVAS_GROUP_LAYOUT.minWidth, right - left + CANVAS_GROUP_LAYOUT.padding * 2),
      height: group.collapsed ? CANVAS_GROUP_LAYOUT.collapsedHeight
        : bottom - top + CANVAS_GROUP_LAYOUT.headerHeight + CANVAS_GROUP_LAYOUT.padding * 2 + CANVAS_GROUP_LAYOUT.memberChromeSpace,
      memberIds: groupItems.map((item) => item.id),
    });
    for (const item of groupItems) {
      members.set(item.id, { groupId: group.id, collapsed: group.collapsed,
        selectable: !group.collapsed, draggable: !group.collapsed, focusable: !group.collapsed,
        // Do not set React Flow's `hidden`: that unmounts the editor and discards local text.
        style: group.collapsed ? { visibility: "hidden", pointerEvents: "none" } : {},
      });
      if (group.collapsed) collapsedNodeByMemberId.set(item.id, id);
    }
  }
  return { groups: projected, members, collapsedNodeByMemberId };
}

/** Fold only the display endpoints. Connection IDs and metadata continue to identify the
 * original persisted relation, including when several connections share the folded endpoints. */
export function remapCollapsedCanvasEdges<T extends Edge>(edges: readonly T[], collapsedNodeByMemberId: ReadonlyMap<string, string>): T[] {
  return edges.map((edge) => {
    const source = collapsedNodeByMemberId.get(edge.source);
    const target = collapsedNodeByMemberId.get(edge.target);
    if (!source && !target) return edge;
    return { ...edge, source: source ?? edge.source, target: target ?? edge.target,
      sourceHandle: source ? CANVAS_GROUP_OUTPUT_HANDLE : edge.sourceHandle,
      targetHandle: target ? CANVAS_GROUP_INPUT_HANDLE : edge.targetHandle,
      hidden: edge.hidden || Boolean(source && target && source === target),
    };
  });
}
