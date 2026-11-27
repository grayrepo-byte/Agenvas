import { ArrowClockwise, ClockCounterClockwise, FilmStrip, ArrowRight, Cube, X } from "@/shared/ui/icons";
import {
  queryOptions,
  useInfiniteQuery,
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useId, useRef, useState, useSyncExternalStore, type CSSProperties, type FormEvent } from "react";
import { Link } from "react-router";
import { ApiError, assetThumbnailUrl, getCurrentUser } from "../../shared/api/client";
import {
  acceptVideoStudioGeneration,
  checkVideoStudioFrame,
  getVideoStudioContext,
  getVideoStudioGeneration,
  listVideoStudioGenerations,
  uploadVideoStudioFrame,
  uploadVideoStudioReference,
  type VideoStudioCapability,
  type VideoStudioCommand,
  type VideoStudioGeneration,
  type VideoStudioReferenceAsset,
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
import { workflowFieldLabel } from "../canvas/workflowFieldPresentation";
import {
  evaluateWorkflowFields,
  isWorkflowMediaField,
  workflowValidationErrors,
  type WorkflowValue,
} from "../canvas/workflowFields";
import {
  FRAME_LABELS,
  LIMIT_KEYS,
  MAX_REFERENCES,
  allowsFrame,
  studioFrames,
  workflowRatio,
  useVideoStudioDraft,
  useVideoStudioDraftStore,
  type FrameSlot,
  type VideoStudioDraft,
  type VideoStudioSettings,
} from "./videoStudioDraft";
import { VideoStudioInputs } from "./VideoStudioInputs";
import { VideoStudioResults } from "./VideoStudioResults";
import "./VideoStudioPage.css";

const COMPACT_LAYOUT = "(max-width: 900px)";
const POLL_MS = 1_500;
const MAX_PROMPT_LENGTH = 8_000;
const MODE_LABELS = {
  TEXT: "videoStudio.textMode",
  START_END: "videoStudio.frameMode",
  GENERAL_REFERENCE: "common.mixedReference",
} as const;
const REFERENCE_LABELS = {
  IMAGE: "templates.numberedImage",
  VIDEO: "videoStudio.numberedVideo",
  AUDIO: "videoStudio.numberedAudio",
} as const;
type Reference = VideoStudioGeneration["references"][number];
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
    duration: model.defaultDurationSeconds?.toString() ?? "",
    videoResolution: model.defaultVideoResolution,
    dynamicValues: model.defaultDynamicValues,
    references: [],
    startFrame: null,
    endFrame: null,
  };
}

function durationOptions(model: VideoStudioCapability) {
  const { minDurationSeconds: min, maxDurationSeconds: max } = model;
  if (min === null || max === null) return [];
  const options = model.fields?.find(field => field.source === "DURATION_SECONDS" && field.type === "SELECT")?.options;
  return (
    options?.filter(option => typeof option.value === "number" && option.value >= min && option.value <= max) ?? []
  );
}

function validDuration(model: VideoStudioCapability, duration: string) {
  const { minDurationSeconds: min, maxDurationSeconds: max } = model;
  if (min === null || max === null) return true;
  const seconds = Number(duration);
  const options = durationOptions(model);
  return (
    /^\d+$/.test(duration) &&
    seconds >= min &&
    seconds <= max &&
    (!options.length || options.some(option => option.value === seconds))
  );
}

function workflowValues(settings: VideoStudioSettings) {
  return {
    ...settings.dynamicValues,
    ...Object.fromEntries(
      settings.references.flatMap(ref => (ref.fieldKey ? [[ref.fieldKey, ref.versionId] as const] : [])),
    ),
  };
}

function durationValue(settings: VideoStudioSettings) {
  return settings.duration === "" ? null : Number(settings.duration);
}

function referenceLabels(references: Reference[], fields?: VideoStudioCapability["fields"]) {
  const counts = { IMAGE: 0, VIDEO: 0, AUDIO: 0 };
  return references.map(ref => {
    const index = ++counts[ref.kind];
    if (!ref.fieldKey) return t(REFERENCE_LABELS[ref.kind], { 0: index });
    const field = fields?.find(item => item.key === ref.fieldKey);
    return field ? workflowFieldLabel(field) : ref.fieldKey;
  });
}

