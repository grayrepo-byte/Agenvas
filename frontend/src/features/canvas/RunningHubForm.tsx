import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { useId, useState } from "react";
import type { CreatorInputDefinition, CreatorInputField } from "../../shared/creatorInputs";
import { t,useLocale } from "../../shared/i18n";
import { MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { CreatorNumberInput } from "../../shared/ui/CreatorNumberInput";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";
import { workflowFieldHint, workflowFieldLabel } from "./workflowFieldPresentation";
import { evaluateWorkflowFields, isWorkflowMediaField, workflowNumericBounds,
  WORKFLOW_DEFAULT_MAX_LENGTH, type WorkflowValue, type WorkflowChoice } from "./workflowFields";
import "./RunningHubForm.css";

function UploadSlot({ field, disabled, onUpload }: { field: CreatorInputField; disabled: boolean; onUpload: (field: CreatorInputField, file: File) => Promise<void> }) {
  useLocale();
  const inputId = useId();
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function upload(selected: File) {
    setFile(selected); setBusy(true); setError("");
    try { await onUpload(field, selected); setFile(null); }
    catch (failure) { setError(failure instanceof Error ? failure.message : t("media.runningHub.uploadFailed")); }
    finally { setBusy(false); }
  }
  return <Field data-disabled={disabled || busy}>
    <FieldLabel htmlFor={inputId} title={workflowFieldHint(field)}>{t("media.runningHub.uploadNamed", { "0": workflowFieldLabel(field) })}</FieldLabel><Input id={inputId} type="file" disabled={disabled || busy}
      accept={field.type === "IMAGE" ? MEDIA_FILE_ACCEPT.IMAGE : field.type === "VIDEO" ? MEDIA_FILE_ACCEPT.VIDEO : "audio/mpeg,audio/wav,audio/flac"}
      onChange={(event) => { const selected = event.target.files?.[0]; event.target.value = ""; if (selected) void upload(selected); }} />
    {busy ? <p role="status">{t("media.runningHub.uploading")}</p> : null}
    {error ? <p role="alert">{error}{file ? <Button variant="ghost" type="button" disabled={busy || disabled} onClick={() => void upload(file)}>{t("media.retryUpload")}</Button> : null}</p> : null}
  </Field>;
}

export function RunningHubForm({ definition, values, prompt, durationSeconds, choices, disabled = false, onChange, onUpload }: {
  definition: CreatorInputDefinition; values: Record<string, WorkflowValue>; prompt: string; durationSeconds: number | null | undefined;
  choices: WorkflowChoice[]; disabled?: boolean;
  onChange: (key: string, value: WorkflowValue | undefined) => void;
  onUpload?: (field: CreatorInputField, file: File) => Promise<void>;
}) {
  useLocale();
  const formId = useId();
  const { effectiveValues, activeFields } = evaluateWorkflowFields(definition, values, prompt, durationSeconds);
  function control(field: CreatorInputField) {
    const value = effectiveValues[field.key];
    const isMedia = isWorkflowMediaField(field);
    const { minimum, maximum } = workflowNumericBounds(field);
    const inputId = `${formId}-${field.key}`;
    const controlProps = { id: inputId, disabled };
    return <FieldGroup className="runninghub-form-slot" key={field.key}>
      <Field data-disabled={disabled}>
        <FieldLabel htmlFor={inputId} title={workflowFieldHint(field)}>{workflowFieldLabel(field)}{field.required ? " *" : ""}</FieldLabel>
        {isMedia ? <Select {...controlProps} value={typeof value === "string" ? value : ""} onChange={(event) => onChange(field.key, event.target.value || undefined)}>
          <option value="">{t("media.runningHub.selectVersion", { "0": field.type === "IMAGE" ? t("common.image") : field.type === "AUDIO" ? t("common.audio") : t("common.video") })}</option>
          {choices.filter((choice) => choice.kind === field.type).map((choice) => <option key={choice.id} value={choice.id} disabled={!choice.available}>{choice.label}</option>)}
          {value && !choices.some((choice) => choice.id === value) ? <option value={String(value)}>{t("media.runningHub.savedVersionPending")}</option> : null}
        </Select> : field.type === "SELECT" ? <Select {...controlProps} value={value === undefined ? "" : String(field.options?.findIndex((option) => option.value === value) ?? -1)}
          onChange={(event) => onChange(field.key, event.target.value ? field.options?.[Number(event.target.value)]?.value : undefined)}>
          <option value="">{t("media.runningHub.selectPlaceholder")}</option>{field.options?.map((option, index) => <option key={index} value={index}>{option.label}</option>)}
        </Select> : field.type === "BOOLEAN" ? <Select {...controlProps} value={value === undefined ? "" : String(value)} onChange={(event) => onChange(field.key, event.target.value ? event.target.value === "true" : undefined)}>
          <option value="">{t("media.runningHub.useDefault")}</option><option value="true">{t("common.enabled")}</option><option value="false">{t("common.close")}</option>
        </Select> : field.type === "STRING" ? <Textarea {...controlProps} rows={3} maxLength={field.maxLength ?? WORKFLOW_DEFAULT_MAX_LENGTH} value={value === undefined ? "" : String(value)}
          onChange={(event) => onChange(field.key, event.target.value)} /> : <CreatorNumberInput {...controlProps} value={value === undefined ? undefined : Number(value)}
          min={minimum} max={maximum} step={field.step ?? (field.type === "INTEGER" ? 1 : "any")}
          onValueChange={(next) => onChange(field.key, next)} />}
      </Field>
      {isMedia && onUpload ? <UploadSlot field={field} disabled={disabled} onUpload={onUpload} /> : null}
    </FieldGroup>;
  }
  return <FieldGroup className="runninghub-form" aria-label={t("media.runningHub.parameters")}>
    {activeFields.filter((field) => !field.advanced).map(control)}
    {definition.fields.some((field) => field.advanced) ? <details className="runninghub-form-advanced"><summary>{t("media.runningHub.advancedParameters")}</summary><FieldGroup>{activeFields.filter((field) => field.advanced).map(control)}</FieldGroup></details> : null}
  </FieldGroup>;
}
