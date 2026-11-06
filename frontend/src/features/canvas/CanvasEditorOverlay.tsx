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
    const viewport = window.visualViewport;
    const controls = [...element.closest(".workspace-shell")?.querySelectorAll<HTMLElement>(
      ".workspace-header-identity, .workspace-header-actions, .workspace-tool-rail, .canvas-viewport-dock, .agent-chat-dock",
    ) ?? []];
    const place = () => {
      const bounds = canvas.getBoundingClientRect();
      if (!bounds.width) return;
      const top = Math.max(bounds.top + VIEWPORT_MARGIN, element.getBoundingClientRect().top);
      const availableHeight = Math.max(0, bounds.bottom - VIEWPORT_MARGIN - top);
      element.style.setProperty("--canvas-editor-available-width", `${Math.max(0, bounds.width - VIEWPORT_MARGIN * 2)}px`);
      element.style.setProperty("--canvas-editor-node-width", `${anchor?.getBoundingClientRect().width ?? 0}px`);
      element.style.setProperty("--canvas-editor-available-height", `${availableHeight}px`);
      const close = element.querySelector<HTMLElement>(".workspace-editor-content > .workspace-bottom-close");
      if (!close) return;
      const button = close.getBoundingClientRect();
      const [shiftX = 0, shiftY = 0] = close.style.translate.split(" ").map(value => parseFloat(value) || 0);
      const left = Math.max(bounds.left, viewport?.offsetLeft ?? 0) + VIEWPORT_MARGIN;
      const topLimit = Math.max(bounds.top, viewport?.offsetTop ?? 0) + VIEWPORT_MARGIN;
      const right = Math.min(bounds.right, viewport ? viewport.offsetLeft + viewport.width : window.innerWidth) - VIEWPORT_MARGIN;
      const bottom = Math.min(bounds.bottom, viewport ? viewport.offsetTop + viewport.height : window.innerHeight) - VIEWPORT_MARGIN;
      // Keep only the close action reachable when the node-anchored panel is clipped.
      // Remove its previous correction so repeated measurements never accumulate a shift.
      const naturalX = button.left - shiftX;
      const naturalY = button.top - shiftY;
      const x = Math.max(left, Math.min(naturalX, right - button.width));
      const y = Math.max(topLimit, Math.min(naturalY, bottom - button.height));
      // Fixed canvas controls sit above the editor. A button brought back from
      // below the viewport must also avoid the director launcher and navigation.
      const obstacles = controls.map(control => control.getBoundingClientRect()).filter(rect => rect.width && rect.height);
      const columns = [x, ...obstacles.flatMap(rect => [rect.left - VIEWPORT_MARGIN - button.width, rect.right + VIEWPORT_MARGIN])];
      const rows = [y, ...obstacles.flatMap(rect => [rect.top - VIEWPORT_MARGIN - button.height, rect.bottom + VIEWPORT_MARGIN])];
      // Combining both axes also finds gaps between an expanded director and navigation.
      const candidates = columns.flatMap(x => rows.map(y => ({ x, y })))
        .filter(point => point.x >= left && point.x + button.width <= right
        && point.y >= topLimit && point.y + button.height <= bottom
        && !obstacles.some(rect => point.x < rect.right && point.x + button.width > rect.left
          && point.y < rect.bottom && point.y + button.height > rect.top));
      const distance = (point: { x: number; y: number }) => (point.x - x) ** 2 + (point.y - y) ** 2;
      const position = candidates.reduce((nearest, point) => distance(point) < distance(nearest) ? point : nearest,
        candidates[0] ?? { x, y });
      close.style.translate = `${position.x - naturalX}px ${position.y - naturalY}px`;
    };
    place();
    const observer = new ResizeObserver(place);
    observer.observe(element);
    observer.observe(canvas);
    if (anchor) observer.observe(anchor);
    controls.forEach(control => observer.observe(control));
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, true);
    viewport?.addEventListener("resize", place);
    viewport?.addEventListener("scroll", place);
    return () => {
      observer.disconnect();
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, true);
      viewport?.removeEventListener("resize", place);
      viewport?.removeEventListener("scroll", place);
    };
  });
  return <div ref={surface} className="workspace-media-editor" aria-label={t("canvas.workspace.selectionEditor")}>
    <div className="workspace-editor-content">{children}</div>
  </div>;
}
