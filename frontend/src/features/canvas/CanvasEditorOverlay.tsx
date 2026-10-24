import { useStore } from "@xyflow/react";
import { useLayoutEffect, useRef, type ReactNode } from "react";
import { t, useLocale } from "../../shared/i18n";

const VIEWPORT_MARGIN = 16;

/** Size the screen-sized editor within the space below its node.
 * React Flow owns the anchor; shifting the surface into the viewport would cover the card.
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
    // NodeToolbar portals share the selected node's data-id.
    const nodeId = element.parentElement?.dataset.id;
    const anchor = [...canvas.querySelectorAll<HTMLElement>(".react-flow__node")]
      .find(node => node.dataset.id === nodeId);
    const place = () => {
      const bounds = canvas.getBoundingClientRect();
      if (!bounds.width) return;
      const top = Math.max(bounds.top + VIEWPORT_MARGIN, element.getBoundingClientRect().top);
      const availableHeight = Math.max(0, bounds.bottom - VIEWPORT_MARGIN - top);
      element.style.setProperty("--canvas-editor-available-width", `${Math.max(0, bounds.width - VIEWPORT_MARGIN * 2)}px`);
      element.style.setProperty("--canvas-editor-node-width", `${anchor?.getBoundingClientRect().width ?? 0}px`);
      element.style.setProperty("--canvas-editor-available-height", `${availableHeight}px`);
    };
    place();
    const observer = new ResizeObserver(place);
    observer.observe(element);
    observer.observe(canvas);
    if (anchor) observer.observe(anchor);
    return () => observer.disconnect();
  });
  return <div ref={surface} className="workspace-media-editor" aria-label={t("canvas.workspace.selectionEditor")}>
    <div className="workspace-editor-content">{children}</div>
  </div>;
}
