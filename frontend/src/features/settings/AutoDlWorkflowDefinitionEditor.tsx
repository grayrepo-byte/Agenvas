import { useId } from "react";
import type { MediaCapability } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";

type Definition = NonNullable<MediaCapability["settings"]["workflowDefinition"]>;
const MAX_REFERENCES = 14;
const MAX_AUDIOS = 3;
const MAX_SECONDS = 30;
const MAX_PROMPT = 10000;
const MAX_LABEL_LENGTH = 160;
const RESOLUTION_ROWS = 6;

export function AutoDlWorkflowDefinitionEditor({ value, onChange, disabled }: {
  value: Definition; onChange: (value: Definition) => void; disabled?: boolean;
}) {
  useLocale();
  const fieldId = useId();
  return <FieldSet className="autodl-workflow-definition" disabled={disabled}><FieldLegend>{t("settings.autoDl.definition.title")}</FieldLegend><FieldGroup className="ui-form-grid">
    <Field><FieldLabel htmlFor={`${fieldId}-id`}>{t("settings.autoDl.definition.workflowId")}</FieldLabel><Input id={`${fieldId}-id`} required pattern="[A-Za-z0-9][A-Za-z0-9._-]{0,119}" value={value.id} onChange={(event) => onChange({ ...value, id: event.target.value })} /></Field>
    <Field><FieldLabel htmlFor={`${fieldId}-name`}>{t("settings.autoDl.definition.workflowName")}</FieldLabel><Input id={`${fieldId}-name`} required maxLength={MAX_LABEL_LENGTH} value={value.label} onChange={(event) => onChange({ ...value, label: event.target.value })} /></Field>
    <Field><FieldLabel htmlFor={`${fieldId}-mode`}>{t("settings.autoDl.definition.inputMode")}</FieldLabel><Select id={`${fieldId}-mode`} value={value.mode} onChange={(event) => {
      const mode = event.target.value as Definition["mode"];
      onChange({ ...value, mode, imageFields: mode === "START_END" ? ["first_frame", "last_frame"] : mode === "TEXT" ? [] : ["ref_image_0"],
        audioFields: [], minimumImages: mode === "START_END" ? 2 : mode === "TEXT" ? 0 : 1, minimumAudios: 0 });
    }}><option value="TEXT">{t("common.plainText")}</option><option value="START_END">{t("common.startEndFrames")}</option><option value="GENERAL_REFERENCE">{t("common.mixedReference")}</option></Select></Field>
    {(["minimumSeconds", "maximumSeconds", "promptLimit", "minimumImages", "minimumAudios"] as const).map((key) => <Field key={key}><FieldLabel htmlFor={`${fieldId}-${key}`}>{{
      minimumSeconds: t("settings.autoDl.definition.minimumSeconds"), maximumSeconds: t("settings.autoDl.definition.maximumSeconds"), promptLimit: t("settings.autoDl.definition.promptLimit"),
      minimumImages: t("settings.autoDl.definition.requiredImages"), minimumAudios: t("settings.autoDl.definition.requiredAudios"),
    }[key]}</FieldLabel><Input id={`${fieldId}-${key}`} type="number" required min={key === "minimumImages" || key === "minimumAudios" ? 0 : 1}
      max={key === "promptLimit" ? MAX_PROMPT : key === "minimumImages" ? value.imageFields.length : key === "minimumAudios" ? value.audioFields.length : MAX_SECONDS}
      step={1} value={value[key]} onChange={(event) => onChange({ ...value, [key]: Number(event.target.value) })} /></Field>)}
    {value.mode === "GENERAL_REFERENCE" ? <>
      <Field><FieldLabel htmlFor={`${fieldId}-maximumImages`}>{t("settings.autoDl.definition.maximumImages")}</FieldLabel><Input id={`${fieldId}-maximumImages`} type="number" min={0} max={MAX_REFERENCES - value.audioFields.length} step={1} value={value.imageFields.length}
        onChange={(event) => onChange({ ...value, imageFields: Array.from({ length: Math.max(0, Math.min(MAX_REFERENCES, Number(event.target.value))) }, (_, i) => `ref_image_${i}`) })} /></Field>
      <Field><FieldLabel htmlFor={`${fieldId}-maximumAudios`}>{t("settings.autoDl.definition.maximumAudios")}</FieldLabel><Input id={`${fieldId}-maximumAudios`} type="number" min={0} max={Math.min(MAX_AUDIOS, MAX_REFERENCES - value.imageFields.length)} step={1} value={value.audioFields.length}
        onChange={(event) => onChange({ ...value, audioFields: Array.from({ length: Math.max(0, Math.min(MAX_AUDIOS, Number(event.target.value))) }, (_, i) => `ref_audio_${i}`) })} /></Field>
    </> : null}
    <Field><FieldLabel htmlFor={`${fieldId}-seedSupport`}>{t("settings.autoDl.definition.seedSupport")}</FieldLabel><Select id={`${fieldId}-seedSupport`} value={value.supportsSeed ? "yes" : "no"} onChange={(event) => onChange({ ...value, supportsSeed: event.target.value === "yes" })}>
      <option value="yes">{t("settings.autoDl.definition.supported")}</option><option value="no">{t("settings.autoDl.definition.unsupported")}</option></Select></Field>
    <Field className="autodl-definition-resolutions"><FieldLabel htmlFor={`${fieldId}-resolutions`}>{t("settings.autoDl.definition.resolutions")}</FieldLabel><Textarea id={`${fieldId}-resolutions`} aria-describedby={`${fieldId}-resolutionHint`} required rows={RESOLUTION_ROWS} value={value.resolutions.join("\n")}
      onChange={(event) => onChange({ ...value, resolutions: event.target.value.split("\n") })}
      onBlur={() => onChange({ ...value, resolutions: value.resolutions.map((line) => line.trim()).filter(Boolean) })} />
      <FieldDescription id={`${fieldId}-resolutionHint`}>{t("settings.autoDl.definition.resolutionHint")}</FieldDescription>
    </Field>
  </FieldGroup></FieldSet>;
}
