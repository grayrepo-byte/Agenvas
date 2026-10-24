export const CANVAS_EDITING_ACTION = {
  DUPLICATE: "duplicate", COPY: "copy", PASTE: "paste", UNDO: "undo", REDO: "redo", GROUP: "group", UNGROUP: "ungroup",
} as const;
export type CanvasEditingAction = typeof CANVAS_EDITING_ACTION[keyof typeof CANVAS_EDITING_ACTION];

const EDITABLE_TARGETS = "input, textarea, select, [contenteditable]:not([contenteditable='false']), [role='textbox']";
const OPEN_OVERLAYS = "[role='dialog'], [role='alertdialog'], [role='menu'], [role='listbox'], dialog[open]";
type ShortcutEvent = Pick<KeyboardEvent,
  "key" | "metaKey" | "ctrlKey" | "altKey" | "shiftKey" | "defaultPrevented" | "isComposing" | "repeat" | "target"> & {
    keyCode?: number;
  };

/** Overlay contents are usually portalled outside the canvas. Their focus scope owns the
 * keyboard even when an event happens to bubble from the previously focused canvas node. */
export function hasOpenCanvasEditingOverlay(document: Document): boolean {
  return Array.from(document.querySelectorAll<HTMLElement>(OPEN_OVERLAYS)).some((overlay) => {
    if (overlay.closest("[hidden], [inert], [aria-hidden='true'], [data-state='closed']")) return false;
    for (let element: HTMLElement | null = overlay; element; element = element.parentElement) {
      const style = document.defaultView?.getComputedStyle(element);
      if (style?.display === "none" || style?.visibility === "hidden") return false;
    }
    return true;
  });
}

/** Recognise the user's intent only; listeners decide availability and perform/prevent IO. */
export function resolveCanvasEditingShortcut(event: ShortcutEvent,
  options: { overlayOpen?: boolean } = {}): CanvasEditingAction | null {
  if (event.defaultPrevented || event.isComposing || event.keyCode === 229 || event.repeat || event.altKey
    || (!event.metaKey && !event.ctrlKey)) return null;
  const target = event.target instanceof Element ? event.target
    : event.target instanceof Node ? event.target.parentElement : null;
  if (target?.closest(EDITABLE_TARGETS)) return null;
  if (options.overlayOpen ?? hasOpenCanvasEditingOverlay(target?.ownerDocument ?? document)) return null;
  switch (event.key.toLowerCase()) {
    case "d": return event.shiftKey ? null : CANVAS_EDITING_ACTION.DUPLICATE;
    case "c": return event.shiftKey ? null : CANVAS_EDITING_ACTION.COPY;
    case "v": return event.shiftKey ? null : CANVAS_EDITING_ACTION.PASTE;
    case "z": return event.shiftKey ? CANVAS_EDITING_ACTION.REDO : CANVAS_EDITING_ACTION.UNDO;
    case "y": return event.ctrlKey && !event.metaKey && !event.shiftKey ? CANVAS_EDITING_ACTION.REDO : null;
    case "g": return event.shiftKey ? CANVAS_EDITING_ACTION.UNGROUP : CANVAS_EDITING_ACTION.GROUP;
    default: return null;
  }
}
