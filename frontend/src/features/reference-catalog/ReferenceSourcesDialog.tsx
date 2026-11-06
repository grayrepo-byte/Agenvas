import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { addReferenceSource, listReferenceSources, syncReferenceSource, updateReferenceSource, type CreateReferenceSource, type ReferenceSource } from "../../shared/api/referenceCatalog";
import { formatDate, t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";

import { ReferenceCacheManager } from "./ReferenceCacheManager";
import { referenceFailureMessage } from "./referenceFailure";

const EMPTY_SOURCE: CreateReferenceSource = { id: "", name: "", owner: "", repository: "", ref: "main", format: "AGENVAS_REFERENCE_PACK", manifestPath: "manifest.json", imageDistributionApproved: false };
export function ReferenceSourcesDialog({ onClose }: { onClose: () => void }) {
  useLocale(); const id = useId(); const client = useQueryClient();
  const sources = useQuery({ queryKey: ["reference-catalog-sources"], queryFn: listReferenceSources, retry: false });
  const [form, setForm] = useState(EMPTY_SOURCE);
  const [notice, setNotice] = useState("");
  const [syncFailed, setSyncFailed] = useState(false);
  const write = useMutation({ mutationFn: async (action: { kind: "ADD" } | { kind: "SYNC" | "DISABLE" | "RIGHTS"; source: ReferenceSource }) => {
    if (action.kind === "ADD") return { source: await addReferenceSource(form), state: null };
    if (action.kind === "SYNC") return syncReferenceSource(action.source.id, action.source.version);
    return { state: null, source: await updateReferenceSource(action.source.id, { expectedVersion: action.source.version,
      enabled: action.kind === "DISABLE" ? !action.source.enabled : action.source.enabled,
      imageDistributionApproved: action.kind === "RIGHTS" ? !action.source.imageDistributionApproved : action.source.imageDistributionApproved }) };
  }, onSuccess: (saved, action) => {
    if (action.kind === "ADD") setForm(EMPTY_SOURCE);
    setSyncFailed(saved.state === "FAILED");
    setNotice(t(action.kind === "SYNC" ? saved.state === "SUCCESS" ? "reference.syncDone" : saved.state === "FAILED" ? "reference.syncRetained" : "reference.syncPending" : "reference.sourceSaved"));
    client.setQueryData<{ items: ReferenceSource[] }>(["reference-catalog-sources"], (current) => current ? { items: [...current.items.filter((source) => source.id !== saved.source.id), saved.source] } : { items: [saved.source] });
    void client.invalidateQueries({ queryKey: ["reference-catalog-sources"] });
    void client.invalidateQueries({ queryKey: ["reference-catalog"] });
  } });
  return <Dialog className="reference-sources-dialog" title={t("reference.sources")} description={t("reference.manualSyncHint")} busy={write.isPending} onClose={onClose}
    onSubmit={(event) => { event.preventDefault(); write.mutate({ kind: "ADD" }); }} footer={<Button type="button" variant="outline" disabled={write.isPending} onClick={onClose}>{t("common.close")}</Button>}>
    {sources.isPending ? <p role="status">{t("common.loading")}</p> : null}
    {sources.error ? <Notice tone="danger"><p>{sources.error.message}</p><Button type="button" variant="outline" onClick={() => void sources.refetch()}>{t("common.retry")}</Button></Notice> : null}
    {write.error ? <Notice tone="danger"><p>{t("common.inputPreserved", { "0": write.error.message })}</p><p>{t("reference.syncRetained")}</p><Button type="button" variant="outline" disabled={write.isPending} onClick={() => void sources.refetch()}>{t("common.refresh")}</Button></Notice> : null}
    {notice ? syncFailed ? <Notice tone="danger"><p>{notice}</p></Notice> : <p role="status">{notice}</p> : null}
    <div className="reference-source-list">{sources.data?.items.map((source) => <section key={source.id} className="reference-source">
      <strong>{source.name}</strong><p>{source.owner}/{source.repository} · {source.ref} · {source.enabled ? t("reference.enabled") : t("reference.disabled")}</p>
      <p>{source.format !== "OPEN_POSE_COSTUME" && source.imageDistributionApproved ? t("reference.imagesApproved") : t("reference.metadataOnly")}</p>
      {source.resolvedCommit ? <p>{t("reference.pinnedCommit", { "0": source.resolvedCommit })}</p> : null}
      {source.syncedAt ? <p>{t("reference.lastSync", { "0": formatDate(source.syncedAt) })}</p> : null}
      {source.lastSyncError ? <Notice tone="danger"><p>{referenceFailureMessage(source.lastSyncError)}</p><p>{t("reference.syncRetained")}</p></Notice> : null}
      <div className="ui-form-actions"><Button type="button" variant="outline" disabled={write.isPending || !source.enabled} onClick={() => write.mutate({ kind: "SYNC", source })}>{t("reference.sync")}</Button>
      <Button type="button" variant="ghost" disabled={write.isPending} onClick={() => write.mutate({ kind: "DISABLE", source })}>{t(source.enabled ? "reference.disable" : "reference.enable")}</Button></div>
      <Field orientation="horizontal"><Checkbox id={`${id}-${source.id}`} checked={source.format !== "OPEN_POSE_COSTUME" && source.imageDistributionApproved} disabled={write.isPending || source.format === "OPEN_POSE_COSTUME"} onCheckedChange={() => write.mutate({ kind: "RIGHTS", source })} /><FieldLabel htmlFor={`${id}-${source.id}`}>{t("reference.permissionReview")}</FieldLabel></Field>
    </section>)}</div>
    <ReferenceCacheManager sources={sources.data?.items ?? []} disabled={write.isPending} />
    <FieldGroup className="reference-source-form"><h3>{t("reference.addSource")}</h3>
      <Button type="button" variant="outline" disabled={write.isPending} onClick={() => setForm({ ...EMPTY_SOURCE, id: "open-pose-costume", name: "Open Pose Costume", owner: "hongye007", repository: "open-pose-costume", format: "OPEN_POSE_COSTUME", manifestPath: "public/data/catalog.json" })}>{t("reference.preset")}</Button>
      <Field><FieldLabel htmlFor={`${id}-format`}>{t("reference.format")}</FieldLabel><Select id={`${id}-format`} value={form.format} disabled={write.isPending} onChange={(event) => { const format = event.target.value as CreateReferenceSource["format"]; setForm((current) => ({ ...current, format, imageDistributionApproved: false, manifestPath: format === "OPEN_POSE_COSTUME" ? "public/data/catalog.json" : "manifest.json" })); }}><option value="AGENVAS_REFERENCE_PACK">{t("reference.packFormat")}</option><option value="OPEN_POSE_COSTUME">{t("reference.openFormat")}</option></Select></Field>
      {([ ["id", "reference.sourceId"], ["name", "common.name"], ["owner", "reference.owner"], ["repository", "reference.repository"], ["ref", "reference.ref"], ["manifestPath", "reference.manifest"] ] as const).map(([key, label]) => <Field key={key}><FieldLabel htmlFor={`${id}-${key}`}>{t(label)}</FieldLabel><Input id={`${id}-${key}`} required={key !== "manifestPath"} value={form[key] ?? ""} disabled={write.isPending} onChange={(event) => setForm((current) => ({ ...current, [key]: event.target.value }))} /></Field>)}
      <Field orientation="horizontal"><Checkbox id={`${id}-rights`} checked={form.imageDistributionApproved} disabled={write.isPending || form.format === "OPEN_POSE_COSTUME"} onCheckedChange={(checked) => setForm((current) => ({ ...current, imageDistributionApproved: checked === true }))} /><FieldLabel htmlFor={`${id}-rights`}>{t("reference.permissionReview")}</FieldLabel></Field>
      <p>{t("reference.permissionHint")}</p><Button type="submit" disabled={write.isPending || !form.id.trim() || !form.name.trim() || !form.owner.trim() || !form.repository.trim()}>{t("reference.addSource")}</Button>
    </FieldGroup>
  </Dialog>;
}
