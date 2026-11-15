import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { copyText } from "./clipboard";

let clipboard: PropertyDescriptor | undefined;
let execCommand: PropertyDescriptor | undefined;
beforeEach(() => {
  clipboard = Object.getOwnPropertyDescriptor(navigator, "clipboard");
  execCommand = Object.getOwnPropertyDescriptor(document, "execCommand");
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: undefined });
});
afterEach(() => {
  if (clipboard) Object.defineProperty(navigator, "clipboard", clipboard);
  else Reflect.deleteProperty(navigator, "clipboard");
  if (execCommand) Object.defineProperty(document, "execCommand", execCommand);
  else Reflect.deleteProperty(document, "execCommand");
  document.body.replaceChildren();
});

it("copies the complete multiline value on HTTP and restores an input's focus, selection and content", async () => {
  const input = document.createElement("textarea");
  input.value = "Keep this unsaved input";
  document.body.append(input);
  input.focus();
  input.setSelectionRange(5, 9, "backward");
  const text = "合成内容\n" + "x".repeat(100_000);
  let temporary: HTMLTextAreaElement | undefined;
  const copy = vi.fn(() => {
    const active = document.activeElement;
    expect(active).toBeInstanceOf(HTMLTextAreaElement);
    if (!(active instanceof HTMLTextAreaElement)) return false;
    temporary = active;
    expect(active.value.slice(active.selectionStart, active.selectionEnd)).toBe(text);
    return true;
  });
  Object.defineProperty(document, "execCommand", { configurable: true, value: copy });
  await copyText(text);
  expect(copy).toHaveBeenCalledWith("copy");
  expect(input).toHaveFocus();
  expect(input.value).toBe("Keep this unsaved input");
  expect([input.selectionStart, input.selectionEnd, input.selectionDirection]).toEqual([5, 9, "backward"]);
  expect(temporary?.isConnected).toBe(false);
  expect(temporary?.value).toBe("");
  expect(document.querySelectorAll("textarea")).toHaveLength(1);
});

it("restores document text selection after copying", async () => {
  const paragraph = document.createElement("p");
  paragraph.textContent = "Keep the original selected text";
  document.body.append(paragraph);
  const range = document.createRange();
  range.selectNodeContents(paragraph);
  document.getSelection()?.removeAllRanges();
  document.getSelection()?.addRange(range);
  expect(document.getSelection()?.toString()).toBe(paragraph.textContent);
  Object.defineProperty(document, "execCommand", { configurable: true, value: () => true });
  await copyText("Other synthetic content");
  expect(document.getSelection()?.toString()).toBe(paragraph.textContent);
});

it("prefers an available native Clipboard API", async () => {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
  const copy = vi.fn();
  Object.defineProperty(document, "execCommand", { configurable: true, value: copy });
  await copyText("Synthetic content");
  expect(writeText).toHaveBeenCalledWith("Synthetic content");
  expect(copy).not.toHaveBeenCalled();
  expect(document.querySelector("textarea")).toBeNull();
});

it("falls back when an available Clipboard API rejects the write", async () => {
  const writeText = vi.fn().mockRejectedValue(new DOMException("Synthetic denial", "NotAllowedError"));
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
  const copy = vi.fn(() => true);
  Object.defineProperty(document, "execCommand", { configurable: true, value: copy });
  await copyText("Synthetic content");
  expect(writeText).toHaveBeenCalledOnce();
  expect(copy).toHaveBeenCalledWith("copy");
});

it.each(["missing", "false", "throws"])("reports a %s fallback and removes temporary copy content", async mode => {
  const button = document.createElement("button");
  document.body.append(button);
  button.focus();
  Object.defineProperty(document, "execCommand", {
    configurable: true,
    value:
      mode === "missing"
        ? undefined
        : () => {
            if (mode === "throws") throw new Error("Synthetic copy failure");
            return false;
          },
  });
  await expect(copyText("Synthetic private content")).rejects.toThrow();
  expect(button).toHaveFocus();
  expect(document.querySelector("textarea")).toBeNull();
  expect(document.body.textContent).not.toContain("Synthetic private content");
});
