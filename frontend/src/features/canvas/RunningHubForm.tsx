import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { useState } from "react";
import type { RunningHubDefinition,RunningHubField } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";

export type RunningHubValue = string | number | boolean;
export type RunningHubChoice = { id: string; label: string; kind: string; available: boolean };
const MIN_DURATION_SECONDS = 1;
const MAX_DURATION_SECONDS = 60;
export function runningHubUsedVersions(definition: RunningHubDefinition, values: Record<string, RunningHubValue>, prompt: string, seconds: number | null | undefined) {
  const effective = Object.fromEntries(definition.fields.map((field) => [field.key, runningHubFieldValue(field, values, prompt, seconds)]));
  return new Set(definition.fields.filter((field) => ["IMAGE", "AUDIO", "VIDEO"].includes(field.type)
    && (!field.enabledWhen || effective[field.enabledWhen.field] === field.enabledWhen.value))
    .map((field) => effective[field.key]).filter((value) => typeof value === "string"));
}
export function runningHubFieldValue(field: RunningHubField, values: Record<string, RunningHubValue>, prompt: string, seconds: number | null | undefined) {
  return field.source === "PROMPT" ? prompt || (field.defaultValue ?? undefined)
    : field.source === "DURATION_SECONDS" ? seconds ?? field.defaultValue ?? undefined : values[field.key] ?? field.defaultValue ?? undefined;
}
export function runningHubErrors(definition: RunningHubDefinition, values: Record<string, RunningHubValue>, prompt: string,
  seconds: number | null | undefined, choices: RunningHubChoice[]) {
  const effective = Object.fromEntries(definition.fields.map((field) => [field.key, runningHubFieldValue(field, values, prompt, seconds)]));
  const errors: string[] = [];
  for (const field of definition.fields) {
    if (field.enabledWhen && effective[field.enabledWhen.field] !== field.enabledWhen.value) continue;
    const value = effective[field.key];
    if (value === undefined || value === "") { if (field.required) errors.push(t("请填写“{0}”", { "0": field.label })); continue; }
    const valid = field.type === "STRING" ? typeof value === "string" && value.length <= (field.maxLength ?? 20000)
      : field.type === "BOOLEAN" ? typeof value === "boolean"
      : field.type === "NUMBER" || field.type === "INTEGER" ? typeof value === "number" && Number.isFinite(value)
        && (field.type !== "INTEGER" || Number.isInteger(value)) && (field.minimum == null || value >= field.minimum) && (field.maximum == null || value <= field.maximum)
        && (field.source !== "DURATION_SECONDS" || value >= MIN_DURATION_SECONDS && value <= MAX_DURATION_SECONDS)
      : field.type === "SELECT" ? field.options?.some((option) => option.value === value)
      : choices.some((choice) => choice.id === value && choice.kind === field.type && choice.available);
    if (!valid) errors.push(t("“{0}”的值或素材版本不可用", { "0": field.label }));
  }
  if (Object.keys(values).some((key) => !definition.fields.some((field) => field.key === key))) errors.push(t("能力字段已变化，请重新核对草稿"));
  return errors;
}

function UploadSlot({ field, disabled, onUpload }: { field: RunningHubField; disabled: boolean; onUpload: (field: RunningHubField, file: File) => Promise<void> }) {
  useLocale();
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function upload(selected: File) {
    setFile(selected); setBusy(true); setError("");
    try { await onUpload(field, selected); setFile(null); }
    catch (failure) { setError(failure instanceof Error ? failure.message : t("上传失败")); }
    finally { setBusy(false); }
  }
  return <div>
    <Field><FieldLabel className="ui-field block">{t("上传{0}", { "0": field.label })}<Input type="file" disabled={disabled || busy}
      accept={field.type === "IMAGE" ? "image/png,image/jpeg,image/webp" : field.type === "VIDEO" ? "video/mp4" : "audio/mpeg,audio/wav,audio/flac"}
      onChange={(event) => { const selected = event.target.files?.[0]; event.target.value = ""; if (selected) void upload(selected); }} /></FieldLabel></Field>
    {busy ? <p role="status">{t("正在上传并保存精确版本…")}</p> : null}
    {error ? <p role="alert">{error}{file ? <Button variant="ghost" type="button" disabled={busy || disabled} onClick={() => void upload(file)}>{t("重试上传")}</Button> : null}</p> : null}
  </div>;
}

