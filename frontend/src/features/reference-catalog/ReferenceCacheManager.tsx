import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { ApiError } from "../../shared/api/client";
import {
  cancelReferenceCacheJob,
  clearReferenceCache,
  getReferenceCacheStats,
  listReferenceCacheJobs,
  retryReferenceCacheJob,
  startReferenceCacheJob,
  type ReferenceCacheClearRequest,
  type ReferenceCacheJob,
  type ReferenceCacheRequest,
  type ReferenceSource,
} from "../../shared/api/referenceCatalog";
import { formatDate, formatNumber, t, useLocale, type MessageKey } from "../../shared/i18n";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { referenceFailureMessage } from "./referenceFailure";

const STATS_KEY = ["reference-catalog-cache-stats"];
const JOBS_KEY = ["reference-catalog-cache-jobs"];
const POLL_MS = 1500;
const JOB_LABELS: Record<ReferenceCacheJob["state"], MessageKey> = {
  QUEUED: "reference.cache.queued",
  RUNNING: "reference.cache.running",
  SUCCEEDED: "reference.cache.succeeded",
  PARTIAL_FAILED: "reference.cache.partialFailed",
  CANCELLED: "reference.cache.cancelled",
};
const isActive = (job: ReferenceCacheJob) => job.state === "QUEUED" || job.state === "RUNNING";

