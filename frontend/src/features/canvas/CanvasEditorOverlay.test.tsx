import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { CanvasEditorOverlay } from "./CanvasEditorOverlay";

const viewport = vi.hoisted(() => ({ transform: [0, 0, 1] }));
vi.mock("@xyflow/react", () => ({ useStore: (select: (state: typeof viewport) => unknown) => select(viewport) }));

it("keeps the editor and actions anchored without edge avoidance and retains input on resize/zoom", async () => {
  let resized: ResizeObserverCallback | undefined;
  vi.stubGlobal("ResizeObserver", class {
    constructor(callback: ResizeObserverCallback) { resized = callback; }
    observe() { /* Driven explicitly below. */ }
    disconnect() { /* No live observer in jsdom. */ }
  });
  const bounds = { left: 0, top: 44, right: 1280, bottom: 720, width: 1280, height: 676 };
  let nodeWidth = 420;
  let natural = { left: 500, top: 650, width: 1248, height: 400 };
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    if (this.classList.contains("react-flow")) return DOMRect.fromRect({ x: bounds.left, y: bounds.top, ...bounds });
    if (this.classList.contains("react-flow__node")) return DOMRect.fromRect({ width: nodeWidth, height: 270 });
    if (this.classList.contains("workspace-media-editor")) {
      const x = natural.left + parseFloat(this.style.getPropertyValue("--editor-shift-x") || "0");
      const y = natural.top + parseFloat(this.style.getPropertyValue("--editor-shift-y") || "0");
      return DOMRect.fromRect({ x, y, width: natural.width, height: natural.height });
    }
    if (this.classList.contains("artifact-actions-toolbar")) {
      const [x = 0, y = 0] = this.style.translate.split(" ").map(value => parseFloat(value) || 0);
      return DOMRect.fromRect({ x: 800 + x, y: 400 + y, width: 400, height: 46 });
    }
    return DOMRect.fromRect();
  });
  const content = () => <div className="react-flow">
    <div className="react-flow__node" data-id="selected" />
    <div className="artifact-actions-toolbar" data-id="selected" aria-label="节点操作" />
    <div data-id="selected"><CanvasEditorOverlay><textarea aria-label="工作草稿" /></CanvasEditorOverlay></div>
  </div>;
  const view = render(content());
  const editor = screen.getByLabelText("所选卡片编辑区");
  expect(editor.style.getPropertyValue("--canvas-editor-available-width")).toBe("1248px");
  expect(editor.style.getPropertyValue("--canvas-editor-available-height")).toBe("54px");
  expect(editor.style.getPropertyValue("--canvas-editor-node-width")).toBe("420px");
  expect(editor.getBoundingClientRect().left).toBe(500);
  expect(editor.getBoundingClientRect().top).toBe(650);
  expect(screen.getByLabelText("节点操作").style.translate).toBe("");
  await userEvent.setup().type(screen.getByRole("textbox"), "保留这段输入");
  natural = { left: -200, top: -100, width: 1248, height: 400 };
  viewport.transform = [10, 20, 0.5];
  view.rerender(content());
  expect(editor.getBoundingClientRect().left).toBe(-200);
  expect(editor.getBoundingClientRect().top).toBe(-100);
  expect(screen.getByRole("textbox")).toHaveValue("保留这段输入");
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
  vi.unstubAllGlobals();
});
