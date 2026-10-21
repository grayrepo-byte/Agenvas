import { act, render, screen } from "@testing-library/react";
import { useRef } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { usePromptContentHeight } from "./usePromptContentHeight";

type Kind = "textarea" | "contenteditable";
function Prompt({ kind, text }: { kind: Kind; text: string }) {
  const textarea = useRef<HTMLTextAreaElement>(null);
  const richText = useRef<HTMLDivElement>(null);
  usePromptContentHeight(kind === "textarea" ? textarea : richText);
  const style = { lineHeight: "24px", padding: "8px" };
  return <div className="media-draft-prompt-shell" data-testid="shell">
    {kind === "textarea" ? <textarea ref={textarea} rows={1} value={text} onChange={() => {}} style={style} />
      : <div ref={richText} role="textbox" contentEditable suppressContentEditableWarning style={style}>{text}</div>}
  </div>;
}
afterEach(() => vi.unstubAllGlobals());

it.each<Kind>(["textarea", "contenteditable"])("grows and shrinks %s with actual content without remounting it", kind => {
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(function (this: HTMLElement) {
    const text = this instanceof HTMLTextAreaElement ? this.value : this.textContent ?? "";
    return text.split("\n").length * 24 + 16;
  });
  const view = render(<Prompt kind={kind} text="一句提示词" />);
  const editor = screen.getByRole("textbox");
  editor.focus();
  const shell = screen.getByTestId("shell");
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("40px");
  view.rerender(<Prompt kind={kind} text={Array(20).fill("长提示词").join("\n")} />);
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("496px");
  expect(screen.getByRole("textbox")).toBe(editor);
  expect(editor).toHaveFocus();
  view.rerender(<Prompt kind={kind} text="删回短句" />);
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("40px");
  expect(editor).toHaveFocus();
});

it("remeasures wrapped lines on width changes and preserves the textarea's selection and scroll", () => {
  let width = 300;
  let onResize: ResizeObserverCallback | undefined;
  vi.stubGlobal("ResizeObserver", class {
    constructor(callback: ResizeObserverCallback) { onResize = callback; }
    observe() { /* Driven below. */ }
    disconnect() { /* No live browser observer. */ }
  });
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockImplementation(() => width);
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(() => width === 300 ? 160 : 64);
  render(<Prompt kind="textarea" text="保留光标和未保存的工作草稿" />);
  const editor = screen.getByRole<HTMLTextAreaElement>("textbox");
  editor.focus();
  editor.setSelectionRange(3, 7);
  editor.scrollTop = 24;
  expect(screen.getByTestId("shell").style.getPropertyValue("--prompt-content-height")).toBe("160px");
  width = 600;
  act(() => onResize?.([], {} as ResizeObserver));
  expect(screen.getByTestId("shell").style.getPropertyValue("--prompt-content-height")).toBe("64px");
  expect(editor.selectionStart).toBe(3);
  expect(editor.selectionEnd).toBe(7);
  expect(editor.scrollTop).toBe(24);
  expect(editor).toHaveFocus();
});
