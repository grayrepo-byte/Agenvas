import { ArrowClockwise, ClockCounterClockwise, FilmStrip, Plus, Sparkle, X } from "@/shared/ui/icons";
import {
  queryOptions,
  useInfiniteQuery,
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useId, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, assetContentUrl, assetThumbnailUrl, getCurrentUser } from "../../shared/api/client";
import {
  acceptVideoStudioGeneration,
  checkVideoStudioFrame,
  getVideoStudioContext,
  getVideoStudioGeneration,
  listVideoStudioGenerations,
  uploadVideoStudioFrame,
  type VideoStudioCapability,
  type VideoStudioCommand,
  type VideoStudioFrame,
  type VideoStudioGeneration,
} from "../../shared/api/videoStudio";
import { formatDate, t, useLocale, type MessageKey } from "../../shared/i18n";
import { createCommandKey } from "../../shared/lib/clientId";
import { BrandLogo } from "../../shared/ui/BrandLogo";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
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
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import {
  studioFrames,
  useVideoStudioDraft,
  useVideoStudioDraftStore,
  type FrameSlot,
  type VideoStudioDraft,
  type VideoStudioSettings,
} from "./videoStudioDraft";
import { VideoStudioResults } from "./VideoStudioResults";
import "./VideoStudioPage.css";

const COMPACT_LAYOUT = "(max-width: 900px)";
const POLL_MS = 1_500;
const MAX_PROMPT_LENGTH = 8_000;
const MODE_LABELS = { TEXT: "videoStudio.textMode", START_END: "videoStudio.frameMode" } as const;
const FRAME_LABELS = { startFrame: "videoStudio.startFrame", endFrame: "videoStudio.endFrame" } as const;
const STATUS_LABELS = {
  READY: "imageStudio.status.ready",
  RUNNING: "imageStudio.status.running",
  SUBMITTING: "imageStudio.status.submitting",
  WAITING_PROVIDER: "imageStudio.status.waiting",
  UNKNOWN: "imageStudio.status.unknown",
  BLOCKED: "imageStudio.status.blocked",
  SUCCEEDED: "imageStudio.status.succeeded",
  FAILED: "imageStudio.status.failed",
  CANCELED: "imageStudio.status.canceled",
} as const satisfies Record<VideoStudioGeneration["task"]["status"], MessageKey>;

function subscribeLayout(listener: () => void) {
  const media = window.matchMedia?.(COMPACT_LAYOUT);
  media?.addEventListener("change", listener);
  return () => media?.removeEventListener("change", listener);
}

function isCompact() {
  return window.matchMedia?.(COMPACT_LAYOUT).matches ?? false;
}

function active({ task }: VideoStudioGeneration) {
  return ["READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER"].includes(task.status);
}

function errorText(error: unknown, fallback: MessageKey) {
  return error instanceof ApiError ? error.message : t(fallback);
}

function modelKey({ capabilityId, capabilityVersion }: VideoStudioCapability | VideoStudioSettings) {
  return `${capabilityId}:${capabilityVersion}`;
}

function defaults(model: VideoStudioCapability): VideoStudioSettings {
  return {
    capabilityId: model.capabilityId,
    capabilityVersion: model.capabilityVersion,
    mode: model.defaultMode,
    aspectRatio: model.defaultAspectRatio,
    duration: String(model.defaultDurationSeconds),
    startFrame: null,
    endFrame: null,
  };
}

function validDuration(model: VideoStudioCapability, duration: string) {
  const seconds = Number(duration);
  return /^\d+$/.test(duration) && seconds >= model.minDurationSeconds && seconds <= model.maxDurationSeconds;
}

function allowsFrame(model: VideoStudioCapability, mode: VideoStudioSettings["mode"], slot: FrameSlot) {
  return mode === "START_END" && model.modes.includes(mode) && (slot === "startFrame" || model.supportsEndFrame);
}

function contextOptions(ownerId: string) {
  return queryOptions({
    queryKey: ["video-studio", ownerId, "context"],
    queryFn: ({ signal }) => getVideoStudioContext(signal),
    retry: false,
  });
}

function detailKey(ownerId: string, id?: string) {
  return ["video-studio", ownerId, "detail", id] as const;
}

export function VideoStudioPage() {
  useLocale();
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  return (
    <PageShell title={t("videoStudio.title")}>
      {session.data ? <VideoStudioWorkspace key={session.data.id} ownerId={session.data.id} /> : null}
    </PageShell>
  );
}

