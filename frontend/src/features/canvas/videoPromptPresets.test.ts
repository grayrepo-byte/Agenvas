import { describe, expect, it } from "vitest";
import { SUPPORTED_LOCALES } from "../../shared/i18n";
import { appendVideoPrompt, filterVideoPromptPresets, VIDEO_PROMPT_CATEGORIES, VIDEO_PROMPT_PRESETS, VIDEO_PROMPT_SCENARIOS } from "./videoPromptPresets";
import { VIDEO_PROMPT_SCENARIOS_BY_PRESET } from "./videoPromptPresetScenarios";

describe("bundled video camera presets", () => {
  it("ships 112 distinct complete presets across eleven categories with four localized guides", () => {
    expect(VIDEO_PROMPT_PRESETS).toHaveLength(112);
    expect(new Set(VIDEO_PROMPT_PRESETS.map((preset) => preset.id)).size).toBe(112);
    expect(new Set(VIDEO_PROMPT_PRESETS.map((preset) => preset.prompt)).size).toBe(112);
    const counts = { framing: 10, rotation: 4, dolly: 11, tracking: 10, orbit: 11, vertical: 10,
      lens: 10, handheld: 11, aerial: 13, reveal: 10, sequence: 12 };
    expect(VIDEO_PROMPT_CATEGORIES.map(({ id }) => id).sort()).toEqual(Object.keys(counts).sort());
    for (const category of VIDEO_PROMPT_CATEGORIES) {
      expect(VIDEO_PROMPT_PRESETS.filter((preset) => preset.category === category.id)).toHaveLength(counts[category.id]);
    }
    for (const preset of VIDEO_PROMPT_PRESETS) {
      expect(preset.id).toMatch(/^[a-z][a-z0-9-]+$/);
      expect(preset.englishName.trim()).not.toBe("");
      expect(preset.prompt.length).toBeGreaterThan(80);
      expect(preset.prompt).not.toMatch(/\{\{|\[subject\]|\[scene\]|https?:\/\//i);
      for (const locale of SUPPORTED_LOCALES) {
        for (const value of Object.values(preset.copy[locale])) expect(value.trim().length).toBeGreaterThan(0);
      }
    }
    expect(VIDEO_PROMPT_PRESETS.some((preset) => /over.the.shoulder/i.test(preset.englishName))).toBe(true);
    expect(VIDEO_PROMPT_PRESETS.some((preset) => /steadicam/i.test(preset.englishName))).toBe(true);
  });

  it("finds Chinese and English terminology, descriptions and intersecting category filters", () => {
    expect(filterVideoPromptPresets("过肩", "all").some((preset) => /over.the.shoulder/i.test(preset.englishName))).toBe(true);
    expect(filterVideoPromptPresets("  STEADICAM  ", "tracking").length).toBeGreaterThan(0);
    expect(filterVideoPromptPresets("STEADICAM", "lens")).toHaveLength(0);
    expect(filterVideoPromptPresets("360", "rotation").map(({ id }) => id)).toContain("axial-barrel-roll");
    expect(filterVideoPromptPresets("Snorricam", "handheld").map(({ id }) => id)).toContain("snorricam-body-mount");
    expect(filterVideoPromptPresets("FPV", "aerial").map(({ id }) => id)).toEqual(expect.arrayContaining(["fpv-vertical-dive", "fpv-gap-slalom"]));
    expect(filterVideoPromptPresets("nonexistent-camera-presets-xyz", "all")).toHaveLength(0);
    const preset = VIDEO_PROMPT_PRESETS[0]!;
    expect(filterVideoPromptPresets(preset.copy.ja.name, "all")).toContain(preset);
    expect(filterVideoPromptPresets(preset.copy.ru.name, "all")).toContain(preset);
  });

  it("preserves every existing character and mention marker when appending", () => {
    const prompt = "  Scene \uFFFC with a subject.\n";
    expect(appendVideoPrompt(prompt, "Camera instruction.")).toBe(`${prompt}\nCamera instruction.`);
    expect(appendVideoPrompt("", "Camera instruction.")).toBe("Camera instruction.");
    expect(appendVideoPrompt("Scene.\n\n", "Camera instruction.")).toBe("Scene.\n\nCamera instruction.");
    expect(appendVideoPrompt("Scene.", "Camera instruction.")).toBe("Scene.\n\nCamera instruction.");
  });

  it("curates valid, nonempty use cases for every preset without orphaned mappings", () => {
    expect(Object.keys(VIDEO_PROMPT_SCENARIOS_BY_PRESET).sort()).toEqual(VIDEO_PROMPT_PRESETS.map(({ id }) => id).sort());
    const scenarios = VIDEO_PROMPT_SCENARIOS.map(({ id }) => id);
    expect(scenarios).toEqual(["dialogue", "product", "entrance", "chase", "landscape"]);
    for (const preset of VIDEO_PROMPT_PRESETS) {
      expect(preset.scenarios.length, preset.id).toBeGreaterThan(0);
      expect(new Set(preset.scenarios).size, preset.id).toBe(preset.scenarios.length);
      for (const scenario of preset.scenarios) expect(scenarios, preset.id).toContain(scenario);
    }
    for (const scenario of scenarios) expect(filterVideoPromptPresets("", "all", scenario).length).toBeGreaterThan(0);
  });

  it("intersects use case, category and keyword without treating all scenes as a reset", () => {
    expect(filterVideoPromptPresets("过肩", "all", "dialogue").map(({ id }) => id)).toContain("over-the-shoulder");
    expect(filterVideoPromptPresets("过肩", "all", "product")).toHaveLength(0);
    expect(filterVideoPromptPresets("FPV", "aerial", "chase").map(({ id }) => id)).toEqual(["fpv-vertical-dive", "fpv-gap-slalom"]);
    expect(filterVideoPromptPresets("FPV", "lens", "chase")).toHaveLength(0);
    expect(filterVideoPromptPresets("微距", "lens", "product").map(({ id }) => id)).toContain("macro-focus-travel");
    expect(filterVideoPromptPresets("微距", "lens", "landscape")).toHaveLength(0);
    expect(filterVideoPromptPresets("微距", "lens", "all")).toEqual(filterVideoPromptPresets("微距", "lens"));
  });
});