export function RunningHubForm({ definition, values, prompt, durationSeconds, choices, disabled = false, onChange, onUpload }: {
  definition: RunningHubDefinition; values: Record<string, RunningHubValue>; prompt: string; durationSeconds: number | null | undefined;
  choices: RunningHubChoice[]; disabled?: boolean;
  onChange: (key: string, value: RunningHubValue | undefined) => void;
  onUpload?: (field: RunningHubField, file: File) => Promise<void>;
}) {
  useLocale();
  const effective = Object.fromEntries(definition.fields.map((field) => [field.key, runningHubFieldValue(field, values, prompt, durationSeconds)]));
  function control(field: RunningHubField) {
    if (field.enabledWhen && effective[field.enabledWhen.field] !== field.enabledWhen.value) return null;
    const value = effective[field.key];
    const isMedia = ["IMAGE", "AUDIO", "VIDEO"].includes(field.type);
    return <div className="ui-stack" key={field.key}>
      <Field><FieldLabel className="ui-field block">{field.label}{field.required ? " *" : ""}
        {isMedia ? <Select value={typeof value === "string" ? value : ""} disabled={disabled} onChange={(event) => onChange(field.key, event.target.value || undefined)}>
          <option value="">{t("选择{0}精确版本", { "0": field.type === "IMAGE" ? t("图片") : field.type === "AUDIO" ? t("音频") : t("视频") })}</option>
          {choices.filter((choice) => choice.kind === field.type).map((choice) => <option key={choice.id} value={choice.id} disabled={!choice.available}>{choice.label}</option>)}
          {value && !choices.some((choice) => choice.id === value) ? <option value={String(value)}>{t("已保存版本（正在核对或不可用）")}</option> : null}
        </Select> : field.type === "SELECT" ? <Select value={value === undefined ? "" : String(field.options?.findIndex((option) => option.value === value) ?? -1)} disabled={disabled}
          onChange={(event) => onChange(field.key, event.target.value ? field.options?.[Number(event.target.value)]?.value : undefined)}>
          <option value="">{t("请选择")}</option>{field.options?.map((option, index) => <option key={index} value={index}>{option.label}</option>)}
        </Select> : field.type === "BOOLEAN" ? <Select value={value === undefined ? "" : String(value)} disabled={disabled} onChange={(event) => onChange(field.key, event.target.value ? event.target.value === "true" : undefined)}>
          <option value="">{t("使用默认值")}</option><option value="true">{t("开启")}</option><option value="false">{t("关闭")}</option>
        </Select> : field.type === "STRING" ? <Textarea rows={3} maxLength={field.maxLength ?? 20000} disabled={disabled} value={value === undefined ? "" : String(value)}
          onChange={(event) => onChange(field.key, event.target.value)} /> : <Input type="number" value={value === undefined ? "" : Number(value)} disabled={disabled}
          min={field.source === "DURATION_SECONDS" ? Math.max(MIN_DURATION_SECONDS, field.minimum ?? MIN_DURATION_SECONDS) : field.minimum ?? undefined}
          max={field.source === "DURATION_SECONDS" ? Math.min(MAX_DURATION_SECONDS, field.maximum ?? MAX_DURATION_SECONDS) : field.maximum ?? undefined} step={field.type === "INTEGER" ? 1 : "any"}
          onChange={(event) => onChange(field.key, event.target.value ? Number(event.target.value) : undefined)} />}
      </FieldLabel></Field>
      {field.description ? <p className="ui-muted">{field.description}</p> : null}
      {isMedia && onUpload ? <UploadSlot field={field} disabled={disabled} onUpload={onUpload} /> : null}
    </div>;
  }
  return <div className="ui-stack runninghub-form" aria-label={t("能力专属参数")}>
    {definition.fields.filter((field) => !field.advanced).map(control)}
    {definition.fields.some((field) => field.advanced) ? <details><summary>{t("高级参数")}</summary><div className="ui-stack">{definition.fields.filter((field) => field.advanced).map(control)}</div></details> : null}
  </div>;
}
