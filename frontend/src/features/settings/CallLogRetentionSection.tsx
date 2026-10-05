import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Navigate } from "react-router";
import { HTTP_STATUS,ApiError,cleanupCallLogs,getCallLogRetentionSettings,updateCallLogRetentionSettings } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice,Panel } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";

const SETTINGS_KEY = ["settings", "call-log-retention"] as const;
const MIN_DAYS = 1;
const MAX_DAYS = 3650;
const MONTH_DAYS = 30;
const QUARTER_DAYS = 90;
type Draft = { mode: "forever" | "30" | "90" | "custom"; custom: string };
function fromDays(days: number | null): Draft {
  return { mode: days === null ? "forever" : days === MONTH_DAYS ? "30" : days === QUARTER_DAYS ? "90" : "custom", custom: String(days ?? MONTH_DAYS) };
}

/** Settings stay server-owned; a separate draft survives failures, conflicts and tab changes. */
export function CallLogRetentionSection({ enabled }: { enabled: boolean }) {
  useLocale();
  const client = useQueryClient();
  const settings = useQuery({ queryKey: SETTINGS_KEY, queryFn: getCallLogRetentionSettings, enabled, retry: false });
  const [draft, setDraft] = useState<Draft | null>(null);
  const [confirmation, setConfirmation] = useState<{ days: number; version: number } | null>(null);
  const save = useMutation({ mutationFn: updateCallLogRetentionSettings,
    onSuccess: (value) => { client.setQueryData(SETTINGS_KEY, value); setDraft(null); },
  });
  const cleanup = useMutation({ mutationFn: cleanupCallLogs, retry: false,
    onSuccess: () => { void client.invalidateQueries({ queryKey: ["call-logs"] }); },
    onSettled: () => setConfirmation(null),
  });
  const error = cleanup.error ?? save.error ?? settings.error;
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === HTTP_STATUS.FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT;
  const value = draft ?? fromDays(settings.data?.retentionDays ?? null);
  const days = value.mode === "forever" ? null : value.mode === "custom" ? Number(value.custom) : Number(value.mode);
  const valid = days === null || ((value.mode !== "custom" || value.custom.trim() !== "") && Number.isInteger(days) && days >= MIN_DAYS && days <= MAX_DAYS);
  const disabled = !settings.data || settings.isError || settings.isFetching || forbidden || save.isPending || cleanup.isPending;
  const change = (next: Draft) => { setDraft(next); save.reset(); cleanup.reset(); };
  return <Panel title={t("settings.logRetention.title")} description={t("settings.logRetention.defaultPolicyHint") }>
    <div className="ui-stack">
      <Notice tone="warning" title={t("settings.logRetention.scopeTitle")}>
        <p>{t("settings.logRetention.cleanupDetails")}</p>
        <p>{t("settings.logRetention.scopeHint")}</p>
        <p>{t("settings.logRetention.recoveryWarning")}</p>
        <p>{t("settings.logRetention.manualCleanupHint")}</p>
      </Notice>
      {settings.isPending && enabled ? <LoadingState compact label={t("settings.logRetention.loading")} /> : null}
      {error ? <Notice tone="danger" title={forbidden ? t("settings.logRetention.forbidden") : conflict ? t("settings.logRetention.settingsChanged") : cleanup.isError ? t("settings.logRetention.cleanupFailed") : save.isError ? t("settings.logRetention.saveFailed") : t("settings.logRetention.loadFailed")}>
        <p>{conflict ? t("settings.shared.conflict") : t("settings.shared.sessionHint")}</p>
        {cleanup.isError ? <p>{t("settings.logRetention.cleanupUnknown")}</p> : null}
        {!forbidden ? <Button variant="outline"  type="button" disabled={settings.isFetching || save.isPending || cleanup.isPending}
          onClick={() => { save.reset(); cleanup.reset(); void settings.refetch(); }}>{t("settings.shared.refresh")}</Button> : null}
      </Notice> : null}
      <label className="ui-field call-log-retention-field"><span>{t("settings.logRetention.duration")}</span>
        <Select value={value.mode} disabled={disabled} onChange={(event) => {
          const mode = event.target.value;
          if (mode === "forever" || mode === "30" || mode === "90" || mode === "custom") change({ ...value, mode });
        }}>
          <option value="forever">{t("settings.logRetention.forever")}</option>
          <option value="30">{t("settings.logRetention.daysThirty")}</option><option value="90">{t("settings.logRetention.daysNinety")}</option>
          <option value="custom">{t("settings.logRetention.customDays")}</option>
        </Select>
      </label>
      {value.mode === "custom" ? <label className="ui-field call-log-retention-field"><span>{t("settings.logRetention.customDays")}</span>
        <Input type="number" min={MIN_DAYS} max={MAX_DAYS} step={MIN_DAYS} value={value.custom} disabled={disabled}
          aria-invalid={!valid} onChange={(event) => change({ ...value, custom: event.target.value })} />
        <small>{t("settings.logRetention.invalidDays")}</small>
      </label> : null}
      {save.isSuccess && draft === null ? <p role="status">{t("settings.logRetention.saved")}</p> : null}
      {cleanup.isSuccess ? <p role="status">{t("settings.logRetention.cleanupSummary", { "0": cleanup.data.cleanedExecutions })}</p> : null}
      {cleanup.isSuccess && cleanup.data.batchLimitReached ? <Notice tone="warning" title={t("settings.logRetention.limitReached")}><p>{t("settings.logRetention.moreExpiredHint")}</p></Notice> : null}
      <div className="ui-form-actions"><Button variant="default"  type="button"
        disabled={disabled || conflict || !valid || days === settings.data?.retentionDays}
        onClick={() => { if (settings.data && valid) save.mutate({ retentionDays: days, expectedVersion: settings.data.version }); }}>
        {save.isPending ? t("common.savingProgress") : t("settings.logRetention.save")}
      </Button>
        <Button variant="outline"  type="button" disabled={disabled || conflict || !valid || days !== settings.data?.retentionDays || settings.data?.retentionDays == null}
          onClick={() => { if (settings.data?.retentionDays != null) {
            cleanup.reset(); setConfirmation({ days: settings.data.retentionDays, version: settings.data.version });
          } }}>{cleanup.isPending ? t("settings.logRetention.cleaning") : t("settings.logRetention.cleanup")}</Button>
      </div>
      <p className="ui-muted">{settings.data?.retentionDays == null ? t("settings.logRetention.foreverHint") : days !== settings.data.retentionDays ? t("settings.logRetention.saveBeforeCleanup") : t("settings.logRetention.cleanupLimitHint")}</p>
    </div>
    {confirmation ? <Dialog title={t("settings.logRetention.confirmationTitle")} description={t("settings.logRetention.policyHint", { "0": confirmation.days })}
      busy={cleanup.isPending} onClose={() => setConfirmation(null)}
      onSubmit={(event) => { event.preventDefault(); if (!cleanup.isPending) cleanup.mutate({ expectedVersion: confirmation.version }); }}
      footer={<><Button variant="outline"  type="button" disabled={cleanup.isPending} onClick={() => setConfirmation(null)}>{t("common.cancel")}</Button>
        <Button variant="default"  type="submit" disabled={cleanup.isPending}>{cleanup.isPending ? t("settings.logRetention.cleaning") : t("settings.logRetention.confirm")}</Button></>}>
      <Notice tone="warning" title={t("settings.logRetention.scopeTitle")}><p>{t("settings.logRetention.cleanupWarning")}</p></Notice>
    </Dialog> : null}
  </Panel>;
}
