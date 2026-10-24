import type { SkillCategory } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { SKILL_CATEGORIES, skillCategoryLabel } from "./skillCategories";

export function SkillCategoryFilter({ value, onChange, disabled = false }: {
  value?: SkillCategory; onChange: (category: SkillCategory | undefined) => void; disabled?: boolean;
}) {
  useLocale();
  return <Field className="skills-category-filter"><FieldLabel className="sr-only">{t("skills.category")}</FieldLabel>
    <Select aria-label={t("skills.category")} value={value ?? ""} disabled={disabled}
      onChange={(event) => onChange(SKILL_CATEGORIES.find((category) => category === event.target.value))}>
      <option value="">{t("skills.category.all")}</option>
      {SKILL_CATEGORIES.map((category) => <option key={category} value={category}>{skillCategoryLabel(category)}</option>)}
    </Select>
  </Field>;
}
