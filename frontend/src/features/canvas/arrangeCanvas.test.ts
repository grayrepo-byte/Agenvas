import { describe,expect,it } from "vitest";
import { arrangeCanvas,CANVAS_ARRANGE_GAP,type ArrangeNode } from "./arrangeCanvas";

const node = (id: string, patch: Partial<ArrangeNode> = {}): ArrangeNode =>
  ({ id, x: 20, y: 30, width: 280, height: 180, locked: false, ...patch });

describe("arrangeCanvas", () => {
  it("follows connected sources before targets even when the spatial order is reversed", () => {
    const nodes = [node("third"), node("second", { y: 200 }), node("first", { y: 300 })];
    const positions = arrangeCanvas(nodes, [{ source: "first", target: "second" }, { source: "second", target: "third" }]);
    expect([...positions.keys()]).toEqual(["first", "second", "third"]);
    expect(positions.get("first")).toEqual({ x: 20, y: 30 });
  });

  it("wraps a chain after five cards, with spacing based on each row's actual dimensions", () => {
    const nodes = Array.from({ length: 12 }, (_, index) => node(String(index), { x: index, width: 100 + index, height: 100 + index }));
    const edges = nodes.slice(1).map((n, index) => ({ source: String(index), target: n.id }));
    const positions = arrangeCanvas(nodes, edges);
    expect([...positions.values()].filter((p) => p.y === 30)).toHaveLength(5);
    expect(positions.get("1")?.x).toBe(100 + CANVAS_ARRANGE_GAP.x);
    expect(positions.get("5")).toEqual({ x: 0, y: 30 + 104 + CANVAS_ARRANGE_GAP.y });
    expect(positions.get("10")).toEqual({ x: 0, y: 30 + 104 + 109 + CANVAS_ARRANGE_GAP.y * 2 });
  });

  it("keeps disconnected chains together and respects both inputs of a merge", () => {
    const nodes = [node("a", { x: 0 }), node("independent", { x: 1 }), node("b", { x: 2 }), node("merge", { x: 3 })];
    const result = arrangeCanvas(nodes, [{ source: "a", target: "merge" }, { source: "b", target: "merge" }]);
    expect([...result.keys()]).toEqual(["a", "b", "merge", "independent"]);
  });

  it("places every cyclic card once and ignores duplicate, self and missing-endpoint edges", () => {
    const nodes = [node("a"), node("b")];
    const edges = [{ source: "a", target: "b" }, { source: "b", target: "a" },
      { source: "a", target: "b" }, { source: "a", target: "a" }, { source: "missing", target: "a" }];
    expect([...arrangeCanvas(nodes, edges).keys()]).toEqual(["a", "b"]);
    expect(arrangeCanvas(nodes, edges)).toEqual(arrangeCanvas([...nodes].reverse(), [...edges].reverse()));
  });

  it("keeps locked cards fixed and avoids placing new rows over them", () => {
    const result = arrangeCanvas([node("locked", { locked: true, y: 200, height: 800 }), node("free")], []);
    expect(result.has("locked")).toBe(false);
    expect(result.get("free")?.y).toBe(1000 + CANVAS_ARRANGE_GAP.y);
  });

  it("handles empty and entirely locked canvases", () => {
    expect(arrangeCanvas([], []).size).toBe(0);
    expect(arrangeCanvas([node("locked", { locked: true })], []).size).toBe(0);
  });
});
