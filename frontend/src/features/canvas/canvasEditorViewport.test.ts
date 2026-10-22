import { describe,expect,it } from "vitest";
import { calculateCanvasEditorViewport,type CanvasEditorViewportInput } from "./canvasEditorViewport";

const input = (patch: Partial<CanvasEditorViewportInput> = {}): CanvasEditorViewportInput => ({
  canvas: { width: 1280, height: 900 }, nodeSize: { width: 420, height: 270 },
  currentZoom: 1, minZoom: 0.1, editorHeight: 220, editorGap: 16,
  topToolbarSpace: 48, margin: 16, ...patch,
});

describe("calculateCanvasEditorViewport", () => {
  it("keeps the current zoom and geometric center when the editor fits below the node", () => {
    expect(calculateCanvasEditorViewport(input())).toEqual({ zoom: 1, centerOffsetY: 0 });
  });

  it("moves the node upward at 720px without zooming when that leaves room for the editor", () => {
    const options = input({ canvas: { width: 1280, height: 720 } });
    const result = calculateCanvasEditorViewport(options);
    expect(result).toEqual({ zoom: 1, centerOffsetY: 27 });
    const nodeCenterY = options.canvas.height / 2 - result.centerOffsetY * result.zoom;
    const editorBottom = nodeCenterY + options.nodeSize.height * result.zoom / 2
      + options.editorGap + options.editorHeight;
    expect(editorBottom).toBe(options.canvas.height - options.margin);
  });

  it("zooms a tall node out in a 600px canvas so the toolbar, node and editor all fit", () => {
    const options = input({ canvas: { width: 1280, height: 600 }, nodeSize: { width: 420, height: 600 } });
    const result = calculateCanvasEditorViewport(options);
    expect(result.zoom).toBeCloseTo(284 / 600);
    expect(result.centerOffsetY).toBeGreaterThan(0);
    const nodeCenterY = options.canvas.height / 2 - result.centerOffsetY * result.zoom;
    const halfNodeHeight = options.nodeSize.height * result.zoom / 2;
    expect(nodeCenterY - halfNodeHeight - options.topToolbarSpace).toBeCloseTo(options.margin);
    expect(nodeCenterY + halfNodeHeight + options.editorGap + options.editorHeight)
      .toBeCloseTo(options.canvas.height - options.margin);
  });

  it("accounts for node width when choosing the zoom", () => {
    const options = input({ canvas: { width: 400, height: 900 }, nodeSize: { width: 1000, height: 270 } });
    const result = calculateCanvasEditorViewport(options);
    expect(result.zoom).toBeCloseTo(0.368);
    expect(result.zoom * options.nodeSize.width).toBeCloseTo(options.canvas.width - options.margin * 2);
    expect(result.centerOffsetY).toBe(0);
  });

  it("preserves an already smaller zoom instead of enlarging the node", () => {
    expect(calculateCanvasEditorViewport(input({ currentZoom: 0.5 })))
      .toEqual({ zoom: 0.5, centerOffsetY: 0 });
  });

  it.each([
    { width: 1280, height: 220 },
    { width: 20, height: 220 },
  ])("stops at minZoom and never moves the node downward when space runs out: %o", (canvas) => {
    const result = calculateCanvasEditorViewport(input({ canvas, nodeSize: { width: 420, height: 600 }, minZoom: 0.4 }));
    expect(result).toEqual({ zoom: 0.4, centerOffsetY: 0 });
  });

  it("honors minZoom even when the remaining positive space cannot fit the full stack", () => {
    const result = calculateCanvasEditorViewport(input({ canvas: { width: 1280, height: 400 },
      nodeSize: { width: 420, height: 600 }, minZoom: 0.4 }));
    expect(result.zoom).toBe(0.4);
    expect(result.centerOffsetY).toBe(40);
  });
});