function VideoStudioWorkspace({ ownerId }: { ownerId: string }) {
  const queryClient = useQueryClient();
  const draft = useVideoStudioDraft(ownerId);
  const update = (patch: Partial<VideoStudioDraft>) => useVideoStudioDraftStore.getState().update(ownerId, patch);
  const current = () => useVideoStudioDraftStore.getState().owners[ownerId];
  const narrow = useSyncExternalStore(subscribeLayout, isCompact);
  const [railOpen, setRailOpen] = useState(true);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [change, setChange] = useState<{ next: VideoStudioSettings; incompatible: MessageKey[] } | null>(null);
  const changeTrigger = useRef<"mode" | "model">(null);
  const cancelChange = useRef<HTMLButtonElement>(null);
  const [restoreError, setRestoreError] = useState<string>();
  const [uploadError, setUploadError] = useState<string>();
  const fieldId = useId();
  const historyId = `${fieldId}-history`;

  const context = useQuery(contextOptions(ownerId));
  const historyKey = ["video-studio", ownerId, "history"] as const;
  const history = useInfiniteQuery({
    queryKey: historyKey,
    queryFn: ({ pageParam, signal }) => listVideoStudioGenerations(pageParam, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: page => page.nextCursor ?? undefined,
    retry: false,
    refetchInterval: query => (query.state.data?.pages.some(page => page.items.some(active)) ? POLL_MS : false),
  });
  const rows = new Map(history.data?.pages.flatMap(page => page.items.map(item => [item.id, item] as const)));
  const selectedId = draft.selectedId ?? rows.keys().next().value;
  const detail = useQuery({
    queryKey: detailKey(ownerId, selectedId),
    queryFn: ({ signal }) => getVideoStudioGeneration(selectedId!, signal),
    enabled: !!selectedId,
    retry: false,
    refetchInterval: query => (query.state.data && active(query.state.data) ? POLL_MS : false),
  });
  const listed = selectedId ? rows.get(selectedId) : undefined;
  const selected =
    !listed ||
    (detail.data &&
      (detail.data.task.version > listed.task.version ||
        (detail.data.task.version === listed.task.version &&
          detail.data.task.results.length >= listed.task.results.length)))
      ? detail.data
      : listed;
  if (selected) rows.set(selected.id, selected);

  const models = context.data?.capabilities ?? [];
  const preferred = models.find(model => model.capabilityId === context.data?.defaultCapabilityId) ?? models[0];
  const settings = draft.settings ?? (preferred ? defaults(preferred) : null);
  const model = models.find(item => settings && modelKey(item) === modelKey(settings));
  const stale = !!settings && !model;
  const invalidDuration = !!(model && settings && !validDuration(model, settings.duration));
  const invalidMode = !!(model && settings && !model.modes.includes(settings.mode));
  const invalidRatio = !!(model && settings && !model.aspectRatios.includes(settings.aspectRatio));
  const acceptanceKey = ["video-studio", ownerId, "accept"] as const;
  const accepting = useIsMutating({ mutationKey: acceptanceKey, exact: true }) > 0;
  const locked = accepting || !!draft.command;

  const accept = useMutation({
    mutationKey: acceptanceKey,
    mutationFn: acceptVideoStudioGeneration,
    retry: false,
    onSuccess: (generation, command) => {
      const state = current();
      if (state?.command !== command) return;
      queryClient.setQueryData(detailKey(ownerId, generation.id), generation);
      update({
        command: null,
        viewingSubmission: false,
        acceptanceError: null,
        selectedId: state.viewingSubmission ? generation.id : state.selectedId,
      });
      void queryClient.invalidateQueries({ queryKey: historyKey });
    },
    onError: (error, command) => {
      if (current()?.command !== command) return;
      if (error instanceof ApiError && [400, 404, 409, 415, 422].includes(error.status)) {
        update({ command: null, viewingSubmission: false, acceptanceError: error.message });
        void queryClient.invalidateQueries({ queryKey: contextOptions(ownerId).queryKey });
      }
    },
  });
  const upload = useMutation({ mutationFn: uploadVideoStudioFrame, retry: false });
  const restore = useMutation({
    mutationFn: async (id: string) => {
      const [generation] = await Promise.all([
        getVideoStudioGeneration(id),
        queryClient.fetchQuery({ ...contextOptions(ownerId), staleTime: 0 }),
      ]);
      const frames = studioFrames(generation);
      const checks = await Promise.allSettled(frames.map(([, frame]) => frame && checkVideoStudioFrame(frame)));
      return { generation, unreadable: frames.filter((_, index) => checks[index]?.status === "rejected") };
    },
    retry: false,
  });

  const prompt = draft.prompt.trim();
  const canGenerate =
    context.isSuccess &&
    !context.isFetching &&
    !!model &&
    !!settings &&
    !invalidDuration &&
    !invalidMode &&
    !invalidRatio &&
    !!prompt &&
    prompt.length <= MAX_PROMPT_LENGTH &&
    !locked &&
    !upload.isPending &&
    !restore.isPending &&
    (settings.mode === "TEXT"
      ? !settings.startFrame && !settings.endFrame
      : !!settings.startFrame && (!settings.endFrame || model.supportsEndFrame));

  function edit(patch: Partial<VideoStudioDraft>) {
    setRestoreError(undefined);
    update({ acceptanceError: null, ...patch });
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canGenerate || !settings || current()?.command) return;
    const command: VideoStudioCommand = {
      key: createCommandKey(),
      request: {
        capabilityId: settings.capabilityId,
        capabilityVersion: settings.capabilityVersion,
        mode: settings.mode,
        aspectRatio: settings.aspectRatio,
        durationSeconds: Number(settings.duration),
        prompt,
        ...(settings.startFrame ? { startFrameVersionId: settings.startFrame.versionId } : {}),
        ...(settings.endFrame ? { endFrameVersionId: settings.endFrame.versionId } : {}),
      },
    };
    edit({ command, settings, viewingSubmission: true, promptSource: null });
    accept.mutate(command);
  }

  function choose(generation: VideoStudioGeneration) {
    const latest = current() ?? draft;
    const loadPrompt =
      !latest.command && !locked && generation.task.results.length > 0 && generation.prompt !== latest.prompt;
    setRestoreError(undefined);
    update({
      selectedId: generation.id,
      viewingSubmission: false,
      ...(loadPrompt
        ? {
            prompt: generation.prompt,
            promptSource: { id: generation.id, prompt: generation.prompt, previous: latest.prompt },
          }
        : {}),
    });
  }

  function restoreSettings(id: string) {
    if (locked || current()?.command || upload.isPending || restore.isPending) return;
    setRestoreError(undefined);
    update({});
    const snapshot = current();
    restore.mutate(id, {
      // Browsing, typing, or signing out while HEAD is in flight invalidates this restore.
      onSuccess: ({ generation, unreadable }) => {
        if (current() !== snapshot) return;
        if (unreadable.length) {
          setRestoreError(
            t("videoStudio.restoreFrameRolesFailed", {
              0: unreadable.map(([slot]) => t(FRAME_LABELS[slot])).join(" · "),
            }),
          );
          return;
        }
        const { capabilityId, capabilityVersion, mode, durationSeconds, aspectRatio, startFrame, endFrame, prompt } =
          generation;
        edit({
          prompt,
          promptSource: null,
          settings: {
            capabilityId,
            capabilityVersion,
            mode,
            duration: String(durationSeconds),
            aspectRatio,
            startFrame,
            endFrame,
          },
        });
      },
      onError: error => {
        if (current() === snapshot) setRestoreError(errorText(error, "videoStudio.restoreFailed"));
      },
    });
  }

  function changeSettings(nextModel: VideoStudioCapability, requestedMode?: VideoStudioSettings["mode"]) {
    if (locked || !settings || upload.isPending) return;
    const mode = requestedMode ?? (nextModel.modes.includes(settings.mode) ? settings.mode : nextModel.defaultMode);
    const ratio = nextModel.aspectRatios.includes(settings.aspectRatio)
      ? settings.aspectRatio
      : nextModel.defaultAspectRatio;
    const duration = validDuration(nextModel, settings.duration)
      ? settings.duration
      : String(nextModel.defaultDurationSeconds);
    const incompatible: MessageKey[] = [];
    if (!nextModel.modes.includes(settings.mode)) incompatible.push("videoStudio.mode");
    if (ratio !== settings.aspectRatio) incompatible.push("media.editor.aspectRatio");
    if (duration !== settings.duration) incompatible.push("videoStudio.seconds");
    const next = {
      ...settings,
      capabilityId: nextModel.capabilityId,
      capabilityVersion: nextModel.capabilityVersion,
      mode,
      duration,
      aspectRatio: ratio,
    };
    for (const [slot, frame] of studioFrames(settings)) {
      if (frame && !allowsFrame(nextModel, mode, slot)) {
        incompatible.push(FRAME_LABELS[slot]);
        next[slot] = null;
      }
    }
    if (incompatible.length) {
      update({ settings });
      changeTrigger.current = requestedMode ? "mode" : "model";
      setChange({ next, incompatible });
    } else {
      edit({ settings: next });
    }
  }

  function uploadFrame(slot: FrameSlot, file: File) {
    if (
      !settings ||
      !model ||
      !allowsFrame(model, settings.mode, slot) ||
      locked ||
      upload.isPending ||
      restore.isPending
    )
      return;
    setUploadError(undefined);
    edit({ settings });
    upload.mutate(file, {
      onSuccess: frame => {
        if (current()?.settings === settings && frame.projectId === context.data?.projectId) {
          edit({ settings: { ...settings, [slot]: frame } });
        }
      },
      onError: error => {
        if (current()?.settings === settings) setUploadError(errorText(error, "videoStudio.uploadFailed"));
      },
    });
  }

  function refresh() {
    void context.refetch();
    void history.refetch();
    if (selectedId) void detail.refetch();
  }

  const refreshButton = (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={t("imageStudio.refresh")}
      title={t("imageStudio.refresh")}
      onClick={refresh}
    >
      <ArrowClockwise aria-hidden />
    </Button>
  );
  const historyButton = (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      aria-label={t(narrow || !railOpen ? "imageStudio.expandHistory" : "imageStudio.collapseHistory")}
      aria-expanded={narrow ? undefined : railOpen}
      aria-controls={narrow ? undefined : historyId}
      onClick={narrow ? undefined : () => setRailOpen(!railOpen)}
    >
      <ClockCounterClockwise data-icon="inline-start" aria-hidden />
      {t("imageStudio.historyShort")}
    </Button>
  );
  const historyList = (
    <div className="video-studio-history-list">
      {history.isPending ? <LoadingState compact label={t("imageStudio.loadingHistory")} /> : null}
      {history.error ? (
        <Notice tone="danger" title={t("imageStudio.historyFailed")}>
          <Button type="button" variant="outline" size="sm" onClick={() => void history.refetch()}>
            {t("common.retryRead")}
          </Button>
        </Notice>
      ) : null}
      {!history.isPending && !history.error && !rows.size ? <p>{t("imageStudio.noHistory")}</p> : null}
      {rows.size ? (
        <ul>
          {[...rows.values()].map(row => (
            <HistoryEntry
              key={row.id}
              row={row}
              selected={row.id === selectedId}
              onChoose={() => {
                choose(row);
                setDrawerOpen(false);
              }}
            />
          ))}
        </ul>
      ) : null}
      {history.hasNextPage ? (
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={history.isFetching}
          onClick={() => void history.fetchNextPage()}
        >
          {t("videoStudio.moreHistory")}
        </Button>
      ) : null}
    </div>
  );
  const { promptSource } = draft;
  const inactiveControls = locked || upload.isPending;
  const canRestore = !inactiveControls && !restore.isPending;

  return (
    <div className="video-studio">
      <header className="video-studio-header">
        <div className="video-studio-brand">
          <Link to="/projects" aria-label={t("ui.pageShell.homeLabel")}>
            <BrandLogo decorative />
          </Link>
          <h1>{t("videoStudio.title")}</h1>
        </div>
        <div className="video-studio-header-actions">
          {refreshButton}
          {narrow ? (
            <Dialog open={drawerOpen} onOpenChange={setDrawerOpen}>
              <DialogTrigger asChild>{historyButton}</DialogTrigger>
              <DialogContent
                className="video-studio-history-drawer"
                aria-describedby={undefined}
                showCloseButton={false}
                fullscreen
              >
                <DialogHeader>
                  <DialogTitle>{t("imageStudio.history")}</DialogTitle>
                  <DialogClose asChild>
                    <Button type="button" variant="ghost" size="icon" aria-label={t("common.close")}>
                      <X aria-hidden />
                    </Button>
                  </DialogClose>
                </DialogHeader>
                {historyList}
              </DialogContent>
            </Dialog>
          ) : (
            historyButton
          )}
        </div>
      </header>
      <div className="video-studio-workspace">
        <div className="video-studio-stage">
          {detail.error ? (
            <Notice tone="danger" title={t("imageStudio.statusFailed")}>
              <Button type="button" variant="outline" size="sm" onClick={() => void detail.refetch()}>
                {t("common.retryRead")}
              </Button>
            </Notice>
          ) : null}
          <VideoStudioResults key={selected?.id ?? "none"} generation={selected} />
          {selected ? (
            <div className="video-studio-restore">
              <Button
                type="button"
                variant="outline"
                size="content"
                disabled={!canRestore}
                onClick={() => restoreSettings(selected.id)}
              >
                {t("videoStudio.restoreSettings")}
              </Button>
            </div>
          ) : null}
        </div>
        {!narrow ? (
          <aside
            id={historyId}
            className="video-studio-history"
            aria-label={t("imageStudio.history")}
            hidden={!railOpen}
          >
            <h2>{t("imageStudio.history")}</h2>
            {historyList}
          </aside>
        ) : null}
      </div>
      <form className="video-studio-composer" aria-label={t("imageStudio.parameters")} onSubmit={submit}>
        {context.isPending ? <LoadingState compact label={t("videoStudio.loadingModels")} /> : null}
        {context.error ? (
          <Notice title={t("videoStudio.loadFailed")} tone="danger">
            <Button type="button" variant="outline" size="sm" onClick={() => void context.refetch()}>
              {t("imageStudio.retryModels")}
            </Button>
          </Notice>
        ) : null}
        {context.isSuccess && !models.length ? (
          <Notice title={t("videoStudio.noModels")}>
            <Button asChild variant="outline" size="sm">
              <Link to="/settings/media">{t("imageStudio.configureModels")}</Link>
            </Button>
          </Notice>
        ) : null}
        {stale && context.isSuccess ? (
          <Notice tone="warning" title={t("imageStudio.staleModel")}>
            {t("imageStudio.unavailableModelHint")}
          </Notice>
        ) : null}
        {accepting ? <LoadingState compact label={t("imageStudio.sendingRequest")} /> : null}
        {draft.command && !accepting ? (
          <Notice tone="warning" title={t("imageStudio.acceptanceUncertain")}>
            <p>{t("videoStudio.acceptanceUncertainHint")}</p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
                const command = current()?.command;
                if (command && !queryClient.isMutating({ mutationKey: acceptanceKey, exact: true }))
                  accept.mutate(command);
              }}
            >
              {t("imageStudio.retryAcceptance")}
            </Button>
          </Notice>
        ) : null}
        {draft.acceptanceError ? (
          <Notice tone="danger" title={t("imageStudio.submitFailed")}>
            {draft.acceptanceError}
          </Notice>
        ) : null}
        {restore.isPending ? <LoadingState compact label={t("videoStudio.restorePending")} /> : null}
        {restoreError ? (
          <Notice tone="danger" title={t("videoStudio.restoreFailed")}>
            {restoreError}
          </Notice>
        ) : null}
        {upload.isPending ? <LoadingState compact label={t("imageStudio.uploading")} /> : null}
        {uploadError ? (
          <Notice tone="danger" title={t("videoStudio.uploadFailed")}>
            {uploadError}
          </Notice>
        ) : null}

        <FieldGroup className="video-studio-fields">
          <Field>
            <FieldLabel htmlFor={`${fieldId}-prompt`}>{t("imageStudio.prompt")}</FieldLabel>
            <Textarea
              id={`${fieldId}-prompt`}
              value={draft.prompt}
              placeholder={t("videoStudio.promptPlaceholder")}
              maxLength={MAX_PROMPT_LENGTH}
              rows={3}
              readOnly={locked}
              onChange={event => edit({ prompt: event.target.value, promptSource: null, settings })}
            />
          </Field>
          {promptSource ? (
            <div className="video-studio-prompt-source">
              <p role="status">
                {t("imageStudio.historyPromptLoaded", { 0: `#${promptSource.id.slice(0, 8).toUpperCase()}` })}
                <span>{promptSource.prompt}</span>
              </p>
              <Button
                type="button"
                variant="ghost"
                size="content"
                disabled={locked}
                onClick={() => edit({ prompt: promptSource.previous, promptSource: null })}
              >
                {t("imageStudio.undoHistoryPrompt")}
              </Button>
              <Button
                type="button"
                variant="outline"
                size="content"
                disabled={!canRestore}
                onClick={() => restoreSettings(promptSource.id)}
              >
                {t("videoStudio.restoreSourceSettings")}
              </Button>
            </div>
          ) : null}
          {settings ? (
            <FieldGroup className="video-studio-options">
              <Field>
                <FieldLabel htmlFor={`${fieldId}-model`}>{t("common.model")}</FieldLabel>
                <Select
                  id={`${fieldId}-model`}
                  value={modelKey(settings)}
                  disabled={inactiveControls || !models.length}
                  onChange={event => {
                    const next = models.find(item => modelKey(item) === event.target.value);
                    if (next) changeSettings(next);
                  }}
                >
                  {stale ? (
                    <option value={modelKey(settings)} disabled>
                      {t("imageStudio.unavailableModel")} · v{settings.capabilityVersion}
                    </option>
                  ) : null}
                  {models.map(item => (
                    <option key={modelKey(item)} value={modelKey(item)}>
                      {item.name} · {item.connectionName}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field data-invalid={invalidMode || undefined}>
                <FieldLabel htmlFor={`${fieldId}-mode`}>{t("videoStudio.mode")}</FieldLabel>
                <Select
                  id={`${fieldId}-mode`}
                  value={settings.mode}
                  disabled={inactiveControls || !model}
                  aria-invalid={invalidMode || undefined}
                  onChange={event => {
                    const mode = model?.modes.find(item => item === event.target.value);
                    if (model && mode) changeSettings(model, mode);
                  }}
                >
                  {!model || invalidMode ? (
                    <option value={settings.mode} disabled>
                      {t(MODE_LABELS[settings.mode])}
                    </option>
                  ) : null}
                  {model?.modes.map(mode => (
                    <option key={mode} value={mode}>
                      {t(MODE_LABELS[mode])}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field data-invalid={invalidRatio || undefined}>
                <FieldLabel htmlFor={`${fieldId}-ratio`}>{t("media.editor.aspectRatio")}</FieldLabel>
                <Select
                  id={`${fieldId}-ratio`}
                  value={settings.aspectRatio}
                  disabled={inactiveControls || !model}
                  aria-invalid={invalidRatio || undefined}
                  onChange={event => {
                    const aspectRatio = model?.aspectRatios.find(item => item === event.target.value);
                    if (aspectRatio) edit({ settings: { ...settings, aspectRatio } });
                  }}
                >
                  {!model || invalidRatio ? (
                    <option value={settings.aspectRatio} disabled>
                      {settings.aspectRatio}
                    </option>
                  ) : null}
                  {model?.aspectRatios.map(ratio => (
                    <option key={ratio} value={ratio}>
                      {ratio}
                    </option>
                  ))}
                </Select>
              </Field>
              <Field data-invalid={invalidDuration || undefined}>
                <FieldLabel htmlFor={`${fieldId}-duration`}>{t("videoStudio.seconds")}</FieldLabel>
                <Input
                  id={`${fieldId}-duration`}
                  type="number"
                  inputMode="numeric"
                  min={model?.minDurationSeconds}
                  max={model?.maxDurationSeconds}
                  step={1}
                  aria-invalid={invalidDuration || undefined}
                  aria-describedby={invalidDuration ? `${fieldId}-duration-range` : undefined}
                  disabled={inactiveControls || !model}
                  value={settings.duration}
                  onChange={event => edit({ settings: { ...settings, duration: event.target.value } })}
                />
                {invalidDuration && model ? (
                  <FieldDescription id={`${fieldId}-duration-range`}>
                    {t("videoStudio.durationRange", { 0: model.minDurationSeconds, 1: model.maxDurationSeconds })}
                  </FieldDescription>
                ) : null}
              </Field>
            </FieldGroup>
          ) : null}
          {settings?.mode === "START_END" || settings?.startFrame || settings?.endFrame ? (
            <FieldGroup className="video-studio-frames">
              {studioFrames(settings).map(([slot, frame]) => {
                const supported = !!model && allowsFrame(model, settings.mode, slot);
                return supported || frame ? (
                  <FramePicker
                    key={slot}
                    slot={slot}
                    frame={frame}
                    canUpload={supported && !inactiveControls && !restore.isPending}
                    canRemove={!inactiveControls}
                    onUpload={file => uploadFrame(slot, file)}
                    onRemove={() => edit({ settings: { ...settings, [slot]: null } })}
                  />
                ) : null;
              })}
            </FieldGroup>
          ) : null}
        </FieldGroup>
        <div className="video-studio-composer-actions">
          <Button type="submit" size="lg" disabled={!canGenerate}>
            <Sparkle data-icon="inline-start" aria-hidden />
            {t("videoStudio.generate")}
          </Button>
        </div>
      </form>
      <Dialog
        open={!!change}
        onOpenChange={open => {
          if (!open) setChange(null);
        }}
      >
        <DialogContent
          showCloseButton={false}
          onOpenAutoFocus={event => {
            event.preventDefault();
            cancelChange.current?.focus();
          }}
          onCloseAutoFocus={event => {
            event.preventDefault();
            document.getElementById(`${fieldId}-${changeTrigger.current}`)?.focus();
          }}
        >
          <DialogHeader>
            <DialogTitle>{t("videoStudio.changeSettings")}</DialogTitle>
            <DialogDescription>{t("videoStudio.changeSettingsHint")}</DialogDescription>
          </DialogHeader>
          <ul className="video-studio-incompatible">
            {change?.incompatible.map(key => (
              <li key={key}>{t(key)}</li>
            ))}
          </ul>
          <DialogFooter>
            <DialogClose asChild>
              <Button ref={cancelChange} type="button" variant="outline">
                {t("common.cancel")}
              </Button>
            </DialogClose>
            <Button
              type="button"
              onClick={() => {
                if (change && !current()?.command) edit({ settings: change.next });
                setChange(null);
              }}
            >
              {t("videoStudio.confirmChange")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function HistoryEntry({
  row,
  selected,
  onChoose,
}: {
  row: VideoStudioGeneration;
  selected: boolean;
  onChoose: () => void;
}) {
  const cover = row.task.results[0];
  return (
    <li>
      <Button
        type="button"
        variant="ghost"
        size="content"
        className="video-studio-history-entry"
        aria-pressed={selected}
        onClick={onChoose}
      >
        <span className="video-studio-history-cover" aria-hidden>
          {cover ? <img src={assetThumbnailUrl(row.projectId, cover.assetId)} alt="" loading="lazy" /> : <FilmStrip />}
        </span>
        <span className="video-studio-history-copy">
          <strong title={row.prompt}>{row.prompt}</strong>
          <span>
            {t(STATUS_LABELS[row.task.status])} · {t("videoStudio.historyResultCount", { 0: row.task.results.length })}
          </span>
          <time dateTime={row.createdAt}>
            {formatDate(row.createdAt, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}
          </time>
        </span>
      </Button>
    </li>
  );
}

function FramePicker({
  slot,
  frame,
  canUpload,
  canRemove,
  onUpload,
  onRemove,
}: {
  slot: FrameSlot;
  frame: VideoStudioFrame | null;
  canUpload: boolean;
  canRemove: boolean;
  onUpload: (file: File) => void;
  onRemove: () => void;
}) {
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);
  const label = t(FRAME_LABELS[slot]);
  const uploadLabel = t("videoStudio.uploadFrame", { 0: label });
  return (
    <Field className="video-studio-frame">
      <FieldLabel htmlFor={inputId}>{label}</FieldLabel>
      <FieldDescription>
        {t(slot === "startFrame" ? "videoStudio.requiredFrame" : "videoStudio.optionalFrame")}
      </FieldDescription>
      <div className="video-studio-frame-actions">
        {frame ? <img src={assetContentUrl(frame.projectId, frame.assetId)} alt={label} /> : null}
        <input
          id={inputId}
          ref={input}
          type="file"
          accept="image/png,image/jpeg,image/webp"
          hidden
          disabled={!canUpload}
          onChange={event => {
            const file = event.currentTarget.files?.[0];
            event.currentTarget.value = "";
            if (file) onUpload(file);
          }}
        />
        {canUpload ? (
          <Button type="button" size="content" variant="outline" onClick={() => input.current?.click()}>
            <Plus data-icon="inline-start" aria-hidden />
            {uploadLabel}
          </Button>
        ) : null}
        {frame ? (
          <Button
            type="button"
            size="icon-sm"
            variant="ghost"
            disabled={!canRemove}
            aria-label={t("videoStudio.removeFrame", { 0: label })}
            onClick={onRemove}
          >
            <X aria-hidden />
          </Button>
        ) : null}
      </div>
    </Field>
  );
}
