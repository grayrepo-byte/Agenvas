import { AudioPlayer } from "./AudioPlayer";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { cn } from "cn";
import { CaretDown, ArrowUp, FilmStrip, Plus, X } from "@/shared/ui/icons";
import {
  ApiError, assetContentUrl, assetThumbnailUrl, getMediaDraft, getMediaSettings, getStoryboard, listArtifacts,
  listArtifactVersions, listCanvasMediaVersions, listDirectMediaTasks, listStoryboardMediaApprovals,
  runMediaDraft, saveStoryboard, type ArtifactVersionList, type CanvasItem,
  type MediaDraft, type SaveStoryboardRequest,
} from "../../shared/api/client";
import { t, useLocale, type MessageKey } from "../../shared/i18n";
import { estimatedMediaCost } from "../../shared/mediaPricing";
import { Button } from "../../shared/ui/primitives/button";
import { Badge } from "../../shared/ui/primitives/badge";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "../../shared/ui/primitives/dialog";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../shared/ui/primitives/tabs";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "../../shared/ui/primitives/empty";
import { Select } from "../../shared/ui/Select";
import { LoadingState } from "../../shared/ui/LoadingState";
import { AgentMediaApprovalCard } from "./AgentMediaApprovalCard";
import { MediaPreviewDialog } from "./MediaPreviewDialog";
import { readContentText } from "./artifactContent";
import { MEDIA_TASK_REFRESH_INTERVAL_MS, occupiesMediaCard } from "./mediaTaskState";
import {
  compositionNeedsReview, storyboardContentSignature, newStoryboardShot, parseStoryboardImport, productionNeedsReview,
  shotStatus, STORYBOARD_BATCH_LIMIT, STORYBOARD_MAX_SHOTS, STORYBOARD_ROLES,
  useStoryboardDraftStore, type ProductionInput, type ShotInput, type ShotStatus,
} from "./storyboardDraft";
import { createStoryboardNodePreparation, prepareStoryboardNode, type StoryboardNodePreparation } from "./storyboardNodePreparation";
import { StoryboardMediaCandidates } from "./StoryboardMediaCandidates";
import { approvalMatchesShot, outputStoryboard, shotMediaCandidates } from "./storyboardMedia";
import "./StoryboardWorkbench.css";

const ROLE_KIND = { KEYFRAME: "IMAGE", VIDEO: "VIDEO", AUDIO: "AUDIO" } as const;
const ROLE_KEYS: Record<ProductionInput["role"], MessageKey> = {
  KEYFRAME: "storyboard.role.keyframe", VIDEO: "storyboard.role.video", AUDIO: "storyboard.role.audio",
};
const STATUS_KEYS: Record<ShotStatus, MessageKey> = {
  PREPARING: "storyboard.status.preparing", APPROVAL: "storyboard.status.approval", RUNNING: "storyboard.status.running",
  CANCELED: "storyboard.status.canceled", READY: "storyboard.status.ready", FAILED: "storyboard.status.failed", UNKNOWN: "storyboard.status.unknown", REVIEW: "storyboard.status.review",
  REJECTED: "agent.approval.rejected", EXPIRED: "agent.approval.expired",
};
const storyboardSubmissionKeys = new Map<string, string>();
const IMPORT_EXAMPLE = JSON.stringify([{ code: "SHOT-01", scene: "SC-01", title: "Opening", description: "Wide shot", dialogue: "Hello.", durationSeconds: 5 }], null, 2);

function ReadError({ error, retry }: { error: Error; retry?: () => void }) {
  return <Alert variant="destructive"><AlertDescription>{error instanceof ApiError && error.status === 401
    ? t("storyboard.unauthorized") : error.message}{retry ? <Button variant="outline" size="sm" onClick={retry}>{t("common.retryRead")}</Button> : null}</AlertDescription></Alert>;
}
function Labeled({ label, children }: { label: string; children: (id: string) => ReactNode }) {
  const id = useId();
  return <Field><FieldLabel htmlFor={id}>{label}</FieldLabel>{children(id)}</Field>;
}
function Status({ value }: { value: ShotStatus }) {
  return <Badge variant={value === "FAILED" || value === "UNKNOWN" ? "destructive" : value === "READY" ? "default" : "secondary"}>{t(STATUS_KEYS[value])}</Badge>;
}

