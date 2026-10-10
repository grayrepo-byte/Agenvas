import { PaintBrush, PencilSimple, Plus } from "@/shared/ui/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { Navigate } from "react-router";
import { ApiError, HTTP_STATUS, createMediaStyle, getMediaStyleSettings, updateMediaStyle,
  uploadMediaStyleThumbnail, type CreateMediaStyleRequest, type MediaStyle } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MAX_MEDIA_STYLE_CATEGORY_LENGTH, MAX_MEDIA_STYLE_NAME_LENGTH, MAX_MEDIA_STYLE_PROMPT_LENGTH,
  MEDIA_STYLE_PREVIEW_ACCEPT, MEDIA_STYLES_KEY, MEDIA_STYLE_SETTINGS_KEY } from "../../shared/mediaStyles";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { MediaStylePreview } from "../../shared/ui/MediaStylePreview";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Switch } from "../../shared/ui/primitives/switch";
import { Textarea } from "../../shared/ui/primitives/textarea";

function metadata(style: MediaStyle): CreateMediaStyleRequest {
  return { name: style.name, category: style.category, promptSuffix: style.promptSuffix, enabled: style.enabled };
}

function MediaStyleForm({ initial, onPersisted, onClose }: {
  initial: MediaStyle | null; onPersisted: (style: MediaStyle) => void; onClose: () => void;
}) {
  useLocale();
  const id = useId();
  const [fields, setFields] = useState<CreateMediaStyleRequest>(() => initial ? metadata(initial)
    : { name: "", category: "", promptSuffix: "", enabled: true });
  const persisted = useRef<MediaStyle | null>(initial);
  const [knownStyle, setKnownStyle] = useState(initial);
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const stage = useRef<"metadata" | "thumbnail">("metadata");
  useEffect(() => {
    if (!file) return;
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => { URL.revokeObjectURL(url); setPreviewUrl(null); };
  }, [file]);
  function remember(style: MediaStyle) {
    persisted.current = style;
    setKnownStyle(style);
    onPersisted(style);
  }
  const save = useMutation({ mutationFn: async () => {
    stage.current = "metadata";
    const previous = persisted.current;
    const values = { ...fields, name: fields.name.trim(), category: fields.category.trim(), promptSuffix: fields.promptSuffix.trim() };
    // A known metadata success survives an image failure; retry updates/uploads that same style.
    const saved = previous && JSON.stringify(metadata(previous)) === JSON.stringify(values) ? previous
      : previous ? await updateMediaStyle(previous.id, { ...values, expectedVersion: previous.version })
        : await createMediaStyle(values);
    remember(saved);
    if (file) {
      stage.current = "thumbnail";
      remember(await uploadMediaStyleThumbnail(saved.id, file, saved.version));
    }
  }, onSuccess: onClose });
  const refresh = useMutation({ mutationFn: getMediaStyleSettings, onSuccess: (styles) => {
    const fresh = styles.find((style) => style.id === persisted.current?.id);
    if (fresh) { remember(fresh); save.reset(); }
  } });
  const error = refresh.error ?? save.error;
  const forbidden = error instanceof ApiError && error.status === HTTP_STATUS.FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT;
  const busy = save.isPending || refresh.isPending;
  const preview = previewUrl ? { name: fields.name, thumbnailUrl: previewUrl } : knownStyle;
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  return <Dialog title={knownStyle ? t("styles.edit") : t("styles.add")} description={t("styles.formHint")}
    onClose={onClose} busy={busy} className="media-style-form" onSubmit={(event) => {
      event.preventDefault();
      if (!busy && !conflict && !forbidden) save.mutate();
    }} footer={<>
      <Button variant="outline" type="button" disabled={busy} onClick={onClose}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={busy || conflict || forbidden}>{busy ? t("common.savingProgress") : t("styles.save")}</Button>
    </>}>
    {preview ? <MediaStylePreview style={preview} /> : null}
    {error ? <Notice tone="danger" title={forbidden ? t("styles.forbidden") : conflict ? t("styles.conflict") : t("styles.saveFailed")}>
      <p>{stage.current === "thumbnail" && knownStyle ? t("styles.partialUpload") : error.message}</p>
      {stage.current === "thumbnail" && knownStyle ? <p>{error.message}</p> : null}
      {conflict && knownStyle ? <Button type="button" variant="outline" disabled={busy} onClick={() => refresh.mutate()}>{t("styles.refreshVersion")}</Button> : null}
    </Notice> : null}
    <fieldset disabled={busy || forbidden}>
      <FieldGroup>
        <Field><FieldLabel htmlFor={`${id}-name`}>{t("styles.name")}</FieldLabel><Input id={`${id}-name`} required
          maxLength={MAX_MEDIA_STYLE_NAME_LENGTH} value={fields.name} onChange={(event) => setFields({ ...fields, name: event.target.value })} /></Field>
        <Field><FieldLabel htmlFor={`${id}-category`}>{t("styles.category")}</FieldLabel><Input id={`${id}-category`} required
          maxLength={MAX_MEDIA_STYLE_CATEGORY_LENGTH} value={fields.category} onChange={(event) => setFields({ ...fields, category: event.target.value })} /></Field>
        <Field><FieldLabel htmlFor={`${id}-prompt`}>{t("styles.prompt")}</FieldLabel><Textarea id={`${id}-prompt`} required
          maxLength={MAX_MEDIA_STYLE_PROMPT_LENGTH} rows={4} value={fields.promptSuffix} onChange={(event) => setFields({ ...fields, promptSuffix: event.target.value })} />
          <FieldDescription>{t("styles.promptHint")}</FieldDescription></Field>
        <Field><FieldLabel htmlFor={`${id}-image`}>{t("styles.preview")}</FieldLabel><Input id={`${id}-image`} type="file"
          accept={MEDIA_STYLE_PREVIEW_ACCEPT} required={!knownStyle?.thumbnailUrl && !file} onChange={(event) => setFile(event.target.files?.[0] ?? null)} />
          <FieldDescription>{t("styles.previewHint")}</FieldDescription></Field>
        <Field orientation="horizontal"><FieldLabel htmlFor={`${id}-enabled`}>{t("styles.enabled")}</FieldLabel>
          <Switch id={`${id}-enabled`} checked={fields.enabled} onCheckedChange={(enabled) => setFields({ ...fields, enabled })} /></Field>
      </FieldGroup>
    </fieldset>
  </Dialog>;
}

