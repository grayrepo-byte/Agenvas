import { describe, expect, it } from "vitest";
import { decodeOptimizationPrompt, encodeOptimizationPrompt } from "./videoPromptOptimization";

const mentions = [{ versionId: "start-v1", role: "START_FRAME" as const }, { versionId: "end-v2", role: "END_FRAME" as const }];
describe("video prompt optimization references", () => {
  it("preserves exact identities when the model reorders reference tokens", () => {
    expect(encodeOptimizationPrompt("从 \uFFFC 转向 \uFFFC", mentions)).toBe("从 [[REF_1]] 转向 [[REF_2]]");
    expect(decodeOptimizationPrompt("[[REF_2]] 前景，[[REF_1]] 背景", mentions)).toEqual({
      prompt: "\uFFFC 前景，\uFFFC 背景", mentions: [mentions[1], mentions[0]],
    });
  });
  it.each(["[[REF_1]]", "[[REF_1]] [[REF_1]]", "[[REF_1]] [[REF_3]]", "\uFFFC [[REF_1]] [[REF_2]]"])("refuses incomplete or fabricated references: %s", (text) => {
    expect(decodeOptimizationPrompt(text, mentions)).toBeNull();
  });
  it("refuses ambiguous source text and accepts ordinary text without references", () => {
    expect(encodeOptimizationPrompt("[[REF_1]]", [])).toBeNull();
    expect(encodeOptimizationPrompt("\uFFFC", [])).toBeNull();
    expect(encodeOptimizationPrompt("湖边晨光", [])).toBe("湖边晨光");
    expect(decodeOptimizationPrompt("湖边晨光", [])).toEqual({ prompt: "湖边晨光", mentions: [] });
  });
});