function compatibleReferences(model: VideoStudioCapability, settings: VideoStudioSettings, prompt: string) {
  const fields = model.fields
    ? evaluateWorkflowFields(
        { fields: model.fields },
        workflowValues(settings),
        prompt,
        model.minDurationSeconds === null ? null : durationValue(settings),
      ).activeFields
    : null;
  const counts = { IMAGE: 0, VIDEO: 0, AUDIO: 0 };
  const limits = model.referenceLimits;
  const references: Reference[] = [];
  for (const ref of settings.references) {
    const matchesField =
      settings.mode === "GENERAL_REFERENCE" &&
      fields?.some(field => field.key === ref.fieldKey && field.type === ref.kind && field.source === "PARAMETER");
    const matchesStandardMode = !fields && settings.mode === "GENERAL_REFERENCE" && !ref.fieldKey;
    if (
      (matchesField || matchesStandardMode) &&
      references.length < MAX_REFERENCES &&
      counts[ref.kind] < limits[LIMIT_KEYS[ref.kind]]
    ) {
      counts[ref.kind]++;
      references.push(ref);
    }
  }
  return references;
}

/** Only explicit actions move the viewport. History refreshes and polling keep the editor in place. */
function reveal(element: HTMLElement | null) {
  requestAnimationFrame(() => {
    if (!element?.isConnected) return;
    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    element.scrollIntoView?.({ block: "start", behavior: reduced ? "instant" : "smooth" });
  });
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
  const [historyPreference, setHistoryPreference] = useState<boolean | null>(null);
  const stage = useRef<HTMLDivElement>(null);
  const composer = useRef<HTMLFormElement>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const historyTrigger = useRef<HTMLButtonElement>(null);
  const showResultAfterClose = useRef(false);
  const [refreshing, setRefreshing] = useState(false);
  const [change, setChange] = useState<{ patch: Partial<VideoStudioDraft>; incompatible: string[] } | null>(null);
  const changeTrigger = useRef<string>(null);
  const cancelChange = useRef<HTMLButtonElement>(null);
  const [restoreError, setRestoreError] = useState<string>();
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
  const railOpen = historyPreference ?? (rows.size > 0 || history.isError);
  // A newer row from another tab must not replace the video already open in this workspace.
  const firstListed = rows.keys().next().value;
  const [initialHistoryId, setInitialHistoryId] = useState<string>();
  if (!initialHistoryId && firstListed) setInitialHistoryId(firstListed);
  const selectedId = draft.selectedId ?? initialHistoryId ?? firstListed;
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
  const invalidMode = !!(model && settings && !model.modes.includes(settings.mode));
  const invalidRatio = !!(
    model?.aspectRatios.length &&
    settings &&
    settings.aspectRatio &&
    !model.aspectRatios.includes(settings.aspectRatio)
  );
  const invalidResolution = !!(
    model?.videoResolutions.length &&
    settings?.videoResolution &&
    !model.videoResolutions.includes(settings.videoResolution)
  );
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
      if (state.viewingSubmission) reveal(stage.current);
    },
    onError: (error, command) => {
      if (current()?.command !== command) return;
      if (error instanceof ApiError && [400, 404, 409, 415, 422].includes(error.status)) {
        update({ command: null, viewingSubmission: false, acceptanceError: error.message });
        void queryClient.invalidateQueries({ queryKey: contextOptions(ownerId).queryKey });
      }
    },
  });
  const frameUploadKey = ["video-studio", ownerId, "upload-frame"] as const;
  const upload = useMutation({
    mutationKey: frameUploadKey,
    mutationFn: ({ file }: { file: File; slot: FrameSlot; settings: VideoStudioSettings }) =>
      uploadVideoStudioFrame(file),
    // The workspace may have unmounted while the upload ran. Only the original settings can receive this frame.
    onSuccess: (frame, { slot, settings }) => {
      if (current()?.settings !== settings) return;
      const catalog = queryClient.getQueryData(contextOptions(ownerId).queryKey);
      if (
        frame.projectId === catalog?.projectId &&
        catalog?.capabilities.some(item => modelKey(item) === modelKey(settings))
      ) {
        update({ settings: { ...settings, [slot]: frame }, frameUploadError: null });
      } else {
        update({ frameUploadError: t("videoStudio.uploadFailed") });
      }
    },
    onError: (error, { settings }) => {
      if (current()?.settings === settings) update({ frameUploadError: errorText(error, "videoStudio.uploadFailed") });
    },
    retry: false,
  });
  const uploadingFrame = useIsMutating({ mutationKey: frameUploadKey, exact: true }) > 0;
  const referenceUploadKey = ["video-studio", ownerId, "upload-reference"] as const;
  const referenceUpload = useMutation({
    mutationKey: referenceUploadKey,
    mutationFn: ({ kind, file }: { kind: VideoStudioReferenceAsset["kind"]; file: File }) =>
      uploadVideoStudioReference(kind, file),
    retry: false,
  });
  const uploadingReference = useIsMutating({ mutationKey: referenceUploadKey, exact: true }) > 0;
  const uploading = uploadingFrame || uploadingReference;
  const restore = useMutation({
    mutationFn: async (id: string) => {
      const [generation, catalog] = await Promise.all([
        getVideoStudioGeneration(id),
        queryClient.fetchQuery({ ...contextOptions(ownerId), staleTime: 0 }),
      ]);
      if (generation.projectId !== catalog.projectId) throw new Error(t("videoStudio.restoreFailed"));
      const savedModel = catalog.capabilities.find(
        item =>
          item.capabilityId === generation.capabilityId && item.capabilityVersion === generation.capabilityVersion,
      );
      const frames = studioFrames(generation).flatMap(([slot, frame]) =>
        frame ? [{ asset: frame, label: t(FRAME_LABELS[slot]) }] : [],
      );
      const labels = referenceLabels(generation.references, savedModel?.fields);
      const assets = [...frames, ...generation.references.map((asset, index) => ({ asset, label: labels[index]! }))];
      const checks = await Promise.allSettled(
        assets.map(async ({ asset }) => {
          if (asset.projectId !== catalog.projectId) throw new Error(t("videoStudio.restoreFailed"));
          return checkVideoStudioFrame(asset);
        }),
      );
      return { generation, unreadable: assets.filter((_, index) => checks[index]?.status === "rejected") };
    },
    retry: false,
  });

  const prompt = draft.prompt.trim();
  // Each published prompt mapping uses the same visible shot-description control.
  const definition = model?.fields
    ? {
        fields: model.fields.map(field =>
          field.source === "PROMPT"
            ? { ...field, label: t("videoStudio.shotDescription"), description: t("videoStudio.shotDescription") }
            : field,
        ),
      }
    : null;
  const values = settings ? workflowValues(settings) : {};
  const durationInput = settings && model?.minDurationSeconds != null ? durationValue(settings) : null;
  const workflow = definition ? evaluateWorkflowFields(definition, values, prompt, durationInput) : null;
  const promptFields = workflow?.activeFields.filter(field => field.source === "PROMPT") ?? [];
  const promptLimit = Math.min(MAX_PROMPT_LENGTH, ...promptFields.map(field => field.maxLength ?? MAX_PROMPT_LENGTH));
  const durationField = model?.fields?.find(field => field.source === "DURATION_SECONDS");
  const hasDuration =
    model?.minDurationSeconds != null && (!durationField || workflow?.activeFields.includes(durationField));
  const duration = hasDuration ? durationInput : null;
  const invalidDuration = !!(hasDuration && model && settings && !validDuration(model, settings.duration));
  const labels = referenceLabels(settings?.references ?? [], model?.fields);
  const choices = (settings?.references ?? []).flatMap((ref, index, refs) =>
    ref.fieldKey && refs.findIndex(item => item.versionId === ref.versionId) === index
      ? [
          {
            id: ref.versionId,
            label: labels[index]!,
            kind: ref.kind,
            available: ref.projectId === context.data?.projectId,
          },
        ]
      : [],
  );
  const validation = definition ? workflowValidationErrors(definition, values, prompt, duration, choices) : [];
  const referenceErrors: string[] = [];
  if (model && settings) {
    if (
      compatibleReferences(model, settings, prompt).length !== settings.references.length ||
      settings.references.some(ref => ref.projectId !== context.data?.projectId)
    ) {
      referenceErrors.push(t("videoStudio.incompatibleReferences"));
    } else if (settings.mode === "GENERAL_REFERENCE" && !definition && !settings.references.length) {
      referenceErrors.push(t("videoStudio.referenceRequired"));
    } else if (
      model.adapterId === "ARK_SEEDANCE_2_I2V" &&
      settings.references.some(ref => ref.kind === "AUDIO") &&
      !settings.references.some(ref => ref.kind !== "AUDIO")
    ) {
      referenceErrors.push(t("videoStudio.visualReferenceRequired"));
    }
  }
  const canGenerate =
    context.isSuccess &&
    !context.isFetching &&
    !!model &&
    !!settings &&
    !invalidDuration &&
    !invalidMode &&
    !invalidRatio &&
    !invalidResolution &&
    (!!definition || !!prompt) &&
    !validation.length &&
    !referenceErrors.length &&
    prompt.length <= promptLimit &&
    !locked &&
    !uploading &&
    !restore.isPending &&
    !change &&
    (settings.mode === "START_END"
      ? !!settings.startFrame &&
        (!settings.endFrame || model.supportsEndFrame) &&
        (!model.requiresEndFrame || !!settings.endFrame)
      : !settings.startFrame && !settings.endFrame);

  function edit(patch: Partial<VideoStudioDraft>) {
    setRestoreError(undefined);
    update({ acceptanceError: null, frameUploadError: null, ...patch });
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canGenerate || !settings || current()?.command) return;
    const dynamicValues = Object.fromEntries<WorkflowValue>(
      workflow?.activeFields.flatMap(field => {
        const value = workflow.effectiveValues[field.key];
        return field.source === "PARAMETER" && !isWorkflowMediaField(field) && value !== undefined
          ? [[field.key, value] as const]
          : [];
      }) ?? [],
    );
    const command: VideoStudioCommand = {
      key: createCommandKey(),
      request: {
        capabilityId: settings.capabilityId,
        capabilityVersion: settings.capabilityVersion,
        mode: settings.mode,
        ...(model?.aspectRatios.length && settings.aspectRatio ? { aspectRatio: settings.aspectRatio } : {}),
        ...(model?.videoResolutions.length && settings.videoResolution
          ? { videoResolution: settings.videoResolution }
          : {}),
        ...(Object.keys(dynamicValues).length ? { dynamicValues } : {}),
        ...(settings.references.length
          ? {
              references: settings.references.map(({ kind, versionId, fieldKey }) =>
                fieldKey ? { kind, versionId, fieldKey } : { kind, versionId },
              ),
            }
          : {}),
        durationSeconds: duration,
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
      !latest.command &&
      !locked &&
      !uploadingReference &&
      generation.task.results.length > 0 &&
      generation.prompt !== latest.prompt;
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
    if (narrow) showResultAfterClose.current = true;
    else reveal(stage.current);
  }

  function restoreSettings(id: string) {
    if (locked || current()?.command || uploading || restore.isPending) return;
    setRestoreError(undefined);
    update({});
    const snapshot = current();
    restore.mutate(id, {
      // Browsing, typing, or signing out while HEAD is in flight invalidates this restore.
      onSuccess: ({ generation, unreadable }) => {
        if (current() !== snapshot) return;
        if (unreadable.length) {
          setRestoreError(
            t(
              generation.references.length
                ? "videoStudio.restoreReferencesFailed"
                : "videoStudio.restoreFrameRolesFailed",
              {
                0: unreadable.map(({ label }) => label).join(" · "),
              },
            ),
          );
          return;
        }
        const {
          capabilityId,
          capabilityVersion,
          mode,
          durationSeconds,
          aspectRatio,
          videoResolution,
          dynamicValues,
          startFrame,
          endFrame,
          references,
          prompt,
        } = generation;
        edit({
          prompt,
          promptSource: null,
          settings: {
            capabilityId,
            capabilityVersion,
            mode,
            duration: durationSeconds?.toString() ?? "",
            aspectRatio,
            videoResolution,
            dynamicValues,
            references,
            startFrame,
            endFrame,
          },
        });
        reveal(composer.current);
      },
      onError: error => {
        if (current() === snapshot) setRestoreError(errorText(error, "videoStudio.restoreFailed"));
      },
    });
  }

  function applyChoice(
    nextModel: VideoStudioCapability,
    next: VideoStudioSettings,
    trigger: string,
    patch: Partial<VideoStudioDraft> = {},
    incompatible: string[] = [],
  ) {
    const nextPrompt = (patch.prompt ?? draft.prompt).trim();
    let kept = compatibleReferences(nextModel, next, nextPrompt);
    // Dropping a controlling field can deactivate another named media slot.
    for (let count = next.references.length; kept.length < count;) {
      count = kept.length;
      kept = compatibleReferences(nextModel, { ...next, references: kept }, nextPrompt);
    }
    const dropped = referenceLabels(next.references, model?.fields).filter(
      (_, index) => !kept.includes(next.references[index]!),
    );
    incompatible.push(...dropped);
    patch = { ...patch, settings: { ...next, references: kept } };
    if (incompatible.length) {
      // Invalidate a pending history restore without applying the proposed change.
      update({ settings });
      changeTrigger.current = trigger;
      setChange({ patch, incompatible });
    } else {
      edit(patch);
    }
  }

  function changeSettings(nextModel: VideoStudioCapability, requestedMode?: VideoStudioSettings["mode"]) {
    if (locked || !settings || uploading) return;
    const next = defaults(nextModel);
    const sameModel = modelKey(nextModel) === modelKey(settings);
    next.mode = requestedMode ?? (nextModel.modes.includes(settings.mode) ? settings.mode : next.mode);
    if (settings.aspectRatio && nextModel.aspectRatios.includes(settings.aspectRatio))
      next.aspectRatio = settings.aspectRatio;
    if (settings.videoResolution && nextModel.videoResolutions.includes(settings.videoResolution)) {
      next.videoResolution = settings.videoResolution;
    }
    if (nextModel.minDurationSeconds !== null && validDuration(nextModel, settings.duration))
      next.duration = settings.duration;
    if (sameModel) next.dynamicValues = settings.dynamicValues;
    next.references = settings.references;
    const incompatible: string[] = [];
    for (const [slot, frame] of studioFrames(settings)) {
      if (allowsFrame(nextModel, next.mode, slot)) next[slot] = frame;
      else if (frame) incompatible.push(t(FRAME_LABELS[slot]));
    }
    applyChoice(nextModel, next, `${fieldId}-${requestedMode ? "mode" : "model"}`, {}, incompatible);
  }

  function uploadFrame(slot: FrameSlot, file: File) {
    if (
      !settings ||
      !model ||
      !allowsFrame(model, settings.mode, slot) ||
      locked ||
      uploading ||
      restore.isPending ||
      queryClient.isMutating({ mutationKey: frameUploadKey, exact: true })
    )
      return;
    edit({ settings });
    upload.mutate({ file, slot, settings });
  }

  async function uploadReference(kind: VideoStudioReferenceAsset["kind"], file: File, fieldKey: string | null = null) {
    const projectId = context.data?.projectId;
    if (
      !settings ||
      !model ||
      !projectId ||
      settings.mode !== "GENERAL_REFERENCE" ||
      locked ||
      uploading ||
      restore.isPending ||
      current()?.command ||
      queryClient.isMutating({ mutationKey: referenceUploadKey, exact: true })
    )
      return;
    const others = fieldKey ? settings.references.filter(ref => ref.fieldKey !== fieldKey) : settings.references;
    if (
      others.length >= MAX_REFERENCES ||
      others.filter(ref => ref.kind === kind).length >= model.referenceLimits[LIMIT_KEYS[kind]]
    )
      throw new Error(t("videoStudio.incompatibleReferences"));
    edit({ settings });
    let ref: VideoStudioReferenceAsset;
    try {
      ref = await referenceUpload.mutateAsync({ kind, file });
    } catch (error) {
      if (current()?.settings === settings) throw error;
      return;
    }
    if (current()?.settings !== settings) return;
    const latest = queryClient.getQueryData(contextOptions(ownerId).queryKey);
    if (
      projectId !== latest?.projectId ||
      projectId !== ref.projectId ||
      ref.kind !== kind ||
      !latest?.capabilities.some(item => modelKey(item) === modelKey(settings))
    ) {
      throw new Error(t("media.runningHub.uploadFailed"));
    }
    const next = { ...settings, references: [...others, { ...ref, fieldKey }] };
    if (compatibleReferences(model, next, prompt).length !== next.references.length) {
      throw new Error(t("videoStudio.incompatibleReferences"));
    }
    edit({ settings: next });
  }

  function changeWorkflowField(key: string, value: WorkflowValue | undefined) {
    if (!settings || !model || inactiveControls || current()?.command) return;
    const field = workflow?.activeFields.find(item => item.key === key);
    if (!field || field.source !== "PARAMETER" || isWorkflowMediaField(field)) return;
    const trigger = document.activeElement?.id || `${fieldId}-mode`;
    const dynamicValues = { ...settings.dynamicValues };
    if (value === undefined) delete dynamicValues[key];
    else dynamicValues[key] = value;
    applyChoice(model, { ...settings, dynamicValues }, trigger);
  }

  function editPrompt(nextPrompt: string, trigger: string) {
    const patch = { prompt: nextPrompt, promptSource: null, settings };
    if (model?.fields && settings && settings.references.length) applyChoice(model, settings, trigger, patch);
    else edit(patch);
  }

  async function refresh() {
    if (refreshing) return;
    setRefreshing(true);
    try {
      await Promise.all([context.refetch(), history.refetch(), selectedId && detail.refetch()]);
    } finally {
      setRefreshing(false);
    }
  }

  const refreshLabel = t(refreshing ? "common.refreshing" : "imageStudio.refresh");
  const refreshButton = (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      className="video-studio-refresh"
      aria-label={refreshLabel}
      aria-disabled={refreshing || undefined}
      aria-busy={refreshing}
      title={refreshLabel}
      onClick={() => void refresh()}
    >
      <ArrowClockwise aria-hidden />
    </Button>
  );
  const historyButton = (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      ref={historyTrigger}
      className="video-studio-history-toggle"
      aria-label={t(narrow || !railOpen ? "imageStudio.expandHistory" : "imageStudio.collapseHistory")}
      aria-expanded={narrow ? undefined : railOpen}
      aria-controls={narrow ? undefined : historyId}
      onClick={narrow ? undefined : () => setHistoryPreference(!railOpen)}
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
  const inactiveControls = locked || uploading;
  const canRestore = !inactiveControls && !restore.isPending;
  const workflowControls = workflow?.activeFields
    .filter(
      field => field.source === "PARAMETER" && (settings?.mode === "GENERAL_REFERENCE" || !isWorkflowMediaField(field)),
    )
    .map(field => ({ ...field, enabledWhen: null }));
  const secondsOptions = model ? durationOptions(model) : [];
  const secondsId = `${fieldId}-duration`;
  const fixedMode = !invalidMode && model?.modes.length === 1;

  const issues = [...validation, ...referenceErrors];
  if (model && settings && !locked) {
    if (!definition && !prompt) issues.unshift(t("videoStudio.promptNeeded"));
    if (settings.mode === "START_END") {
      if (!settings.startFrame) issues.push(t("videoStudio.frameNeeded", { 0: t(FRAME_LABELS.startFrame) }));
      if (model.requiresEndFrame && !settings.endFrame)
        issues.push(t("videoStudio.frameNeeded", { 0: t(FRAME_LABELS.endFrame) }));
    }
  }
  const outputRatio =
    model?.aspectRatios.length && settings?.aspectRatio
      ? settings.aspectRatio
      : workflow?.activeFields.map(field => workflowRatio(field, workflow.effectiveValues[field.key])).find(Boolean);
  const ratioParts = outputRatio?.match(/^([1-9]\d*):([1-9]\d*)$/);
  const frameShape = ratioParts ? Number(ratioParts[1]) / Number(ratioParts[2]) : null;
  const referenceCount = settings
    ? settings.references.length + Number(!!settings.startFrame) + Number(!!settings.endFrame)
    : 0;
  const outputSummary = [
    outputRatio,
    model?.videoResolutions.length ? settings?.videoResolution : null,
    duration && !invalidDuration ? t("agent.approval.seconds", { 0: duration }) : null,
    referenceCount ? t("videoStudio.referenceCount", { 0: referenceCount }) : null,
  ]
    .filter(Boolean)
    .join(" · ");
  const pendingReceipt = !!draft.command && !accepting;
  const busyLabel = accepting
    ? t("videoStudio.submitting")
    : restore.isPending
      ? t("videoStudio.restorePending")
      : uploadingFrame
        ? t("imageStudio.uploading")
        : uploadingReference
          ? t("media.runningHub.uploading")
          : null;

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
                onCloseAutoFocus={event => {
                  // Restore keyboard focus without moving the scroller back to the header.
                  event.preventDefault();
                  historyTrigger.current?.focus({ preventScroll: true });
                  if (showResultAfterClose.current) {
                    showResultAfterClose.current = false;
                    reveal(stage.current);
                  }
                }}
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
        <div className="video-studio-desk">
          <form
            ref={composer}
            className="video-studio-composer"
            aria-label={t("videoStudio.composer")}
            onSubmit={submit}
          >
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
            {restoreError ? (
              <Notice tone="danger" title={t("videoStudio.restoreFailed")}>
                {restoreError}
              </Notice>
            ) : null}
            {draft.frameUploadError ? (
              <Notice tone="danger" title={t("videoStudio.uploadFailed")}>
                {draft.frameUploadError}
              </Notice>
            ) : null}

            <FieldGroup className="video-studio-fields">
              <Field className="video-studio-shot">
                <div className="video-studio-shot-label">
                  <FieldLabel htmlFor={`${fieldId}-prompt`}>{t("videoStudio.shotDescription")}</FieldLabel>
                  {draft.prompt.length > 0 ? (
                    <span>
                      {draft.prompt.length} / {promptLimit}
                    </span>
                  ) : null}
                </div>
                <Textarea
                  id={`${fieldId}-prompt`}
                  value={draft.prompt}
                  placeholder={t("videoStudio.promptPlaceholder")}
                  maxLength={promptLimit}
                  rows={3}
                  variant="ghost"
                  aria-invalid={draft.prompt.trim().length > promptLimit || undefined}
                  readOnly={locked || uploadingReference}
                  onChange={event => editPrompt(event.target.value, event.target.id)}
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
                    disabled={locked || uploadingReference}
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
                  <Field className="video-studio-model">
                    <div className="video-studio-model-label">
                      <FieldLabel htmlFor={`${fieldId}-model`}>{t("common.model")}</FieldLabel>
                      {fixedMode ? (
                        <span>
                          <span className="sr-only">{t("videoStudio.mode")} </span>
                          {t(MODE_LABELS[settings.mode])}
                        </span>
                      ) : null}
                    </div>
                    <Select
                      id={`${fieldId}-model`}
                      value={modelKey(settings)}
                      icon={<Cube aria-hidden />}
                      optionDetails={Object.fromEntries(
                        models.map(item => [
                          modelKey(item),
                          { description: `${item.connectionName} · v${item.capabilityVersion}` },
                        ]),
                      )}
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
                          {models.some(other => other !== item && other.name === item.name)
                            ? `${item.name} · ${item.connectionName} · v${item.capabilityVersion}`
                            : item.name}
                        </option>
                      ))}
                    </Select>
                  </Field>
                  {!fixedMode ? (
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
                  ) : null}
                  {model?.aspectRatios.length || (stale && settings.aspectRatio) ? (
                    <StudioSelect
                      id={`${fieldId}-ratio`}
                      label={t("media.editor.aspectRatio")}
                      value={settings.aspectRatio}
                      options={model?.aspectRatios ?? []}
                      nativeDefault={model?.defaultAspectRatio}
                      disabled={inactiveControls || !model}
                      invalid={invalidRatio}
                      onChange={value => {
                        const aspectRatio = model?.aspectRatios.find(item => item === value) ?? null;
                        edit({ settings: { ...settings, aspectRatio } });
                      }}
                    />
                  ) : null}
                  {model?.videoResolutions.length || (stale && settings.videoResolution) ? (
                    <StudioSelect
                      id={`${fieldId}-resolution`}
                      label={t("media.editor.resolution")}
                      value={settings.videoResolution}
                      options={model?.videoResolutions ?? []}
                      nativeDefault={model?.defaultVideoResolution}
                      disabled={inactiveControls || !model}
                      invalid={invalidResolution}
                      onChange={videoResolution => edit({ settings: { ...settings, videoResolution } })}
                    />
                  ) : null}
                  {hasDuration || (stale && settings.duration !== "") ? (
                    <Field data-invalid={invalidDuration || undefined}>
                      <FieldLabel htmlFor={secondsId}>{t("videoStudio.seconds")}</FieldLabel>
                      {secondsOptions.length ? (
                        <Select
                          id={secondsId}
                          value={settings.duration}
                          disabled={inactiveControls}
                          aria-invalid={invalidDuration || undefined}
                          onChange={event =>
                            model && applyChoice(model, { ...settings, duration: event.target.value }, secondsId)
                          }
                        >
                          {invalidDuration ? (
                            <option value={settings.duration} disabled>
                              {settings.duration}
                            </option>
                          ) : null}
                          {secondsOptions.map(option => (
                            <option key={String(option.value)} value={String(option.value)}>
                              {option.label}
                            </option>
                          ))}
                        </Select>
                      ) : (
                        <Input
                          id={secondsId}
                          type="number"
                          inputMode="numeric"
                          min={model?.minDurationSeconds ?? undefined}
                          max={model?.maxDurationSeconds ?? undefined}
                          step={1}
                          aria-invalid={invalidDuration || undefined}
                          aria-describedby={invalidDuration ? `${secondsId}-range` : undefined}
                          disabled={inactiveControls || !model}
                          value={settings.duration}
                          onChange={event =>
                            model && applyChoice(model, { ...settings, duration: event.target.value }, secondsId)
                          }
                        />
                      )}
                      {invalidDuration &&
                      !secondsOptions.length &&
                      model?.minDurationSeconds != null &&
                      model.maxDurationSeconds != null ? (
                        <FieldDescription id={`${secondsId}-range`}>
                          {t("videoStudio.durationRange", { 0: model.minDurationSeconds, 1: model.maxDurationSeconds })}
                        </FieldDescription>
                      ) : null}
                    </Field>
                  ) : null}
                </FieldGroup>
              ) : null}
              {settings ? (
                <VideoStudioInputs
                  key={`${modelKey(settings)}:${settings.mode}`}
                  model={model}
                  settings={settings}
                  fields={workflowControls ?? []}
                  values={values}
                  labels={labels}
                  disabled={inactiveControls}
                  restoring={restore.isPending}
                  onChange={changeWorkflowField}
                  onUploadFrame={uploadFrame}
                  onRemoveFrame={slot => edit({ settings: { ...settings, [slot]: null } })}
                  onUploadReference={uploadReference}
                  onRemoveReference={(index, trigger) => {
                    const next = { ...settings, references: settings.references.filter((_, i) => i !== index) };
                    if (model) applyChoice(model, next, trigger);
                    else edit({ settings: next });
                  }}
                />
              ) : null}
            </FieldGroup>
            <div className="video-studio-composer-actions" data-receipt={pendingReceipt || undefined}>
              <div className="video-studio-submit-context">
                {busyLabel ? (
                  <div className="video-studio-operation" role="status" aria-label={busyLabel}>
                    <span className="video-studio-operation-mark" aria-hidden />
                    {busyLabel}
                  </div>
                ) : pendingReceipt ? (
                  <div className="video-studio-receipt" role="status" id={`${fieldId}-receipt`}>
                    <strong>{t("imageStudio.acceptanceUncertain")}</strong>
                    <span>{t("videoStudio.acceptanceUncertainHint")}</span>
                  </div>
                ) : (
                  <>
                    {outputSummary ? (
                      <div className="video-studio-output-summary">
                        {frameShape && Number.isFinite(frameShape) ? (
                          <span
                            aria-hidden
                            className="video-studio-frame-shape"
                            style={{ "--studio-ratio": frameShape } as CSSProperties}
                          />
                        ) : null}
                        {outputSummary}
                      </div>
                    ) : null}
                    <div id={`${fieldId}-requirements`} aria-live="polite">
                      {issues.length > 1 ? (
                        <details>
                          <summary>{t("videoStudio.incompleteInputs", { 0: issues.length })}</summary>
                          <ul className="video-studio-incompatible">
                            {issues.map((issue, i) => (
                              <li key={i}>{issue}</li>
                            ))}
                          </ul>
                        </details>
                      ) : (
                        issues[0]
                      )}
                    </div>
                    {draft.acceptanceError ? (
                      <span className="video-studio-submit-error" role="alert">
                        {t("imageStudio.submitFailed")} · {draft.acceptanceError}
                      </span>
                    ) : null}
                  </>
                )}
              </div>
              <Button
                className="video-studio-generate"
                type={pendingReceipt ? "button" : "submit"}
                size="lg"
                disabled={!canGenerate && !pendingReceipt && !accepting}
                aria-disabled={accepting || undefined}
                aria-busy={accepting || undefined}
                aria-describedby={
                  pendingReceipt
                    ? `${fieldId}-receipt`
                    : issues.length && !busyLabel
                      ? `${fieldId}-requirements`
                      : undefined
                }
                onClick={event => {
                  if (accepting) {
                    event.preventDefault();
                    return;
                  }
                  if (!pendingReceipt) return;
                  event.preventDefault();
                  const command = current()?.command;
                  if (command && !queryClient.isMutating({ mutationKey: acceptanceKey, exact: true }))
                    accept.mutate(command);
                }}
              >
                {pendingReceipt ? <ArrowClockwise aria-hidden /> : null}
                {t(pendingReceipt ? "imageStudio.retryAcceptance" : "videoStudio.generate")}
                {!pendingReceipt ? <ArrowRight data-icon="inline-end" aria-hidden /> : null}
              </Button>
            </div>
          </form>
          <div ref={stage} className="video-studio-stage">
            {detail.error ? (
              <Notice tone="danger" title={t("imageStudio.statusFailed")}>
                <Button type="button" variant="ghost" size="sm" onClick={() => void detail.refetch()}>
                  {t("common.retryRead")}
                </Button>
              </Notice>
            ) : null}
            {selected || (!history.isPending && !detail.isFetching && !detail.error && !history.error) ? (
              <VideoStudioResults
                key={selected?.id ?? "none"}
                generation={selected}
                restoreAction={
                  selected ? (
                    <Button
                      type="button"
                      variant="ghost"
                      size="content"
                      disabled={!canRestore}
                      onClick={() => restoreSettings(selected.id)}
                    >
                      {t("videoStudio.restoreSettings")}
                    </Button>
                  ) : undefined
                }
              />
            ) : null}
          </div>
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
            (
              document.getElementById(changeTrigger.current ?? "") ?? document.getElementById(`${fieldId}-model`)
            )?.focus();
          }}
        >
          <DialogHeader>
            <DialogTitle>{t("videoStudio.changeSettings")}</DialogTitle>
            <DialogDescription>{t("videoStudio.changeSettingsHint")}</DialogDescription>
          </DialogHeader>
          <ul className="video-studio-incompatible">
            {change?.incompatible.map((label, index) => (
              <li key={index}>{label}</li>
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
                if (change && !current()?.command) edit(change.patch);
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
  const [unavailableCover, setUnavailableCover] = useState<string>();
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
          {cover && unavailableCover !== cover.assetId ? (
            <img
              src={assetThumbnailUrl(row.projectId, cover.assetId)}
              alt=""
              loading="lazy"
              onError={() => setUnavailableCover(cover.assetId)}
            />
          ) : (
            <FilmStrip />
          )}
        </span>
        <span className="video-studio-history-copy">
          <strong title={row.prompt || undefined}>{row.prompt || t("storyboard.untitled")}</strong>
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

function StudioSelect({
  id,
  label,
  value,
  options,
  nativeDefault,
  disabled,
  invalid,
  onChange,
}: {
  id: string;
  label: string;
  value: string | null;
  options: string[];
  nativeDefault?: string | null;
  disabled: boolean;
  invalid: boolean;
  onChange: (value: string | null) => void;
}) {
  return (
    <Field data-invalid={invalid || undefined}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Select
        id={id}
        value={value ?? ""}
        disabled={disabled}
        aria-invalid={invalid || undefined}
        onChange={event => onChange(event.target.value || null)}
      >
        {!value || nativeDefault == null ? <option value="">{t("media.runningHub.useDefault")}</option> : null}
        {value && !options.includes(value) ? (
          <option value={value} disabled>
            {value}
          </option>
        ) : null}
        {options.map(option => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </Select>
    </Field>
  );
}
