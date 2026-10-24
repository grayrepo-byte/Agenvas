import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Navigate } from "react-router";
import { ApiError, HTTP_STATUS, getCurrentUser, getDirectorSettings, updateDirectorSettings, type DirectorSettings } from "../../shared/api/client";
import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice, Panel, SummaryStrip } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import "./DirectorSettingsPage.css";

const SETTINGS_KEY = ["settings", "director"] as const;
const CONTEXT_FIELDS = [
  { name: "contextWindowTokens", label: "settings.director.window", hint: "settings.director.windowHint", min: 8192, max: 2_000_000 },
  { name: "compactionThresholdPercent", label: "settings.director.threshold", hint: "settings.director.thresholdHint", min: 1, max: 100 },
  { name: "responseReserveTokens", label: "settings.director.reserve", hint: "settings.director.reserveHint", min: 1024, max: 999_999 },
  { name: "summaryTokens", label: "settings.director.summary", hint: "settings.director.summaryHint", min: 256, max: 499_744 },
  { name: "recentToolRounds", label: "settings.director.recent", hint: "settings.director.recentHint", min: 0, max: 16 },
] as const;
const RECOVERY_FIELDS = [
  { name: "recoveryWindowMinutes", label: "settings.director.recoveryWindow", hint: "settings.director.recoveryWindowHint", min: 1, max: 10_080 },
  { name: "maxModelRetries", label: "settings.director.retries", hint: "settings.director.retriesHint", min: 1, max: 10_000 },
  { name: "retryMaxDelaySeconds", label: "settings.director.retryDelay", hint: "settings.director.retryDelayHint", min: 2, max: 3600 },
  { name: "maxRepairAttempts", label: "settings.director.repairs", hint: "settings.director.repairsHint", min: 1, max: 10 },
] as const;
const FIELDS = [...CONTEXT_FIELDS, ...RECOVERY_FIELDS];
type SettingName = typeof FIELDS[number]["name"];
type Values = Record<SettingName, string>;
type Draft = { version: number; values: Values };

function valuesFrom(settings: DirectorSettings): Values {
  return Object.fromEntries(FIELDS.map(({ name }) => [name, String(settings[name])])) as Values;
}
function numbersFrom(values: Values) {
  return Object.fromEntries(FIELDS.map(({ name }) => [name, Number(values[name])])) as Omit<DirectorSettings, "version">;
}