/** Server jobs own progress and disk storage; opening this panel performs reads only. */
export function ReferenceCacheManager({
  sources,
  disabled = false,
}: {
  sources: ReferenceSource[];
  disabled?: boolean;
}) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const [sourceId, setSourceId] = useState("");
  const [clearTarget, setClearTarget] = useState<{ request: ReferenceCacheClearRequest; name?: string } | null>(null);
  const [notice, setNotice] = useState("");
  const [requestFrozen, setRequestFrozen] = useState(false);
  const intent = useRef<ReferenceCacheRequest | null>(null);
  const jobs = useQuery({
    queryKey: JOBS_KEY,
    queryFn: listReferenceCacheJobs,
    retry: false,
    refetchInterval: query => (query.state.data?.items.some(isActive) ? POLL_MS : false),
  });
  const stats = useQuery({ queryKey: STATS_KEY, queryFn: getReferenceCacheStats, retry: false });
  useEffect(() => {
    if (!jobs.data) return;
    void client.invalidateQueries({ queryKey: STATS_KEY });
    void client.invalidateQueries({ queryKey: ["reference-catalog"] });
  }, [jobs.data, client]);
  const source = sources.find(candidate => candidate.id === sourceId);
  const eligible = Boolean(
    source?.enabled &&
    source.currentSnapshotId &&
    source.imageDistributionApproved &&
    source.format === "AGENVAS_REFERENCE_PACK",
  );
  function acceptJob(saved: ReferenceCacheJob) {
    client.setQueryData<{ items: ReferenceCacheJob[] }>(JOBS_KEY, current => ({
      items: [saved, ...(current?.items.filter(job => job.id !== saved.id) ?? [])],
    }));
  }
  const start = useMutation({
    mutationFn: () => {
      if (!intent.current) {
        if (!source?.currentSnapshotId || !eligible) throw new Error(t("reference.cache.notAuthorized"));
        intent.current = {
          sourceId: source.id,
          snapshotId: source.currentSnapshotId,
          entryIds: [],
          commandKey: crypto.randomUUID(),
        };
      }
      setRequestFrozen(true);
      return startReferenceCacheJob(intent.current);
    },
    onSuccess: saved => {
      acceptJob(saved);
      intent.current = null;
      setRequestFrozen(false);
    },
    onError: error => {
      // A lost acknowledgement replays the original source, snapshot and command key.
      if (error instanceof ApiError && error.status < 500 && error.status !== 408) {
        intent.current = null;
        setRequestFrozen(false);
      }
    },
  });
  const changeJob = useMutation({
    mutationFn: ({ job, action }: { job: ReferenceCacheJob; action: "RETRY" | "CANCEL" }) =>
      action === "RETRY" ? retryReferenceCacheJob(job.id) : cancelReferenceCacheJob(job.id),
    onSuccess: acceptJob,
  });
  const clear = useMutation({
    mutationFn: (request: ReferenceCacheClearRequest) => clearReferenceCache(request),
    onSuccess: saved => {
      client.setQueryData(STATS_KEY, saved.stats);
      setClearTarget(null);
      setNotice(
        t("reference.cache.cleared", { "0": formatNumber(saved.removedFiles), "1": formatNumber(saved.removedBytes) }),
      );
      void client.invalidateQueries({ queryKey: JOBS_KEY });
      void client.invalidateQueries({ queryKey: ["reference-catalog"] });
    },
  });
  const busy = disabled || start.isPending || changeJob.isPending || clear.isPending;
  const sourceActive = jobs.data?.items.some(job => job.sourceId === sourceId && isActive(job));
  const failure = stats.error ?? jobs.error;
  return (
    <section className="reference-cache-manager" aria-labelledby={`${id}-title`}>
      <h3 id={`${id}-title`}>{t("reference.cache.title")}</h3>
      <p className="reference-hint">{t("reference.cache.privateHint")}</p>
      {stats.isPending || jobs.isPending ? <p role="status">{t("common.loading")}</p> : null}
      {stats.data ? (
        <p>
          {t("reference.cache.usage", {
            "0": formatNumber(stats.data.usedBytes),
            "1": formatNumber(stats.data.quotaBytes),
            "2": formatNumber(stats.data.fileCount),
          })}
        </p>
      ) : null}
      {stats.data && stats.data.protectedFileCount > 0 ? (
        <p className="reference-hint">
          {t("reference.cache.protected", {
            "0": formatNumber(stats.data.protectedBytes),
            "1": formatNumber(stats.data.protectedFileCount),
          })}
        </p>
      ) : null}
      {failure ? (
        <Notice tone="danger">
          <p>{failure.message}</p>
          <Button
            size="content"
            type="button"
            variant="outline"
            onClick={() => {
              void stats.refetch();
              void jobs.refetch();
            }}
          >
            {t("reference.cache.retryStatus")}
          </Button>
        </Notice>
      ) : null}
      <div className="reference-cache-target">
        <Field>
          <FieldLabel htmlFor={`${id}-source`}>{t("reference.cache.source")}</FieldLabel>
          <Select
            id={`${id}-source`}
            value={sourceId}
            disabled={busy || requestFrozen || Boolean(clearTarget)}
            onChange={event => setSourceId(event.target.value)}
          >
            <option value="">{t("reference.cache.chooseSource")}</option>
            {sources.map(item => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </Select>
        </Field>
        <Button
          size="content"
          type="button"
          variant="outline"
          disabled={busy || requestFrozen || !eligible || !jobs.isSuccess || sourceActive}
          onClick={() => start.mutate()}
        >
          {t("reference.cache.start")}
        </Button>
      </div>
      {source && !eligible ? (
        <p>
          {t(
            source.format === "OPEN_POSE_COSTUME" || !source.imageDistributionApproved
              ? "reference.cache.notAuthorized"
              : !source.enabled
                ? "reference.sourceUnavailable"
                : "reference.cache.syncFirst",
          )}
        </p>
      ) : null}
      <p className="reference-hint">{t("reference.cache.onDemandHint")}</p>
      {start.error ? (
        <Notice tone="danger">
          <p>{start.error.message}</p>
          {requestFrozen ? (
            <Button size="content" type="button" variant="outline" disabled={busy} onClick={() => start.mutate()}>
              {t("reference.cache.retryRequest")}
            </Button>
          ) : null}
        </Notice>
      ) : null}
      {changeJob.error ? (
        <Notice tone="danger">
          <p>{changeJob.error.message}</p>
          <Button size="content" type="button" variant="outline" onClick={() => void jobs.refetch()}>
            {t("reference.cache.retryStatus")}
          </Button>
        </Notice>
      ) : null}
      {jobs.data?.items.length ? <p className="reference-hint">{t("reference.cache.historyHint")}</p> : null}
      <div className="reference-cache-jobs">
        {jobs.data?.items.map(job => (
          <section className="reference-cache-job" key={job.id}>
            <strong>
              {sources.find(item => item.id === job.sourceId)?.name ?? job.sourceId} · {t(JOB_LABELS[job.state])}
            </strong>
            <p className="reference-hint">
              {formatDate(job.createdAt)} · {job.snapshotId}
            </p>
            <p role={isActive(job) ? "status" : undefined}>
              {t("reference.cache.progress", {
                "0": formatNumber(job.readyCount),
                "1": formatNumber(job.totalCount),
                "2": formatNumber(job.readyBytes),
                "3": formatNumber(job.totalBytes),
                "4": formatNumber(job.failedCount),
              })}
            </p>
            {job.failures.slice(0, 10).map(item => (
              <p className="reference-hint" key={`${item.entryId}:${item.variantId}`}>
                {item.entryId} / {item.variantId} · {referenceFailureMessage(item.code)}
              </p>
            ))}
            {job.failures.length > 10 ? (
              <p>{t("reference.cache.moreFailures", { "0": job.failures.length - 10 })}</p>
            ) : null}
            <div className="ui-form-actions">
              {isActive(job) ? (
                <Button
                  size="content"
                  type="button"
                  variant="outline"
                  disabled={busy}
                  onClick={() => changeJob.mutate({ job, action: "CANCEL" })}
                >
                  {t("reference.cache.cancel")}
                </Button>
              ) : job.state === "PARTIAL_FAILED" ? (
                <Button
                  size="content"
                  type="button"
                  variant="outline"
                  disabled={busy}
                  onClick={() => changeJob.mutate({ job, action: "RETRY" })}
                >
                  {t("reference.cache.retryFailed")}
                </Button>
              ) : null}
            </div>
          </section>
        ))}
      </div>
      <div className="ui-form-actions">
        <Button
          size="content"
          type="button"
          variant="outline"
          disabled={busy || !stats.data}
          onClick={() => {
            clear.reset();
            setClearTarget({ request: {} });
          }}
        >
          {t("reference.cache.clearAll")}
        </Button>
        <Button
          size="content"
          type="button"
          variant="ghost"
          disabled={busy || !source || !stats.data}
          onClick={() => {
            clear.reset();
            setClearTarget({ request: { sourceId: source!.id }, name: source!.name });
          }}
        >
          {t("reference.cache.clearSource")}
        </Button>
        <Button
          size="content"
          type="button"
          variant="ghost"
          disabled={busy || stats.isFetching || jobs.isFetching}
          onClick={() => {
            void stats.refetch();
            void jobs.refetch();
          }}
        >
          {t("common.refresh")}
        </Button>
      </div>
      {clearTarget ? (
        <Notice tone="warning">
          <p>{t("reference.cache.clearConfirm")}</p>
          {clearTarget.name ? <p>{clearTarget.name}</p> : null}
          <div className="ui-form-actions">
            <Button
              size="content"
              type="button"
              variant="destructive"
              disabled={busy}
              onClick={() => clear.mutate(clearTarget.request)}
            >
              {t("reference.cache.confirmClear")}
            </Button>
            <Button size="content" type="button" variant="outline" disabled={busy} onClick={() => setClearTarget(null)}>
              {t("common.cancel")}
            </Button>
          </div>
        </Notice>
      ) : null}
      {clear.error ? (
        <Notice tone="danger">
          <p>{clear.error.message}</p>
        </Notice>
      ) : null}
      {notice ? <p role="status">{notice}</p> : null}
    </section>
  );
}
