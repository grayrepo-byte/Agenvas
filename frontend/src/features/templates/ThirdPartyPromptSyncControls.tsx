import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { ApiError, getThirdPartyPromptSyncSettings, HTTP_STATUS, updateThirdPartyPromptSyncSettings } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Switch } from "../../shared/ui/primitives/switch";

const SETTINGS_KEY = ["settings", "third-party-prompt-sync"] as const;
type Draft = { enabled: boolean; version: number };

/** Consent is explicit, versioned and shared by both image and video source panels. */
export function ThirdPartyPromptSyncControls() {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const settings = useQuery({ queryKey: SETTINGS_KEY, queryFn: getThirdPartyPromptSyncSettings, retry: false });
  const [draft, setDraft] = useState<Draft | null>(null);
  const save = useMutation({ mutationFn: updateThirdPartyPromptSyncSettings,
    onSuccess: (value) => { client.setQueryData(SETTINGS_KEY, value); setDraft(null); },
  });
  const error = save.error ?? settings.error;
  const conflict = (error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT)
    || (draft !== null && settings.data !== undefined && draft.version !== settings.data.version);
  const enabled = draft?.enabled ?? settings.data?.enabled ?? false;
  const busy = save.isPending || settings.isFetching;
  const disabled = busy || !settings.data || settings.isError;
  const reload = async () => {
    const result = await settings.refetch();
    if (result.isSuccess) { setDraft(null); save.reset(); }
  };
  return <FieldGroup>
    {settings.isLoading ? <LoadingState compact label={t("templates.loading")} /> : null}
    <Field>
      <FieldLabel htmlFor={id}>{t("thirdParty.autoSyncTitle")}</FieldLabel>
      <Switch id={id} checked={enabled} disabled={disabled} onCheckedChange={(value) => {
        if (!settings.data) return;
        setDraft({ enabled: value, version: draft?.version ?? settings.data.version });
        if (!conflict) save.reset();
      }} />
      <FieldDescription>{t("thirdParty.autoSyncHint")}</FieldDescription>
      <FieldDescription>{t("thirdParty.autoSyncSources")}</FieldDescription>
      <FieldDescription>{t("thirdParty.autoSyncDisabledHint")}</FieldDescription>
    </Field>
    {settings.data && !settings.data.deploymentEnabled ? <Notice tone="warning">{t("thirdParty.autoSyncDeploymentOff")}</Notice> : null}
    {error ? <Notice tone="danger">{error.message}</Notice> : null}
    {conflict ? <Notice tone="warning">{t("thirdParty.autoSyncConflict")}</Notice> : null}
    <div className="ui-form-actions">
      <Button type="button" disabled={disabled || !draft || conflict || draft.enabled === settings.data?.enabled}
        onClick={() => { if (draft) save.mutate({ enabled: draft.enabled, expectedVersion: draft.version }); }}>
        {t("thirdParty.autoSyncSave")}
      </Button>
      {(settings.isError || conflict) ? <Button type="button" variant="outline" disabled={busy}
        onClick={() => void reload()}>{t("thirdParty.autoSyncReload")}</Button> : null}
    </div>
  </FieldGroup>;
}
