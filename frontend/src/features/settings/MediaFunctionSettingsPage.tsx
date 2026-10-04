import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, Navigate } from "react-router";
import { HTTP_STATUS, ApiError, getCurrentUser, getMediaFunctions, getMediaSettings, updateMediaFunction,
  type MediaFunctionSetting, type MediaSettings } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MEDIA_FUNCTIONS_QUERY_KEY, IMAGE_OPERATIONS, VIDEO_OPERATIONS, imageFunction, videoFunction, mediaFunctionChoices, mediaFunctionLabel } from "../../shared/mediaFunctions";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice, Panel } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Select } from "../../shared/ui/Select";
import "./MediaFunctionSettingsPage.css";

function FunctionRow({ setting, settings }: { setting: MediaFunctionSetting; settings: MediaSettings }) {
  const client = useQueryClient();
  const [draft, setDraft] = useState({ capabilityId: setting.capabilityId ?? "", version: setting.version });
  const [saved, setSaved] = useState(false);
  const choices = mediaFunctionChoices(settings, setting.operation);
  const dirty = draft.capabilityId !== (setting.capabilityId ?? "");
  const stale = draft.version !== setting.version;
  const save = useMutation({
    mutationFn: () => updateMediaFunction(setting.operation, draft.version, draft.capabilityId || null),
    onSuccess: (result) => {
      client.setQueryData(MEDIA_FUNCTIONS_QUERY_KEY, result);
      const updated = result.find((entry) => entry.operation === setting.operation);
      if (updated) setDraft({ capabilityId: updated.capabilityId ?? "", version: updated.version });
      setSaved(true);
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) void client.invalidateQueries({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY });
    },
  });
  const label = mediaFunctionLabel(setting.operation);
  return <section className="media-function-row" aria-label={label}>
    <div><h3>{label}</h3><p className="ui-muted">{setting.operation.startsWith("IMAGE_") ? t("media.functions.imageHint") : setting.operation === "VIDEO_UPSCALE" ? t("media.functions.upscaleHint")
      : setting.operation === "VIDEO_DEPTH_MAP" ? t("media.functions.depthHint") : t("media.functions.audioHint")}</p></div>
    <div className="ui-stack">
      <label className="ui-field">{t("media.functions.method")}<Select value={draft.capabilityId} disabled={save.isPending}
        aria-label={`${label} · ${t("media.functions.method")}`} onChange={(event) => {
          setDraft({ ...draft, capabilityId: event.target.value }); setSaved(false); save.reset();
        }}>
        <option value="">{t("media.functions.unconfigured")}</option>
        {choices.map(({ capability, label: choiceLabel }) => <option key={capability.id} value={capability.id}>{choiceLabel}</option>)}
        {draft.capabilityId && !choices.some(({ capability }) => capability.id === draft.capabilityId)
          ? <option value={draft.capabilityId} disabled>{t("media.functions.unavailable")}</option> : null}
      </Select></label>
      {save.error ? <Notice tone="danger">{save.error.message}</Notice> : null}
      {stale ? <Notice tone="warning">{t("media.functions.changed")}<Button variant="outline" type="button" disabled={save.isPending}
        onClick={() => { setDraft({ capabilityId: setting.capabilityId ?? "", version: setting.version }); save.reset(); setSaved(false); }}>{t("common.refresh")}</Button></Notice> : null}
      {saved ? <p role="status">{t("media.functions.saved")}</p> : null}
      <Button type="button" className="media-function-save" disabled={!dirty || stale || save.isPending}
        onClick={() => { setSaved(false); save.mutate(); }}>{save.isPending ? t("common.savingProgress") : t("common.saveConfig")}</Button>
    </div>
  </section>;
}

export function MediaFunctionSettingsPage() {
  useLocale();
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const functions = useQuery({ queryKey: MEDIA_FUNCTIONS_QUERY_KEY, queryFn: getMediaFunctions, enabled: session.isSuccess, retry: false });
  const settings = useQuery({ queryKey: ["settings", "media"], queryFn: getMediaSettings, enabled: session.isSuccess, retry: false });
  const loadError = session.error ?? functions.error ?? settings.error;
  if (loadError instanceof ApiError && loadError.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  return <PageShell title={t("media.functions.title")} description={t("media.functions.description")}
    actions={<Link className="secondary-button" to="/settings/media">{t("ui.pageShell.mediaSettings")}</Link>}>
    {!loadError && (functions.isPending || settings.isPending) ? <LoadingState label={t("app.pageLoading")} /> : null}
    {loadError ? <Notice tone="danger"><p>{loadError.message}</p>
      <Button variant="outline" type="button" onClick={() => { void session.refetch(); void functions.refetch(); void settings.refetch(); }}>{t("common.retry")}</Button></Notice> : null}
    {functions.data && settings.data ? [
      { title: t("media.functions.imageTools"), operations: IMAGE_OPERATIONS.map(imageFunction) },
      { title: t("media.functions.videoTools"), operations: VIDEO_OPERATIONS.map(videoFunction) },
    ].map((group) => <Panel key={group.title} title={group.title} description={t("media.functions.catalogHint")}>
      {group.operations.map((operation) => {
        const setting = functions.data.find((entry) => entry.operation === operation);
        return setting ? <FunctionRow key={operation} setting={setting} settings={settings.data} /> : null;
      })}
    </Panel>) : null}
    <p className="ui-muted">{t("media.functions.compatibilityHint")}</p>
  </PageShell>;
}
