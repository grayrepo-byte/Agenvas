import {
  ArrowClockwise,
  CircleNotch,
  ClockCounterClockwise,
  ImageSquare,
  Plus,
  SlidersHorizontal,
  Stack,
  Rectangle,
  ArrowsOutSimple,
  Cube,
  Sparkle,
  Trash,
  X,
} from "@/shared/ui/icons";
import {
  queryOptions,
  useInfiniteQuery,
  useIsMutating,
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  useEffect,
  useId,
  useRef,
  useState,
  useSyncExternalStore,
  type FormEvent,
  type ReactNode,
  type Ref,
} from "react";
import { Link } from "react-router";
import { ApiError, assetContentUrl, assetThumbnailUrl, getCurrentUser } from "../../shared/api/client";
import {
  createImageStudioBatch,
  deleteImageStudioBatch,
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
} from "../../shared/api/imageStudio";
import { formatDate, t, useLocale, type MessageKey } from "../../shared/i18n";
import { createCommandKey } from "../../shared/lib/clientId";
import { BrandLogo } from "../../shared/ui/BrandLogo";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "../../shared/ui/primitives/dialog";
import { Popover, PopoverContent, PopoverTrigger } from "../../shared/ui/primitives/popover";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { MediaPreviewDialog } from "../canvas/MediaPreviewDialog";
import {
  useImageStudioDraft,
  useImageStudioDraftStore,
  type StudioCommand,
  type StudioDraft,
} from "./imageStudioDraft";
import { StudioBatchResults, type StudioResultsView } from "./StudioBatchResults";
import { batchReference, batchStatus, studioGallery, type StudioPreview } from "./studioGallery";
import "./ImageStudioPage.css";

const QUALITY_LABELS: Record<string, MessageKey> = {
  low: "imageStudio.quality.low",
  medium: "imageStudio.quality.medium",
  high: "imageStudio.quality.high",
};
const historyKey = (ownerId: string) => ["image-studio", ownerId, "history"] as const;
const batchKey = (ownerId: string, batchId?: string) => ["image-studio", ownerId, "batch", batchId] as const;
function batchOptions(ownerId: string, id?: string) {
  return queryOptions({
    queryKey: batchKey(ownerId, id),
    queryFn: ({ signal }) => getImageStudioBatch(id!, signal),
    enabled: !!id,
    retry: false,
    refetchOnMount: false,
    refetchInterval: query =>
      query.state.data && imageStudioBatchActive(query.state.data) ? IMAGE_STUDIO_POLL_MS : false,
  });
}
const COMPACT_LAYOUT = "(max-width: 900px)";
function subscribeLayout(listener: () => void) {
  const media = window.matchMedia?.(COMPACT_LAYOUT);
  media?.addEventListener("change", listener);
  return () => media?.removeEventListener("change", listener);
}
function compactLayout() {
  return window.matchMedia?.(COMPACT_LAYOUT).matches ?? false;
}

// Some mobile keyboards shrink only the visual viewport. Leave pinch zoom under browser control.
function subscribeViewport(listener: () => void) {
  const viewport = window.visualViewport;
  viewport?.addEventListener("resize", listener);
  return () => viewport?.removeEventListener("resize", listener);
}
function visibleHeight() {
  const viewport = window.visualViewport;
  return viewport?.scale === 1 ? viewport.height : undefined;
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
    <PageShell title={t("imageStudio.title")}>
      {session.data ? <ImageStudioWorkspace key={session.data.id} ownerId={session.data.id} /> : null}
    </PageShell>
  );
}

