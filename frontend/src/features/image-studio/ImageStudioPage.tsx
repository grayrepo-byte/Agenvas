import {
  ArrowClockwise,
  ClockCounterClockwise,
  DownloadSimple,
  ImageSquare,
  Plus,
  Sparkle,
  X,
} from "@/shared/ui/icons";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, assetContentUrl, assetThumbnailUrl, getCurrentUser } from "../../shared/api/client";
import {
  createImageStudioBatch,
  getImageStudioBatch,
  getImageStudioContext,
  IMAGE_STUDIO_COUNTS,
  IMAGE_STUDIO_POLL_MS,
  imageStudioBatchActive,
  listImageStudioBatches,
  uploadImageStudioReference,
  type ImageStudioBatch,
  type ImageStudioCapability,
  type ImageStudioParameters,
  type ImageStudioResult,
  type ImageStudioTask,
} from "../../shared/api/imageStudio";
import { formatDate, t, useLocale, type MessageKey } from "../../shared/i18n";
import { createCommandKey } from "../../shared/lib/clientId";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { MediaPreviewDialog } from "../canvas/MediaPreviewDialog";
import {
  useImageStudioDraft,
  useImageStudioDraftStore,
  type StudioCommand,
  type StudioDraft,
} from "./imageStudioDraft";
import "./ImageStudioPage.css";

const ARCHIVE_RETRY_CODE = "STUDIO_IMAGE_ARCHIVE_RETRY";
const ARCHIVE_MISSING_CODE = "STUDIO_IMAGE_ARCHIVE_MISSING";
const QUALITY_LABELS: Record<string, MessageKey> = {
  low: "imageStudio.quality.low",
  medium: "imageStudio.quality.medium",
  high: "imageStudio.quality.high",
};
const STATUS_LABELS: Record<ImageStudioTask["status"], MessageKey> = {
  READY: "imageStudio.status.ready",
  RUNNING: "imageStudio.status.running",
  SUBMITTING: "imageStudio.status.submitting",
  WAITING_PROVIDER: "imageStudio.status.waiting",
  UNKNOWN: "imageStudio.status.unknown",
  BLOCKED: "imageStudio.status.blocked",
  SUCCEEDED: "imageStudio.status.succeeded",
  FAILED: "imageStudio.status.failed",
  CANCELED: "imageStudio.status.canceled",
};
const historyKey = (ownerId: string) => ["image-studio", ownerId, "history"] as const;
const batchKey = (ownerId: string, batchId?: string) => ["image-studio", ownerId, "batch", batchId] as const;
const DOWNLOAD_EXTENSIONS: Readonly<Record<string, string>> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/webp": "webp",
};
function downloadFilename(index: number, contentType: string) {
  const extension = DOWNLOAD_EXTENSIONS[contentType];
  return extension ? `image-${index}.${extension}` : `image-${index}`;
}
function errorText(error: unknown) {
  return error instanceof Error ? error.message : t("imageStudio.loadFailed");
}
function defaults(capability: ImageStudioCapability): ImageStudioParameters {
  return {
    ...capability.defaultParameters,
    aspectRatio: capability.defaultParameters.aspectRatio === "AUTO" ? "1:1" : capability.defaultParameters.aspectRatio,
    generationCount: capability.defaultParameters.generationCount ?? 1,
  };
}

/** Entry is account scoped. No project picker or canvas state participates in this workflow. */
export function ImageStudioPage() {
  useLocale();
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  return (
    <PageShell title={t("imageStudio.title")} description={t("imageStudio.description")}>
      {session.data ? <ImageStudioWorkspace key={session.data.id} ownerId={session.data.id} /> : null}
    </PageShell>
  );
}