/** Drafts keep the version at first edit; a refresh can never silently authorize a stale save. */
export function DirectorSettingsPage() {
  useLocale();
  const client = useQueryClient();
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const administrator = session.data?.role === "ADMIN";
  const settings = useQuery({ queryKey: SETTINGS_KEY, queryFn: getDirectorSettings, enabled: administrator, retry: false });
  const [draft, setDraft] = useState<Draft | null>(null);
  const save = useMutation({ mutationFn: updateDirectorSettings,
    onSuccess: (value) => { client.setQueryData(SETTINGS_KEY, value); setDraft(null); },
  });
  const error = save.error ?? settings.error;
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = (session.isSuccess && !administrator) || (error instanceof ApiError && error.status === HTTP_STATUS.FORBIDDEN);
  const conflict = (error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT)
    || (draft !== null && settings.data !== undefined && draft.version !== settings.data.version);
  const values = draft?.values ?? (settings.data ? valuesFrom(settings.data) : null);
  const numbers = values ? numbersFrom(values) : null;
  const errors: Partial<Record<SettingName, string>> = {};
  if (values && numbers) {
    for (const field of FIELDS) {
      const value = numbers[field.name];
      if (values[field.name].trim() === "" || !Number.isInteger(value) || value < field.min || value > field.max)
        errors[field.name] = t("settings.director.range", { "0": field.min, "1": field.max });
    }
    if (numbers.responseReserveTokens >= Math.floor(numbers.contextWindowTokens / 2))
      errors.responseReserveTokens = t("settings.director.reserveInvalid");
    if (numbers.summaryTokens > Math.floor((numbers.contextWindowTokens - numbers.responseReserveTokens) / 4))
      errors.summaryTokens = t("settings.director.summaryInvalid");
  }
  const valid = numbers !== null && Object.keys(errors).length === 0;
  const disabled = !administrator || forbidden || settings.isError || settings.isFetching || save.isPending;
  const dirty = draft !== null && settings.data !== undefined && FIELDS.some(({ name }) => Number(draft.values[name]) !== settings.data?.[name] || draft.values[name].trim() === "");
  const trigger = valid && numbers ? Math.min(numbers.contextWindowTokens - numbers.responseReserveTokens,
    Math.floor(numbers.contextWindowTokens * numbers.compactionThresholdPercent / 100)) : null;
  const change = (name: SettingName, value: string) => {
    if (!settings.data || !values) return;
    setDraft({ version: draft?.version ?? settings.data.version, values: { ...values, [name]: value } });
    if (!conflict) save.reset();
  };
  const loadLatest = async () => {
    const result = await settings.refetch();
    if (result.isSuccess) { setDraft(null); save.reset(); }
  };
  const fields = (definitions: typeof CONTEXT_FIELDS | typeof RECOVERY_FIELDS) => <FieldGroup className="director-settings-fields">
    {definitions.map((field) => <Field key={field.name} data-invalid={!!errors[field.name]} data-disabled={disabled}>
      <FieldLabel htmlFor={`director-${field.name}`}>{t(field.label)}</FieldLabel>
      <Input id={`director-${field.name}`} type="number" inputMode="numeric" min={field.min} max={field.max} step={1}
        required value={values?.[field.name] ?? ""} disabled={disabled} aria-invalid={!!errors[field.name]}
        aria-describedby={`director-${field.name}-hint${errors[field.name] ? ` director-${field.name}-error` : ""}`}
        onChange={(event) => change(field.name, event.target.value)} />
      <FieldDescription id={`director-${field.name}-hint`}>{t(field.hint)}</FieldDescription>
      {errors[field.name] ? <FieldError id={`director-${field.name}-error`}>{errors[field.name]}</FieldError> : null}
    </Field>)}
  </FieldGroup>;

  return <PageShell title={t("settings.director.title")} description={t("settings.director.description")}>
    {forbidden ? <Notice tone="danger" title={t("settings.director.forbidden")}><p>{t("settings.director.adminHint")}</p></Notice> : null}
    {settings.isPending && administrator ? <LoadingState label={t("settings.director.loading")} /> : null}
    {(error || conflict) && !forbidden ? <Notice tone="danger" title={conflict ? t("settings.director.conflict") : save.isError ? t("settings.director.saveFailed") : t("settings.director.loadFailed")}>
      <p>{conflict ? t("settings.director.conflictHint") : t("settings.shared.sessionHint")}</p>
      <Button variant="outline" type="button" disabled={settings.isFetching || save.isPending} onClick={() => void loadLatest()}>
        {t("settings.director.loadLatest")}
      </Button>
    </Notice> : null}
    {settings.data && values ? <form className="director-settings-form" onSubmit={(event) => {
      event.preventDefault();
      if (valid && numbers && dirty && !disabled && !conflict) save.mutate({ ...numbers, expectedVersion: draft?.version ?? settings.data.version });
    }}>
      <SummaryStrip items={[
        { label: t("settings.director.trigger"), value: trigger === null ? "—" : t("settings.director.tokens", { "0": trigger.toLocaleString(getFormatLocale()) }), detail: t("settings.director.estimateHint") },
        { label: t("settings.director.scope"), value: t("settings.director.scopeValue"), detail: t("settings.director.scopeHint") },
      ]} />
      <Panel title={t("settings.director.contextTitle")} description={t("settings.director.contextHint")}>{fields(CONTEXT_FIELDS)}</Panel>
      <Panel title={t("settings.director.recoveryTitle")} description={t("settings.director.recoveryHint")}>{fields(RECOVERY_FIELDS)}</Panel>
      <div className="director-settings-save">
        <p className="ui-muted" role="status">{save.isSuccess && draft === null ? t("settings.director.saved") : t("settings.director.effectHint")}</p>
        <Button type="submit" disabled={disabled || conflict || !dirty || !valid}>{save.isPending ? t("common.savingProgress") : t("settings.director.save")}</Button>
      </div>
    </form> : null}
  </PageShell>;
}
