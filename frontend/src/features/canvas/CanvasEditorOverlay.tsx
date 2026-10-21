import { useStore } from "@xyflow/react";
import { useLayoutEffect, useRef, type ReactNode } from "react";
import { t, useLocale } from "../../shared/i18n";

const VIEWPORT_MARGIN = 16;
// The bottom strip belongs to the viewport dock and gesture hints, even while editing.
const VIEWPORT_BOTTOM_INSET = 80;
const ACTIONS_GAP = 10;

/** Keep the node-anchored, screen-sized editor reachable without moving the user's viewport.
 * Only the inner surface shifts: React Flow still owns the toolbar's anchor and geometry.
 */
export function CanvasEditorOverlay({ children }: { children: ReactNode }) {
  useLocale();
  useStore(state => state.transform);
  const surface = useRef<HTMLDivElement>(null);
  // Re-measure after renders as well: a draft aspect ratio can move the anchor without zooming.
  useLayoutEffect(() => {
    const element = surface.current;
    const canvas = element?.closest(".react-flow");
    if (!element || !canvas) return;
    // NodeToolbar portals share the selected node's data-id. Keep its existing actions mounted
    // and only correct their presentation when the editor would cover them.
    const nodeId = element.parentElement?.dataset.id;
    const actions = [...canvas.querySelectorAll<HTMLElement>(".artifact-actions-toolbar")]
      .find(toolbar => toolbar.dataset.id === nodeId);
    const anchor = [...canvas.querySelectorAll<HTMLElement>(".react-flow__node")]
      .find(node => node.dataset.id === nodeId);
    let shiftX = 0;
    let shiftY = 0;
    let actionsShiftX = 0;
    let actionsShiftY = 0;
    const place = () => {
      const bounds = canvas.getBoundingClientRect();
      if (!bounds.width) return;
      const actionsHeight = actions?.getBoundingClientRect().height ?? 0;
      const availableHeight = Math.max(0, bounds.height - VIEWPORT_MARGIN - VIEWPORT_BOTTOM_INSET
        - (actionsHeight ? actionsHeight + ACTIONS_GAP : 0));
      element.style.setProperty("--canvas-editor-available-width", `${Math.max(0, bounds.width - VIEWPORT_MARGIN * 2)}px`);
      element.style.setProperty("--canvas-editor-node-width", `${anchor?.getBoundingClientRect().width ?? 0}px`);
      element.style.setProperty("--canvas-editor-available-height", `${availableHeight}px`);
      const rect = element.getBoundingClientRect();
      if (!rect.width) return;
      const left = rect.left - shiftX;
      const top = rect.top - shiftY;
      shiftX = Math.max(bounds.left + VIEWPORT_MARGIN,
        Math.min(left, bounds.right - VIEWPORT_MARGIN - rect.width)) - left;
      shiftY = Math.max(bounds.top + VIEWPORT_MARGIN,
        Math.min(top, bounds.bottom - VIEWPORT_BOTTOM_INSET - rect.height)) - top;
      element.style.setProperty("--editor-shift-x", `${shiftX}px`);
      element.style.setProperty("--editor-shift-y", `${shiftY}px`);
      if (actions) {
        const actionRect = actions.getBoundingClientRect();
        const actionLeft = actionRect.left - actionsShiftX;
        const actionTop = actionRect.top - actionsShiftY;
        const editorTop = top + shiftY;
        const editorLeft = left + shiftX;
        const overlapsEditor = actionLeft < editorLeft + rect.width && actionLeft + actionRect.width > editorLeft
          && actionTop < editorTop + rect.height && actionTop + actionRect.height > editorTop;
        actionsShiftX = Math.max(bounds.left + VIEWPORT_MARGIN,
          Math.min(actionLeft, bounds.right - VIEWPORT_MARGIN - actionRect.width)) - actionLeft;
        actionsShiftY = Math.max(bounds.top + VIEWPORT_MARGIN,
          Math.min(actionTop, overlapsEditor ? editorTop - actionRect.height - ACTIONS_GAP
            : bounds.bottom - VIEWPORT_BOTTOM_INSET - actionRect.height)) - actionTop;
        actions.style.translate = `${actionsShiftX}px ${actionsShiftY}px`;
      }
    };
    // The element may already have a correction from a previous viewport render.
    element.style.setProperty("--editor-shift-x", "0px");
    element.style.setProperty("--editor-shift-y", "0px");
    if (actions) actions.style.translate = "none";
    place();
    const observer = new ResizeObserver(place);
    observer.observe(element);
    observer.observe(canvas);
    if (actions) observer.observe(actions);
    if (anchor) observer.observe(anchor);
    return () => {
      observer.disconnect();
      if (actions) actions.style.removeProperty("translate");
    };
  });
  return <div ref={surface} className="workspace-media-editor" aria-label={t("canvas.workspace.selectionEditor")}>
    <div className="workspace-editor-content">{children}</div>
  </div>;
}
