import { describe, expect, it } from "vitest";
import { MENTION_MARKER, promptForMediaInputs, removePromptReferences } from "./mediaPrompt";

const IMAGE_MENTION = { versionId: "image-version", role: "REFERENCE" } as const;
const AUDIO_MENTION = { versionId: "audio-version", role: "AUDIO_REFERENCE" } as const;
const OTHER_MENTION = { versionId: "other-version", role: "REFERENCE" } as const;
const IMAGE_INPUT = { ...IMAGE_MENTION, color: "#F15CAF" };
const AUDIO_INPUT = { ...AUDIO_MENTION, color: "#67C7F3" };
const OTHER_INPUT = { ...OTHER_MENTION, color: "#8DD17E" };

describe("structured media prompts", () => {
  it("removes multiple versions and repeated mentions while preserving surrounding Unicode text", () => {
    expect(removePromptReferences(`🙂${MENTION_MARKER} / ${MENTION_MARKER} / ${MENTION_MARKER} / ${MENTION_MARKER}!`,
      [IMAGE_MENTION, AUDIO_MENTION, IMAGE_MENTION, OTHER_MENTION], "image-version", "audio-version"))
      .toEqual({ prompt: `🙂 /  /  / ${MENTION_MARKER}!`, mentions: [OTHER_MENTION] });
  });

  it("prunes removed audio and updates each surviving image mention when changing to frames", () => {
    expect(promptForMediaInputs({ prompt: `${MENTION_MARKER} -> ${MENTION_MARKER} + ${MENTION_MARKER}`,
      mentions: [AUDIO_MENTION, IMAGE_MENTION, IMAGE_MENTION], mediaInputs: [IMAGE_INPUT, AUDIO_INPUT] },
    [{ ...IMAGE_INPUT, role: "START_FRAME" }])).toEqual({
      prompt: ` -> ${MENTION_MARKER} + ${MENTION_MARKER}`,
      mentions: [{ ...IMAGE_MENTION, role: "START_FRAME" }, { ...IMAGE_MENTION, role: "START_FRAME" }],
    });
  });

  it("reordering references leaves text and exact-version mention identity unchanged", () => {
    const fields = { prompt: `${MENTION_MARKER} then ${MENTION_MARKER}`,
      mentions: [IMAGE_MENTION, OTHER_MENTION], mediaInputs: [IMAGE_INPUT, OTHER_INPUT] };
    expect(promptForMediaInputs(fields, [OTHER_INPUT, IMAGE_INPUT])).toEqual({
      prompt: fields.prompt, mentions: fields.mentions,
    });
    expect(fields.mentions).toEqual([IMAGE_MENTION, OTHER_MENTION]);
  });

  it("removes all structured labels when all inputs are cleared", () => {
    expect(promptForMediaInputs({ prompt: `Keep text ${MENTION_MARKER}.`, mentions: [IMAGE_MENTION],
      mediaInputs: [IMAGE_INPUT] }, [])).toEqual({ prompt: "Keep text .", mentions: [] });
  });
});
