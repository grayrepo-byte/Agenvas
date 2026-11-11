import { describe, expect, it } from "vitest";
import { setLocale, t } from "../../shared/i18n";
import { seededPrompts, upgradedCharacterPrompt } from "../../test/seededCatalogs";
import { getPromptLabels } from "./builtInPrompts";

describe("built-in prompt labels", () => {
  it.each(["zh", "en", "ja", "ru"] as const)("localizes current and preserved legacy character metadata in %s without rewriting custom text", (locale) => {
    setLocale(locale);
    const legacy = seededPrompts.find((prompt) => prompt.key === "image.three-view.character")!;
    const current = { ...upgradedCharacterPrompt };
    const contentBefore = current.content;
    const currentLabels = getPromptLabels(current);
    expect(currentLabels.description).toContain("4:3");
    expect(currentLabels.description).toContain("90");
    expect(currentLabels.description).not.toContain("16:9");
    if (locale !== "zh") expect(currentLabels.description).not.toBe(current.description);
    expect(getPromptLabels(legacy).description).toContain("16:9");
    if (locale !== "zh") expect(getPromptLabels(legacy).description).not.toBe(legacy.description);
    for (const changed of [{ ...current, description: "我的自定义说明" }, { ...current, description: `${current.description} ` }, { ...current, builtIn: false }]) {
      expect(getPromptLabels(changed).description).toBe(changed.description);
    }
    expect(current.content).toBe(contentBefore);
  });

  it("matches metadata independently without translating near matches or custom identities", () => {
    setLocale("en");
    const prompt = seededPrompts[0]!;
    expect(getPromptLabels({ ...prompt, name: "我的导演" })).toEqual({ name: "我的导演", description: t("prompts.builtIn.director.description") });
    expect(getPromptLabels({ ...prompt, description: "我的说明" })).toEqual({ name: "Director Agent", description: "我的说明" });
    expect(getPromptLabels({ ...prompt, name: `${prompt.name} `, description: `${prompt.description} ` }))
      .toEqual({ name: `${prompt.name} `, description: `${prompt.description} ` });
    for (const changed of [{ ...prompt, builtIn: false }, { ...prompt, key: "constructor" }]) {
      expect(getPromptLabels(changed)).toEqual({ name: prompt.name, description: prompt.description });
    }
    expect(prompt.name).toBe("导演 Agent");
  });
});