function ImageStudioWorkspace({ ownerId }: { ownerId: string }) {
  const locale = useLocale();
  const queryClient = useQueryClient();
  const draft = useImageStudioDraft(ownerId);
  const { promptBeforeHistory } = draft;
  const update = (patch: Partial<StudioDraft>) => useImageStudioDraftStore.getState().update(ownerId, patch);
  const [preview, setPreview] = useState<StudioPreview | null>(null);
  const [deletion, setDeletion] = useState<{ id: string; deleteFiles: boolean } | null>(null);
  const deleteTrigger = useRef<HTMLButtonElement>(null);
  const cancelDeletion = useRef<HTMLButtonElement>(null);
  const historyListRef = useRef<HTMLDivElement>(null);
  const historyToggle = useRef<HTMLButtonElement>(null);
  const drawerClose = useRef<HTMLButtonElement>(null);
  const deleteFilesId = useId();
  // Unlike the accepted command, reveal eligibility belongs to this mounted workspace.
  const revealCommand = useRef<string | null>(null);
  const [revealBatchId, setRevealBatchId] = useState<string>();
  const inputRef = useRef<HTMLInputElement>(null);
  const promptId = useId();
  const historyId = useId();
  const narrow = useSyncExternalStore(subscribeLayout, compactLayout);
  const maxHeight = useSyncExternalStore(subscribeViewport, visibleHeight);
  const [railOpen, setRailOpen] = useState(true);
  const context = useQuery({
    queryKey: ["image-studio", ownerId, "context"],
    queryFn: getImageStudioContext,
    retry: false,
  });
  const history = useInfiniteQuery({
    queryKey: historyKey(ownerId),
    queryFn: ({ pageParam, signal }) => listImageStudioBatches(pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: page => page.nextCursor ?? undefined,
    enabled: context.isSuccess,
    retry: false,
    refetchInterval: query =>
      draft.awaitingHistory.length || query.state.data?.pages.some(page => page.items.some(imageStudioBatchActive))
        ? IMAGE_STUDIO_POLL_MS
        : false,
  });
  const historyRows = history.data?.pages.flatMap(page => page.items) ?? [];
  const selectedId = draft.selectedBatchId ?? historyRows[0]?.id;
  const snapshot = historyRows.find(batch => batch.id === selectedId);
  const selected = useQuery({ ...batchOptions(ownerId, selectedId), initialData: snapshot });
  // Accepted tasks may be missing from a lagging history page. Observe their existing private
  // detail queries independently of the viewed batch until the server lists them.
  const unlistedIds = draft.awaitingHistory.filter(id => !historyRows.some(row => row.id === id));
  const unlistedQueries = useQueries({
    queries: unlistedIds.map(id => ({ ...batchOptions(ownerId, id), enabled: id !== selectedId })),
  });
  useEffect(() => {
    const listed = new Set(history.data?.pages.flatMap(page => page.items.map(item => item.id)));
    const store = useImageStudioDraftStore.getState();
    const ids = store.owners[ownerId]?.awaitingHistory ?? [];
    if (ids.some(id => listed.has(id))) store.update(ownerId, { awaitingHistory: ids.filter(id => !listed.has(id)) });
  }, [ownerId, history.data, draft.awaitingHistory]);
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
  // The POST can outlive this route. Reopening the Studio should still show the in-flight request.
  const acceptanceKey = ["image-studio", ownerId, "accept"] as const;
  const accepting = useIsMutating({ mutationKey: acceptanceKey, exact: true }) > 0;
  const accept = useMutation({
    mutationKey: acceptanceKey,
    mutationFn: createImageStudioBatch,
    onSuccess: (batch, command) => {
      const current = useImageStudioDraftStore.getState().owners[ownerId];
      if (revealCommand.current === command.key) {
        revealCommand.current = null;
        if (current?.viewingSubmission) setRevealBatchId(batch.id);
      }
      queryClient.setQueryData(batchKey(ownerId, batch.id), batch);
      const awaitingHistory = current?.awaitingHistory ?? [];
      update({
        command: null,
        // Browsing a previous batch while the request is in flight takes precedence over its receipt.
        selectedBatchId: current?.viewingSubmission ? batch.id : (current?.selectedBatchId ?? batch.id),
        viewingSubmission: false,
        awaitingHistory: [batch.id, ...awaitingHistory.filter(id => id !== batch.id)],
      });
      void queryClient.invalidateQueries({ queryKey: historyKey(ownerId) });
    },
    onError: (error, command) => {
      // A definitive client rejection did not accept a batch. Network uncertainty retains the command.
      if (
        error instanceof ApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        error.status !== 408 &&
        error.status !== 429
      ) {
        if (revealCommand.current === command.key) revealCommand.current = null;
        update({ command: null, viewingSubmission: false });
      }
    },
    retry: false,
  });
  const removeBatch = useMutation({
    mutationFn: deleteImageStudioBatch,
    onSuccess: async (_, { id }) => {
      const listKey = historyKey(ownerId);
      const detailKey = batchKey(ownerId, id);
      await Promise.all([
        queryClient.cancelQueries({ queryKey: listKey, exact: true }),
        queryClient.cancelQueries({ queryKey: detailKey, exact: true }),
      ]);
      const current = useImageStudioDraftStore.getState().owners[ownerId];
      const cached = queryClient.getQueryData<typeof history.data>(listKey);
      const pages = cached?.pages.map(page => ({ ...page, items: page.items.filter(row => row.id !== id) }));
      if (current) {
        const awaitingHistory = current.awaitingHistory.filter(batchId => batchId !== id);
        const selectedVersions = { ...current.selectedVersions };
        delete selectedVersions[id];
        const selected = current.selectedBatchId ?? cached?.pages.find(page => page.items.length)?.items[0]?.id;
        update({
          selectedVersions,
          awaitingHistory,
          ...(selected === id
            ? { selectedBatchId: pages?.find(page => page.items.length)?.items[0]?.id ?? awaitingHistory[0] ?? null }
            : {}),
        });
      }
      if (cached && pages) queryClient.setQueryData(listKey, { ...cached, pages });
      queryClient.removeQueries({ queryKey: detailKey, exact: true });
      setRevealBatchId(value => (value === id ? undefined : value));
      setDeletion(null);
      void queryClient.invalidateQueries({ queryKey: listKey, exact: true });
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
    !accepting &&
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
    revealCommand.current = command.key;
    setRevealBatchId(undefined);
    update({ command, viewingSubmission: true, promptBeforeHistory: null });
    accept.mutate(command);
  }
  function chooseBatch(batch: ImageStudioBatch, hasImage: boolean) {
    revealCommand.current = null;
    setRevealBatchId(undefined);
    update({
      selectedBatchId: batch.id,
      viewingSubmission: false,
      ...(!locked && hasImage && batch.prompt !== draft.prompt
        ? { prompt: batch.prompt, promptBeforeHistory: draft.prompt }
        : {}),
    });
  }
  function chooseVersion(batchId: string, version: string) {
    revealCommand.current = null;
    setRevealBatchId(undefined);
    const current = useImageStudioDraftStore.getState().owners[ownerId];
    update({ selectedBatchId: batchId, selectedVersions: { ...current?.selectedVersions, [batchId]: version } });
  }
  function confirmFirstPreview(batchId: string, version: string) {
    if (revealBatchId === batchId) chooseVersion(batchId, version);
  }
  function restoreBatch(batch: ImageStudioBatch) {
    update({
      prompt: batch.prompt,
      promptBeforeHistory: null,
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
  const locked = accepting || !!draft.command;
  const batch = selected.data;
  const gallery = batch && studioGallery(batch, draft.selectedVersions[batch.id]);
  const viewingSubmission = draft.viewingSubmission && !!draft.command;
  const visibleBatchId = viewingSubmission ? undefined : batch?.id;
  const awaitingFirstPreview = visibleBatchId !== undefined && visibleBatchId === revealBatchId;
  const visibleVersion = gallery?.active?.result.artifactVersionId;
  useEffect(() => {
    if (!visibleBatchId || !visibleVersion || awaitingFirstPreview) return;
    // Keep the first displayed photo in view when an earlier numbered output arrives later.
    const store = useImageStudioDraftStore.getState();
    const versions = store.owners[ownerId]?.selectedVersions;
    if (!versions?.[visibleBatchId])
      store.update(ownerId, { selectedVersions: { ...versions, [visibleBatchId]: visibleVersion } });
  }, [ownerId, visibleBatchId, visibleVersion, awaitingFirstPreview]);
  const resultsView: StudioResultsView | undefined =
    draft.command && viewingSubmission
      ? { request: draft.command.request, pending: accepting }
      : batch && gallery
        ? { batch, gallery }
        : undefined;
  const contextFailure = context.error ? (
    <Notice tone="danger" title={t("imageStudio.loadFailed")}>
      {errorText(context.error) !== t("imageStudio.loadFailed") ? <p>{errorText(context.error)}</p> : null}
      <Button variant="outline" type="button" disabled={context.isFetching} onClick={() => void context.refetch()}>
        {t("imageStudio.retryModels")}
      </Button>
    </Notice>
  ) : null;
  const historyDetail = history.error && errorText(history.error);
  const historyFeedback = history.error ? (
    <Notice tone="danger" title={t("imageStudio.historyFailed")}>
      {historyDetail !== t("imageStudio.historyFailed") ? <p>{historyDetail}</p> : null}
      <Button variant="outline" type="button" disabled={history.isFetching} onClick={() => void history.refetch()}>
        {t("common.retryRead")}
      </Button>
    </Notice>
  ) : history.isLoading || context.isPending ? (
    <LoadingState compact label={t("imageStudio.loadingHistory")} />
  ) : null;
  const displayedHistory = [...unlistedQueries.flatMap(query => (query.data ? [query.data] : [])), ...historyRows];
  if (batch && !displayedHistory.some(row => row.id === batch.id)) displayedHistory.unshift(batch);
  const historyList = (onChoose?: () => void) => (
    <div className="image-studio-history-list" ref={historyListRef}>
      {removeBatch.isSuccess ? (
        <Notice tone="success">
          {t(removeBatch.variables.deleteFiles ? "imageStudio.deleteFilesQueued" : "imageStudio.batchDeleted")}
        </Notice>
      ) : null}
      {narrow && !history.data && contextFailure ? contextFailure : historyFeedback}
      {displayedHistory.map(snapshot => {
        // Detail polling can lead the history list. Use the same version as the hero.
        const row = batch?.id === snapshot.id ? batch : snapshot;
        const result = (row === batch ? gallery : studioGallery(row, draft.selectedVersions[row.id]))?.active?.result;
        const status = batchStatus(row);
        const date = formatDate(row.createdAt, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
        return (
          <div className="image-studio-history-row" key={row.id}>
            <Button
              type="button"
              variant="ghost"
              className="image-studio-history-item"
              data-batch-id={row.id}
              aria-pressed={!viewingSubmission && selectedId === row.id}
              onClick={() => {
                chooseBatch(row, !!result);
                onChoose?.();
              }}
              title={[batchReference(row.id), row.prompt, date, t(status)].join(" · ")}
            >
              <span className="image-studio-history-thumb">
                {result ? (
                  <img src={assetThumbnailUrl(row.projectId, result.assetId)} alt="" loading="lazy" />
                ) : imageStudioBatchActive(row) ? (
                  <CircleNotch className="image-studio-spinner" size={24} aria-hidden="true" />
                ) : (
                  <ClockCounterClockwise size={24} aria-hidden="true" />
                )}
              </span>
              <span className="image-studio-history-caption">
                <strong>{row.prompt}</strong>
                <small>
                  <span className="image-studio-history-number">{batchReference(row.id)}</span>
                  <time dateTime={row.createdAt}>{date}</time>
                  <span>{t(status)}</span>
                </small>
              </span>
            </Button>
            <Button
              type="button"
              variant="secondary"
              size="icon-sm"
              className="image-studio-history-delete absolute end-1 top-1"
              aria-label={t("imageStudio.deleteBatchLabel", { "0": batchReference(row.id) })}
              aria-haspopup="dialog"
              onClick={event => {
                deleteTrigger.current = event.currentTarget;
                removeBatch.reset();
                setDeletion({ id: row.id, deleteFiles: false });
              }}
            >
              <Trash aria-hidden="true" />
            </Button>
          </div>
        );
      })}
      {history.isSuccess && displayedHistory.length === 0 ? (
        <p className="image-studio-hint">{t("imageStudio.noHistory")}</p>
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
  );
  const reloadButton = (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={t("imageStudio.refresh")}
      title={t("imageStudio.refresh")}
      disabled={context.isFetching || history.isFetching || selected.isFetching}
      onClick={() => void refresh()}
    >
      <ArrowClockwise aria-hidden="true" />
    </Button>
  );
  return (
    <div className="image-studio" data-locale={locale} style={{ maxHeight }}>
      <header className="image-studio-header">
        <div className="image-studio-brand">
          <Link to="/projects" aria-label={t("ui.pageShell.homeLabel")} title={t("ui.pageShell.homeLabel")}>
            <BrandLogo decorative />
          </Link>
          <h1>{t("imageStudio.title")}</h1>
        </div>
        {narrow ? (
          <MobileHistory refresh={reloadButton} toggleRef={historyToggle} closeRef={drawerClose}>
            {historyList}
          </MobileHistory>
        ) : (
          <Button
            type="button"
            ref={historyToggle}
            variant="ghost"
            size="sm"
            className="image-studio-history-toggle"
            aria-controls={historyId}
            aria-expanded={railOpen}
            aria-label={railOpen ? t("imageStudio.collapseHistory") : t("imageStudio.expandHistory")}
            onClick={() => setRailOpen(!railOpen)}
          >
            <ClockCounterClockwise aria-hidden="true" />
            {t("imageStudio.historyShort")}
          </Button>
        )}
      </header>
      <div className="image-studio-workspace">
        <section className="image-studio-stage" aria-label={t("imageStudio.results")}>
          {narrow || !railOpen ? historyFeedback : null}
          {!viewingSubmission && selected.error ? (
            <Notice tone="danger" title={t("imageStudio.statusFailed")}>
              <p>{errorText(selected.error)}</p>
              <Button type="button" variant="outline" onClick={() => void selected.refetch()}>
                {t("common.retryRead")}
              </Button>
            </Notice>
          ) : null}
          {!resultsView && selected.isFetching && !history.isLoading ? (
            <LoadingState label={t("imageStudio.loadingHistory")} />
          ) : null}
          {!resultsView && !selected.error && !selected.isFetching && history.isSuccess ? (
            <div className="image-studio-empty">
              <EmptyState
                icon={<ImageSquare size={38} aria-hidden="true" />}
                title={t("imageStudio.emptyTitle")}
                description={t("imageStudio.emptyHint")}
              />
            </div>
          ) : null}
          {resultsView ? (
            <StudioBatchResults
              key={viewingSubmission ? draft.command?.key : batch?.id}
              view={resultsView}
              awaitingFirstPreview={awaitingFirstPreview}
              onFirstPreview={confirmFirstPreview}
              onChoose={chooseVersion}
              onPreview={setPreview}
              onRestore={restoreBatch}
              locked={locked || upload.isPending}
            />
          ) : null}
        </section>
        {!narrow && railOpen ? (
          <aside id={historyId} className="image-studio-history" aria-label={t("imageStudio.history")}>
            <header>
              <h2>{t("imageStudio.historyShort")}</h2>
              {reloadButton}
            </header>
            {historyList()}
          </aside>
        ) : null}
      </div>

      <form className="image-studio-controls" onSubmit={submit} aria-label={t("imageStudio.parameters")}>
        {context.isPending ? <LoadingState label={t("imageStudio.loadingModels")} /> : null}
        {contextFailure}
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
        {accept.error && !draft.command && !accepting ? (
          <Notice tone="danger" title={t("imageStudio.submitFailed")}>
            <p>{errorText(accept.error)}</p>
          </Notice>
        ) : null}
        {draft.command && !accepting ? (
          <Notice tone="warning" title={t("imageStudio.acceptanceUncertain")}>
            {accept.error ? <p>{errorText(accept.error)}</p> : null}
            <p>{t("imageStudio.acceptanceUncertainHint")}</p>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                if (draft.command) {
                  update({ viewingSubmission: true });
                  accept.mutate(draft.command);
                }
              }}
            >
              {t("imageStudio.retryAcceptance")}
            </Button>
          </Notice>
        ) : null}
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

        <fieldset className="image-studio-fieldset" disabled={locked}>
          <div className="image-studio-prompt-row">
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

                  <Button
                    variant="ghost"
                    size="icon-sm"
                    type="button"
                    aria-label={t("imageStudio.removeReference", { "0": index + 1 })}
                    onClick={() =>
                      update({
                        references: draft.references.filter(item => item.versionId !== reference.versionId),
                      })
                    }
                  >
                    <X aria-hidden="true" />
                  </Button>
                </div>
              ))}
              <div className="image-studio-attachment">
                <Button
                  className="image-studio-upload"
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label={upload.isPending ? t("imageStudio.uploading") : t("imageStudio.addReference")}
                  title={
                    capability
                      ? `${t("imageStudio.referenceHint")} · ${draft.references.length}/${capability.maxReferenceImages}`
                      : t("imageStudio.referenceHint")
                  }
                  disabled={!capability || upload.isPending || draft.references.length >= capability.maxReferenceImages}
                  onClick={() => inputRef.current?.click()}
                >
                  {upload.isPending ? (
                    <CircleNotch className="image-studio-spinner" aria-hidden="true" />
                  ) : (
                    <Plus aria-hidden="true" />
                  )}
                </Button>
                <input
                  ref={inputRef}
                  type="file"
                  hidden
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
            </div>
            <Field className="image-studio-prompt-field">
              <FieldLabel className="sr-only" htmlFor={promptId}>
                {t("imageStudio.prompt")}
              </FieldLabel>
              <Textarea
                id={promptId}
                value={draft.prompt}
                onChange={event => update({ prompt: event.target.value, promptBeforeHistory: null })}
                placeholder={t("imageStudio.promptPlaceholder")}
                maxLength={8000}
                rows={3}
                variant="ghost"
              />
              {promptBeforeHistory !== null ? (
                <Button
                  className="image-studio-undo-history-prompt self-start"
                  type="button"
                  size="xs"
                  variant="ghost"
                  onClick={() => update({ prompt: promptBeforeHistory, promptBeforeHistory: null })}
                >
                  {t("imageStudio.undoHistoryPrompt")}
                </Button>
              ) : null}
            </Field>
          </div>
          <div className="image-studio-composer-toolbar">
            <div className="image-studio-settings">
              <Field className="image-studio-model">
                <FieldLabel className="sr-only" htmlFor={`${promptId}-model`}>
                  {t("imageStudio.model")}
                </FieldLabel>
                <Select
                  id={`${promptId}-model`}
                  variant="ghost"
                  density="compact"
                  icon={<Cube aria-hidden="true" />}
                  optionDetails={Object.fromEntries(
                    (context.data?.capabilities ?? []).map(item => [
                      item.capabilityId,
                      { description: item.connectionName, icon: <ImageSquare aria-hidden="true" /> },
                    ]),
                  )}
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
              </Field>
              {capability ? (
                <>
                  {capability.aspectRatios.length ? (
                    <ParameterSelect
                      id={`${promptId}-ratio`}
                      label={t("imageStudio.ratio")}
                      icon={<Rectangle aria-hidden="true" />}
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
                      icon={<ArrowsOutSimple aria-hidden="true" />}
                      value={parameters.resolution}
                      options={capability.resolutions}
                      onChange={value => updateParameters({ resolution: value as ImageStudioParameters["resolution"] })}
                    />
                  ) : null}
                  {capability.qualities.length ? (
                    <ParameterSelect
                      id={`${promptId}-quality`}
                      label={t("imageStudio.quality")}
                      icon={<Sparkle aria-hidden="true" />}
                      value={parameters.quality}
                      options={capability.qualities}
                      labels={Object.fromEntries(Object.entries(QUALITY_LABELS).map(([key, label]) => [key, t(label)]))}
                      onChange={value => updateParameters({ quality: value as ImageStudioParameters["quality"] })}
                    />
                  ) : null}

                  <ParameterSelect
                    id={`${promptId}-count`}
                    label={t("imageStudio.count")}
                    icon={<Stack aria-hidden="true" />}
                    value={String(parameters.generationCount ?? 1)}
                    options={IMAGE_STUDIO_COUNTS.map(String)}
                    labels={Object.fromEntries(
                      IMAGE_STUDIO_COUNTS.map(count => [count, t("imageStudio.countLabel", { "0": count })]),
                    )}
                    onChange={value => {
                      const count = IMAGE_STUDIO_COUNTS.find(option => String(option) === value);
                      if (count) updateParameters({ generationCount: count });
                    }}
                  />
                  {capability.supportsTransparentBackground || capability.complexity ? (
                    <Popover key={capability.capabilityId}>
                      <PopoverTrigger asChild>
                        <Button
                          className="image-studio-advanced-toggle"
                          type="button"
                          size="icon-sm"
                          variant="ghost"
                          title={t("imageStudio.advanced")}
                          aria-label={t("imageStudio.advanced")}
                        >
                          <SlidersHorizontal aria-hidden="true" />
                        </Button>
                      </PopoverTrigger>
                      <PopoverContent
                        align="end"
                        side="top"
                        className="image-studio-advanced"
                        aria-label={t("imageStudio.advanced")}
                      >
                        <h3>{t("imageStudio.advanced")}</h3>
                        {capability.complexity ? (
                          <p className="image-studio-hint">
                            {t("imageStudio.complexity", { "0": capability.complexity })}
                          </p>
                        ) : null}
                        {capability.supportsTransparentBackground ? (
                          <label className="image-studio-checkbox">
                            <Checkbox
                              disabled={locked}
                              checked={parameters.transparentBackground ?? false}
                              onCheckedChange={checked => updateParameters({ transparentBackground: checked === true })}
                            />
                            {t("imageStudio.transparent")}
                          </label>
                        ) : null}
                      </PopoverContent>
                    </Popover>
                  ) : null}
                </>
              ) : null}
            </div>
            <Button className="image-studio-generate" type="submit" disabled={!canGenerate} aria-busy={accepting}>
              <Sparkle data-icon="inline-start" weight="fill" aria-hidden="true" />
              {accepting ? t("imageStudio.submitting") : t("imageStudio.generate")}
            </Button>
          </div>
        </fieldset>
      </form>
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
      <Dialog
        open={deletion !== null}
        onOpenChange={open => {
          if (!open && !removeBatch.isPending) setDeletion(null);
        }}
      >
        <DialogContent
          className="image-studio-delete-dialog app-page max-h-[calc(100dvh-2rem)] overflow-y-auto"
          showCloseButton={false}
          onOpenAutoFocus={event => {
            event.preventDefault();
            cancelDeletion.current?.focus();
          }}
          onCloseAutoFocus={event => {
            event.preventDefault();
            const trigger = deleteTrigger.current;
            deleteTrigger.current = null;
            const list = historyListRef.current;
            const next =
              (trigger?.isConnected ? trigger : null) ??
              list?.querySelector<HTMLButtonElement>('[data-batch-id][aria-pressed="true"]') ??
              list?.querySelector<HTMLButtonElement>("[data-batch-id]") ??
              drawerClose.current ??
              historyToggle.current;
            next?.focus();
          }}
          onEscapeKeyDown={event => {
            if (removeBatch.isPending) event.preventDefault();
          }}
          onPointerDownOutside={event => event.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle>
              {deletion ? t("imageStudio.deleteBatchTitle", { "0": batchReference(deletion.id) }) : null}
            </DialogTitle>
            <DialogDescription>{t("imageStudio.deleteBatchDescription")}</DialogDescription>
          </DialogHeader>
          <Field orientation="horizontal" data-disabled={removeBatch.isPending}>
            <Checkbox
              id={deleteFilesId}
              checked={deletion?.deleteFiles ?? false}
              disabled={removeBatch.isPending}
              onCheckedChange={checked => {
                if (deletion) setDeletion({ ...deletion, deleteFiles: checked === true });
              }}
            />
            <FieldLabel htmlFor={deleteFilesId}>{t("imageStudio.deleteFilesOption")}</FieldLabel>
          </Field>
          {removeBatch.error ? (
            <Notice tone="danger" title={t("imageStudio.deleteBatchFailed")}>
              {removeBatch.error.message}
            </Notice>
          ) : null}
          <DialogFooter>
            <DialogClose asChild>
              <Button ref={cancelDeletion} type="button" variant="outline" disabled={removeBatch.isPending}>
                {t("common.cancel")}
              </Button>
            </DialogClose>
            <Button
              type="button"
              variant="destructive"
              disabled={removeBatch.isPending}
              aria-busy={removeBatch.isPending}
              onClick={() => {
                if (deletion && !removeBatch.isPending) removeBatch.mutate(deletion);
              }}
            >
              {t("imageStudio.deleteBatchConfirm")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ParameterSelect({
  id,
  label,
  icon,
  value,
  options,
  labels,
  onChange,
}: {
  id: string;
  label: string;
  icon: ReactNode;
  value?: string;
  options: string[];
  labels?: Record<string, string>;
  onChange: (value: string) => void;
}) {
  return (
    <Field className="image-studio-parameter" title={label}>
      <FieldLabel className="sr-only" htmlFor={id}>
        {label}
      </FieldLabel>
      <Select
        density="compact"
        variant="ghost"
        icon={icon}
        id={id}
        aria-label={label}
        value={value ?? ""}
        onChange={event => onChange(event.target.value)}
      >
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

function MobileHistory({
  children,
  refresh,
  toggleRef,
  closeRef,
}: {
  children: (onChoose: () => void) => ReactNode;
  refresh: ReactNode;
  toggleRef: Ref<HTMLButtonElement>;
  closeRef: Ref<HTMLButtonElement>;
}) {
  const [open, setOpen] = useState(false);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button
          type="button"
          ref={toggleRef}
          variant="ghost"
          size="sm"
          className="image-studio-history-toggle"
          aria-label={t("imageStudio.expandHistory")}
        >
          <ClockCounterClockwise aria-hidden="true" />
          {t("imageStudio.historyShort")}
        </Button>
      </DialogTrigger>
      <DialogContent className="image-studio-history-drawer" showCloseButton={false} aria-describedby={undefined}>
        <header>
          <DialogTitle>{t("imageStudio.history")}</DialogTitle>
          {refresh}
          <DialogClose asChild>
            <Button
              ref={closeRef}
              type="button"
              variant="ghost"
              size="icon-sm"
              aria-label={t("imageStudio.collapseHistory")}
            >
              <X aria-hidden="true" />
            </Button>
          </DialogClose>
        </header>
        {children(() => setOpen(false))}
      </DialogContent>
    </Dialog>
  );
}
