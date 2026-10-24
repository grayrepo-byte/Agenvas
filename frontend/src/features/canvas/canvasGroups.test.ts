import type { Edge } from "@xyflow/react";
import { describe, expect, it } from "vitest";
import { CANVAS_GROUP_INPUT_HANDLE, CANVAS_GROUP_LAYOUT, CANVAS_GROUP_OUTPUT_HANDLE, canvasGroupCreationPoint, canvasGroupDropTarget, canvasGroupIdFromNodeId,
  canvasGroupNodeId, projectCanvasGroups, remapCollapsedCanvasEdges, type CanvasGroupMemberLayout, type CanvasGroupModel } from "./canvasGroups";

function group(id: string, collapsed = false): CanvasGroupModel {
  return { id, name: id, projectId: "project", version: 0, collapsed };
}
const items: CanvasGroupMemberLayout[] = [
  { id: "first", groupId: "a", x: -200, y: 100, width: 320, height: 200 },
  { id: "second", groupId: "a", x: 300, y: 500, width: 400, height: 300 },
  { id: "third", groupId: "b", x: 1000, y: 200, width: 200, height: 200 },
  { id: "unassigned", groupId: null, x: 0, y: 0, width: 200, height: 200 },
];

describe("canvas group projection", () => {
  it("encloses the effective absolute member layout with space for a header and padding", () => {
    const projection = projectCanvasGroups([group("a"), group("b")], items);
    expect(projection.groups[0]).toEqual({ group: group("a"), id: canvasGroupNodeId("a"),
      position: { x: -220, y: -40 }, width: 940, height: 860, memberIds: ["first", "second"] });
    expect(projection.groups[1]?.width).toBe(CANVAS_GROUP_LAYOUT.minWidth);
    expect(projection.members.get("first")).toEqual({ groupId: "a", collapsed: false,
      selectable: true, draggable: true, focusable: true, style: {} });
    expect(projection.members.has("unassigned")).toBe(false);
    expect(items[0]?.x).toBe(-200);
  });

  it("retains member identities and positions while folding their presentation", () => {
    const projection = projectCanvasGroups([group("a", true)], items);
    expect(projection.groups[0]).toMatchObject({ position: { x: -220, y: -40 },
      width: CANVAS_GROUP_LAYOUT.collapsedWidth, height: CANVAS_GROUP_LAYOUT.collapsedHeight });
    expect(projection.members.get("first")).toEqual({ groupId: "a", collapsed: true,
      selectable: false, draggable: false, focusable: false,
      style: { visibility: "hidden", pointerEvents: "none" } });
    expect(projection.members.get("first")).not.toHaveProperty("hidden");
    expect([...projection.collapsedNodeByMemberId]).toEqual([
      ["first", canvasGroupNodeId("a")], ["second", canvasGroupNodeId("a")],
    ]);
    const moved = projectCanvasGroups([group("a", true)], items.map((item) => item.groupId === "a"
      ? { ...item, x: item.x + 150, y: item.y - 50 } : item));
    expect(moved.groups[0]?.position).toEqual({ x: -70, y: -90 });
  });

  it("ignores orphan membership and empty records without hiding cards", () => {
    const projection = projectCanvasGroups([group("empty")], items);
    expect(projection.groups).toEqual([]);
    expect(projection.members.size).toBe(0);
    expect(projection.collapsedNodeByMemberId.size).toBe(0);
  });

  it("uses a separate node identity namespace", () => {
    expect(canvasGroupIdFromNodeId(canvasGroupNodeId("a"))).toBe("a");
    expect(canvasGroupIdFromNodeId("a")).toBeNull();
    expect(canvasGroupIdFromNodeId("canvas-group:")).toBeNull();
  });
});

