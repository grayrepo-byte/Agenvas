import { useCallback, useLayoutEffect, type RefObject } from "react";

/** Size the prompt's flex basis from its rendered contents, including wrapping and mention chips.
 * The panel owns the available-height limit, so measuring never moves the caret or rebuilds text.
 */
export function usePromptContentHeight(ref: RefObject<HTMLElement | null>) {
  const measure = useCallback(() => {
    const editor = ref.current;
    const shell = editor?.closest<HTMLElement>(".media-draft-prompt-shell");
    if (!editor || !shell) return;
    const scrollTop = editor.scrollTop;
    const height = editor.style.height;
    // A natural-height measurement also lets the panel shrink after text is deleted.
    editor.style.height = "auto";
    const style = getComputedStyle(editor);
    const lineHeight = parseFloat(style.lineHeight) || 0;
    const padding = (parseFloat(style.paddingTop) || 0) + (parseFloat(style.paddingBottom) || 0);
    const contentHeight = Math.ceil(Math.max(editor.scrollHeight, lineHeight + padding));
    shell.style.setProperty("--prompt-content-height", `${contentHeight}px`);
    editor.style.height = height;
    editor.scrollTop = scrollTop;
  }, [ref]);

  // Run after the editor's own layout effect has applied external text or mention changes.
  useLayoutEffect(measure);
  useLayoutEffect(() => {
    const editor = ref.current;
    if (!editor) return;
    let width = editor.clientWidth;
    const observer = new ResizeObserver(() => {
      if (editor.clientWidth === width) return;
      width = editor.clientWidth;
      measure();
    });
    observer.observe(editor);
    return () => observer.disconnect();
  }, [measure, ref]);
}
