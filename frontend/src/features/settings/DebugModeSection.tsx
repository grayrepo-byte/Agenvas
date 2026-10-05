import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useId,useState } from "react";
import { Link,Navigate } from "react-router";
import { HTTP_STATUS,ApiError,getDebugSettings,updateDebugSettings } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Field,FieldGroup,FieldLabel } from "../../shared/ui/primitives/field";
import { Switch } from "../../shared/ui/primitives/switch";

const SETTINGS_KEY = ["settings", "debug"] as const;

export function DebugModeSection({ enabled }: { enabled: boolean }) {
  useLocale();
  const switchId = useId();
  const client = useQueryClient();
  const settings = useQuery({ queryKey: SETTINGS_KEY, queryFn: getDebugSettings, enabled, retry: false });
  const [draft, setDraft] = useState<boolean | null>(null);
  const save = useMutation({
    mutationFn: updateDebugSettings,
    onSuccess: (value) => { client.setQueryData(SETTINGS_KEY, value); setDraft(null); },
  });
  const error = save.error ?? settings.error;
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === HTTP_STATUS.FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT;
  const checked = draft ?? settings.data?.debugMode ?? false;
  const disabled = !settings.data || settings.isError || forbidden || save.isPending;

  return <Panel title={t("settings.debug.title")} description={t("settings.debug.defaultHint")}
    actions={<StatusBadge tone={settings.data?.debugMode ? "warning" : "neutral"}>{settings.data ? settings.data.debugMode ? t("settings.debug.enabledState") : t("settings.debug.disabledState") : t("settings.debug.unread")}</StatusBadge>}>
    <div className="ui-stack">
      <Notice tone="warning" title={t("settings.debug.riskTitle")}>
        <p>{t("settings.debug.privacyWarning")}</p>
        <p>{t("settings.debug.collectionHint")}</p>
      </Notice>
      {settings.isPending && enabled ? <LoadingState compact label={t("settings.debug.loading")} /> : null}
      {error ? <Notice tone="danger" title={forbidden ? t("settings.debug.forbidden") : conflict ? t("settings.debug.conflictTitle") : save.isError ? t("settings.debug.saveFailed") : t("settings.debug.loadFailed")}>
        <p>{conflict ? t("settings.shared.conflict") : t("settings.shared.sessionHint")}</p>
        {!forbidden ? <Button variant="outline"  type="button" disabled={settings.isFetching || save.isPending}
          onClick={() => { save.reset(); void settings.refetch(); }}>{t("settings.shared.refresh")}</Button> : null}
      </Notice> : null}
      <FieldGroup>
        <Field orientation="horizontal" className="w-fit" data-disabled={disabled}>
          <Switch id={switchId} checked={checked} disabled={disabled}
            onCheckedChange={(value) => { setDraft(value); save.reset(); }} />
          <FieldLabel htmlFor={switchId}>{t("settings.debug.enable")}</FieldLabel>
        </Field>
      </FieldGroup>
      {save.isSuccess && draft === null ? <p role="status">{t("settings.debug.saved", { "0": settings.data?.debugMode ? t("common.enabled") : t("common.close") })}</p> : null}
      <div className="ui-form-actions">
        <Link className="secondary-button" to="/settings/calls">{t("settings.debug.viewLogs")}</Link>
        <Button variant="default"  type="button" disabled={!settings.data || settings.isError || forbidden || conflict || save.isPending || checked === settings.data.debugMode}
          onClick={() => { if (settings.data) save.mutate({ debugMode: checked, expectedVersion: settings.data.version }); }}>{save.isPending ? t("common.savingProgress") : t("settings.debug.save")}</Button>
      </div>
    </div>
  </Panel>;
}
