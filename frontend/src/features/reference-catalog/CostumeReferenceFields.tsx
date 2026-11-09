import { t } from "../../shared/i18n";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";

export type CostumeMetadata = { name: string; category: string; tags: string };
export function costumeMetadataRequest(fields: CostumeMetadata) {
  return { name: fields.name.trim(), ...(fields.category.trim() ? { category: fields.category.trim() } : {}), tags: [...new Set(fields.tags.split(/[,，]/).map((tag) => tag.trim()).filter(Boolean))] };
}
export function costumeMetadataValid(fields: CostumeMetadata): boolean {
  const request = costumeMetadataRequest(fields);
  return Boolean(request.name) && request.name.length <= 160 && (request.category?.length ?? 0) <= 100 && request.tags.length <= 32 && request.tags.every((tag) => tag.length <= 64);
}
export function CostumeReferenceFields({ fields, onChange, disabled }: { fields: CostumeMetadata; onChange: (fields: CostumeMetadata) => void; disabled: boolean }) {
  return <>
    <Field><FieldLabel>{t("costume.name")}<Input required maxLength={160} value={fields.name} disabled={disabled} onChange={(event) => onChange({ ...fields, name: event.target.value })} /></FieldLabel></Field>
    <Field><FieldLabel>{t("costume.category")}<Input maxLength={100} value={fields.category} disabled={disabled} onChange={(event) => onChange({ ...fields, category: event.target.value })} /></FieldLabel></Field>
    <Field><FieldLabel>{t("costume.tags")}<Input maxLength={2200} value={fields.tags} disabled={disabled} onChange={(event) => onChange({ ...fields, tags: event.target.value })} /></FieldLabel><p className="reference-hint">{t("costume.tagsHint")}</p>{costumeMetadataRequest(fields).tags.length > 32 || costumeMetadataRequest(fields).tags.some((tag) => tag.length > 64) ? <p role="alert">{t("costume.tagsInvalid")}</p> : null}</Field>
  </>;
}
