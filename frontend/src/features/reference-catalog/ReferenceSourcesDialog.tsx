import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import {
  addReferenceSource,
  listReferenceSources,
  syncReferenceSource,
  updateReferenceSource,
  type CreateReferenceSource,
  type ReferenceSource,
} from "../../shared/api/referenceCatalog";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { referenceFailureMessage } from "./referenceFailure";

const SOURCES_KEY = ["reference-catalog-sources"];
const GITHUB: CreateReferenceSource = {
  id: "open-pose-costume",
  name: "Open Pose Costume",
  owner: "hongye007",
  repository: "open-pose-costume",
  ref: "main",
  format: "OPEN_POSE_COSTUME",
  manifestPath: "public/data/catalog.json",
  imageDistributionApproved: false,
  sourceType: "GITHUB",
};
const OMILIC: CreateReferenceSource = {
  ...GITHUB,
  id: "omilic",
  name: "Omilic",
  manifestPath: "data/catalog.json",
  sourceType: "OMILIC",
};
const PRESETS = [OMILIC, GITHUB];
const matchesPreset = (source: ReferenceSource, preset: CreateReferenceSource) =>
  source.sourceType === preset.sourceType &&
  source.owner.toLowerCase() === preset.owner &&
  source.repository.toLowerCase() === preset.repository &&
  source.format === preset.format &&
  source.manifestPath === preset.manifestPath &&
  source.ref === preset.ref;

/** Loading opens saved JSON; only initial loading or explicit refresh fetches the remote directory. */
export function ReferenceSourcesDialog({
  onClose,
  onLoaded,
}: {
  onClose: () => void;
  onLoaded?: (source: ReferenceSource) => void;
}) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const sources = useQuery({ queryKey: SOURCES_KEY, queryFn: listReferenceSources, retry: false });
  const [selectedId, setSelectedId] = useState(`preset:${OMILIC.id}`);
  const preset = PRESETS.find(source => selectedId === `preset:${source.id}`);
  const selected = preset
    ? sources.data?.items.find(source => matchesPreset(source, preset))
    : sources.data?.items.find(source => source.id === selectedId);
  function accept(source: ReferenceSource) {
    client.setQueryData<{ items: ReferenceSource[] }>(SOURCES_KEY, current => ({
      items: [...(current?.items.filter(item => item.id !== source.id) ?? []), source],
    }));
  }
  const load = useMutation({
    mutationFn: async (refresh: boolean) => {
      // Refresh before every attempt to recover an accepted add with a lost response
      // and to use current CAS versions after another administrator's change.
      const fresh = await sources.refetch();
      if (!fresh.isSuccess) throw fresh.error ?? new Error(t("reference.readFailed"));
      let source = preset
        ? fresh.data.items.find(source => matchesPreset(source, preset))
        : fresh.data.items.find(item => item.id === selectedId);
      if (!source) {
        if (!preset) throw new Error(t("reference.sourceUnavailable"));
        let sourceId = preset.id;
        for (let suffix = 2; fresh.data.items.some(item => item.id === sourceId); suffix++)
          sourceId = `${preset.id}-${suffix}`;
        source = await addReferenceSource({ ...preset, id: sourceId });
        accept(source);
      }
      if (!source.enabled) {
        source = await updateReferenceSource(source.id, {
          expectedVersion: source.version,
          enabled: true,
          imageDistributionApproved: source.imageDistributionApproved,
        });
        accept(source);
      }
      if (source.currentSnapshotId && !refresh) return source;
      const result = await syncReferenceSource(source.id, source.version);
      accept(result.source);
      void client.invalidateQueries({ queryKey: ["reference-catalog"] });
      if (result.state !== "SUCCESS")
        throw new Error(
          result.state === "FAILED"
            ? [
                result.source.lastSyncError
                  ? referenceFailureMessage(result.source.lastSyncError)
                  : t("reference.readFailed"),
                result.source.currentSnapshotId ? t("reference.syncRetained") : "",
              ]
                .filter(Boolean)
                .join(" ")
            : t("reference.syncPending"),
        );
      return result.source;
    },
    onSuccess: source => {
      void client.invalidateQueries({ queryKey: ["reference-catalog"] });
      onLoaded?.(source);
      onClose();
    },
  });
  return (
    <Dialog
      className="reference-sources-dialog"
      title={t("reference.sources")}
      busy={load.isPending}
      onClose={onClose}
      onSubmit={event => {
        event.preventDefault();
        if (sources.isSuccess && !load.isPending) load.mutate(false);
      }}
      footer={
        <>
          {selected?.currentSnapshotId ? (
            <Button type="button" variant="ghost" disabled={load.isPending} onClick={() => load.mutate(true)}>
              {t("reference.refreshCatalog")}
            </Button>
          ) : null}
          <Button type="button" variant="outline" disabled={load.isPending} onClick={onClose}>
            {t("common.close")}
          </Button>
          <Button type="submit" disabled={!sources.isSuccess || load.isPending}>
            {t(load.isPending ? "reference.loadingSource" : load.isError ? "reference.retryLoad" : "reference.load")}
          </Button>
        </>
      }
    >
      <Field>
        <FieldLabel htmlFor={id}>{t("reference.source")}</FieldLabel>
        <Select
          id={id}
          value={selectedId}
          disabled={!sources.isSuccess || load.isPending}
          onChange={event => {
            setSelectedId(event.target.value);
            load.reset();
          }}
        >
          <option value={`preset:${OMILIC.id}`}>{t("reference.omilicSource")}</option>
          <option value={`preset:${GITHUB.id}`}>GitHub</option>
          {sources.data?.items
            .filter(source => !PRESETS.some(preset => matchesPreset(source, preset)))
            .map(source => (
              <option key={source.id} value={source.id}>
                {source.name}
              </option>
            ))}
        </Select>
      </Field>
      {sources.isPending ? <p role="status">{t("common.loading")}</p> : null}
      {sources.error ? (
        <Notice tone="danger">
          <p>{sources.error.message}</p>
          <Button type="button" variant="outline" onClick={() => void sources.refetch()}>
            {t("common.retry")}
          </Button>
        </Notice>
      ) : null}
      {load.isPending ? <p role="status">{t("reference.loadingSource")}</p> : null}
      {load.error ? (
        <Notice tone="danger">
          <p>{referenceFailureMessage(load.error)}</p>
        </Notice>
      ) : null}
    </Dialog>
  );
}
