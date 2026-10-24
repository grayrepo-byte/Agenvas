import { act, render, screen } from "@testing-library/react";
import { useRef } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { usePromptContentHeight } from "./usePromptContentHeight";

type Kind = "textarea" | "contenteditable";
function Prompt({ kind, text, minimumRows }: { kind: Kind; text: string; minimumRows?: number }) {
  const textarea = useRef<HTMLTextAreaElement>(null);
  const richText = useRef<HTMLDivElement>(null);
  usePromptContentHeight(kind === "textarea" ? textarea : richText, minimumRows);
  const style = { lineHeight: "24px", padding: "8px" };
  return <div className="media-draft-prompt-shell" data-testid="shell">
    {kind === "textarea" ? <textarea ref={textarea} rows={1} value={text} onChange={() => {}} style={style} />
      : <div ref={richText} role="textbox" contentEditable suppressContentEditableWarning style={style}>{text}</div>}
  </div>;
}
afterEach(() => {
  window.getSelection()?.removeAllRanges();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it.each<Kind>(["textarea", "contenteditable"])("reserves four lines for empty and short %s prompts, grows, and shrinks back to that minimum", kind => {
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(function (this: HTMLElement) {
    const text = this instanceof HTMLTextAreaElement ? this.value : this.textContent ?? "";
    return text.split("\n").length * 24 + 16;
  });
  const view = render(<Prompt kind={kind} text="" minimumRows={4} />);
  const editor = screen.getByRole("textbox");
  const shell = screen.getByTestId("shell");
  editor.focus();
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("112px");
  expect(shell.style.getPropertyValue("--prompt-min-height")).toBe("112px");
  view.rerender(<Prompt kind={kind} text="一句短提示词" minimumRows={4} />);
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("112px");
  view.rerender(<Prompt kind={kind} text={Array(20).fill("长提示词").join("\n")} minimumRows={4} />);
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("496px");
  expect(shell.style.getPropertyValue("--prompt-min-height")).toBe("112px");
  view.rerender(<Prompt kind={kind} text="删回短句" minimumRows={4} />);
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("112px");
  expect(screen.getByRole("textbox")).toBe(editor);
  expect(editor).toHaveFocus();
  view.rerender(<Prompt kind={kind} text="删回短句" minimumRows={2} />);
  expect(shell.style.getPropertyValue("--prompt-min-height")).toBe("64px");
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("64px");
});

it.each<Kind>(["textarea", "contenteditable"])("grows and shrinks %s with actual content without remounting it", kind => {
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(function (this: HTMLElement) {
    const text = this instanceof HTMLTextAreaElement ? this.value : this.textContent ?? "";
    return text.split("\n").length * 24 + 16;
  });
  const view = render(<Prompt kind={kind} text="一句提示词" />);
  const editor = screen.getByRole("textbox");
  editor.focus();
  const shell = screen.getByTestId("shell");
  expect(shell.style.getPropertyValue("--prompt-min-height")).toBe("40px");
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("40px");
  view.rerender(<Prompt kind={kind} text={Array(20).fill("长提示词").join("\n")} />);
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("496px");
  expect(screen.getByRole("textbox")).toBe(editor);
  expect(editor).toHaveFocus();
  view.rerender(<Prompt kind={kind} text="删回短句" />);
  expect(shell.style.getPropertyValue("--prompt-content-height")).toBe("40px");
  expect(editor).toHaveFocus();
});

it.each([
  { kind: "textarea", minimumRows: undefined, expectedMinimum: "40px", expectedWrappedHeight: "64px" },
  { kind: "textarea", minimumRows: 4, expectedMinimum: "112px", expectedWrappedHeight: "112px" },
  { kind: "contenteditable", minimumRows: undefined, expectedMinimum: "40px", expectedWrappedHeight: "64px" },
  { kind: "contenteditable", minimumRows: 4, expectedMinimum: "112px", expectedWrappedHeight: "112px" },
] as const)("remeasures wrapped $kind lines with minimumRows=$minimumRows and preserves selection and scroll", ({ kind, minimumRows, expectedMinimum, expectedWrappedHeight }) => {
  let width = 300;
  let onResize: ResizeObserverCallback | undefined;
  vi.stubGlobal("ResizeObserver", class {
    constructor(callback: ResizeObserverCallback) { onResize = callback; }
    observe() { /* Driven below. */ }
    disconnect() { /* No live browser observer. */ }
  });
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockImplementation(() => width);
  vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(() => width === 300 ? 160 : 64);
  render(<Prompt kind={kind} text="保留光标和未保存的工作草稿" minimumRows={minimumRows} />);
  const editor = screen.getByRole("textbox");
  editor.focus();
  if (editor instanceof HTMLTextAreaElement) editor.setSelectionRange(3, 7);
  else {
    const range = document.createRange();
    range.setStart(editor.firstChild!, 3);
    range.setEnd(editor.firstChild!, 7);
    window.getSelection()!.removeAllRanges();
    window.getSelection()!.addRange(range);
  }
  editor.scrollTop = 24;
  expect(screen.getByTestId("shell").style.getPropertyValue("--prompt-content-height")).toBe("160px");
  expect(screen.getByTestId("shell").style.getPropertyValue("--prompt-min-height")).toBe(expectedMinimum);
  width = 600;
  act(() => onResize?.([], {} as ResizeObserver));
  expect(screen.getByTestId("shell").style.getPropertyValue("--prompt-content-height")).toBe(expectedWrappedHeight);
  if (editor instanceof HTMLTextAreaElement) {
    expect(editor.selectionStart).toBe(3);
    expect(editor.selectionEnd).toBe(7);
  } else {
    const selection = window.getSelection()!.getRangeAt(0);
    expect(selection.startContainer).toBe(editor.firstChild);
    expect(selection.startOffset).toBe(3);
    expect(selection.endOffset).toBe(7);
  }
  expect(editor.scrollTop).toBe(24);
  expect(editor).toHaveFocus();
});
