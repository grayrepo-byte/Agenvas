import { describe, expect, it } from "vitest";
import { seededStyles } from "../test/seededCatalogs";
import { setLocale } from "./i18n";
import { getMediaStyleLabels } from "./mediaStyles";

describe("built-in style labels", () => {
  it("matches each seeded field independently and preserves near matches and custom identities", () => {
    setLocale("en");
    const style = seededStyles[0]!;
    expect(getMediaStyleLabels({ ...style, name: "我的摄影" })).toMatchObject({
      name: "我的摄影",
      category: "Realistic",
    });
    expect(getMediaStyleLabels({ ...style, category: "我的分类" })).toMatchObject({
      name: "Photorealistic photography",
      category: "我的分类",
    });
    expect(getMediaStyleLabels({ ...style, name: `${style.name} `, category: `${style.category} ` })).toMatchObject({
      name: `${style.name} `,
      category: `${style.category} `,
    });
    for (const changed of [
      { ...style, builtIn: false },
      { ...style, id: "unrecognized" },
    ]) {
      expect(getMediaStyleLabels(changed)).toMatchObject({ name: style.name, category: style.category });
    }
    expect(style.name).toBe("写实摄影");
  });
});
