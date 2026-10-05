import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { canvasDisplayStorageKey, DEFAULT_CANVAS_DISPLAY_PREFERENCES, useCanvasDisplayPreferences } from "./useCanvasDisplayPreferences";

beforeEach(() => localStorage.clear());

describe("canvas display preference persistence", () => {
  it("restores both switches after remount without persisting selection or server entities", () => {
    const first = renderHook(() => useCanvasDisplayPreferences("owner", "project"));
    expect(first.result.current.preferences).toEqual(DEFAULT_CANVAS_DISPLAY_PREFERENCES);
    act(() => first.result.current.setPreference("alwaysShowConnections", false));
    act(() => first.result.current.setPreference("connectionFlowEnabled", false));
    first.unmount();
    const restored = renderHook(() => useCanvasDisplayPreferences("owner", "project"));
    expect(restored.result.current.preferences).toEqual({ alwaysShowConnections: false, connectionFlowEnabled: false });
    expect(JSON.parse(localStorage.getItem(canvasDisplayStorageKey("owner", "project"))!)).toEqual({
      version: 1, alwaysShowConnections: false, connectionFlowEnabled: false,
    });
  });

  it("loads the authenticated scope and keeps accounts and projects independent", () => {
    localStorage.setItem(canvasDisplayStorageKey("owner", "project"), JSON.stringify({
      version: 1, alwaysShowConnections: false, connectionFlowEnabled: false,
    }));
    const initialProps: { userId: string | undefined; projectId: string } = { userId: undefined, projectId: "project" };
    const hook = renderHook(({ userId, projectId }: typeof initialProps) =>
      useCanvasDisplayPreferences(userId, projectId), { initialProps });
    expect(hook.result.current.ready).toBe(false);
    hook.rerender({ userId: "owner", projectId: "project" });
    expect(hook.result.current.preferences.alwaysShowConnections).toBe(false);
    hook.rerender({ userId: "other-owner", projectId: "project" });
    expect(hook.result.current.preferences).toEqual(DEFAULT_CANVAS_DISPLAY_PREFERENCES);
    hook.rerender({ userId: "owner", projectId: "other-project" });
    expect(hook.result.current.preferences).toEqual(DEFAULT_CANVAS_DISPLAY_PREFERENCES);
    hook.rerender({ userId: "owner", projectId: "project" });
    expect(hook.result.current.preferences.connectionFlowEnabled).toBe(false);
  });

  it.each(["broken-json", "null", '{"version":2,"alwaysShowConnections":false}',
    '{"version":1,"alwaysShowConnections":"false","connectionFlowEnabled":0}'])(
    "safely handles invalid or unsupported stored values: %s", (saved) => {
    localStorage.setItem(canvasDisplayStorageKey("owner", "project"), saved);
    const hook = renderHook(() => useCanvasDisplayPreferences("owner", "project"));
    expect(hook.result.current.preferences).toEqual(DEFAULT_CANVAS_DISPLAY_PREFERENCES);
    });

  it("retains the current choice when saving is blocked and retries the same preferences", () => {
    const hook = renderHook(() => useCanvasDisplayPreferences("owner", "project"));
    const save = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException("Quota exceeded"); });
    act(() => hook.result.current.setPreference("alwaysShowConnections", false));
    expect(hook.result.current.preferences.alwaysShowConnections).toBe(false);
    expect(hook.result.current.persistenceError).toMatch(/未能保存/);
    save.mockRestore();
    act(() => hook.result.current.retrySave());
    expect(hook.result.current.persistenceError).toBeNull();
    expect(JSON.parse(localStorage.getItem(canvasDisplayStorageKey("owner", "project"))!).alwaysShowConnections).toBe(false);
  });

  it("handles a blocked read and recovers after storage is available", () => {
    const read = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new DOMException("Access denied"); });
    const hook = renderHook(() => useCanvasDisplayPreferences("owner", "project"));
    expect(hook.result.current.persistenceError).toMatch(/无法读取/);
    read.mockRestore();
    act(() => hook.result.current.setPreference("connectionFlowEnabled", false));
    expect(hook.result.current.persistenceError).toBeNull();
  });

  it("synchronizes another tab's change only for this scope, including storage clearing", () => {
    const key = canvasDisplayStorageKey("owner", "project");
    const hook = renderHook(() => useCanvasDisplayPreferences("owner", "project"));
    localStorage.setItem(key, JSON.stringify({ version: 1, alwaysShowConnections: false, connectionFlowEnabled: true }));
    act(() => window.dispatchEvent(new StorageEvent("storage", { key: "another-project" })));
    expect(hook.result.current.preferences.alwaysShowConnections).toBe(true);
    act(() => window.dispatchEvent(new StorageEvent("storage", { key })));
    expect(hook.result.current.preferences.alwaysShowConnections).toBe(false);
    localStorage.clear();
    act(() => window.dispatchEvent(new StorageEvent("storage", { key: null })));
    expect(hook.result.current.preferences).toEqual(DEFAULT_CANVAS_DISPLAY_PREFERENCES);
  });
});
