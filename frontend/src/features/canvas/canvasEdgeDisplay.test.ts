import type { Edge } from "@xyflow/react";
import { describe, expect, it } from "vitest";
import { displayCanvasRelations } from "./canvasEdgeDisplay";
import { DEFAULT_CANVAS_DISPLAY_PREFERENCES } from "./useCanvasDisplayPreferences";

const edges: Edge[] = [
  { id: "upstream", source: "parent", target: "node", className: "relation-edge--input-binding-historical" },
  { id: "downstream", source: "node", target: "child", selectable: false, deletable: false },
  { id: "indirect", source: "child", target: "grandchild" },
  { id: "unrelated", source: "other-parent", target: "other-child" },
];
const onSelection = { ...DEFAULT_CANVAS_DISPLAY_PREFERENCES, alwaysShowConnections: false };
const visible = (displayed: Edge[]) => displayed.filter((edge) => !edge.hidden).map((edge) => edge.id);

describe("canvas relation display", () => {
  it("keeps all relation types and their deletion semantics visible by default", () => {
    const displayed = displayCanvasRelations(edges, [], [], DEFAULT_CANVAS_DISPLAY_PREFERENCES);
    expect(visible(displayed)).toEqual(edges.map((edge) => edge.id));
    expect(displayed[0]?.className).toBe("relation-edge--input-binding-historical");
    expect(displayed[1]).toMatchObject({ source: "node", target: "child", selectable: false, deletable: false });
  });

  it("shows just the immediate incoming and outgoing lines of selected nodes, then hides them on deselection", () => {
    expect(visible(displayCanvasRelations(edges, [], [], onSelection))).toEqual([]);
    expect(visible(displayCanvasRelations(edges, ["node"], [], onSelection))).toEqual(["upstream", "downstream"]);
    expect(visible(displayCanvasRelations(edges, ["node", "other-child"], [], onSelection)))
      .toEqual(["upstream", "downstream", "unrelated"]);
    expect(visible(displayCanvasRelations(edges, [], [], onSelection))).toEqual([]);
  });

  it("keeps a selected line available for deletion when its node is deselected", () => {
    const displayed = displayCanvasRelations(edges, [], ["upstream"], onSelection);
    expect(visible(displayed)).toEqual(["upstream"]);
    expect(displayed[0]?.selected).toBe(true);
  });

  it("disables motion independently while preserving direction, static lines and server projection", () => {
    const displayed = displayCanvasRelations(edges, [], [], { ...DEFAULT_CANVAS_DISPLAY_PREFERENCES, connectionFlowEnabled: false });
    expect(visible(displayed)).toHaveLength(edges.length);
    expect(displayed.every((edge) => edge.data?.connectionFlowEnabled === false)).toBe(true);
    expect(displayed.map(({ source, target }) => ({ source, target })))
      .toEqual(edges.map(({ source, target }) => ({ source, target })));
    expect(edges.every((edge) => edge.hidden === undefined && edge.data === undefined)).toBe(true);
  });
});