describe("group creation and drop targets", () => {
  const frames = projectCanvasGroups([group("a"), group("b")], items).groups;
  const moving = { id: "unassigned", groupId: null, x: 0, y: 200, width: 200, height: 200 };

  it("joins only when every moved node center enters the same expanded group", () => {
    expect(canvasGroupDropTarget(frames, [moving])?.group.id).toBe("a");
    expect(canvasGroupDropTarget(frames, [moving, { ...moving, id: "outside", x: 900 }])).toBeUndefined();
    expect(canvasGroupDropTarget(frames, [{ ...moving, x: -340 }])).toBeUndefined();
    expect(canvasGroupDropTarget(frames, [{ ...moving, groupId: "a" }])).toBeUndefined();
    expect(canvasGroupDropTarget(frames, [])).toBeUndefined();
  });

  it("excludes folded groups and prefers the smaller frame when expanded frames overlap", () => {
    const small = { ...frames[0]!, group: group("small"), id: canvasGroupNodeId("small"), width: 500, height: 500 };
    expect(canvasGroupDropTarget([...frames, small], [moving])?.group.id).toBe("small");
    expect(canvasGroupDropTarget([{ ...small, group: group("small", true) }], [moving])).toBeUndefined();
    expect(canvasGroupDropTarget(frames, [{ ...moving, groupId: "other" }])?.group.id).toBe("a");
  });

  it("places a new member below effective card bounds with room for its title and toolbar", () => {
    expect(canvasGroupCreationPoint(items.filter((item) => item.groupId === "a"))).toEqual({ x: -200, y: 892 });
    expect(canvasGroupCreationPoint([])).toBeUndefined();
  });
});

describe("folded group connections", () => {
  const edges: Edge[] = [
    { id: "internal", source: "first", target: "second", sourceHandle: "out", targetHandle: "in", data: { version: 2 } },
    { id: "outgoing", source: "first", target: "third", sourceHandle: "out", targetHandle: "in", selected: true },
    { id: "incoming", source: "unassigned", target: "second", sourceHandle: "out", targetHandle: "in" },
    { id: "parallel", source: "second", target: "third" },
    { id: "unchanged", source: "third", target: "unassigned" },
    { id: "already-hidden", source: "first", target: "third", hidden: true },
  ];

  it("folds the view only, hides internal lines, and preserves parallel connection identities", () => {
    const projection = projectCanvasGroups([group("a", true)], items);
    const folded = remapCollapsedCanvasEdges(edges, projection.collapsedNodeByMemberId);
    expect(folded.map((edge) => edge.id)).toEqual(edges.map((edge) => edge.id));
    expect(folded[0]).toMatchObject({ source: canvasGroupNodeId("a"), target: canvasGroupNodeId("a"),
      sourceHandle: CANVAS_GROUP_OUTPUT_HANDLE, targetHandle: CANVAS_GROUP_INPUT_HANDLE, hidden: true,
      data: { version: 2 } });
    expect(folded[1]).toMatchObject({ source: canvasGroupNodeId("a"), target: "third", sourceHandle: CANVAS_GROUP_OUTPUT_HANDLE,
      targetHandle: "in", hidden: false, selected: true });
    expect(folded[2]).toMatchObject({ source: "unassigned", target: canvasGroupNodeId("a"), sourceHandle: "out", targetHandle: CANVAS_GROUP_INPUT_HANDLE });
    expect(folded[3]).toMatchObject({ id: "parallel", source: canvasGroupNodeId("a"), target: "third" });
    expect(folded[4]).toBe(edges[4]);
    expect(folded[5]?.hidden).toBe(true);
    expect(edges[0]?.source).toBe("first");
  });

  it("shows relations between two folded groups and restores original endpoints on expand", () => {
    const collapsed = projectCanvasGroups([group("a", true), group("b", true)], items);
    expect(remapCollapsedCanvasEdges(edges, collapsed.collapsedNodeByMemberId)[1]).toMatchObject({
      source: canvasGroupNodeId("a"), target: canvasGroupNodeId("b"), hidden: false,
    });
    const expanded = projectCanvasGroups([group("a"), group("b")], items);
    expect(remapCollapsedCanvasEdges(edges, expanded.collapsedNodeByMemberId)).toEqual(edges);
  });
});
