import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { PromptMentionEditor } from "./PromptMentionEditor";

const MENTION = { versionId: "synthetic-image-version", role: "REFERENCE" } as const;
const REFERENCE = { ...MENTION, color: "#F15CAF", label: "Image 1" };

describe("structured prompt editing", () => {
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
