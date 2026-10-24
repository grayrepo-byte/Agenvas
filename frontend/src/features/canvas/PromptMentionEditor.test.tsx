import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PromptMentionEditor } from "./PromptMentionEditor";

const MENTION = { versionId: "synthetic-image-version", role: "REFERENCE" } as const;
const REFERENCE = { ...MENTION, color: "#F15CAF", label: "Image 1" };

afterEach(() => {
  window.getSelection()?.removeAllRanges();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("structured prompt editing", () => {
  it.each([
    { context: "workspace overlay", workspace: true },
    { context: "standalone editor", workspace: false },
  ])("opens bottom-edge mention menus in the $context without losing their scroll anchor", ({ workspace }) => {
    vi.stubGlobal("innerHeight", 320);
    const { container } = render(<div className={workspace ? "workspace-media-editor" : undefined}>
      <div className="media-draft-editor">
        <PromptMentionEditor id="prompt" label="Prompt" placeholder="Enter prompt" maxLength={100}
          prompt="@" mentions={[]} references={[REFERENCE]} onChange={vi.fn()} />
      </div>
    </div>);
    const editor = screen.getByRole("textbox", { name: "Prompt" });
    const panel = editor.closest<HTMLElement>(".media-draft-editor")!;
    let editorBounds = new DOMRect(140, 210, 360, 100);
    let caretBounds = new DOMRect(190, 270, 0, 18);
    vi.spyOn(editor, "getBoundingClientRect").mockImplementation(() => editorBounds);
    vi.spyOn(container.firstElementChild!, "getBoundingClientRect")
      .mockReturnValue(new DOMRect(100, 100, 600, 200));
    const range = document.createRange();
    range.setStart(editor.firstChild!, 1);
    range.collapse(true);
    Object.defineProperty(range, "getBoundingClientRect", { value: () => caretBounds });
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);

    for (const scrollTop of [0, 40]) {
      panel.scrollTop = scrollTop;
      editorBounds = new DOMRect(140, 210 - scrollTop, 360, 100);
      caretBounds = new DOMRect(190, 270 - scrollTop, 0, 18);
      fireEvent.scroll(panel);
      fireEvent.input(editor);
      const menu = container.querySelector<HTMLElement>(".media-draft-mention-menu")!;
      if (workspace) {
        expect(menu).toHaveStyle({ left: "90px", top: `${162 - scrollTop}px`,
          transform: "translateY(-100%)", maxHeight: `${246 - scrollTop}px` });
        // Even a menu filled to its height limit fits above the caret inside the window.
        expect(100 + parseFloat(menu.style.top) - parseFloat(menu.style.maxHeight)).toBeGreaterThanOrEqual(0);
      } else {
        expect(menu).toHaveStyle({ left: "50px", top: "86px" });
        expect(menu.style.transform).toBe("");
        expect(menu.style.maxHeight).toBe("");
      }
      fireEvent.keyDown(editor, { key: "Escape" });
      expect(container.querySelector(".media-draft-mention-menu")).not.toBeInTheDocument();
    }
  });

  it.each([
    { context: "workspace overlay", workspace: true,
      initial: { left: "90px", top: "216px" }, scrolled: { left: "90px", top: "136px" } },
    { context: "standalone editor", workspace: false,
      initial: { left: "50px", top: "56px" }, scrolled: { left: "50px", top: "56px" } },
  ])("positions mention menus against the $context when opening after parent scrolling", ({ workspace, initial, scrolled }) => {
    const changed = vi.fn();
    const { container } = render(<div className={workspace ? "workspace-media-editor" : undefined}>
      <div className="media-draft-editor">
        <PromptMentionEditor id="prompt" label="Prompt" placeholder="Enter prompt" maxLength={100}
          prompt="@" mentions={[]} references={[REFERENCE]} onChange={changed} />
      </div>
    </div>);
    const editor = screen.getByRole("textbox", { name: "Prompt" });
    const panel = editor.closest<HTMLElement>(".media-draft-editor")!;
    let editorBounds = new DOMRect(140, 260, 360, 120);
    let caretBounds = new DOMRect(190, 290, 0, 18);
    vi.spyOn(editor, "getBoundingClientRect").mockImplementation(() => editorBounds);
    vi.spyOn(container.firstElementChild!, "getBoundingClientRect")
      .mockReturnValue(new DOMRect(100, 100, 600, 400));
    const range = document.createRange();
    range.setStart(editor.firstChild!, 1);
    range.collapse(true);
    Object.defineProperty(range, "getBoundingClientRect", { value: () => caretBounds });
    const selection = window.getSelection()!;
    selection.removeAllRanges();
    selection.addRange(range);
    fireEvent.input(editor);
    expect(container.querySelector(".media-draft-mention-menu")).toHaveStyle(initial);
    expect(screen.getByRole("option", { name: "Image 1 synthetic-image-version" })).toBeVisible();

    fireEvent.keyDown(editor, { key: "Escape" });
    expect(container.querySelector(".media-draft-mention-menu")).not.toBeInTheDocument();
    // Parent scrolling moves the editor and caret together; the screen-sized overlay stays fixed.
    panel.scrollTop = 80;
    editorBounds = new DOMRect(140, 180, 360, 120);
    caretBounds = new DOMRect(190, 210, 0, 18);
    fireEvent.scroll(panel);
    fireEvent.input(editor);
    expect(container.querySelector(".media-draft-mention-menu")).toHaveStyle(scrolled);
    expect(changed).toHaveBeenLastCalledWith("@", []);
  });

  it("keeps a locked prompt readable and ignores input events until unlocked", () => {
    const changed = vi.fn();
    const props = { id: "prompt", label: "Prompt", placeholder: "Enter prompt", maxLength: 100,
      prompt: "saved", mentions: [], references: [], onChange: changed };
    const { rerender } = render(<PromptMentionEditor {...props} readOnly />);
    const editor = screen.getByRole("textbox", { name: "Prompt" });
    expect(editor).toHaveAttribute("contenteditable", "false");
    expect(editor).toHaveTextContent("saved");
    fireEvent.input(editor);
    expect(changed).not.toHaveBeenCalled();
    rerender(<PromptMentionEditor {...props} readOnly={false} />);
    fireEvent.input(editor);
    expect(changed).toHaveBeenCalledWith("saved", []);
  });

  it("round-trips multiline Unicode text and repeated labels through the editable DOM", () => {
    const changed = vi.fn();
    const prompt = "🙂 first\n\uFFFC and \uFFFC\nlast";
    render(<PromptMentionEditor id="prompt" label="Prompt" placeholder="Enter prompt" maxLength={100}
      prompt={prompt} mentions={[MENTION, MENTION]} references={[REFERENCE]} onChange={changed} />);
    const editor = screen.getByRole("textbox", { name: "Prompt" });
    fireEvent.input(editor);
    expect(changed).toHaveBeenCalledWith(prompt, [MENTION, MENTION]);
  });

  it("restores the persisted prompt when input exceeds the supplied length limit", () => {
    const changed = vi.fn();
    render(<PromptMentionEditor id="prompt" label="Prompt" placeholder="Enter prompt" maxLength={5}
      prompt="saved" mentions={[]} references={[]} onChange={changed} />);
    const editor = screen.getByRole("textbox", { name: "Prompt" });
    editor.textContent = "too long";
    fireEvent.input(editor);
    expect(changed).not.toHaveBeenCalled();
    expect(editor).toHaveTextContent("saved");
  });
});
