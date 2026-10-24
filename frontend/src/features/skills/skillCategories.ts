import type { CreativeSkill, SkillCategory } from "../../shared/api/client";
import { t } from "../../shared/i18n";

export const SKILL_CATEGORIES = ["STORY", "ADVERTISING", "ANIMATION", "EXPLAINER", "MUSIC", "PROMPT"] as const satisfies readonly SkillCategory[];
const LABELS = {
  STORY: "skills.category.story", ADVERTISING: "skills.category.advertising", ANIMATION: "skills.category.animation",
  EXPLAINER: "skills.category.explainer", MUSIC: "skills.category.music", PROMPT: "skills.category.prompt",
} as const;
export function skillCategoryLabel(category: SkillCategory) { return t(LABELS[category]); }

/** The catalogue assigns purpose independently of publisher, model and fixed publication contents. */
export function skillCatalogueGroups(items: CreativeSkill[]) {
  return [
    ...SKILL_CATEGORIES.map((category) => ({ label: `${t("skills.builtin")} · ${skillCategoryLabel(category)}`,
      items: items.filter((skill) => skill.builtin && skill.category === category) })),
    { label: t("skills.builtin"), items: items.filter((skill) => skill.builtin && !skill.category) },
    { label: t("skills.title"), items: items.filter((skill) => !skill.builtin) },
  ].filter((group) => group.items.length > 0);
}
