import { ArrowRight, ImageSquare, MusicNotes, VideoCamera } from "@/shared/ui/icons";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import {
  ApiError, HTTP_STATUS, getMediaSettings, setMediaDefault, type MediaSettings,
} from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";

const DEFAULT_KINDS = [
  { kind: "IMAGE_GENERATION", label: "settings.mediaWorkspace.imageDefault", icon: ImageSquare },
  { kind: "VIDEO_GENERATION", label: "settings.mediaWorkspace.videoDefault", icon: VideoCamera },
  { kind: "AUDIO_GENERATION", label: "settings.mediaWorkspace.audioDefault", icon: MusicNotes },
] as const;
type DefaultKind = typeof DEFAULT_KINDS[number]["kind"];
type DefaultDraft = { kind: DefaultKind; version: number | null; capabilityId: string };
const SETTINGS_KEY = ["settings", "media"] as const;

function availableChoices(settings: MediaSettings, kind: DefaultKind) {
  return settings.connections.filter((connection) => connection.enabled && connection.platform !== "LOCAL")
    .flatMap((connection) => connection.capabilities.filter((capability) => capability.enabled && capability.kind === kind)
      .map((capability) => ({ capability, connection })));
}

function draftFor(settings: MediaSettings, kind: DefaultKind): DefaultDraft {
  const current = settings.defaults.find((entry) => entry.kind === kind);
  return { kind, version: current?.version ?? null, capabilityId: current?.capabilityId ?? "" };
}