/** Admin edits are independent of frozen media tasks; disabling a style retains its history. */
export function MediaStyleSettingsSection({ enabled }: { enabled: boolean }) {
  useLocale();
  const client = useQueryClient();
  const styles = useQuery({ queryKey: MEDIA_STYLE_SETTINGS_KEY, queryFn: getMediaStyleSettings, enabled, retry: false });
  const [editing, setEditing] = useState<MediaStyle | "new" | null>(null);
  function saved(style: MediaStyle) {
    client.setQueryData<MediaStyle[]>(MEDIA_STYLE_SETTINGS_KEY, (current) => current?.some((item) => item.id === style.id)
      ? current.map((item) => item.id === style.id ? style : item) : [...current ?? [], style]);
    void client.invalidateQueries({ queryKey: MEDIA_STYLES_KEY });
  }
  const toggle = useMutation({ mutationFn: (style: MediaStyle) => updateMediaStyle(style.id,
    { ...metadata(style), enabled: !style.enabled, expectedVersion: style.version }), onSuccess: saved });
  const error = toggle.error ?? styles.error;
  if (error instanceof ApiError && error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = error instanceof ApiError && error.status === HTTP_STATUS.FORBIDDEN;
  const conflict = error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT;
  return <Panel title={t("styles.settingsTitle")} description={t("styles.settingsHint")}
    actions={<Button type="button" disabled={!styles.isSuccess || forbidden} onClick={() => setEditing("new")}><Plus data-icon="inline-start" />{t("styles.add")}</Button>}>
    <div className="ui-stack">
      {styles.isPending && enabled ? <LoadingState compact label={t("styles.loading")} /> : null}
      {error ? <Notice tone="danger" title={forbidden ? t("styles.forbidden") : conflict ? t("styles.conflict") : t("styles.loadFailed")}>
        <p>{conflict ? t("settings.shared.conflict") : error.message}</p>
        {!forbidden ? <Button type="button" variant="outline" disabled={styles.isFetching || toggle.isPending}
          onClick={() => { toggle.reset(); void styles.refetch(); }}>{t("settings.shared.refresh")}</Button> : null}
      </Notice> : null}
      {styles.isSuccess && !styles.data.length ? <EmptyState icon={<PaintBrush />} title={t("styles.empty")} description={t("styles.emptyHint")} /> : null}
      <div className="media-style-settings-grid">
        {styles.data?.map((style) => <article key={style.id} className="media-style-settings-card" aria-label={style.name}>
          <MediaStylePreview style={style} /><div className="media-style-settings-card-body"><h3>{style.name}</h3><p>{style.category}</p>
            <StatusBadge tone={style.enabled ? "success" : "neutral"}>{style.enabled ? t("styles.enabled") : t("styles.disabled")}</StatusBadge>
            {style.builtIn ? <p>{t("styles.builtIn")}</p> : null}
            <div className="media-style-settings-card-actions"><Button type="button" variant="outline" size="sm" onClick={() => setEditing(style)}
              disabled={forbidden || toggle.isPending}><PencilSimple data-icon="inline-start" />{t("styles.edit")}</Button>
              <Button type="button" variant="ghost" size="sm" disabled={forbidden || conflict || toggle.isPending}
                onClick={() => toggle.mutate(style)}>{style.enabled ? t("styles.disable") : t("styles.enable")}</Button></div>
          </div>
        </article>)}
      </div>
    </div>
    {editing ? <MediaStyleForm initial={editing === "new" ? null : editing} onPersisted={saved} onClose={() => setEditing(null)} /> : null}
  </Panel>;
}
