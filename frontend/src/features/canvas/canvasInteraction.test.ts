import { act, fireEvent, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SPACE_HOLD_THRESHOLD_MS, useCanvasInteraction } from "./canvasInteraction";

const space = { key: " ", code: "Space" };
afterEach(() => vi.restoreAllMocks());

describe("Space tap and hold", () => {
  it("persists hand mode after a tap and returns to selection with V", () => {
    vi.spyOn(performance, "now").mockReturnValue(0);
    const { result } = renderHook(useCanvasInteraction);
    fireEvent.keyDown(window, space);
    expect(result.current.selecting).toBe(false);
    fireEvent.keyUp(window, space);
    expect(result.current.tool).toBe("hand");
    expect(result.current.spaceHeld).toBe(false);
    fireEvent.keyDown(window, { key: "v" });
    expect(result.current.selecting).toBe(true);
  });

  it.each(["select", "hand"] as const)("restores %s after holding Space", (tool) => {
    const now = vi.spyOn(performance, "now").mockReturnValue(0);
    const { result } = renderHook(useCanvasInteraction);
    act(() => result.current.setTool(tool));
    fireEvent.keyDown(window, space);
    now.mockReturnValue(SPACE_HOLD_THRESHOLD_MS);
    fireEvent.keyUp(window, space);
    expect(result.current.tool).toBe(tool);
    expect(result.current.spaceHeld).toBe(false);
  });

  it("restores selection after a quick Space pointer gesture", () => {
    vi.spyOn(performance, "now").mockReturnValue(0);
    const { result } = renderHook(useCanvasInteraction);
    fireEvent.keyDown(window, space);
    fireEvent.pointerDown(window);
    fireEvent.keyUp(window, space);
    expect(result.current.selecting).toBe(true);
  });

  it("does not turn a canceled hold into a tap on late keyup", () => {
    vi.spyOn(performance, "now").mockReturnValue(0);
    const { result } = renderHook(useCanvasInteraction);
    fireEvent.keyDown(window, space);
    fireEvent.blur(window);
    fireEvent.keyUp(window, space);
    expect(result.current.selecting).toBe(true);
    fireEvent.keyDown(window, space);
    fireEvent.keyDown(window, { key: "v" });
    fireEvent.keyUp(window, space);
    expect(result.current.selecting).toBe(true);
  });

  it("ignores a tap originating in an input", () => {
    const { result } = renderHook(useCanvasInteraction);
    const input = document.createElement("textarea");
    document.body.append(input);
    fireEvent.keyDown(input, space);
    fireEvent.keyUp(input, space);
    expect(result.current.selecting).toBe(true);
    input.remove();
  });
});
