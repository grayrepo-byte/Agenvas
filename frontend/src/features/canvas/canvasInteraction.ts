import { useEffect, useState } from "react";

export type CanvasTool = "select" | "hand";
// Keep click tolerance and drag activation aligned: no displacement should fall between them.
export const CANVAS_POINTER_THRESHOLD = 3;
export const SPACE_HOLD_THRESHOLD_MS = 200;
const SHORTCUT_TARGETS = "input, textarea, select, [contenteditable]:not([contenteditable='false']), [role='textbox']";

export function useCanvasInteraction() {
  const [tool, setTool] = useState<CanvasTool>("select");
  const [spaceHeld, setSpaceHeld] = useState(false);
  useEffect(() => {
    let pressedAt: number | null = null;
    let usedAsGesture = false;
    const release = () => {
      pressedAt = null;
      usedAsGesture = false;
      setSpaceHeld(false);
    };
    const pointerDown = () => {
      // Even a quick Space + drag is temporary, rather than a tool-switch tap.
      if (pressedAt !== null) usedAsGesture = true;
    };
    const keyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.isComposing || event.metaKey || event.ctrlKey || event.altKey ||
        (event.target instanceof Element && event.target.closest(SHORTCUT_TARGETS))) return;
      if (event.code === "Space") {
        if (event.target instanceof Element && event.target.closest("button, a")) return;
        event.preventDefault();
        if (pressedAt === null && !event.repeat) {
          pressedAt = performance.now();
          setSpaceHeld(true);
        }
        if (event.repeat && pressedAt !== null) usedAsGesture = true;
      } else if (event.key.toLowerCase() === "v") {
        release();
        setTool("select");
      }
    };
    const keyUp = (event: KeyboardEvent) => {
      if (event.code !== "Space") return;
      if (pressedAt !== null && !usedAsGesture &&
        performance.now() - pressedAt < SPACE_HOLD_THRESHOLD_MS) setTool("hand");
      release();
    };
    const visibility = () => { if (document.hidden) release(); };
    window.addEventListener("pointerdown", pointerDown, true);
    window.addEventListener("keydown", keyDown);
    window.addEventListener("keyup", keyUp);
    window.addEventListener("blur", release);
    document.addEventListener("visibilitychange", visibility);
    return () => {
      window.removeEventListener("pointerdown", pointerDown, true);
      window.removeEventListener("keydown", keyDown);
      window.removeEventListener("keyup", keyUp);
      window.removeEventListener("blur", release);
      document.removeEventListener("visibilitychange", visibility);
    };
  }, []);
  return { tool, setTool, spaceHeld, selecting: tool === "select" && !spaceHeld };
}
