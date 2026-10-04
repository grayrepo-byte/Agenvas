import { Image as ImageIcon, MusicNotes, VideoCamera, X } from "@phosphor-icons/react";
import { cn } from "cn";
import { useEffect, useId, useRef, useState, type CSSProperties } from "react";
import type { RunningHubDefinition, RunningHubField } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Empty, EmptyDescription, EmptyHeader } from "../../shared/ui/primitives/empty";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../shared/ui/primitives/table";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { runningHubFieldValue, type RunningHubChoice, type RunningHubValue } from "./RunningHubForm";
import { MediaReferenceSourceMenu, type MediaReferenceSource } from "./MediaReferenceSourceMenu";
import "./WorkflowDraftControls.css";

const MIN_DURATION_SECONDS = 1;
const MAX_DURATION_SECONDS = 60;
const DEFAULT_MAX_LENGTH = 20_000;
const MEDIA_TYPES = new Set<RunningHubField["type"]>(["IMAGE", "VIDEO", "AUDIO"]);

export type WorkflowMediaChoice = RunningHubChoice & { thumbnailUrl?: string | null; title?: string };
type WorkflowValuesProps = {
  definition: Pick<RunningHubDefinition, "fields">;
  values: Record<string, RunningHubValue>;
  prompt: string;
  durationSeconds: number | null | undefined;
  disabled?: boolean;
  onChange: (key: string, value: RunningHubValue | undefined) => void;
};
export type WorkflowMediaInputsProps = WorkflowValuesProps & {
  choices: WorkflowMediaChoice[];
  canvasChoices?: WorkflowMediaChoice[];
  onUpload?: (field: RunningHubField, file: File) => Promise<void>;
  onBusy?: (busy: boolean) => void;
  libraryDisabled?: boolean;
  pickerOpen?: boolean;
  onChooseSource: (field: RunningHubField, source: Exclude<MediaReferenceSource, "upload">, trigger: HTMLButtonElement) => void;
};
export type WorkflowParametersDialogProps = WorkflowValuesProps & {
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

/** Visibility uses the same effective defaults and prompt/duration sources as submission. */
function activeFields({ definition, values, prompt, durationSeconds }: WorkflowValuesProps) {
  const effective = Object.fromEntries(definition.fields.map((field) =>
    [field.key, runningHubFieldValue(field, values, prompt, durationSeconds)]));
  return definition.fields.filter((field) => !field.enabledWhen || effective[field.enabledWhen.field] === field.enabledWhen.value);
}

function MediaPreview({ choice, kind }: { choice?: WorkflowMediaChoice; kind: RunningHubField["type"] }) {
  if (choice?.thumbnailUrl) return <img src={choice.thumbnailUrl} alt="" loading="lazy" />;
  const Icon = kind === "IMAGE" ? ImageIcon : kind === "VIDEO" ? VideoCamera : MusicNotes;
  return <Icon aria-hidden="true" />;
}

function WorkflowMediaSlot({ field, index, value, choices, canvasChoices, disabled, onChange, onUpload, onBusy, onChooseSource, libraryDisabled, pickerOpen }: {
  index: number;
  field: RunningHubField; value: RunningHubValue | undefined; choices: WorkflowMediaChoice[]; canvasChoices: WorkflowMediaChoice[];
  disabled: boolean; onChange: WorkflowValuesProps["onChange"]; onUpload?: WorkflowMediaInputsProps["onUpload"];
  onBusy: (busy: boolean) => void;
  onChooseSource: WorkflowMediaInputsProps["onChooseSource"]; libraryDisabled?: boolean; pickerOpen?: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState("");
  // Choosing another source replaces a failed upload; cancelling a picker retains its retry.
  useEffect(() => { setFile(null); setError(""); }, [value]);
  const fileInput = useRef<HTMLInputElement>(null);
  const labelId = useId();
  const choice = [...choices, ...canvasChoices].find((item) => item.id === value && item.kind === field.type);
  const hasValue = value !== undefined && value !== "";
  const unavailable = hasValue && (!choice || !choice.available);
  const locked = disabled || busy;
  async function upload(selected: File) {
    if (!onUpload || locked) return;
    setFile(selected); setBusy(true); setError(""); onBusy(true);
    try { await onUpload(field, selected); setFile(null); }
    catch (failure) { setError(failure instanceof Error ? failure.message : t("media.runningHub.uploadFailed")); }
    finally { setBusy(false); onBusy(false); }
  }
  return <Field className="workflow-media-slot" aria-labelledby={labelId} data-disabled={disabled} data-invalid={unavailable || !!error}>
    <div className={cn("workflow-media-tile-wrap media-draft-popover-anchor", hasValue && "media-draft-reference-chip")}
      style={hasValue ? { "--reference-color": "var(--ui-accent)" } as CSSProperties : undefined}>
      <MediaReferenceSourceMenu label={t("media.workflow.chooseSlot", { "0": field.label })}
        className={cn("workflow-media-tile", hasValue ? "workflow-media-filled-trigger" : "media-draft-reference-add")}
        disabled={locked} invalid={unavailable || !!error} libraryDisabled={libraryDisabled} uploadDisabled={!onUpload}
        suspended={pickerOpen}
        title={`${field.label}${field.required ? " *" : ""}${choice ? ` · ${choice.label}` : hasValue ? ` · ${t("media.runningHub.savedVersionPending")}` : field.description ? ` · ${field.description}` : ""}`}
        onChoose={(source, trigger) => {
          if (source === "upload") fileInput.current?.click();
          else onChooseSource(field, source, trigger);
        }}>
        {hasValue ? <MediaPreview choice={choice} kind={field.type} /> : undefined}
      </MediaReferenceSourceMenu>
      {hasValue ? <><span className="media-draft-reference-index pointer-events-none" aria-hidden="true">{index + 1}</span>
        <Button type="button" variant="ghost" className="media-draft-reference-remove" disabled={locked}
          aria-label={t("media.workflow.clearSlot", { "0": field.label })} onClick={() => onChange(field.key, undefined)}><X size={13} /></Button></> : null}
    </div>
    <span className="sr-only" id={labelId}>{field.label}{field.required ? " *" : ""}</span>
    {onUpload ? <Input ref={fileInput} type="file" className="media-draft-upload-input" aria-label={t("media.runningHub.uploadNamed", { "0": field.label })}
      tabIndex={-1} disabled={locked} accept={MEDIA_FILE_ACCEPT[field.type as keyof typeof MEDIA_FILE_ACCEPT]}
      onChange={(event) => { const selected = event.target.files?.[0]; event.target.value = ""; if (selected) void upload(selected); }} /> : null}
    {unavailable ? <span className="workflow-media-warning" title={t("media.runningHub.savedVersionPending")}>{field.label} · {t("media.workflow.unavailable")}</span> : null}
    {busy ? <span role="status" className="workflow-media-status">{field.label} · {t("media.workflow.uploading")}</span> : null}
    {error ? <FieldError className="workflow-media-error">{field.label} · {error}{file ? <Button type="button" variant="ghost" disabled={locked}
      onClick={() => void upload(file)}>{t("media.retryUpload")}</Button> : null}</FieldError> : null}
  </Field>;
}

/** A workflow declares an exact number of named inputs; one tile maps to one field. */
export function WorkflowMediaInputs(props: WorkflowMediaInputsProps) {
  useLocale();
  const uploads = useRef(new Set<string>());
  const fields = activeFields(props).filter((field) => MEDIA_TYPES.has(field.type));
  if (!fields.length) return null;
  const hasSelectedMedia = fields.some((field) => {
    const value = runningHubFieldValue(field, props.values, props.prompt, props.durationSeconds);
    return value !== undefined && value !== "";
  });
  function slot(field: RunningHubField, index: number) {
    return <WorkflowMediaSlot key={field.key} field={field} index={index}
      value={runningHubFieldValue(field, props.values, props.prompt, props.durationSeconds)} choices={props.choices}
      canvasChoices={props.canvasChoices ?? []} disabled={props.disabled ?? false} onChange={props.onChange} onUpload={props.onUpload}
      onChooseSource={props.onChooseSource} libraryDisabled={props.libraryDisabled} pickerOpen={props.pickerOpen}
      onBusy={(busy) => { if (busy) uploads.current.add(field.key); else uploads.current.delete(field.key); props.onBusy?.(uploads.current.size > 0); }} />;
  }
  return <div className="media-draft-reference-row workflow-media-inputs" aria-label={t("media.workflow.mediaInputs")}>
    {fields[0] ? slot(fields[0], 0) : null}
    <FieldGroup className={cn("media-draft-reference-list workflow-media-list", !hasSelectedMedia && "workflow-media-list-empty")}>
      {fields.slice(1).map((field, index) => slot(field, index + 1))}
    </FieldGroup>
  </div>;
}

function numericBounds(field: RunningHubField) {
  const duration = field.source === "DURATION_SECONDS";
  return {
    minimum: duration ? Math.max(MIN_DURATION_SECONDS, field.minimum ?? MIN_DURATION_SECONDS) : field.minimum ?? undefined,
    maximum: duration ? Math.min(MAX_DURATION_SECONDS, field.maximum ?? MAX_DURATION_SECONDS) : field.maximum ?? undefined,
  };
}

function WorkflowParameterControl({ field, value, disabled, onChange }: {
  field: RunningHubField; value: RunningHubValue | undefined; disabled: boolean; onChange: WorkflowValuesProps["onChange"];
}) {
  const label = `${field.label}${field.required ? " *" : ""}`;
  const id = useId();
  let control;
  if (field.type === "SELECT") control = <Select id={id} value={value === undefined ? "" : String(field.options?.findIndex((option) => option.value === value) ?? -1)}
    disabled={disabled} aria-label={label} onChange={(event) => onChange(field.key, event.target.value === "" ? undefined : field.options?.[Number(event.target.value)]?.value)}>
    <option value="">{t("media.runningHub.selectPlaceholder")}</option>{field.options?.map((option, index) => <option key={index} value={index}>{option.label}</option>)}
  </Select>;
  else if (field.type === "BOOLEAN") control = <Select id={id} value={value === undefined ? "" : String(value)} disabled={disabled} aria-label={label}
    onChange={(event) => onChange(field.key, event.target.value === "" ? undefined : event.target.value === "true")}>
    <option value="">{t("media.runningHub.useDefault")}</option><option value="true">{t("common.enabled")}</option><option value="false">{t("common.close")}</option>
  </Select>;
  else if (field.type === "STRING") control = <Textarea id={id} rows={2} maxLength={field.maxLength ?? DEFAULT_MAX_LENGTH} disabled={disabled}
    aria-label={label} value={value === undefined ? "" : String(value)} onChange={(event) => onChange(field.key, event.target.value)} />;
  else {
    const { minimum, maximum } = numericBounds(field);
    control = <Input id={id} type="number" min={minimum} max={maximum} step={field.type === "INTEGER" ? 1 : "any"} disabled={disabled}
      aria-label={label} value={value === undefined ? "" : Number(value)} onChange={(event) => onChange(field.key, event.target.value === "" ? undefined : Number(event.target.value))} />;
  }
  return <Field data-disabled={disabled}><FieldLabel className="sr-only" htmlFor={id}>{label}</FieldLabel>{control}</Field>;
}

function parameterConstraints(field: RunningHubField) {
  const { minimum, maximum } = numericBounds(field);
  if (field.type === "STRING") return t("media.workflow.maxLength", { "0": field.maxLength ?? DEFAULT_MAX_LENGTH });
  if (field.type === "INTEGER" || field.type === "NUMBER") return [field.type === "INTEGER" ? t("media.workflow.integer") : t("media.workflow.number"),
    minimum !== undefined ? t("media.workflow.minimum", { "0": minimum }) : "",
    maximum !== undefined ? t("media.workflow.maximum", { "0": maximum }) : ""].filter(Boolean).join(" · ");
  return field.type === "BOOLEAN" ? t("media.workflow.boolean") : t("media.workflow.select");
}

/** Edits persist through the draft's existing onChange path; closing needs no second save. */
export function WorkflowParametersDialog({ open, onOpenChange, disabled = false, ...props }: WorkflowParametersDialogProps) {
  useLocale();
  if (!open) return null;
  const fields = activeFields(props).filter((field) => !MEDIA_TYPES.has(field.type) && field.source !== "PROMPT");
  return <Dialog className="workflow-parameters-dialog nodrag nowheel nopan" title={t("media.workflow.extendedParameters")}
    description={disabled ? t("media.workflow.readOnlyParameters") : t("media.workflow.parametersHint")}
    onClose={() => onOpenChange(false)} onSubmit={(event) => { event.preventDefault(); onOpenChange(false); }}
    footer={<Button type="button" variant="outline" onClick={() => onOpenChange(false)}>{t("common.close")}</Button>}>
    {fields.length ? <Table className="workflow-parameters-table"><TableHeader><TableRow>
      <TableHead>{t("media.workflow.parameter")}</TableHead><TableHead>{t("media.workflow.value")}</TableHead><TableHead>{t("media.workflow.constraints")}</TableHead>
    </TableRow></TableHeader><TableBody>{fields.map((field) => <TableRow key={field.key}>
      <TableCell><strong>{field.label}{field.required ? " *" : ""}</strong>{field.description ? <FieldDescription>{field.description}</FieldDescription> : null}</TableCell>
      <TableCell><WorkflowParameterControl field={field} value={runningHubFieldValue(field, props.values, props.prompt, props.durationSeconds)}
        disabled={disabled} onChange={props.onChange} /></TableCell>
      <TableCell><span>{parameterConstraints(field)}</span><code title={`${field.nodeId}.${field.fieldName}`}>{field.nodeId}.{field.fieldName}</code></TableCell>
    </TableRow>)}</TableBody></Table> : <Empty><EmptyHeader><EmptyDescription>{t("media.workflow.noParameters")}</EmptyDescription></EmptyHeader></Empty>}
  </Dialog>;
}