function ImageStudioWorkspace({ ownerId }: { ownerId: string }) {
  const locale = useLocale();
  const queryClient = useQueryClient();
  const draft = useImageStudioDraft(ownerId);
  const update = (patch: Partial<StudioDraft>) => useImageStudioDraftStore.getState().update(ownerId, patch);
  const [preview, setPreview] = useState<{ result: ImageStudioResult; projectId: string; index: number } | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const promptId = useId();
  const context = useQuery({
    queryKey: ["image-studio", ownerId, "context"],
    queryFn: getImageStudioContext,
    retry: false,
  });
  const history = useInfiniteQuery({
    queryKey: historyKey(ownerId),
    queryFn: ({ pageParam }) => listImageStudioBatches(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: page => page.nextCursor ?? undefined,
    enabled: context.isSuccess,
    retry: false,
    refetchInterval: query =>
      query.state.data?.pages.some(page => page.items.some(imageStudioBatchActive)) ? IMAGE_STUDIO_POLL_MS : false,
  });
  const historyRows = history.data?.pages.flatMap(page => page.items) ?? [];
  const selectedId = draft.selectedBatchId ?? historyRows[0]?.id;
  const snapshot = historyRows.find(batch => batch.id === selectedId);
  const selected = useQuery({
    queryKey: batchKey(ownerId, selectedId),
    queryFn: () => getImageStudioBatch(selectedId!),
    enabled: !!selectedId,
    initialData: snapshot,
    retry: false,
    refetchOnMount: false,
    refetchInterval: query =>
      query.state.data && imageStudioBatchActive(query.state.data) ? IMAGE_STUDIO_POLL_MS : false,
  });
  const capability = context.data?.capabilities.find(item => item.capabilityId === draft.capabilityId);
  useEffect(() => {
    if (draft.capabilityId || !context.data) return;
    const next =
      context.data.capabilities.find(item => item.capabilityId === context.data.defaultCapabilityId) ??
      context.data.capabilities[0];
    if (next)
      useImageStudioDraftStore.getState().update(ownerId, {
        capabilityId: next.capabilityId,
        capabilityVersion: next.capabilityVersion,
        parameters: defaults(next),
      });
  }, [context.data, draft.capabilityId, ownerId]);
  const accept = useMutation({
    mutationFn: createImageStudioBatch,
    onSuccess: batch => {
      queryClient.setQueryData(batchKey(ownerId, batch.id), batch);
      update({ command: null, selectedBatchId: batch.id });
      void queryClient.invalidateQueries({ queryKey: historyKey(ownerId) });
    },
    onError: error => {
      // A definitive client rejection did not accept a batch. Network uncertainty retains the command.
      if (
        error instanceof ApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        error.status !== 408 &&
        error.status !== 429
      )
        update({ command: null });
    },
    retry: false,
  });
  const upload = useMutation({
    mutationFn: uploadImageStudioReference,
    onSuccess: reference => {
      const current = useImageStudioDraftStore.getState().owners[ownerId];
      update({ references: [...(current?.references ?? []), reference] });
    },
    retry: false,
  });
  const parameters = draft.parameters;
  const staleCapability = !!capability && draft.capabilityVersion !== capability.capabilityVersion;
  const invalidParameters =
    capability &&
    ((parameters.aspectRatio &&
      capability.aspectRatios.length > 0 &&
      !capability.aspectRatios.includes(parameters.aspectRatio)) ||
      (parameters.resolution &&
        capability.resolutions.length > 0 &&
        !capability.resolutions.includes(parameters.resolution)) ||
      (parameters.quality && capability.qualities.length > 0 && !capability.qualities.includes(parameters.quality)));
  const tooManyReferences = !!capability && draft.references.length > capability.maxReferenceImages;
  const canGenerate =
    context.isSuccess &&
    !!capability &&
    !staleCapability &&
    !invalidParameters &&
    !tooManyReferences &&
    !!draft.prompt.trim() &&
    !upload.isPending &&
    !accept.isPending &&
    !draft.command;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canGenerate || !capability) return;
    const command: StudioCommand = {
      key: createCommandKey(),
      request: {
        capabilityId: capability.capabilityId,
        expectedCapabilityVersion: capability.capabilityVersion,
        prompt: draft.prompt.trim(),
        parameters: { ...parameters },
        referenceVersionIds: draft.references.map(reference => reference.versionId),
      },
    };
    update({ command });
    accept.mutate(command);
  }
  function chooseBatch(batch: ImageStudioBatch) {
    queryClient.setQueryData(batchKey(ownerId, batch.id), batch);
    update({ selectedBatchId: batch.id });
  }
  function restoreBatch(batch: ImageStudioBatch) {
    update({
      prompt: batch.prompt,
      capabilityId: batch.capabilityId,
      capabilityVersion: batch.capabilityVersion,
      parameters: { ...batch.parameters },
      references: batch.references.map(reference => ({ ...reference })),
    });
  }
  async function refresh() {
    await Promise.allSettled([context.refetch(), history.refetch(), ...(selectedId ? [selected.refetch()] : [])]);
  }
  function updateParameters(patch: Partial<ImageStudioParameters>) {
    update({ parameters: { ...parameters, ...patch } });
  }
  const locked = accept.isPending || !!draft.command;
  const batch = selected.data;
  return (
    <div className="image-studio" data-locale={locale}>
      <header className="image-studio-history">
        <div className="image-studio-history-title">
          <ClockCounterClockwise size={17} aria-hidden="true" />
          <h2>{t("imageStudio.history")}</h2>
        </div>
        <div className="image-studio-history-list" aria-label={t("imageStudio.history")}>
          {historyRows.map(row => (
            <Button
              type="button"
              variant="ghost"
              className="image-studio-history-item"
              key={row.id}
              aria-pressed={selectedId === row.id}
              onClick={() => chooseBatch(row)}
              title={row.prompt}
            >
              <span>{row.prompt}</span>
              <small>
                {formatDate(row.createdAt, { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })}
              </small>
            </Button>
          ))}
          {history.isSuccess && historyRows.length === 0 ? (
            <span className="image-studio-muted">{t("imageStudio.noHistory")}</span>
          ) : null}
          {history.hasNextPage ? (
            <Button
              variant="ghost"
              type="button"
              disabled={history.isFetchingNextPage}
              onClick={() => void history.fetchNextPage()}
            >
              {t("imageStudio.moreHistory")}
            </Button>
          ) : null}
        </div>
        <Button
          variant="ghost"
          size="icon"
          type="button"
          aria-label={t("imageStudio.refresh")}
          disabled={context.isFetching || history.isFetching || selected.isFetching}
          onClick={() => void refresh()}
        >
          <ArrowClockwise aria-hidden="true" />
        </Button>
      </header>
      {history.error ? (
        <Notice tone="danger" title={t("imageStudio.historyFailed")}>
          <p>{errorText(history.error)}</p>
          <Button variant="outline" type="button" onClick={() => void history.refetch()}>
            {t("common.retryRead")}
          </Button>
        </Notice>
      ) : null}
      <div className="image-studio-layout">
        <form className="image-studio-controls" onSubmit={submit} aria-label={t("imageStudio.parameters")}>
          <div className="image-studio-section-heading">
            <span className="image-studio-step">01</span>
            <h2>{t("imageStudio.create")}</h2>
          </div>
          <fieldset className="image-studio-fieldset" disabled={locked}>
            <Field className="image-studio-prompt-field">
              <FieldLabel htmlFor={promptId}>{t("imageStudio.prompt")}</FieldLabel>
              <Textarea
                id={promptId}
                value={draft.prompt}
                onChange={event => update({ prompt: event.target.value })}
                placeholder={t("imageStudio.promptPlaceholder")}
                maxLength={8000}
                rows={8}
              />
            </Field>
            <section className="image-studio-references" aria-label={t("imageStudio.references")}>
              <div className="image-studio-field-heading">
                <h3>{t("imageStudio.references")}</h3>
                <span>
                  {draft.references.length}
                  {capability ? ` / ${capability.maxReferenceImages}` : ""}
                </span>
              </div>
              <div className="image-studio-reference-list">
                {draft.references.map((reference, index) => (
                  <div className="image-studio-reference" key={reference.versionId}>
                    {reference.assetId && reference.projectId ? (
                      <img
                        alt={t("imageStudio.referenceLabel", { "0": index + 1 })}
                        src={assetThumbnailUrl(reference.projectId, reference.assetId)}
                      />
                    ) : (
                      <ImageSquare size={22} aria-hidden="true" />
                    )}
                    <span>{t("imageStudio.referenceLabel", { "0": index + 1 })}</span>
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      type="button"
                      aria-label={t("imageStudio.removeReference", { "0": index + 1 })}
                      onClick={() =>
                        update({ references: draft.references.filter(item => item.versionId !== reference.versionId) })
                      }
                    >
                      <X aria-hidden="true" />
                    </Button>
                  </div>
                ))}
                <Button
                  className="image-studio-upload"
                  type="button"
                  variant="outline"
                  disabled={!capability || upload.isPending || draft.references.length >= capability.maxReferenceImages}
                  onClick={() => inputRef.current?.click()}
                >
                  <Plus aria-hidden="true" />
                  {upload.isPending ? t("imageStudio.uploading") : t("imageStudio.addReference")}
                </Button>
                <input
                  ref={inputRef}
                  type="file"
                  className="sr-only"
                  aria-label={t("imageStudio.uploadReference")}
                  accept="image/png,image/jpeg,image/webp"
                  disabled={
                    locked ||
                    upload.isPending ||
                    !capability ||
                    draft.references.length >= capability.maxReferenceImages
                  }
                  onChange={event => {
                    const file = event.target.files?.[0];
                    if (file) upload.mutate(file);
                    event.target.value = "";
                  }}
                />
              </div>
              <p className="image-studio-hint">{t("imageStudio.referenceHint")}</p>
              {upload.error ? (
                <p className="image-studio-error" role="alert">
                  {errorText(upload.error)}
                </p>
              ) : null}
              {tooManyReferences ? (
                <p className="image-studio-error" role="alert">
                  {t("imageStudio.tooManyReferences", { "0": capability?.maxReferenceImages })}
                </p>
              ) : null}
            </section>
            <Field>
              <FieldLabel htmlFor={`${promptId}-model`}>{t("imageStudio.model")}</FieldLabel>
              <Select
                id={`${promptId}-model`}
                aria-label={t("imageStudio.model")}
                value={draft.capabilityId ?? ""}
                disabled={!context.data?.capabilities.length}
                onChange={event => {
                  const next = context.data?.capabilities.find(item => item.capabilityId === event.target.value);
                  if (next)
                    update({
                      capabilityId: next.capabilityId,
                      capabilityVersion: next.capabilityVersion,
                      parameters: defaults(next),
                    });
                }}
              >
                {!capability ? (
                  <option value={draft.capabilityId ?? ""}>
                    {draft.capabilityId ? t("imageStudio.unavailableModel") : t("imageStudio.chooseModel")}
                  </option>
                ) : null}
                {context.data?.capabilities.map(item => (
                  <option value={item.capabilityId} key={item.capabilityId}>
                    {item.name}
                  </option>
                ))}
              </Select>
              {capability ? <p className="image-studio-hint">{capability.connectionName}</p> : null}
            </Field>
            {capability ? (
              <>
                <div className="image-studio-parameter-grid">
                  {capability.aspectRatios.length ? (
                    <ParameterSelect
                      id={`${promptId}-ratio`}
                      label={t("imageStudio.ratio")}
                      value={parameters.aspectRatio}
                      options={capability.aspectRatios}
                      onChange={value =>
                        updateParameters({ aspectRatio: value as ImageStudioParameters["aspectRatio"] })
                      }
                    />
                  ) : null}
                  {capability.resolutions.length ? (
                    <ParameterSelect
                      id={`${promptId}-resolution`}
                      label={t("imageStudio.resolution")}
                      value={parameters.resolution}
                      options={capability.resolutions}
                      onChange={value => updateParameters({ resolution: value as ImageStudioParameters["resolution"] })}
                    />
                  ) : null}
                  {capability.qualities.length ? (
                    <ParameterSelect
                      id={`${promptId}-quality`}
                      label={t("imageStudio.quality")}
                      value={parameters.quality}
                      options={capability.qualities}
                      labels={Object.fromEntries(Object.entries(QUALITY_LABELS).map(([key, label]) => [key, t(label)]))}
                      onChange={value => updateParameters({ quality: value as ImageStudioParameters["quality"] })}
                    />
                  ) : null}
                </div>
                {capability.complexity ? (
                  <p className="image-studio-hint">{t("imageStudio.complexity", { "0": capability.complexity })}</p>
                ) : null}
                {capability.supportsTransparentBackground ? (
                  <label className="image-studio-checkbox">
                    <Checkbox
                      checked={parameters.transparentBackground ?? false}
                      onCheckedChange={checked => updateParameters({ transparentBackground: checked === true })}
                    />
                    {t("imageStudio.transparent")}
                  </label>
                ) : null}
              </>
            ) : null}
            <Field>
              <FieldLabel asChild>
                <span>{t("imageStudio.count")}</span>
              </FieldLabel>
              <div className="image-studio-count" role="group" aria-label={t("imageStudio.count")}>
                {IMAGE_STUDIO_COUNTS.map(count => (
                  <Button
                    type="button"
                    variant="outline"
                    key={count}
                    aria-pressed={(parameters.generationCount ?? 1) === count}
                    onClick={() => updateParameters({ generationCount: count })}
                  >
                    {t("imageStudio.countLabel", { "0": count })}
                  </Button>
                ))}
              </div>
            </Field>
          </fieldset>
          {context.isPending ? <LoadingState label={t("imageStudio.loadingModels")} /> : null}
          {context.error ? (
            <Notice tone="danger" title={t("imageStudio.loadFailed")}>
              <p>{errorText(context.error)}</p>
              <Button variant="outline" type="button" onClick={() => void context.refetch()}>
                {t("imageStudio.retryModels")}
              </Button>
            </Notice>
          ) : null}
          {context.isSuccess && context.data.capabilities.length === 0 ? (
            <Notice tone="warning" title={t("imageStudio.noModels")}>
              <Button variant="link" asChild>
                <Link to="/settings/media">{t("imageStudio.configureModels")}</Link>
              </Button>
            </Notice>
          ) : null}
          {context.isSuccess && draft.capabilityId && !capability ? (
            <Notice tone="warning" title={t("imageStudio.unavailableModel")}>
              <p>{t("imageStudio.unavailableModelHint")}</p>
            </Notice>
          ) : null}
          {staleCapability || invalidParameters ? (
            <Notice tone="warning" title={t("imageStudio.staleModel")}>
              <p>{t("imageStudio.staleModelHint")}</p>
              <Button
                type="button"
                variant="outline"
                disabled={locked}
                onClick={() =>
                  capability &&
                  update({ capabilityVersion: capability.capabilityVersion, parameters: defaults(capability) })
                }
              >
                {t("imageStudio.useCurrentModel")}
              </Button>
            </Notice>
          ) : null}
          {accept.error && !draft.command && !accept.isPending ? (
            <Notice tone="danger" title={t("imageStudio.submitFailed")}>
              <p>{errorText(accept.error)}</p>
            </Notice>
          ) : null}
          {draft.command && !accept.isPending ? (
            <Notice tone="warning" title={t("imageStudio.acceptanceUncertain")}>
              <p>{accept.error ? errorText(accept.error) : t("imageStudio.acceptanceUncertainHint")}</p>
              <p>{t("imageStudio.acceptanceUncertainHint")}</p>
              <Button type="button" variant="outline" onClick={() => draft.command && accept.mutate(draft.command)}>
                {t("imageStudio.retryAcceptance")}
              </Button>
            </Notice>
          ) : null}
          <div className="image-studio-submit">
            <Button type="submit" disabled={!canGenerate} size="lg">
              <Sparkle aria-hidden="true" />
              {accept.isPending ? t("imageStudio.submitting") : t("imageStudio.generate")}
            </Button>
            <p>{t("imageStudio.executionHint")}</p>
          </div>
        </form>
        <section className="image-studio-results" aria-label={t("imageStudio.results")}>
          <div className="image-studio-results-header">
            <div className="image-studio-section-heading">
              <span className="image-studio-step">02</span>
              <h2>{t("imageStudio.results")}</h2>
              {batch ? (
                <span className="image-studio-muted">
                  {t("imageStudio.resultCount", { "0": batch.tasks.flatMap(task => task.results).length })}
                </span>
              ) : null}
            </div>
            {batch ? (
              <Button type="button" variant="ghost" disabled={locked} onClick={() => restoreBatch(batch)}>
                <ClockCounterClockwise aria-hidden="true" />
                {t("imageStudio.restore")}
              </Button>
            ) : null}
          </div>
          {selected.error ? (
            <Notice tone="danger" title={t("imageStudio.statusFailed")}>
              <p>{errorText(selected.error)}</p>
              <Button type="button" variant="outline" onClick={() => void selected.refetch()}>
                {t("common.retryRead")}
              </Button>
            </Notice>
          ) : null}
          {!batch && (history.isPending || selected.isFetching) ? (
            <LoadingState label={t("imageStudio.loadingHistory")} />
          ) : null}
          {!batch && history.isSuccess ? (
            <div className="image-studio-empty">
              <EmptyState
                icon={<ImageSquare size={38} aria-hidden="true" />}
                title={t("imageStudio.emptyTitle")}
                description={t("imageStudio.emptyHint")}
              />
              <div className="image-studio-empty-slots" aria-hidden="true">
                <span />
                <span />
                <span />
                <span />
              </div>
            </div>
          ) : null}
          {batch ? (
            <>
              <div className="image-studio-batch-summary">
                <p>{batch.prompt}</p>
                <span>
                  {formatDate(batch.createdAt, { dateStyle: "medium", timeStyle: "short" })} ·{" "}
                  {batch.parameters.aspectRatio} {batch.parameters.resolution}
                </span>
              </div>
              {batch.tasks.some(task => task.status === "UNKNOWN") ? (
                <Notice tone="warning" title={t("imageStudio.status.unknown")}>
                  <p>{t("imageStudio.unknownHint")}</p>
                </Notice>
              ) : null}
              {batch.tasks.some(task => task.errorCode === ARCHIVE_RETRY_CODE) ? (
                <Notice title={t("imageStudio.archiveRetryTitle")}>
                  <p>{t("imageStudio.archiveRetryHint")}</p>
                </Notice>
              ) : null}
              {batch.tasks.some(task => task.errorCode === ARCHIVE_MISSING_CODE) ? (
                <Notice tone="warning" title={t("imageStudio.archiveMissingTitle")}>
                  <p>{t("imageStudio.archiveMissingHint")}</p>
                </Notice>
              ) : batch.tasks.some(task => task.status === "BLOCKED") ? (
                <Notice tone="warning" title={t("imageStudio.status.blocked")}>
                  <p>{t("imageStudio.blockedHint")}</p>
                </Notice>
              ) : null}
              <div className="image-studio-result-grid">
                {batch.tasks.flatMap((task, taskIndex) =>
                  task.results.length
                    ? task.results.map((result, resultIndex) => {
                        const index =
                          batch.tasks.slice(0, taskIndex).reduce((count, row) => count + row.results.length, 0) +
                          resultIndex +
                          1;
                        return (
                          <article className="image-studio-result-card" key={result.artifactVersionId}>
                            <Button
                              type="button"
                              variant="ghost"
                              className="image-studio-image-button"
                              aria-label={t("imageStudio.previewImage", { "0": index })}
                              onClick={() => setPreview({ result, projectId: batch.projectId, index })}
                            >
                              <img
                                src={assetThumbnailUrl(batch.projectId, result.assetId)}
                                alt={t("imageStudio.resultImage", { "0": index })}
                              />
                            </Button>
                            <footer>
                              <span>
                                {result.width} × {result.height}
                              </span>
                              <Button asChild variant="ghost" size="icon-sm">
                                <a
                                  href={assetContentUrl(batch.projectId, result.assetId)}
                                  download={downloadFilename(index, result.contentType)}
                                  aria-label={t("imageStudio.downloadImage", { "0": index })}
                                >
                                  <DownloadSimple aria-hidden="true" />
                                </a>
                              </Button>
                            </footer>
                          </article>
                        );
                      })
                    : [
                        <article
                          className="image-studio-task-card"
                          key={task.id}
                          aria-label={t("imageStudio.taskLabel", { "0": taskIndex + 1 })}
                        >
                          <ImageSquare size={32} aria-hidden="true" />
                          <StatusBadge
                            tone={
                              task.status === "FAILED"
                                ? "danger"
                                : task.status === "UNKNOWN" || task.status === "BLOCKED"
                                  ? "warning"
                                  : "neutral"
                            }
                          >
                            {task.errorCode === ARCHIVE_RETRY_CODE
                              ? t("imageStudio.status.archiving")
                              : task.errorCode === ARCHIVE_MISSING_CODE
                                ? t("imageStudio.status.archiveMissing")
                                : t(STATUS_LABELS[task.status])}
                          </StatusBadge>
                          {task.errorCode ? <p>{task.errorCode}</p> : null}
                        </article>,
                      ],
                )}
              </div>
            </>
          ) : null}
        </section>
      </div>
      {preview ? (
        <MediaPreviewDialog
          kind="image"
          title={t("imageStudio.resultImage", { "0": preview.index })}
          sourceUrl={assetContentUrl(preview.projectId, preview.result.assetId)}
          width={preview.result.width}
          height={preview.result.height}
          contentType={preview.result.contentType}
          onClose={() => setPreview(null)}
        />
      ) : null}
    </div>
  );
}

function ParameterSelect({
  id,
  label,
  value,
  options,
  labels,
  onChange,
}: {
  id: string;
  label: string;
  value?: string;
  options: string[];
  labels?: Record<string, string>;
  onChange: (value: string) => void;
}) {
  return (
    <Field>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Select id={id} aria-label={label} value={value ?? ""} onChange={event => onChange(event.target.value)}>
        {value && !options.includes(value) ? <option value={value}>{value}</option> : null}
        {options.map(option => (
          <option value={option} key={option}>
            {labels?.[option] ?? option}
          </option>
        ))}
      </Select>
    </Field>
  );
}