/** Default selection has its own explicit save and pins the version when the dialog opens. */
export function MediaDefaultsSection({ settings, onApply, onPublish }: {
  settings: MediaSettings;
  onApply: (settings: MediaSettings) => void;
  onPublish: () => void;
}) {
  useLocale();
  const queryClient = useQueryClient();
  const selectId = useId();
  const [draft, setDraft] = useState<DefaultDraft | null>(null);
  const [conflicted, setConflicted] = useState(false);
  const [notice, setNotice] = useState("");
  const save = useMutation({
    mutationFn: ({ kind, version, capabilityId }: DefaultDraft & { version: number }) =>
      setMediaDefault(kind, { expectedVersion: version, capabilityId }),
    onSuccess: (result) => {
      onApply(result);
      setDraft(null);
      setConflicted(false);
      setNotice(t("settings.mediaWorkspace.defaultSaved"));
    },
    onError: (cause) => {
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) {
        setConflicted(true);
        void queryClient.invalidateQueries({ queryKey: SETTINGS_KEY });
      }
    },
  });
  const reload = useMutation({
    mutationFn: getMediaSettings,
    onSuccess: (result) => {
      onApply(result);
      setDraft((current) => current ? draftFor(result, current.kind) : null);
      setConflicted(false);
      save.reset();
    },
  });
  const choices = draft ? availableChoices(settings, draft.kind) : [];
  const currentVersion = draft ? settings.defaults.find((entry) => entry.kind === draft.kind)?.version ?? null : null;
  const stale = draft !== null && (conflicted || currentVersion !== draft.version);
  const busy = save.isPending || reload.isPending;
  const selectedAvailable = choices.some(({ capability }) => capability.id === draft?.capabilityId);
  const dialogKind = DEFAULT_KINDS.find((entry) => entry.kind === draft?.kind);

  return <Panel className="media-defaults-section" title={t("settings.mediaWorkspace.defaultsTitle")}
    description={t("settings.mediaWorkspace.defaultsDescription")}>
    <div className="media-defaults-grid">{DEFAULT_KINDS.map(({ kind, label, icon: Icon }) => {
      const currentId = settings.defaults.find((entry) => entry.kind === kind)?.capabilityId;
      const connection = settings.connections.find((entry) => entry.capabilities.some((capability) => capability.id === currentId));
      const capability = connection?.capabilities.find((entry) => entry.id === currentId);
      const available = Boolean(connection?.enabled && capability?.enabled);
      return <article className="media-default-card" key={kind} aria-label={t(label)}
        data-kind={kind} data-state={currentId ? available ? "available" : "unavailable" : "empty"}>
        <div className="media-default-card-heading"><span className="media-default-icon"><Icon aria-hidden /></span>
          <span>{t(label)}</span>
          {currentId ? <StatusBadge tone={available ? "success" : "warning"}>{available
            ? t("settings.mediaSettings.default") : t("settings.mediaWorkspace.defaultUnavailable")}</StatusBadge> : null}
        </div>
        <strong className="media-default-card-name" title={capability ? `${capability.name} · ${connection?.name ?? ""}` : t("settings.mediaWorkspace.defaultMissingHint")}>{capability?.name ?? t(currentId ? "settings.mediaWorkspace.defaultUnavailable" : "settings.mediaWorkspace.noDefault")}</strong>
        <span className="media-default-card-source">{connection?.name ?? t("settings.mediaWorkspace.defaultMissingHint")}</span>
        <div className="media-default-card-footer"><span>{t("settings.mediaWorkspace.availableCount", { "0": availableChoices(settings, kind).length })}</span>
          <Button className="media-default-action" type="button" size="sm" variant="ghost"
            aria-label={t(currentId ? "settings.mediaWorkspace.changeDefault" : "settings.mediaWorkspace.chooseDefault")}
            title={t(currentId ? "settings.mediaWorkspace.changeDefault" : "settings.mediaWorkspace.chooseDefault")} onClick={() => {
            setDraft(draftFor(settings, kind)); setConflicted(false); setNotice(""); save.reset(); reload.reset();
          }}><span>{t(currentId ? "settings.mediaWorkspace.changeDefault" : "settings.mediaWorkspace.chooseDefault")}</span><ArrowRight data-icon="inline-end" /></Button>
        </div>
      </article>;
    })}</div>
    {notice ? <Notice tone="success">{notice}</Notice> : null}
    {draft && dialogKind ? <Dialog compact title={`${t(dialogKind.label)} · ${t("settings.mediaWorkspace.chooseDefault")}`}
      description={t("settings.mediaWorkspace.defaultDialogHint")} busy={busy}
      onClose={() => setDraft(null)} onSubmit={(event) => {
        event.preventDefault();
        if (!busy && !stale && selectedAvailable && draft.version !== null) save.mutate({ ...draft, version: draft.version });
      }} footer={<>
        <Button type="button" variant="outline" disabled={busy} onClick={() => setDraft(null)}>{t("common.cancel")}</Button>
        {choices.length > 0 ? <Button type="submit" disabled={busy || stale || draft.version === null || !selectedAvailable}>
          {save.isPending ? t("common.savingProgress") : t("settings.mediaWorkspace.saveDefault")}</Button> : null}
      </>}>
      <div className="ui-stack">
        {stale ? <Notice tone="warning" title={t("settings.mediaSettings.versionChanged")}>
          <p>{t("settings.mediaSettings.refreshDraftHint", { "0": t("settings.mediaSettings.defaultCapability") })}</p>
          <Button type="button" variant="outline" disabled={busy} onClick={() => reload.mutate()}>{t("common.refreshVersion")}</Button>
        </Notice> : null}
        {choices.length > 0 ? <FieldGroup><Field><FieldLabel htmlFor={selectId}>{t("settings.mediaWorkspace.defaultSelectionLabel")}</FieldLabel>
          <Select id={selectId} value={draft.capabilityId} disabled={busy} onChange={(event) => setDraft({ ...draft, capabilityId: event.target.value })}>
            <option value="" disabled>{t("settings.mediaWorkspace.chooseDefault")}</option>
            {draft.capabilityId && !selectedAvailable ? <option value={draft.capabilityId} disabled>{t("settings.mediaWorkspace.defaultUnavailable")}</option> : null}
            {choices.map(({ capability, connection }) => <option key={capability.id} value={capability.id}>{capability.name} · {connection.name}</option>)}
          </Select>
        </Field></FieldGroup> : <EmptyState title={t("settings.mediaWorkspace.noDefault")}
          description={t("settings.mediaWorkspace.defaultMissingHint")}
          action={<Button type="button" onClick={() => { setDraft(null); onPublish(); }}>{t("settings.mediaSettings.publishNewCapability")}</Button>} />}
        {draft.version === null && choices.length > 0 ? <Notice tone="warning">{t("settings.mediaSettings.defaultVersionMissing")}</Notice> : null}
        {save.error || reload.error ? <Notice tone="danger">{(save.error ?? reload.error) instanceof ApiError
          ? (save.error ?? reload.error)?.message : t("settings.shared.saveFailed")}</Notice> : null}
      </div>
    </Dialog> : null}
  </Panel>;
}
