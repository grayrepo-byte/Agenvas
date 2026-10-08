import { ArrowLeft, ArrowRight, Plus, X } from "@/shared/ui/icons";
import { useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState, type FormEvent } from "react";
import { ApiError, HTTP_STATUS, copyMediaTemplateImage, listMediaTemplates, saveMediaTemplate,
  deleteMediaTemplateImage, uploadMediaTemplateImage, type MediaTemplate, type MediaTemplateImage, type MediaTemplateKind,
  type MediaTemplateScope } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { Dialog } from "../../shared/ui/Dialog";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import "./MediaTemplates.css";

export type TemplateSeedImage = { versionId: string; thumbnailUrl: string; title: string };
type FormImage = { image: MediaTemplateImage } | { source: TemplateSeedImage; copied?: MediaTemplateImage };
const MAX_IMAGES = 8;
const MAX_NAME = 160;
const MAX_PROMPT = 20000;

export function MediaTemplateForm({ template, scope, targetKind, lockTargetKind = false, seedPrompt = "", seedImages = [], projectId,
  onClose, onSaved }: { template?: MediaTemplate; scope: MediaTemplateScope; targetKind: MediaTemplateKind;
  seedPrompt?: string; seedImages?: TemplateSeedImage[]; projectId?: string; lockTargetKind?: boolean;
  onClose: () => void; onSaved: (template: MediaTemplate) => void }) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const fileInput = useRef<HTMLInputElement>(null);
  const [name, setName] = useState(template?.name ?? "");
  const [kind, setKind] = useState(template?.targetKind ?? targetKind);
  const [prompt, setPrompt] = useState(template?.prompt ?? seedPrompt);
  const [images, setImages] = useState<FormImage[]>(template?.images.map((image) => ({ image }))
    ?? seedImages.map((source) => ({ source })));
  const [expectedVersion, setExpectedVersion] = useState(template?.version);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [failedFiles, setFailedFiles] = useState<File[]>([]);
  const [cleanupFailed, setCleanupFailed] = useState(false);
  const createdImages = useRef(new Set<string>());

  async function cleanup(ids: Iterable<string>) {
    for (const imageId of ids) { await deleteMediaTemplateImage(imageId); createdImages.current.delete(imageId); }
  }
  async function close() {
    if (busy) return;
    setBusy(true); setError(null); setCleanupFailed(false);
    try { await cleanup([...createdImages.current]); onClose(); }
    catch (failure) { setCleanupFailed(true); setError(failure instanceof Error ? failure : new Error(t("templates.cleanupFailed"))); }
    finally { setBusy(false); }
  }
  async function removeImage(index: number) {
    const entry = images[index]; if (!entry || busy) return;
    const imageId = "image" in entry ? entry.image.id : entry.copied?.id;
    setBusy(true); setError(null);
    try {
      if (imageId && createdImages.current.has(imageId)) await cleanup([imageId]);
      setImages((current) => current.filter((_, position) => position !== index));
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("templates.cleanupFailed"))); }
    finally { setBusy(false); }
  }

  async function upload(files: File[]) {
    if (images.length + files.length > MAX_IMAGES) { setError(new Error(t("templates.imageLimit", { "0": MAX_IMAGES }))); return; }
    setBusy(true); setError(null); setFailedFiles([]);
    for (const [index, file] of files.entries()) {
      try {
        const image = await uploadMediaTemplateImage(file);
        createdImages.current.add(image.id);
        setImages((current) => [...current, { image }]);
      } catch (failure) {
        setError(failure instanceof Error ? failure : new Error(t("templates.uploadFailed")));
        setFailedFiles(files.slice(index)); break;
      }
    }
    setBusy(false);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || !name.trim() || !prompt.trim() || images.length > MAX_IMAGES) return;
    setBusy(true); setError(null);
    try {
      const imageIds: string[] = [];
      for (const entry of images) {
        if ("image" in entry) imageIds.push(entry.image.id);
        else {
          if (!projectId) throw new Error(t("templates.sourceUnavailable"));
          entry.copied ??= await copyMediaTemplateImage(projectId, entry.source.versionId);
          createdImages.current.add(entry.copied.id);
          imageIds.push(entry.copied.id);
        }
      }
      await cleanup([...createdImages.current].filter((imageId) => !imageIds.includes(imageId)));
      const saved = await saveMediaTemplate({ name: name.trim(), targetKind: kind, prompt, imageIds,
        ...(template ? { expectedVersion: expectedVersion! } : {}) }, scope, template?.id);
      imageIds.forEach((imageId) => createdImages.current.delete(imageId));
      await client.invalidateQueries({ queryKey: ["media-templates"] });
      onSaved(saved);
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("templates.saveFailed"))); }
    finally { setBusy(false); }
  }
  async function refreshVersion() {
    if (!template) return;
    setBusy(true);
    try {
      const latest = (await listMediaTemplates(undefined, scope === "SYSTEM")).find((entry) => entry.id === template.id);
      if (!latest) throw new Error(t("templates.deletedConflict"));
      setExpectedVersion(latest.version); setError(null);
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("templates.loadFailed"))); }
    finally { setBusy(false); }
  }
  function move(index: number, offset: number) {
    setImages((current) => { const next = [...current]; const other = index + offset;
      if (other < 0 || other >= current.length) return current;
      [next[index], next[other]] = [next[other]!, next[index]!]; return next; });
  }
  return <Dialog title={template ? t("templates.edit") : scope === "SYSTEM" ? t("templates.createSystem") : t("templates.create")}
    description={t("templates.formHint")} onClose={() => void close()} onSubmit={(event) => void submit(event)} busy={busy}
    className="media-template-form" footer={<><Button type="button" variant="outline" disabled={busy} onClick={() => void close()}>{t("common.cancel")}</Button>
      <Button type="submit" disabled={busy || !name.trim() || !prompt.trim() || images.length > MAX_IMAGES}>{busy ? t("common.saving") : t("templates.save")}</Button></>}>
    <FieldGroup>
      <Field><FieldLabel htmlFor={`${id}-name`}>{t("templates.name")}</FieldLabel><Input id={`${id}-name`} value={name} maxLength={MAX_NAME}
        required disabled={busy} onChange={(event) => setName(event.target.value)} placeholder={t("templates.namePlaceholder")} /></Field>
      <Field><FieldLabel htmlFor={`${id}-kind`}>{t("templates.targetKind")}</FieldLabel><Select id={`${id}-kind`} value={kind} disabled={busy || lockTargetKind}
        onChange={(event) => setKind(event.target.value as MediaTemplateKind)}><option value="IMAGE">{t("common.image")}</option><option value="VIDEO">{t("common.video")}</option></Select></Field>
      <Field><FieldLabel htmlFor={`${id}-prompt`}>{t("templates.prompt")}</FieldLabel><Textarea id={`${id}-prompt`} value={prompt} rows={8}
        maxLength={MAX_PROMPT} required disabled={busy} onChange={(event) => setPrompt(event.target.value)} placeholder={t("templates.promptPlaceholder")} /></Field>
      <Field><FieldLabel>{t("templates.images")}</FieldLabel><FieldDescription>{t("templates.imagesHint", { "0": MAX_IMAGES })}</FieldDescription>
        <div className="media-template-images" role="list" aria-label={t("templates.images")}>
          {images.map((entry, index) => <div key={"image" in entry ? entry.image.id : entry.source.versionId} className="media-template-image" role="listitem">
            <img src={"image" in entry ? entry.image.thumbnailUrl : entry.source.thumbnailUrl} alt={t("templates.numberedImage", { "0": index + 1 })} />
            <span>{index + 1}</span><div className="media-template-image-actions">
              <Button type="button" variant="ghost" size="icon-sm" aria-label={t("templates.moveEarlier", { "0": index + 1 })} disabled={busy || index === 0} onClick={() => move(index, -1)}><ArrowLeft /></Button>
              <Button type="button" variant="ghost" size="icon-sm" aria-label={t("templates.moveLater", { "0": index + 1 })} disabled={busy || index === images.length - 1} onClick={() => move(index, 1)}><ArrowRight /></Button>
              <Button type="button" variant="ghost" size="icon-sm" aria-label={t("templates.removeImage", { "0": index + 1 })} disabled={busy} onClick={() => void removeImage(index)}><X /></Button>
            </div></div>)}
          <Button variant="outline" type="button" disabled={busy || images.length >= MAX_IMAGES} onClick={() => fileInput.current?.click()}><Plus data-icon="inline-start" />{t("templates.addImages")}</Button>
        </div><Input ref={fileInput} className="media-template-upload" type="file" accept={MEDIA_FILE_ACCEPT.IMAGE} multiple disabled={busy}
          aria-label={t("templates.addImages")} onChange={(event) => { const files = Array.from(event.target.files ?? []); event.target.value = ""; if (files.length) void upload(files); }} /></Field>
    </FieldGroup>
    {error ? <Notice tone="danger">{error.message}{error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT && template
      ? <><p>{t("templates.conflictHint")}</p><Button type="button" variant="outline" disabled={busy} onClick={() => void refreshVersion()}>{t("templates.refreshVersion")}</Button></> : null}
      {failedFiles.length ? <Button type="button" variant="outline" disabled={busy} onClick={() => void upload(failedFiles)}>{t("media.retryUpload")}</Button> : null}</Notice> : null}
    {cleanupFailed ? <Notice tone="warning">{t("templates.cleanupCloseHint")}<Button type="button" variant="outline" disabled={busy} onClick={onClose}>{t("templates.closeKeepingImages")}</Button></Notice> : null}
  </Dialog>;
}
