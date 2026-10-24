import { describe, expect, it, vi } from "vitest";
import { SUPPORTED_LOCALES, translate } from "../../shared/i18n";
import { appendVideoPrompt, filterVideoPromptPresets, VIDEO_PROMPT_CATEGORIES, VIDEO_PROMPT_PRESETS, VIDEO_PROMPT_SCENARIOS, type VideoPromptScenario } from "./videoPromptPresets";
import { VIDEO_PROMPT_SCENARIOS_BY_PRESET } from "./videoPromptPresetScenarios";
import firstPresets from "./video-prompt-presets-a.json";
import secondPresets from "./video-prompt-presets-b.json";
import supplementalPresets from "./video-prompt-presets-c.json";

/** Allow new matching presets while checking every returned entry against the requested filters. */
function matchingPresets(query: string, category: string, scenario: VideoPromptScenario | "all" = "all") {
  const results = filterVideoPromptPresets(query, category, scenario);
  for (const preset of results) {
    if (category !== "all") expect(preset.category, preset.id).toBe(category);
    if (scenario !== "all") expect(preset.scenarios, preset.id).toContain(scenario);
    const searchable = JSON.stringify({ englishName: preset.englishName, prompt: preset.prompt, copy: preset.copy })
      .normalize("NFKC").toLocaleLowerCase();
    for (const term of query.normalize("NFKC").trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)) {
      expect(searchable, preset.id).toContain(term);
    }
  }
  return results;
}

