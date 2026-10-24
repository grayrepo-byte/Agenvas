import { act, renderHook } from "@testing-library/react";
import type { ReactFlowInstance, Viewport } from "@xyflow/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useCanvasViewportMotion } from "./useCanvasViewportMotion";

function setup() {
  let current: Viewport = { x: 0, y: 0, zoom: 1 };
  // Cancelled native transitions can remain pending forever. Navigation must still work.
  const interrupted = new Promise<boolean>(() => {});
  const instance = {
    getViewport: vi.fn<ReactFlowInstance["getViewport"]>(() => current),
    setViewport: vi.fn<ReactFlowInstance["setViewport"]>().mockResolvedValue(true),
    setCenter: vi.fn<ReactFlowInstance["setCenter"]>().mockReturnValue(interrupted),
    fitView: vi.fn<ReactFlowInstance["fitView"]>().mockReturnValue(interrupted),
    zoomIn: vi.fn<ReactFlowInstance["zoomIn"]>().mockReturnValue(interrupted),
    zoomOut: vi.fn<ReactFlowInstance["zoomOut"]>().mockReturnValue(interrupted),
    zoomTo: vi.fn<ReactFlowInstance["zoomTo"]>().mockReturnValue(interrupted),
  };
  const element = document.createElement("section");
  const inner = document.createElement("div"); inner.className = "react-flow"; element.append(inner);
  vi.spyOn(inner, "getBoundingClientRect").mockReturnValue(new DOMRect(0, 0, 1000, 800));
  const flow = { current: instance }; const canvas = { current: element };
  const hook = renderHook(() => useCanvasViewportMotion(flow, canvas));
  return { ...hook, instance, moveCamera: (viewport: Viewport) => { current = viewport; } };
}

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals(); });

describe("canvas camera navigation", () => {
  it("gives a short, bounded eased focus without an implicit zoom excursion", () => {
    const { result, instance } = setup();
    act(() => result.current.center(900, 900, 0.7));
    const [x, y, options] = instance.setCenter.mock.calls[0]!;
    if (!options?.ease) throw new Error("Camera navigation requires an easing function");
    expect({ x, y }).toEqual({ x: 900, y: 900 });
    expect(options).toMatchObject({ zoom: 0.7, interpolate: "linear", ease: expect.any(Function) });
    expect(options.duration).toBeGreaterThanOrEqual(240); expect(options.duration).toBeLessThanOrEqual(420);
    const samples = [0, 0.2, 0.5, 0.8, 1].map(options.ease);
    expect(samples[0]).toBe(0); expect(samples.at(-1)).toBe(1);
    expect(samples).toEqual([...samples].sort((a, b) => a - b));
  });

  it("lets a new target take over from the visible camera while the old promise remains pending", () => {
    const { result, instance, moveCamera } = setup();
    act(() => result.current.center(900, 900, 1));
    moveCamera({ x: -140, y: -50, zoom: 1 });
    act(() => result.current.center(1700, 300, 1));
    expect(instance.setViewport).toHaveBeenCalledExactlyOnceWith({ x: -140, y: -50, zoom: 1 }, { duration: 0 });
    expect(instance.setViewport.mock.invocationCallOrder[0]).toBeLessThan(instance.setCenter.mock.invocationCallOrder[1]!);
    expect(instance.setCenter).toHaveBeenLastCalledWith(1700, 300, expect.objectContaining({ zoom: 1 }));
  });

  it("freezes an in-flight camera at its current position exactly once when input takes over", () => {
    const { result, instance, moveCamera } = setup();
    act(() => result.current.center(900, 900, 1));
    moveCamera({ x: -80, y: -60, zoom: 1 });
    act(() => { result.current.cancel(); result.current.cancel(); });
    act(() => vi.advanceTimersByTime(1000));
    expect(instance.setViewport).toHaveBeenCalledExactlyOnceWith({ x: -80, y: -60, zoom: 1 }, { duration: 0 });
    expect(instance.setCenter).toHaveBeenCalledTimes(1);
  });

  it("does not interrupt later gestures after the camera transition has finished", () => {
    const { result, instance } = setup();
    act(() => result.current.center(900, 900, 1));
    act(() => vi.advanceTimersByTime(1000));
    act(() => result.current.cancel());
    expect(instance.setViewport).not.toHaveBeenCalled();
  });

  it("skips motion for an already aligned target and for a live reduced-motion preference", () => {
    const preference = { matches: false };
    vi.stubGlobal("matchMedia", vi.fn(() => preference));
    const { result, instance } = setup();
    act(() => result.current.center(500, 400, 1));
    expect(instance.setCenter).toHaveBeenLastCalledWith(500, 400, expect.objectContaining({ duration: 0 }));
    preference.matches = true;
    act(() => result.current.center(1500, 1400, 1));
    expect(instance.setCenter).toHaveBeenLastCalledWith(1500, 1400, expect.objectContaining({ duration: 0 }));
    act(() => result.current.cancel()); expect(instance.setViewport).not.toHaveBeenCalled();
  });

  it("uses consistent overview and zoom transitions without waiting for their completion", () => {
    const { result, instance } = setup();
    act(() => result.current.fit({ nodes: [{ id: "visible" }], padding: 0.2, maxZoom: 1 }));
    expect(instance.fitView).toHaveBeenCalledWith(expect.objectContaining({
      nodes: [{ id: "visible" }], duration: 360, interpolate: "linear",
    }));
    act(() => result.current.zoomIn()); act(() => result.current.zoomOut()); act(() => result.current.zoomTo(1));
    expect(instance.zoomIn).toHaveBeenCalledWith(expect.objectContaining({ duration: 220, interpolate: "linear" }));
    expect(instance.zoomOut).toHaveBeenCalledWith(expect.objectContaining({ duration: 220, interpolate: "linear" }));
    expect(instance.zoomTo).toHaveBeenCalledWith(1, expect.objectContaining({ duration: 220, interpolate: "linear" }));
  });

  it("stops an in-flight transition when reduced motion is enabled without remounting", () => {
    const preference = Object.assign(new EventTarget(), { matches: false });
    vi.stubGlobal("matchMedia", vi.fn(() => preference));
    const { result, instance, moveCamera } = setup();
    act(() => result.current.center(900, 900, 1));
    moveCamera({ x: -100, y: -70, zoom: 1 });
    act(() => { preference.matches = true; preference.dispatchEvent(new Event("change")); });
    expect(instance.setViewport).toHaveBeenCalledExactlyOnceWith({ x: -100, y: -70, zoom: 1 }, { duration: 0 });
    act(() => result.current.fit({ padding: 0.2 }));
    expect(instance.fitView).toHaveBeenLastCalledWith(expect.objectContaining({ duration: 0 }));
  });

  it("cancels outstanding motion on window blur and unmount without waiting for native promises", () => {
    const { result, instance, unmount } = setup();
    act(() => result.current.center(900, 900, 1));
    act(() => window.dispatchEvent(new Event("blur")));
    expect(instance.setViewport).toHaveBeenCalledTimes(1);
    act(() => result.current.center(600, 400, 1));
    unmount();
    expect(instance.setViewport).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
  });
});