/** Every choice is an immutable version; resource defaults never silently update a shot. */
function ExactVersionPicker({ projectId, source, onChoose, onClose }: {
  projectId: string; source: boolean; onChoose: (versionId: string) => void; onClose: () => void;
}) {
  const [artifactId, setArtifactId] = useState("");
  const resources = useQuery({ queryKey: ["artifacts", projectId], queryFn: () => listArtifacts(projectId) });
  const versions = useQuery({ queryKey: ["artifact-versions", projectId, artifactId],
    queryFn: () => listArtifactVersions(projectId, artifactId), enabled: Boolean(artifactId) });
  const artifacts = resources.data?.items.filter((item) => source ? item.kind === "TEXT" : item.kind !== "TEXT") ?? [];
  return <Dialog open onOpenChange={(open) => { if (!open) onClose(); }}><DialogContent className="app-page" aria-describedby={undefined}>
    <DialogHeader><DialogTitle>{t(source ? "storyboard.chooseSource" : "storyboard.chooseReference")}</DialogTitle></DialogHeader>
    {resources.isPending ? <LoadingState compact label={t("common.versionLoading")} /> : null}
    {resources.error ? <ReadError error={resources.error} retry={() => void resources.refetch()} /> : null}
    <FieldGroup><Labeled label={t("storyboard.resource")}>{(id) => <Select id={id} value={artifactId} onChange={(event) => setArtifactId(event.target.value)}>
      <option value="">{t("storyboard.chooseResource")}</option>{artifacts.map((artifact) => <option value={artifact.id} key={artifact.id}>{artifact.title}</option>)}
    </Select>}</Labeled></FieldGroup>
    {artifactId && versions.isPending ? <LoadingState compact label={t("common.versionLoading")} /> : null}
    {versions.error ? <ReadError error={versions.error} retry={() => void versions.refetch()} /> : null}
    <div className="storyboard-version-list">{versions.data?.items.map((version) => <Button variant="outline" key={version.id} onClick={() => { onChoose(version.id); onClose(); }}>
      <span>v{version.versionNo}</span><small className="truncate">{source ? readContentText(version.content, "text").slice(0, 100) : version.id}</small>
    </Button>)}</div>
    {versions.data?.items.length === 0 || resources.isSuccess && artifacts.length === 0 ? <p>{t("storyboard.noVersions")}</p> : null}
  </DialogContent></Dialog>;
}

function ProductionEditor({ projectId, role, production, items, review, disabled, preparing, onPrepare, onChange, onOpenNode }: {
  projectId: string; role: ProductionInput["role"]; production?: ProductionInput; items: CanvasItem[];
  review: boolean; disabled: boolean; preparing?: boolean; onPrepare?: () => void; onChange: (production: ProductionInput | null) => void; onOpenNode: (item: CanvasItem) => void;
}) {
  const item = items.find((candidate) => candidate.id === production?.canvasItemId);
  const history = useQuery({ queryKey: ["canvas-media-versions", projectId, item?.id],
    queryFn: () => listCanvasMediaVersions(projectId, item!.id), enabled: Boolean(item) });
  const [preview, setPreview] = useState<ArtifactVersionList["items"][number] | null>(null);
  const selected = history.data?.items.find((version) => version.id === production?.selectedVersionId);
  const sourceUrl = preview ? assetContentUrl(projectId, readContentText(preview.content, "assetId")) : "";
  return <section className="storyboard-production" aria-label={t(ROLE_KEYS[role])}>
    <div className="storyboard-inline"><h3>{t(ROLE_KEYS[role])}</h3>{review ? <Status value="REVIEW" /> : null}</div>
    <FieldGroup><Labeled label={t("storyboard.productionNode")}>{(id) => <Select id={id} value={production?.canvasItemId ?? ""} disabled={disabled}
      onChange={(event) => onChange(event.target.value ? { role, canvasItemId: event.target.value, selectedVersionId: null, confirmReviewed: false } : null)}>
      <option value="">{t("storyboard.notBound")}</option>{items.filter((candidate) => candidate.artifact.kind === ROLE_KIND[role]).map((candidate) =>
        <option key={candidate.id} value={candidate.id}>{candidate.title}</option>)}
      {production && !item ? <option value={production.canvasItemId}>{t("storyboard.missingNode")}</option> : null}
    </Select>}</Labeled>
    {production && item ? <Labeled label={t("storyboard.selectedResult")}>{(id) => <Select id={id} value={production.selectedVersionId ?? ""}
      disabled={disabled || history.isPending || history.isError} onChange={(event) => onChange({ ...production,
        selectedVersionId: event.target.value || null, confirmReviewed: false })}>
      <option value="">{t("storyboard.noSelection")}</option>{history.data?.items.map((version) => <option key={version.id} value={version.id}>v{version.versionNo} · {version.id.slice(0, 8)}</option>)}
    </Select>}</Labeled> : null}</FieldGroup>
    {selected && role !== "AUDIO" && readContentText(selected.content, "assetId") ? <Button variant="ghost" className="storyboard-result-thumbnail" aria-label={t("storyboard.preview")} onClick={() => setPreview(selected)}>
      <img alt={item?.title ?? ""} src={role === "KEYFRAME" ? assetContentUrl(projectId, readContentText(selected.content, "assetId")) : assetThumbnailUrl(projectId, readContentText(selected.content, "assetId"))} />
    </Button> : null}
    {!production && onPrepare ? <Button variant="outline" size="sm" disabled={disabled || preparing} onClick={onPrepare}>{t(preparing ? "storyboard.preparingNode" : "storyboard.prepareProduction", { "0": t(ROLE_KEYS[role]) })}</Button> : null}
    {history.error ? <ReadError error={history.error} retry={() => void history.refetch()} /> : null}
    {item ? <div className="storyboard-inline">
      <Button variant="outline" size="sm" disabled={disabled} onClick={() => onOpenNode(item)}>{t("storyboard.openNode")}</Button>
      <Button variant="ghost" size="sm" disabled={!selected} onClick={() => setPreview(selected ?? null)}>{t("storyboard.preview")}</Button>
      {review && production ? <Button variant="outline" size="sm" disabled={disabled} onClick={() => onChange({ ...production, confirmReviewed: true })}>{t("storyboard.confirmReview")}</Button> : null}
    </div> : null}
    {preview && role !== "AUDIO" ? <MediaPreviewDialog kind={role === "KEYFRAME" ? "image" : "video"} title={item?.title ?? ""} sourceUrl={sourceUrl} onClose={() => setPreview(null)} /> : null}
    {preview && role === "AUDIO" ? <Dialog open onOpenChange={(open) => { if (!open) setPreview(null); }}><DialogContent className="app-page" aria-describedby={undefined}>
      <DialogHeader><DialogTitle>{item?.title}</DialogTitle></DialogHeader><AudioPlayer src={sourceUrl} title={item?.title ?? ""} />
    </DialogContent></Dialog> : null}
  </section>;
}