describe("bundled video camera presets", () => {
  it("loads every authored preset without duplicates, with complete categories and localized guides", () => {
    const authoredPresets = [...firstPresets, ...secondPresets, ...supplementalPresets];
    // The JSON files are the catalogue: expansions must not require updating another count snapshot.
    expect(authoredPresets.length).toBeGreaterThan(0);
    expect(VIDEO_PROMPT_PRESETS.map(({ id, category, englishName, prompt, copy }) =>
      ({ id, category, englishName, prompt, copy }))).toEqual(authoredPresets);
    expect(new Set(VIDEO_PROMPT_PRESETS.map((preset) => preset.id)).size).toBe(authoredPresets.length);
    expect(new Set(VIDEO_PROMPT_PRESETS.map((preset) => preset.prompt)).size).toBe(authoredPresets.length);
    const categories = VIDEO_PROMPT_CATEGORIES.map(({ id }) => id);
    expect(categories).toEqual(expect.arrayContaining(["framing", "rotation", "dolly", "tracking", "orbit", "vertical",
      "lens", "handheld", "aerial", "reveal", "sequence"]));
    expect(new Set(categories).size).toBe(categories.length);
    for (const category of VIDEO_PROMPT_CATEGORIES) {
      expect(category.id).toMatch(/^[a-z][a-z0-9-]+$/);
      for (const locale of SUPPORTED_LOCALES) expect(translate(locale, category.label).trim()).not.toBe("");
      expect(VIDEO_PROMPT_PRESETS.some((preset) => preset.category === category.id), category.id).toBe(true);
    }
    for (const preset of VIDEO_PROMPT_PRESETS) {
      expect(preset.id).toMatch(/^[a-z][a-z0-9-]+$/);
      expect(preset.englishName.trim()).not.toBe("");
      expect(categories, preset.id).toContain(preset.category);
      expect(preset.prompt.trim(), preset.id).not.toBe("");
      expect(preset.prompt).not.toMatch(/\{\{|\[subject\]|\[scene\]|https?:\/\//i);
      for (const locale of SUPPORTED_LOCALES) {
        for (const field of ["name", "conditions", "effect", "caution"] as const) {
          expect(preset.copy[locale][field].trim(), `${preset.id}: ${locale}.${field}`).not.toBe("");
        }
      }
    }
    expect(VIDEO_PROMPT_PRESETS.some((preset) => /over.the.shoulder/i.test(preset.englishName))).toBe(true);
    expect(VIDEO_PROMPT_PRESETS.some((preset) => /steadicam/i.test(preset.englishName))).toBe(true);
  });

  it("finds Chinese and English terminology, descriptions and intersecting category filters", () => {
    expect(filterVideoPromptPresets("过肩", "all").some((preset) => /over.the.shoulder/i.test(preset.englishName))).toBe(true);
    expect(matchingPresets("  STEADICAM  ", "tracking").map(({ id }) => id)).toContain("steadicam-follow");
    expect(matchingPresets("STEADICAM", "lens").map(({ id }) => id)).not.toContain("steadicam-follow");
    expect(filterVideoPromptPresets("360", "rotation").map(({ id }) => id)).toContain("axial-barrel-roll");
    expect(filterVideoPromptPresets("Snorricam", "handheld").map(({ id }) => id)).toContain("snorricam-body-mount");
    expect(filterVideoPromptPresets("FPV", "aerial").map(({ id }) => id)).toEqual(expect.arrayContaining(["fpv-vertical-dive", "fpv-gap-slalom"]));
    expect(filterVideoPromptPresets("nonexistent-camera-presets-xyz", "all")).toHaveLength(0);
    const preset = VIDEO_PROMPT_PRESETS[0]!;
    expect(filterVideoPromptPresets(preset.copy.ja.name, "all")).toContain(preset);
    expect(filterVideoPromptPresets(preset.copy.ru.name, "all")).toContain(preset);
  });

  it.each(SUPPORTED_LOCALES)("searches every guide field in %s and ANDs normalized terms across languages", (locale) => {
    const preset = VIDEO_PROMPT_PRESETS.find(({ id }) => id === "steadicam-follow")!;
    for (const field of ["name", "conditions", "effect", "caution"] as const) {
      expect(filterVideoPromptPresets(preset.copy[locale][field], "tracking"), field).toContain(preset);
    }
    expect(filterVideoPromptPresets(`\u3000ＳＴＥＡＤＩＣＡＭ\u3000ＦＯＬＬＯＷ\u3000${preset.copy[locale].name}`, "tracking")).toContain(preset);
    expect(filterVideoPromptPresets(`ＳＴＥＡＤＩＣＡＭ ${preset.copy[locale].name} nonexistent-camera-presets-xyz`, "tracking")).toHaveLength(0);
  });

  it("skips search documents for empty queries and reuses them for repeated searches", async () => {
    vi.resetModules();
    const { filterVideoPromptPresets: filter } = await import("./videoPromptPresets");
    const normalize = vi.spyOn(String.prototype, "normalize");
    filter("\u3000 ", "all");
    expect(normalize).toHaveBeenCalledTimes(1);
    filter("FPV", "all");
    normalize.mockClear();
    const queries = ["ＦＰＶ", "过肩", "  STEADICAM FOLLOW  ", "微距"];
    for (const query of queries) filter(query, "all");
    // Only each new query is normalized; the bundled four-language documents are stable.
    expect(normalize).toHaveBeenCalledTimes(queries.length);
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
    expect(scenarios).toEqual(expect.arrayContaining(["dialogue", "product", "entrance", "chase", "landscape"]));
    expect(new Set(scenarios).size).toBe(scenarios.length);
    for (const scenario of VIDEO_PROMPT_SCENARIOS) {
      expect(scenario.id).toMatch(/^[a-z][a-z0-9-]+$/);
      for (const locale of SUPPORTED_LOCALES) expect(translate(locale, scenario.label).trim()).not.toBe("");
    }
    for (const preset of VIDEO_PROMPT_PRESETS) {
      expect(preset.scenarios.length, preset.id).toBeGreaterThan(0);
      expect(new Set(preset.scenarios).size, preset.id).toBe(preset.scenarios.length);
      for (const scenario of preset.scenarios) expect(scenarios, preset.id).toContain(scenario);
    }
    for (const scenario of scenarios) expect(filterVideoPromptPresets("", "all", scenario).length).toBeGreaterThan(0);
  });

  it("intersects use case, category and keyword without treating all scenes as a reset", () => {
    expect(matchingPresets("过肩", "all", "dialogue").map(({ id }) => id)).toContain("over-the-shoulder");
    expect(matchingPresets("过肩", "all", "product").map(({ id }) => id)).not.toContain("over-the-shoulder");
    const fpvChase = matchingPresets("FPV", "aerial", "chase");
    expect(fpvChase.map(({ id }) => id)).toEqual(expect.arrayContaining(["fpv-vertical-dive", "fpv-gap-slalom"]));
    const fpvLens = matchingPresets("FPV", "lens", "chase").map(({ id }) => id);
    expect(fpvLens).not.toContain("fpv-vertical-dive");
    expect(fpvLens).not.toContain("fpv-gap-slalom");
    expect(matchingPresets("微距", "lens", "product").map(({ id }) => id)).toContain("macro-focus-travel");
    expect(matchingPresets("微距", "lens", "landscape").map(({ id }) => id)).not.toContain("macro-focus-travel");
    expect(filterVideoPromptPresets("微距", "lens", "all")).toEqual(filterVideoPromptPresets("微距", "lens"));
  });
});
