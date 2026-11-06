import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { CanvasEditorOverlay } from "./CanvasEditorOverlay";

const viewport = vi.hoisted(() => ({ transform: [0, 0, 1] }));
vi.mock("@xyflow/react", () => ({ useStore: (select: (state: typeof viewport) => unknown) => select(viewport) }));

it("keeps the editor anchored while its close action stays visible on resize, zoom and scroll", async () => {
  let resized: ResizeObserverCallback | undefined;
  vi.stubGlobal("ResizeObserver", class {
    constructor(callback: ResizeObserverCallback) { resized = callback; }
    observe() { /* Driven explicitly below. */ }
    disconnect() { /* No live observer in jsdom. */ }
  });
  vi.stubGlobal("innerWidth", 1280);
  vi.stubGlobal("innerHeight", 720);
  const bounds = { left: 0, top: 44, right: 1280, bottom: 720, width: 1280, height: 676 };
  let nodeWidth = 420;
  let natural = { left: 500, top: 650, width: 1248, height: 400 };
  let dock = DOMRect.fromRect();
  let header = DOMRect.fromRect();
  let navigation = DOMRect.fromRect();
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    if (this.classList.contains("react-flow")) return DOMRect.fromRect({ x: bounds.left, y: bounds.top, ...bounds });
    if (this.classList.contains("react-flow__node")) return DOMRect.fromRect({ width: nodeWidth, height: 270 });
    if (this.classList.contains("agent-chat-dock")) return dock;
    if (this.classList.contains("workspace-header-actions")) return header;
    if (this.classList.contains("canvas-viewport-dock")) return navigation;
    if (this.classList.contains("workspace-media-editor")) {
      const x = natural.left + parseFloat(this.style.getPropertyValue("--editor-shift-x") || "0");
      const y = natural.top + parseFloat(this.style.getPropertyValue("--editor-shift-y") || "0");
      return DOMRect.fromRect({ x, y, width: natural.width, height: natural.height });
    }
    if (this.classList.contains("workspace-bottom-close")) {
      const [x = 0, y = 0] = this.style.translate.split(" ").map(value => parseFloat(value) || 0);
      return DOMRect.fromRect({ x: natural.left + natural.width - 36 + x, y: natural.top + 10 + y, width: 24, height: 24 });
    }
    if (this.classList.contains("artifact-actions-toolbar")) {
      const [x = 0, y = 0] = this.style.translate.split(" ").map(value => parseFloat(value) || 0);
      return DOMRect.fromRect({ x: 800 + x, y: 400 + y, width: 400, height: 46 });
    }
    return DOMRect.fromRect();
  });
  const content = () => <div className="workspace-shell"><div className="react-flow">
    <div className="react-flow__node" data-id="selected" />
    <div className="artifact-actions-toolbar" data-id="selected" aria-label="节点操作" />
    <div data-id="selected"><CanvasEditorOverlay>
      <button className="workspace-bottom-close" aria-label="关闭编辑区" />
      <textarea aria-label="工作草稿" />
    </CanvasEditorOverlay></div>
  </div><div className="agent-chat-dock" /><div className="workspace-header-actions" /><div className="canvas-viewport-dock" /></div>;
  const view = render(content());
  const editor = screen.getByLabelText("所选卡片编辑区");
  expect(editor.style.getPropertyValue("--canvas-editor-available-width")).toBe("1248px");
  expect(editor.style.getPropertyValue("--canvas-editor-available-height")).toBe("54px");
  expect(editor.style.getPropertyValue("--canvas-editor-node-width")).toBe("420px");
  expect(editor.getBoundingClientRect().left).toBe(500);
  expect(editor.getBoundingClientRect().top).toBe(650);
  const close = screen.getByRole("button", { name: "关闭编辑区" });
  expect(close.getBoundingClientRect().right).toBe(1264);
  expect(close.getBoundingClientRect().top).toBe(660);
  expect(screen.getByLabelText("节点操作").style.translate).toBe("");
  await userEvent.setup().type(screen.getByRole("textbox"), "保留这段输入");
  natural = { left: -200, top: -100, width: 1248, height: 400 };
  viewport.transform = [10, 20, 0.5];
  view.rerender(content());
  expect(editor.getBoundingClientRect().left).toBe(-200);
  expect(editor.getBoundingClientRect().top).toBe(-100);
  expect(close.getBoundingClientRect().top).toBe(60);
  expect(close.getBoundingClientRect().right).toBe(1036);
  expect(screen.getByRole("textbox")).toHaveValue("保留这段输入");
  // A window resize may clip the canvas without resizing the canvas element itself.
  vi.stubGlobal("innerWidth", 600);
  window.dispatchEvent(new Event("resize"));
  expect(close.getBoundingClientRect().right).toBe(584);
  vi.stubGlobal("innerHeight", 620);
  natural = { left: 80, top: 590, width: 500, height: 220 };
  window.dispatchEvent(new Event("scroll"));
  expect(close.getBoundingClientRect().bottom).toBe(604);
  expect(editor.getBoundingClientRect().top).toBe(590);
  natural = { left: 80, top: 100, width: 500, height: 220 };
  act(() => resized?.([], {} as ResizeObserver));
  expect(editor.getBoundingClientRect().left).toBe(80);
  expect(editor.getBoundingClientRect().top).toBe(100);
  bounds.width = 800;
  bounds.right = 800;
  bounds.height = 576;
  bounds.bottom = 620;
  nodeWidth = 760;
  act(() => resized?.([], {} as ResizeObserver));
  expect(editor.style.getPropertyValue("--canvas-editor-available-width")).toBe("768px");
  expect(editor.style.getPropertyValue("--canvas-editor-available-height")).toBe("504px");
  expect(editor.style.getPropertyValue("--canvas-editor-node-width")).toBe("760px");
  expect(editor.getBoundingClientRect().top).toBe(100);
  expect(screen.getByLabelText("节点操作").style.translate).toBe("");
  expect(screen.getByRole("textbox")).toHaveValue("保留这段输入");
  dock = DOMRect.fromRect({ x: 500, y: 100, width: 300, height: 520 });
  act(() => resized?.([], {} as ResizeObserver));
  expect(close.getBoundingClientRect().bottom).toBe(84);
  // A diagonal gap remains reachable when neither horizontal nor vertical
  // movement alone can avoid the expanded director and fixed navigation.
  header = DOMRect.fromRect({ x: 500, y: 0, width: 300, height: 100 });
  navigation = DOMRect.fromRect({ x: 0, y: 100, width: 500, height: 520 });
  act(() => resized?.([], {} as ResizeObserver));
  expect(close.getBoundingClientRect().right).toBe(484);
  expect(close.getBoundingClientRect().bottom).toBe(84);
  dock = DOMRect.fromRect();
  header = DOMRect.fromRect();
  navigation = DOMRect.fromRect();
  const visibleViewport = Object.assign(new EventTarget(), { offsetLeft: 40, offsetTop: 200, width: 400, height: 300 });
  vi.stubGlobal("visualViewport", visibleViewport);
  view.rerender(content());
  expect(close.getBoundingClientRect().right).toBe(424);
  expect(close.getBoundingClientRect().top).toBe(216);
  visibleViewport.width = 300;
  visibleViewport.dispatchEvent(new Event("resize"));
  expect(close.getBoundingClientRect().right).toBe(324);
  visibleViewport.offsetTop = 100;
  visibleViewport.dispatchEvent(new Event("scroll"));
  expect(close.getBoundingClientRect().top).toBe(116);
  view.unmount();
  vi.unstubAllGlobals();
});
