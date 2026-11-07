import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { addReferenceSource, listReferenceSources, syncReferenceSource, updateReferenceSource, type CreateReferenceSource, type ReferenceSource } from "../../shared/api/referenceCatalog";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { referenceFailureMessage } from "./referenceFailure";

const SOURCES_KEY = ["reference-catalog-sources"];
const PRESET_SELECTION = "preset:open-pose-costume";
const PRESET: CreateReferenceSource = { id: "open-pose-costume", name: "Open Pose Costume", owner: "hongye007", repository: "open-pose-costume", ref: "main", format: "OPEN_POSE_COSTUME", manifestPath: "public/data/catalog.json", imageDistributionApproved: false };
const matchesPreset = (source: ReferenceSource) => source.owner.toLowerCase() === PRESET.owner && source.repository.toLowerCase() === PRESET.repository && source.format === PRESET.format && source.manifestPath === PRESET.manifestPath && source.ref === PRESET.ref;

/** Selecting a known source supplies its configuration; loading adds/enables and syncs it in one action. */
export function ReferenceSourcesDialog({ onClose, onLoaded }: { onClose: () => void; onLoaded?: (source: ReferenceSource) => void }) {
  useLocale(); const id = useId(); const client = useQueryClient();
  const sources = useQuery({ queryKey: SOURCES_KEY, queryFn: listReferenceSources, retry: false });
  const [selectedId, setSelectedId] = useState(PRESET_SELECTION);
  function accept(source: ReferenceSource) {
    client.setQueryData<{ items: ReferenceSource[] }>(SOURCES_KEY, (current) => ({ items: [...(current?.items.filter((item) => item.id !== source.id) ?? []), source] }));
  }
  const load = useMutation({ mutationFn: async () => {
    // Refresh before every attempt to recover an accepted add with a lost response
    // and to use current CAS versions after another administrator's change.
    const fresh = await sources.refetch();
    if (!fresh.isSuccess) throw fresh.error ?? new Error(t("reference.readFailed"));
    let source = selectedId === PRESET_SELECTION ? fresh.data.items.find(matchesPreset) : fresh.data.items.find((item) => item.id === selectedId);
    if (!source) {
      if (selectedId !== PRESET_SELECTION) throw new Error(t("reference.sourceUnavailable"));
      let sourceId = PRESET.id;
      for (let suffix = 2; fresh.data.items.some((item) => item.id === sourceId); suffix++) sourceId = `${PRESET.id}-${suffix}`;
      source = await addReferenceSource({ ...PRESET, id: sourceId }); accept(source);
    }
    if (!source.enabled) {
      source = await updateReferenceSource(source.id, { expectedVersion: source.version, enabled: true, imageDistributionApproved: source.imageDistributionApproved }); accept(source);
    }
    const result = await syncReferenceSource(source.id, source.version); accept(result.source);
    void client.invalidateQueries({ queryKey: ["reference-catalog"] });
    if (result.state !== "SUCCESS") throw new Error(result.state === "FAILED"
      ? [result.source.lastSyncError ? referenceFailureMessage(result.source.lastSyncError) : "", t("reference.syncRetained")].filter(Boolean).join(" ")
      : t("reference.syncPending"));
    return result.source;
  }, onSuccess: (source) => { onLoaded?.(source); onClose(); } });
  return <Dialog className="reference-sources-dialog" title={t("reference.sources")} busy={load.isPending} onClose={onClose}
    onSubmit={(event) => { event.preventDefault(); if (sources.isSuccess && !load.isPending) load.mutate(); }}
    footer={<><Button type="button" variant="outline" disabled={load.isPending} onClick={onClose}>{t("common.close")}</Button>
      <Button type="submit" disabled={!sources.isSuccess || load.isPending}>{t(load.isPending ? "reference.loadingSource" : load.isError ? "reference.retryLoad" : "reference.load")}</Button></>}>
    <Field><FieldLabel htmlFor={id}>{t("reference.source")}</FieldLabel><Select id={id} value={selectedId} disabled={!sources.isSuccess || load.isPending} onChange={(event) => { setSelectedId(event.target.value); load.reset(); }}>
      <option value={PRESET_SELECTION}>{PRESET.name}</option>
      {sources.data?.items.filter((source) => !matchesPreset(source)).map((source) => <option key={source.id} value={source.id}>{source.name}</option>)}
    </Select></Field>
    {sources.isPending ? <p role="status">{t("common.loading")}</p> : null}
    {sources.error ? <Notice tone="danger"><p>{sources.error.message}</p><Button type="button" variant="outline" onClick={() => void sources.refetch()}>{t("common.retry")}</Button></Notice> : null}
    {load.isPending ? <p role="status">{t("reference.loadingSource")}</p> : null}
    {load.error ? <Notice tone="danger"><p>{referenceFailureMessage(load.error)}</p></Notice> : null}
  </Dialog>;
}
