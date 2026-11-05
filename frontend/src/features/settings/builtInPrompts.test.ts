import { describe, expect, it } from "vitest";
import { setLocale, t } from "../../shared/i18n";
import { seededPrompts } from "../../test/seededCatalogs";
import { getPromptLabels } from "./builtInPrompts";

describe("built-in prompt labels", () => {
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
