import { afterEach, describe, expect, it } from "vitest";
import { hasOpenCanvasEditingOverlay, resolveCanvasEditingShortcut } from "./canvasEditingShortcuts";

function event(key: string, options: KeyboardEventInit = {}, target: EventTarget = document.body): KeyboardEvent {
  const result = new KeyboardEvent("keydown", { key, ctrlKey: true, bubbles: true, cancelable: true, ...options });
  target.dispatchEvent(result);
  return result;
}
afterEach(() => document.body.replaceChildren());

describe("canvas editing shortcuts", () => {
  it.each([
    ["d", {}, "duplicate"], ["c", {}, "copy"], ["v", {}, "paste"], ["z", {}, "undo"],
    ["Z", { shiftKey: true }, "redo"], ["y", {}, "redo"], ["g", {}, "group"], ["G", { shiftKey: true }, "ungroup"],
    ["d", { ctrlKey: false, metaKey: true }, "duplicate"], ["z", { ctrlKey: false, metaKey: true }, "undo"],
    ["Z", { ctrlKey: false, metaKey: true, shiftKey: true }, "redo"],
  ] as const)("recognises %s with %j", (key, options, expected) => {
    const keyEvent = event(key, options);
    expect(resolveCanvasEditingShortcut(keyEvent)).toBe(expected);
    expect(keyEvent.defaultPrevented).toBe(false);
  });

  it.each([
    ["d", { ctrlKey: false }], ["d", { altKey: true }], ["d", { repeat: true }],
    ["d", { isComposing: true }], ["c", { shiftKey: true }], ["v", { shiftKey: true }],
    ["d", { shiftKey: true }], ["y", { metaKey: true, ctrlKey: false }], ["y", { shiftKey: true }], ["x", {}],
  ] as const)("ignores unsupported modifier state for %s %j", (key, options) => {
    expect(resolveCanvasEditingShortcut(event(key, options))).toBeNull();
  });

  it("ignores handled events and the IME compatibility keycode", () => {
    const handled = event("z"); handled.preventDefault();
    expect(resolveCanvasEditingShortcut(handled)).toBeNull();
    const ime = event("z"); Object.defineProperty(ime, "keyCode", { value: 229 });
    expect(resolveCanvasEditingShortcut(ime)).toBeNull();
  });

  it.each(["input", "textarea", "select", "[contenteditable]", "[role='textbox']"])("leaves %s and descendants to their own editor", (selector) => {
    const element = selector.startsWith("[") ? document.createElement("div") : document.createElement(selector);
    if (selector === "[contenteditable]") element.setAttribute("contenteditable", "true");
    if (selector === "[role='textbox']") element.setAttribute("role", "textbox");
    document.body.append(element);
    expect(resolveCanvasEditingShortcut(event("z", {}, element))).toBeNull();
    const child = document.createElement("span"); element.append(child);
    expect(resolveCanvasEditingShortcut(event("g", {}, child))).toBeNull();
  });

  it("also recognises editable ancestors of a text node", () => {
    const editable = document.createElement("div"); editable.setAttribute("contenteditable", "true");
    const text = document.createTextNode("draft"); editable.append(text); document.body.append(editable);
    expect(resolveCanvasEditingShortcut(event("c", {}, text))).toBeNull();
  });

  it.each(["dialog", "alertdialog", "menu", "listbox"])("ignores shortcuts while a %s is open elsewhere", (role) => {
    const overlay = document.createElement("div"); overlay.setAttribute("role", role); document.body.append(overlay);
    expect(hasOpenCanvasEditingOverlay(document)).toBe(true);
    expect(resolveCanvasEditingShortcut(event("z"))).toBeNull();
  });

  it("ignores hidden/closed overlays and supports an explicit known overlay state", () => {
    document.body.innerHTML = `<div role="dialog" hidden></div><div aria-hidden="true"><div role="menu"></div></div>
      <div role="listbox" data-state="closed"></div><div role="menu" style="display:none"></div>
      <div style="display:none"><div role="menu"></div></div>`;
    expect(hasOpenCanvasEditingOverlay(document)).toBe(false);
    expect(resolveCanvasEditingShortcut(event("z"))).toBe("undo");
    expect(resolveCanvasEditingShortcut(event("z"), { overlayOpen: true })).toBeNull();
  });
});