type BatchEntry = { item: CanvasItem; draft: MediaDraft; name: string; cost: string };
type BatchResult = { name: string; state: "submitted" | "failed" | "uncertain"; detail: string };

export function StoryboardWorkbench({ projectId, items, onClose, onOpenNode }: {
  projectId: string; items: CanvasItem[]; onClose: () => void; onOpenNode: (item: CanvasItem) => void;
}) {
  useLocale();
  const client = useQueryClient();
  const draft = useStoryboardDraftStore((state) => state.projects[projectId]);
  const accept = useStoryboardDraftStore((state) => state.accept);
  const edit = useStoryboardDraftStore((state) => state.edit);
  const discard = useStoryboardDraftStore((state) => state.discard);
  const board = useQuery({ queryKey: ["storyboard", projectId], queryFn: () => getStoryboard(projectId), retry: false });
  const approvals = useQuery({ queryKey: ["storyboard-media-approvals", projectId], queryFn: () => listStoryboardMediaApprovals(projectId),
    refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS, retry: false });
  const [tab, setTab] = useState("shots");
  const [activeId, setActiveId] = useState<string | null>(null);
  const editorRef = useRef<HTMLDivElement>(null);
  const [batchRole, setBatchRole] = useState<ProductionInput["role"]>("VIDEO");
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [confirm, setConfirm] = useState<"close" | "reload" | "delete" | null>(null);
  const [picker, setPicker] = useState<"source" | "reference" | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [importText, setImportText] = useState(IMPORT_EXAMPLE);
  const [importShots, setImportShots] = useState<ShotInput[] | null>(null);
  const [importError, setImportError] = useState(false);
  const [batch, setBatch] = useState<BatchEntry[] | null>(null);
  const [batchResults, setBatchResults] = useState<BatchResult[]>([]);
  const [actionError, setActionError] = useState<Error | null>(null);
  const pending = useRef(false);
  const submissionKeys = useRef(storyboardSubmissionKeys);
  const preparationIntents = useRef(new Map<string, StoryboardNodePreparation>());
  const [preparedHint, setPreparedHint] = useState(false);
  const shots = draft?.input.shots ?? [];
  const shot = shots.find((candidate) => candidate.id === activeId) ?? shots[0];
  useEffect(() => { editorRef.current?.scrollTo({ top: 0 }); }, [shot?.id]);
  const shotBase = draft?.base.shots.find((candidate) => candidate.id === shot?.id);
  const boundIds = [...new Set(shots.flatMap((entry) => entry.productions.map((production) => production.canvasItemId)))];
  const boundItems = items.filter((item) => boundIds.includes(item.id));
  const taskQueries = useQueries({ queries: boundItems.map((item) => ({
    queryKey: ["direct-media-tasks", projectId, item.id], queryFn: () => listDirectMediaTasks(projectId, item.artifact.id, item.id),
    refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS,
  })) });
  // Only each node's latest task contributes status; historic failures do not mask a later success.
  const tasks = taskQueries.flatMap((query, index) => {
    const latest = query.data?.[0];
    return latest ? [{ ...latest, input: { ...latest.input, canvasItemId: boundItems[index]!.id } }] : [];
  });
  useEffect(() => {
    const current = useStoryboardDraftStore.getState().projects[projectId];
    // A GET or queued render may finish after a successful save. Automatic refresh must never regress its CAS base.
    if (board.data && !current?.dirty && (!current || board.data.version >= current.base.version)) accept(projectId, board.data);
  }, [board.data, projectId, accept]);
  useEffect(() => {
    if (!draft?.dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      if (useStoryboardDraftStore.getState().projects[projectId]?.dirty) event.preventDefault();
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [draft?.dirty, projectId]);
  const change = (transform: (input: SaveStoryboardRequest) => SaveStoryboardRequest) => { setActionError(null); edit(projectId, (input) => {
    const updated = transform(input);
    return { ...updated, composition: updated.composition && storyboardContentSignature(updated.shots) !== storyboardContentSignature(input.shots)
      ? { ...updated.composition, confirmReviewed: false } : updated.composition };
  }); };
  const patchShot = (patch: Partial<ShotInput>) => {
    if (!shot) return;
    const creativeChanged = ["description", "dialogue", "durationSeconds", "sourceVersionId", "referenceVersionIds"].some((key) => key in patch);
    change((input) => ({ ...input, shots: input.shots.map((candidate) => candidate.id === shot.id
      ? { ...candidate, ...patch, productions: (patch.productions ?? candidate.productions).map((production) =>
          patch.productions || !creativeChanged ? production : { ...production, confirmReviewed: false }) } : candidate) }));
  };
  const refresh = async () => { const result = await board.refetch(); if (result.isSuccess && result.data) accept(projectId, result.data); };
  const save = useMutation({ mutationFn: () => saveStoryboard(projectId, draft!.input), retry: false,
    onSuccess: (saved) => { accept(projectId, saved); client.setQueryData(["storyboard", projectId], saved); },
  });
  const preflight = useMutation({ mutationFn: async () => {
    if (!draft || draft.dirty) throw new Error(t("storyboard.saveBeforeBatch"));
    const selected = shots.filter((entry) => selectedIds.includes(entry.id));
    const missing = selected.find((entry) => !entry.productions.some((production) => production.role === batchRole));
    if (missing) throw new Error(`${missing.code}: ${t("storyboard.prepareNode")}`);
    const productions = selected.flatMap((entry) => entry.productions.filter((production) => production.role === batchRole).map((production) => ({ production, shot: entry })));
    const unique = [...new Map(productions.map((entry) => [entry.production.canvasItemId, entry])).values()];
    if (!unique.length || unique.length > STORYBOARD_BATCH_LIMIT) throw new Error(t("storyboard.batchLimit", { "0": STORYBOARD_BATCH_LIMIT }));
    const settings = await getMediaSettings();
    return Promise.all(unique.map(async ({ production, shot: entry }) => {
      const item = items.find((candidate) => candidate.id === production.canvasItemId);
      if (!item) throw new Error(t("storyboard.missingNode"));
      const [currentDraft, currentTasks] = await Promise.all([getMediaDraft(projectId, item.id), listDirectMediaTasks(projectId, item.artifact.id, item.id)]);
      if (currentTasks.some(occupiesMediaCard)) throw new Error(`${entry.code}: ${t("storyboard.busyNode")}`);
      const capability = settings.connections.filter((connection) => connection.enabled).flatMap((connection) => connection.capabilities)
        .find((candidate) => candidate.id === currentDraft.capabilityId && candidate.enabled);
      if (!capability || !currentDraft.prompt.trim()) throw new Error(`${entry.code}: ${t("storyboard.prepareNode")}`);
      return { item, draft: currentDraft, name: `${entry.code} · ${entry.title} · ${t(ROLE_KEYS[production.role])}`,
        cost: estimatedMediaCost(capability, currentDraft.parameters.generationCount ?? 1, currentDraft.durationSeconds, currentDraft.parameters.videoResolution) };
    }));
  }, onSuccess: (entries) => { setBatchResults([]); setBatch(entries); }, retry: false });
  const submit = useMutation({ mutationFn: async (entries: BatchEntry[]) => {
    if (pending.current) return [];
    pending.current = true;
    try {
      return await Promise.all(entries.map(async (entry): Promise<BatchResult> => {
        try {
          const currentTasks = await listDirectMediaTasks(projectId, entry.item.artifact.id, entry.item.id);
          if (currentTasks.some(occupiesMediaCard)) throw new Error(t("storyboard.busyNode"));
          const operation = `${projectId}:${entry.item.id}:${entry.draft.version}`;
          let key = submissionKeys.current.get(operation);
          if (!key) { key = crypto.randomUUID(); submissionKeys.current.set(operation, key); }
          const task = await runMediaDraft(projectId, entry.item.artifact.id,
            { canvasItemId: entry.item.id, expectedDraftVersion: entry.draft.version }, key);
          submissionKeys.current.delete(operation);
          return { name: entry.name, state: "submitted", detail: task.status === "UNKNOWN" ? t("storyboard.status.unknown") : t("storyboard.submitted") };
        } catch (error) {
          return { name: entry.name, state: error instanceof ApiError && error.status < 500 ? "failed" : "uncertain", detail: `${error instanceof ApiError && error.status < 500 ? error.message : t("storyboard.acceptanceUncertain")} · ${t("storyboard.noAutoRetry")}` };
        }
      }));
    } finally { pending.current = false; }
  }, onSuccess: async (results) => {
    setBatch(null); setBatchResults(results);
    await Promise.all([["direct-media-tasks", projectId], ["canvas", projectId], ["snapshot", projectId], ["media-draft", projectId]]
      .map((queryKey) => client.invalidateQueries({ queryKey })));
  }, retry: false });
  function addShot() {
    let index = shots.length;
    let created = newStoryboardShot(index);
    while (shots.some((entry) => entry.code === created.code)) { index += 1; created = newStoryboardShot(index); }
    change((input) => ({ ...input, shots: [...input.shots, created] })); setActiveId(created.id);
  }
  function moveShot(direction: number) {
    if (!shot) return;
    const index = shots.findIndex((candidate) => candidate.id === shot.id);
    const next = index + direction;
    if (next < 0 || next >= shots.length) return;
    const moved = [...shots]; [moved[index], moved[next]] = [moved[next]!, moved[index]!];
    change((input) => ({ ...input, shots: moved }));
  }
  const prepare = useMutation({ mutationFn: async ({ target, role }: { target: ShotInput; role: ProductionInput["role"] }) => {
    const operation = `${target.id}:${role}`;
    let intent = preparationIntents.current.get(operation);
    if (!intent) { intent = createStoryboardNodePreparation(target, role, items, `${target.code} · ${t(ROLE_KEYS[role])}`); preparationIntents.current.set(operation, intent); }
    return { target, role, operation, node: await prepareStoryboardNode(projectId, intent) };
  }, onSuccess: async ({ target, role, operation, node }) => {
    change((input) => ({ ...input, shots: input.shots.map((entry) => entry.id === target.id ? { ...entry,
      productions: [...entry.productions.filter((production) => production.role !== role),
        { role, canvasItemId: node.canvasItemId, selectedVersionId: null, confirmReviewed: false }] } : entry) }));
    preparationIntents.current.delete(operation); setPreparedHint(true);
    await client.invalidateQueries({ queryKey: ["canvas", projectId] });
  }, retry: false });
  const busy = save.isPending || submit.isPending || prepare.isPending;
  const validateImport = () => {
    const imported = parseStoryboardImport(importText);
    const codes = [...shots.map((entry) => entry.code), ...imported.map((entry) => entry.code)];
    if (codes.length > STORYBOARD_MAX_SHOTS || new Set(codes).size !== codes.length) throw new Error("INVALID_SHOT_LIST");
    return imported;
  };
  const composition = draft?.input.composition;
  const compositionReview = draft ? compositionNeedsReview(draft) : false;
  const statusOf = (entry: ShotInput): ShotStatus => {
    const status = shotStatus(entry, draft?.base.shots.find((candidate) => candidate.id === entry.id), tasks, approvals.data?.items ?? []);
    // Retain detached result references for repair, but do not present a missing production node as ready.
    const available = entry.productions.some((production) => production.selectedVersionId && items.some((item) => item.id === production.canvasItemId))
      || shotMediaCandidates(approvals.data?.items ?? [], entry).some((candidate) => items.some((item) => item.id === candidate.canvasItemId && item.artifact.kind !== "TEXT"));
    return status === "READY" && !available && entry.productions.some((production) => !items.some((item) => item.id === production.canvasItemId))
      ? "PREPARING" : status;
  };
  const statusCounts = shots.reduce<Partial<Record<ShotStatus, number>>>((counts, entry) => {
    const status = statusOf(entry); counts[status] = (counts[status] ?? 0) + 1; return counts;
  }, {});
  return <Dialog open onOpenChange={(open) => { if (!open && !busy) { if (draft?.dirty) setConfirm("close"); else onClose(); } }}>
    <DialogContent fullscreen showCloseButton={false} className="storyboard-workbench app-page" aria-describedby="storyboard-description"
      onEscapeKeyDown={(event) => { event.stopPropagation(); if (busy) event.preventDefault(); }} onPointerDownOutside={(event) => event.preventDefault()}>
      <header className="storyboard-header"><DialogHeader><DialogTitle><FilmStrip aria-hidden />{t("storyboard.title")}</DialogTitle>
        <DialogDescription id="storyboard-description">{t("storyboard.description")}</DialogDescription></DialogHeader>
        <div className="storyboard-inline"><Badge variant="outline">{t(draft?.dirty ? "storyboard.unsaved" : "storyboard.saved")}</Badge>
          <Button data-storyboard-action="reload" variant="outline" disabled={busy || board.isFetching} onClick={() => draft?.dirty ? setConfirm("reload") : void refresh()}>{t("storyboard.loadLatest")}</Button>
          <Button data-storyboard-action="save" disabled={busy || !draft?.dirty} onClick={() => save.mutate()}>{t(save.isPending ? "storyboard.saving" : "storyboard.save")}</Button>
          <Button data-storyboard-action="close" variant="ghost" size="icon" aria-label={t("ui.dialog.close")} disabled={busy} onClick={() => draft?.dirty ? setConfirm("close") : onClose()}><X /></Button>
        </div></header>
      {board.isPending ? <LoadingState label={t("storyboard.loading")} /> : null}
      {board.error ? <ReadError error={board.error} retry={() => void board.refetch()} /> : null}
      {draft?.dirty && board.data && board.data.version > draft.base.version ? <Alert><AlertDescription>{t("storyboard.conflict")}</AlertDescription></Alert> : null}
      {save.error ? <Alert variant="destructive"><AlertDescription>{save.error instanceof ApiError && save.error.status === 409
        ? t("storyboard.conflict") : t("storyboard.saveFailed")} {save.error.message}</AlertDescription></Alert> : null}
      {prepare.error ? <ReadError error={prepare.error} /> : null}
      {preparedHint ? <Alert><AlertDescription>{t("storyboard.preparedHint")}</AlertDescription></Alert> : null}
      {actionError ? <ReadError error={actionError} /> : null}
      {draft ? <Tabs value={tab} onValueChange={setTab} className="storyboard-tabs">
        <div className="storyboard-toolbar"><TabsList><TabsTrigger value="shots">{t("storyboard.shots", { "0": shots.length })}</TabsTrigger>
          <TabsTrigger value="approvals">{t("storyboard.approvals", { "0": approvals.data?.items.filter((entry) => entry.status === "PENDING").length ?? 0 })}</TabsTrigger></TabsList>
          <div className="storyboard-inline"><span>{t("storyboard.totalDuration", { "0": shots.reduce((sum, entry) => sum + entry.durationSeconds, 0) })}</span>
            <Button data-storyboard-action="import" variant="outline" size="sm" disabled={busy} onClick={() => { setImportOpen(true); setImportShots(null); }}>{t("storyboard.import")}</Button>
            <Button size="sm" disabled={busy || shots.length >= STORYBOARD_MAX_SHOTS} onClick={addShot}><Plus data-icon="inline-start" />{t("storyboard.addShot")}</Button></div>
        </div>
        <TabsContent value="shots" className="storyboard-shots-tab">
          <div className="storyboard-summary">{(Object.keys(STATUS_KEYS) as ShotStatus[]).map((status) => <span key={status}><Status value={status} /><strong>{statusCounts[status] ?? 0}</strong></span>)}</div>
          <div className="storyboard-main"><aside className="storyboard-shot-list" aria-label={t("storyboard.shotList")}>
            {shots.length ? <><div className="storyboard-list-actions"><Select aria-label={t("storyboard.batchStage")} value={batchRole} disabled={busy} onChange={(event) => setBatchRole(event.target.value as ProductionInput["role"])}>{STORYBOARD_ROLES.map((role) => <option key={role} value={role}>{t(ROLE_KEYS[role])}</option>)}</Select><Button data-storyboard-action="batchGenerate" variant="outline" size="sm" disabled={busy || preflight.isPending || !selectedIds.length || draft.dirty}
              onClick={() => preflight.mutate()}>{t(preflight.isPending ? "storyboard.checking" : "storyboard.batchGenerate", { "0": selectedIds.length })}</Button>
              {draft.dirty ? <small>{t("storyboard.saveBeforeBatch")}</small> : null}</div>
            {preflight.error ? <ReadError error={preflight.error} /> : null}
            {shots.map((entry, index) => <div className={cn("storyboard-shot-row", entry.id === shot?.id && "storyboard-shot-row-active")} key={entry.id} data-shot-id={entry.id}>
              <Checkbox aria-label={t("storyboard.selectShot", { "0": entry.code })} checked={selectedIds.includes(entry.id)} disabled={busy}
                onCheckedChange={(checked) => setSelectedIds((current) => checked === true ? [...current, entry.id] : current.filter((id) => id !== entry.id))} />
              <Button variant="ghost" onClick={() => setActiveId(entry.id)}><span className="storyboard-shot-copy"><strong>{index + 1}. {entry.code}</strong><span className="truncate">{entry.title || t("storyboard.untitled")}</span><small>{entry.scene} · {entry.durationSeconds}s</small><Status value={statusOf(entry)} /></span></Button>
            </div>)}</> : <Empty><EmptyHeader><EmptyTitle>{t("storyboard.emptyTitle")}</EmptyTitle><EmptyDescription>{t("storyboard.emptyDescription")}</EmptyDescription></EmptyHeader></Empty>}
          </aside>
          <div className="storyboard-editor" ref={editorRef}>{shot ? <fieldset disabled={busy} className="storyboard-shot-editor">
            <div className="storyboard-editor-heading"><div><h2>{shot.code} · {shot.title || t("storyboard.untitled")}</h2><Status value={statusOf(shot)} /></div>
              <div className="storyboard-inline"><Button variant="outline" size="icon-sm" disabled={shots[0]?.id === shot.id} aria-label={t("storyboard.moveUp")} onClick={() => moveShot(-1)}><ArrowUp /></Button>
                <Button variant="outline" size="icon-sm" disabled={shots.at(-1)?.id === shot.id} aria-label={t("storyboard.moveDown")} onClick={() => moveShot(1)}><CaretDown /></Button>
                <Button variant="outline" size="sm" onClick={() => setConfirm("delete")}>{t("storyboard.delete")}</Button></div></div>
            <FieldGroup className="storyboard-creative-fields">
              {(["code", "scene", "title"] as const).map((key) => <Labeled key={key} label={t(`storyboard.field.${key}`)}>{(id) => <Input id={id} value={shot[key]} aria-label={t(`storyboard.field.${key}`)} maxLength={key === "code" ? 80 : 200} onChange={(event) => patchShot({ [key]: event.target.value })} />}</Labeled>)}
              <Labeled label={t("storyboard.field.duration")}>{(id) => <Input id={id} type="number" aria-label={t("storyboard.field.duration")} min="0.1" max="3600" step="0.1" value={shot.durationSeconds} onChange={(event) => {
                const value = Number(event.target.value); if (Number.isFinite(value) && value > 0 && value <= 3600) patchShot({ durationSeconds: value });
              }} />}</Labeled>
              {(["description", "dialogue"] as const).map((key) => <Labeled key={key} label={t(`storyboard.field.${key}`)}>{(id) => <Textarea id={id} aria-label={t(`storyboard.field.${key}`)} value={shot[key]} rows={4} maxLength={20000} onChange={(event) => patchShot({ [key]: event.target.value })} />}</Labeled>)}
            </FieldGroup>
            <section className="storyboard-reference-section"><h3>{t("storyboard.sourceAndReferences")}</h3><p>{t("storyboard.exactVersionHint")}</p>
              <div className="storyboard-inline"><Button variant="outline" size="sm" onClick={() => setPicker("source")}>{t("storyboard.chooseSource")}</Button>
                {shot.sourceVersionId ? <><code>{shot.sourceVersionId}</code><Button variant="ghost" size="sm" onClick={() => patchShot({ sourceVersionId: null })}>{t("storyboard.remove")}</Button></> : null}</div>
              <div className="storyboard-inline"><Button variant="outline" size="sm" onClick={() => setPicker("reference")}>{t("storyboard.chooseReference")}</Button>
                {shot.referenceVersionIds.map((id) => <span className="storyboard-reference-chip" key={id}><code>{id}</code><Button variant="ghost" size="icon-sm" aria-label={t("storyboard.removeReference", { "0": id })}
                  onClick={() => patchShot({ referenceVersionIds: shot.referenceVersionIds.filter((value) => value !== id) })}><X /></Button></span>)}</div></section>
            {shot.productions.some((production) => productionNeedsReview(shot, production, shotBase, approvals.data?.items)) ? <Alert><AlertDescription>{t("storyboard.impactHint")}</AlertDescription></Alert> : null}
            <StoryboardMediaCandidates projectId={projectId} shot={shot} base={shotBase} approvals={approvals.data?.items ?? []}
              items={items} disabled={busy} onReview={() => setTab("approvals")} onChoose={(next) => patchShot({
                productions: [...shot.productions.filter((entry) => entry.role !== next.role), next],
              })} />
            <div className="storyboard-production-grid">{STORYBOARD_ROLES.map((role) => {
              const production = shot.productions.find((entry) => entry.role === role);
              return <ProductionEditor key={`${shot.id}:${role}`} projectId={projectId} role={role} production={production} items={items}
                review={production ? productionNeedsReview(shot, production, shotBase, approvals.data?.items) : false} disabled={busy}
                onOpenNode={onOpenNode} preparing={prepare.isPending} onPrepare={() => prepare.mutate({ target: shot, role })} onChange={(next) => patchShot({ productions: [...shot.productions.filter((entry) => entry.role !== role), ...(next ? [next] : [])] })} />;
            })}</div>
          </fieldset> : null}
          <section className="storyboard-composition"><div className="storyboard-inline"><h3>{t("storyboard.composition")}</h3>{compositionReview ? <Status value="REVIEW" /> : null}</div>
            <p>{t("storyboard.compositionHint")}</p>
            <ProductionEditor projectId={projectId} role="VIDEO" production={composition ? { ...composition, role: "VIDEO" } : undefined} items={items}
              review={compositionReview} disabled={busy} onOpenNode={onOpenNode} onChange={(next) => change((input) => ({ ...input,
                composition: next ? { canvasItemId: next.canvasItemId, selectedVersionId: next.selectedVersionId, confirmReviewed: next.confirmReviewed ?? false } : null }))} />
          </section>
          {taskQueries.some((query) => query.isError) ? <Alert><AlertDescription>{t("storyboard.progressFailed")}</AlertDescription></Alert> : null}
          {batchResults.length ? <section className="storyboard-batch-results" aria-live="polite"><h3>{t("storyboard.batchResults")}</h3>{batchResults.map((result) => <Alert key={result.name} variant={result.state === "failed" ? "destructive" : "default"}><AlertDescription>{result.name} · {result.detail}</AlertDescription></Alert>)}</section> : null}
          </div></div>
        </TabsContent>
        <TabsContent value="approvals" className="storyboard-approvals">
          <p>{t("storyboard.approvalHint")}</p>{approvals.isPending ? <LoadingState label={t("storyboard.loading")} /> : null}
          {approvals.error ? <ReadError error={approvals.error} retry={() => void approvals.refetch()} /> : null}
          {approvals.data?.items.length === 0 ? <Empty><EmptyHeader><EmptyTitle>{t("storyboard.noApprovals")}</EmptyTitle></EmptyHeader></Empty> : null}
          {approvals.data?.items.map((approval) => {
            const labels = [...new Set([...shots.filter((entry) => approvalMatchesShot(approval, entry)).map((entry) => `${entry.code} ${entry.title}`),
              ...approval.outputs.flatMap((output) => { const link = outputStoryboard(output);
                return link && !shots.some((entry) => entry.id === link.shotId) ? [t("storyboard.candidate.removedShot", { "0": link.shotCode })] : []; })])];
            return <section key={approval.id}><h3>{labels.join(" · ") || t("storyboard.unboundApproval")}</h3>
              <AgentMediaApprovalCard projectId={projectId} runId={approval.runId} approval={approval} disabled={busy} /></section>;
          })}
        </TabsContent>
      </Tabs> : null}
      {picker && shot ? <ExactVersionPicker key={`${shot.id}:${picker}`} projectId={projectId} source={picker === "source"} onClose={() => setPicker(null)}
        onChoose={(versionId) => patchShot(picker === "source" ? { sourceVersionId: versionId } : { referenceVersionIds: [...new Set([...shot.referenceVersionIds, versionId])] })} /> : null}
      {confirm ? <Dialog open onOpenChange={(open) => { if (!open) setConfirm(null); }}><DialogContent className="app-page">
        <DialogHeader><DialogTitle>{t(confirm === "delete" ? "storyboard.deleteConfirm" : "storyboard.discardConfirm")}</DialogTitle>
          <DialogDescription>{t(confirm === "delete" ? "storyboard.deleteHint" : "storyboard.discardHint")}</DialogDescription></DialogHeader>
        <DialogFooter><Button variant="outline" onClick={() => setConfirm(null)}>{t("storyboard.cancel")}</Button><Button onClick={() => {
          const choice = confirm; setConfirm(null);
          if (choice === "delete" && shot) { change((input) => ({ ...input, shots: input.shots.filter((entry) => entry.id !== shot.id) })); setSelectedIds((current) => current.filter((id) => id !== shot.id)); }
          else if (choice === "reload") void refresh();
          else { discard(projectId); onClose(); }
        }}>{t(confirm === "delete" ? "storyboard.delete" : "storyboard.discard")}</Button></DialogFooter>
      </DialogContent></Dialog> : null}
      {importOpen ? <Dialog open onOpenChange={(open) => { if (!open) setImportOpen(false); }}><DialogContent className="storyboard-import app-page">
        <DialogHeader><DialogTitle>{t("storyboard.import")}</DialogTitle><DialogDescription>{t("storyboard.importHint")}</DialogDescription></DialogHeader>
        <FieldGroup><Labeled label={t("storyboard.importJson")}>{(id) => <Textarea id={id} rows={12} value={importText} onChange={(event) => { setImportText(event.target.value); setImportShots(null); setImportError(false); }} />}</Labeled></FieldGroup>
        {importError ? <Alert variant="destructive"><AlertDescription>{t("storyboard.importInvalid")}</AlertDescription></Alert> : null}
        {importShots ? <p>{t("storyboard.importPreview", { "0": importShots.length })} {importShots.map((entry) => entry.code).join(" · ")}</p> : null}
        <DialogFooter><Button variant="outline" onClick={() => { setImportText(IMPORT_EXAMPLE); setImportShots(null); setImportError(false); }}>{t("storyboard.example")}</Button>
          <Button variant="outline" onClick={() => { try { const parsed = validateImport(); setImportShots(parsed); setImportError(false); } catch { setImportError(true); } }}>{t("storyboard.validateImport")}</Button>
          <Button disabled={!importShots} onClick={() => { if (!importShots) return; try { const fresh = validateImport(); change((input) => ({ ...input, shots: [...input.shots, ...fresh] })); setActiveId(fresh[0]!.id); setImportOpen(false); } catch { setImportError(true); } }}>{t("storyboard.applyImport")}</Button></DialogFooter>
      </DialogContent></Dialog> : null}
      {batch ? <Dialog open onOpenChange={(open) => { if (!open && !submit.isPending) setBatch(null); }}><DialogContent className="storyboard-batch app-page" showCloseButton={!submit.isPending}
        onEscapeKeyDown={(event) => { if (submit.isPending) event.preventDefault(); }} onPointerDownOutside={(event) => event.preventDefault()}>
        <DialogHeader><DialogTitle>{t("storyboard.batchConfirm")}</DialogTitle><DialogDescription>{t("storyboard.batchCostHint")}</DialogDescription></DialogHeader>
        <div className="storyboard-batch-list">{batch.map((entry) => <section key={entry.item.id}><strong>{entry.name}</strong><Badge variant="outline">{entry.cost}</Badge><p>{entry.draft.prompt}</p>
          <small>{t("storyboard.batchInputs", { "0": entry.draft.mediaInputs.length, "1": entry.draft.version })}</small></section>)}</div>
        <p>{t("storyboard.noAutoRetry")}</p><DialogFooter><Button variant="outline" disabled={submit.isPending} onClick={() => setBatch(null)}>{t("storyboard.cancel")}</Button>
          <Button disabled={submit.isPending} onClick={() => submit.mutate(batch)}>{t(submit.isPending ? "storyboard.submitting" : "storyboard.confirmPaidGeneration")}</Button></DialogFooter>
      </DialogContent></Dialog> : null}
    </DialogContent>
  </Dialog>;
}
