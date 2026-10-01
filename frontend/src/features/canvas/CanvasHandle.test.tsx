import { ReactFlow } from "@xyflow/react";
import { fireEvent, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CanvasHandle } from "./CanvasHandle";

class TestPointerEvent extends MouseEvent {
  readonly pointerType: string;

  constructor(type: string, init: PointerEventInit = {}) {
    super(type, init);
    this.pointerType = init.pointerType ?? "mouse";
  }
}

const NODE_TYPES = {
  handles: () => <>
    <CanvasHandle id="artifact-output" />
    <CanvasHandle id="artifact-input" />
    <CanvasHandle id="agent-input" />
    <CanvasHandle id="agent-output" />
  </>,
};

beforeEach(() => {
  vi.stubGlobal("PointerEvent", TestPointerEvent);
  vi.stubGlobal("matchMedia", vi.fn().mockReturnValue({ matches: false }));
});
afterEach(() => vi.unstubAllGlobals());

function showHandle(scale = 1, selector = ".canvas-handle--out") {
  const { container } = render(<div className="workspace-shell">
    <ReactFlow nodeTypes={NODE_TYPES} nodes={[{
      id: "card", type: "handles", position: { x: 0, y: 0 }, data: {}, selected: true,
    }]} />
  </div>);
  const handle = container.querySelector<HTMLDivElement>(selector)!;
  const home = handle.querySelector<HTMLSpanElement>(".canvas-handle-home")!;
  // jsdom 没有布局；只替换静止 home 的几何，保留真实 React Flow Handle 和指针事件。
  Object.defineProperty(home, "offsetWidth", { value: 22 });
  vi.spyOn(home, "getBoundingClientRect").mockReturnValue(new DOMRect(100, 100, 22 * scale, 22 * scale));
  function move(dx: number, dy: number, init: PointerEventInit = {}) {
    fireEvent.pointerMove(handle, {
      clientX: 100 + (11 + dx) * scale,
      clientY: 100 + (11 + dy) * scale,
      ...init,
    });
  }
  return { container, handle, move };
}

function offset(handle: HTMLElement) {
  return {
    x: parseFloat(handle.style.getPropertyValue("--canvas-handle-offset-x") || "0"),
    y: parseFloat(handle.style.getPropertyValue("--canvas-handle-offset-y") || "0"),
  };
}

describe("CanvasHandle hover motion", () => {
  it("follows the mouse gently and caps the total diagonal displacement at six canvas pixels", () => {
    const { handle, move } = showHandle();
    move(10, -4);
    expect(offset(handle)).toEqual({ x: 3.5, y: -1.4 });
    move(18, 18);
    const { x, y } = offset(handle);
    expect(x).toBeGreaterThan(0);
    expect(y).toBeGreaterThan(0);
    expect(Math.hypot(x, y)).toBeCloseTo(6);
    // 动效只写圆点偏移变量，不移动 React Flow 的几何盒。
    expect(handle.style.transform).toBe("");
  });

  it("returns home beyond the local hover radius", () => {
    const { handle, move } = showHandle();
    move(20, 0);
    expect(offset(handle).x).toBe(6);
    move(29, 0);
    expect(offset(handle)).toEqual({ x: 0, y: 0 });
  });

  it.each([0.5, 2])("keeps the same canvas displacement at zoom %s", (scale) => {
    const { handle, move } = showHandle(scale);
    move(20, 0);
    expect(offset(handle)).toEqual({ x: 6, y: 0 });
    move(29, 0);
    expect(offset(handle)).toEqual({ x: 0, y: 0 });
  });

  it.each(["pointerLeave", "pointerDown", "pointerCancel"] as const)(
    "returns home on %s", (event) => {
      const { handle, move } = showHandle();
      move(10, 0);
      fireEvent[event](handle);
      expect(offset(handle)).toEqual({ x: 0, y: 0 });
    });

  it("stays still while dragging and for touch input", () => {
    const { handle, move } = showHandle();
    move(10, 0);
    move(20, 0, { buttons: 1 });
    expect(offset(handle)).toEqual({ x: 0, y: 0 });
    move(20, 0, { pointerType: "touch" });
    expect(offset(handle)).toEqual({ x: 0, y: 0 });
  });

  it("respects reduced motion even if the mouse was already hovering", () => {
    const { handle, move } = showHandle();
    move(10, 0);
    vi.stubGlobal("matchMedia", vi.fn().mockReturnValue({ matches: true }));
    move(20, 0);
    expect(offset(handle)).toEqual({ x: 0, y: 0 });
  });

  it("keeps input targets still and leaves the Agent output anchor without a visible icon", () => {
    const { container, handle, move } = showHandle(1, ".canvas-handle--in");
    move(20, 0);
    expect(offset(handle)).toEqual({ x: 0, y: 0 });
    expect(container.querySelector(".canvas-handle--anchor svg")).toBeNull();
  });
});
