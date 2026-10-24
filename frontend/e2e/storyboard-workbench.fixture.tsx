import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { StoryboardWorkbench } from "../src/features/canvas/StoryboardWorkbench";
import type { AgentMediaApproval, Artifact, CanvasItem, MediaDraft, SaveStoryboardRequest, Storyboard, StoryboardShot, Task } from "../src/shared/api/client";
import { resolveLocale, setLocale } from "../src/shared/i18n";
import { Button } from "../src/shared/ui/primitives/button";
import { videoFunctionCapability, videoFunctionsFixture } from "../src/test/videoFunctionsFixture";
import "../src/styles.css";

// Synthetic HTTP fixtures exercise real controls and native media bytes; no real Provider is contacted.
const options = new URL(location.href).searchParams;
type ArtifactVersion = NonNullable<Artifact["resourceDefaultVersion"]>;
setLocale(resolveLocale(options.get("locale")) ?? "zh");
const projectId = "synthetic-storyboard";
const createdAt = "2026-10-23T00:00:00Z";
const roles = ["KEYFRAME", "VIDEO", "AUDIO"] as const;
const kinds = { KEYFRAME: "IMAGE", VIDEO: "VIDEO", AUDIO: "AUDIO" } as const;
const assets = { IMAGE: "synthetic-resource-0", VIDEO: "synthetic-resource-5", AUDIO: "synthetic-resource-3" } as const;
const versions = new Map<string, ArtifactVersion[]>();
function mediaItem(id: string, title: string, kind: "IMAGE" | "VIDEO" | "AUDIO"): CanvasItem {
  const history: ArtifactVersion[] = [2, 1].map((versionNo) => ({ id: `${id}-v${versionNo}`, versionNo,
    schemaVersion: 1, content: { sourceType: "UPLOAD", assetId: assets[kind] }, inputReferences: [], createdByKind: "USER", createdAt }));
  const selectedVersion = history[0]!;
  const artifact: Artifact = { id: `${id}-artifact`, projectId, title, kind, version: 2,
    resourceDefaultVersionId: selectedVersion.id, resourceDefaultVersion: selectedVersion, createdAt, updatedAt: createdAt };
  versions.set(id, history); versions.set(artifact.id, history);
  return { id, subjectType: "ARTIFACT", subjectId: artifact.id, title, x: 0, y: 0, width: 320, height: 240,
    zIndex: 1, locked: false, version: 2, selectedVersionId: selectedVersion.id, selectedVersion, artifact };
}
const items: CanvasItem[] = Array.from({ length: 8 }, (_, index) => roles.map((role) =>
  mediaItem(`synthetic-shot-${index + 1}-${role.toLowerCase()}`, `SHOT-${String(index + 1).padStart(2, "0")} · ${role}`, kinds[role]))).flat();
const composition = mediaItem("synthetic-composition", "Synthetic final composition", "VIDEO");
items.push(composition);
const source: ArtifactVersion = { id: "synthetic-script-v1", versionNo: 1, schemaVersion: 1,
  content: { format: "PLAIN_TEXT", text: "合成剧本：林岚与陈河在天台交还信件。所有人物与素材为合成测试数据。" }, inputReferences: [], createdByKind: "USER", createdAt };
const sourceArtifact: Artifact = { id: "synthetic-script", projectId, kind: "TEXT", title: "Synthetic script · 林岚 / 陈河",
  version: 1, resourceDefaultVersionId: source.id, resourceDefaultVersion: source, createdAt, updatedAt: createdAt };
versions.set(sourceArtifact.id, [source]);
const characters = [mediaItem("synthetic-character-lin", "林岚 · 固定外观参考", "IMAGE"), mediaItem("synthetic-character-chen", "陈河 · 固定外观参考", "IMAGE")];
const directorItems = options.has("director-media") ? [mediaItem("synthetic-director-video", "导演候选视频", "VIDEO"), mediaItem("synthetic-director-audio", "导演额外音频", "AUDIO")] : [];
items.push(...directorItems);
const artifacts = [sourceArtifact, ...characters.map((item) => item.artifact), ...items.map((item) => item.artifact)];
const shots: StoryboardShot[] = Array.from({ length: 8 }, (_, index) => ({ id: `synthetic-shot-${index + 1}`, code: `SHOT-${String(index + 1).padStart(2, "0")}`,
  scene: index < 4 ? "SC-01 天台" : "SC-02 楼梯", title: ["天台相逢", "信件特写", "林岚提问", "陈河回答", "走向楼梯", "停步回望", "交还信件", "两人离开"][index]!,
  description: "林岚穿米色外套，陈河穿深蓝夹克；沿用固定人物外观。镜头保持左右关系与道具连续性。",
  dialogue: index % 2 ? "陈河：这封信一直在等你。" : "林岚：我们还会再见吗？", durationSeconds: 5,
  sourceVersionId: source.id, referenceVersionIds: characters.map((item) => item.selectedVersionId!), revision: 1,
  productions: roles.map((role) => { const item = items.find((candidate) => candidate.id === `synthetic-shot-${index + 1}-${role.toLowerCase()}`)!;
    return { role, canvasItemId: item.id, selectedVersionId: item.selectedVersionId, reviewedRevision: 1, needsReview: false }; }) }));
if (options.has("director-media")) { shots[2]!.revision = 2; shots[2]!.productions.forEach((production) => { production.reviewedRevision = 2; }); }
const directorLink = { shotId: "synthetic-shot-3", shotCode: "SHOT-03", shotRevision: 1, role: "VIDEO" as const };
let directorApproval: AgentMediaApproval = { id: "synthetic-director-approval", projectId, runId: "synthetic-director-run", operationId: "synthetic-director-operation",
  status: "PENDING", version: 0, taskIds: [], result: null, createdAt, expiresAt: "2026-10-24T00:00:00Z", executionDeadline: null,
  outputs: directorItems.length ? [{ kind: "VIDEO", title: "第三镜导演视频提案", artifactId: directorItems[0]!.artifact.id,
    canvasItemId: directorItems[0]!.id, draftVersion: 7, storyboard: directorLink,
    preview: { prompt: "Synthetic director shot-three video prompt", adapterId: "MOCK_VIDEO", priceUnknown: true, durationSeconds: 5 } }] : [] };
function finishDirectorApproval(): void {
  directorApproval = { ...directorApproval, status: "SUCCEEDED", version: 2, result: { schemaVersion: 1, status: "SUCCEEDED", tasks: [{
    taskId: "synthetic-director-task", kind: "VIDEO_GENERATION", status: "SUCCEEDED", outputIndex: 0,
    canvasItemId: directorItems[0]!.id, storyboard: directorLink, artifactId: directorItems[0]!.artifact.id,
    artifactVersionId: directorItems[0]!.selectedVersionId!, selected: true,
    additionalResults: [{ ordinal: 1, artifactId: directorItems[1]!.artifact.id, canvasItemId: directorItems[1]!.id,
      artifactVersionId: directorItems[1]!.selectedVersionId!, selected: true }],
  }] } };
}
if (options.get("director-media") === "ready") { directorApproval.taskIds = ["synthetic-director-task"]; finishDirectorApproval(); }
let saved: Storyboard = { projectId, version: 1, shots, composition: { canvasItemId: composition.id, selectedVersionId: composition.selectedVersionId, needsReview: false } };
const evidence = { requests: [] as { method: string; path: string }[], saves: [] as SaveStoryboardRequest[], runs: [] as unknown[], approvals: [] as unknown[], openedNodes: [] as string[], saved };
const publish = () => { const output = document.getElementById("storyboard-browser-evidence"); if (output) output.textContent = JSON.stringify({ ...evidence, saved }); };
function creative(shot: StoryboardShot | SaveStoryboardRequest["shots"][number]) {
  return JSON.stringify([shot.description, shot.dialogue, shot.durationSeconds, shot.sourceVersionId, shot.referenceVersionIds]);
}
/** Only the synthetic server authors revisions and review flags; the production service has independent PostgreSQL tests. */
function persist(input: SaveStoryboardRequest): Storyboard {
  let changed = input.shots.length !== saved.shots.length;
  const next = input.shots.map((shot): StoryboardShot => {
    const previous = saved.shots.find((candidate) => candidate.id === shot.id);
    const revised = !previous || creative(shot) !== creative(previous);
    changed ||= revised || JSON.stringify(shot.productions.map((item) => [item.role, item.canvasItemId, item.selectedVersionId])) !== JSON.stringify(previous?.productions.map((item) => [item.role, item.canvasItemId, item.selectedVersionId]));
    const revision = (previous?.revision ?? 0) + Number(revised);
    return { ...shot, revision, productions: shot.productions.map(({ confirmReviewed, ...production }) => {
      const old = previous?.productions.find((candidate) => candidate.role === production.role && candidate.canvasItemId === production.canvasItemId);
      const candidate = directorItems.some((item) => item.id === production.canvasItemId && item.selectedVersionId === production.selectedVersionId);
      const basis = candidate ? directorLink.shotRevision : old?.reviewedRevision ?? revision;
      const needsReview = Boolean(production.selectedVersionId && !confirmReviewed && (candidate && basis < revision
        || old?.selectedVersionId === production.selectedVersionId && (revised || old.needsReview)));
      return { ...production, reviewedRevision: confirmReviewed || !needsReview ? revision : basis, needsReview };
    }) };
  });
  return { projectId, version: saved.version + 1, shots: next, composition: input.composition ? {
    canvasItemId: input.composition.canvasItemId, selectedVersionId: input.composition.selectedVersionId,
    needsReview: Boolean(input.composition.selectedVersionId && !input.composition.confirmReviewed && (changed || saved.composition?.needsReview)),
  } : null };
}
const settings = videoFunctionsFixture();
settings.connections[0]!.capabilities = roles.map((role) => videoFunctionCapability({ id: `synthetic-cap-${role}`, name: `Synthetic ${role}`,
  kind: role === "KEYFRAME" ? "IMAGE_GENERATION" : role === "VIDEO" ? "VIDEO_GENERATION" : "AUDIO_GENERATION",
  settings: { pricing: { amount: "0.25", currency: "USD", unit: role === "KEYFRAME" ? "IMAGE" : role === "VIDEO" ? "VIDEO" : "SECOND" } } }));
function draftFor(item: CanvasItem): MediaDraft {
  const role = roles.find((candidate) => kinds[candidate] === item.artifact.kind)!;
  return { projectId, canvasItemId: item.id, prompt: `Synthetic frozen ${role} prompt for ${item.title}`, parameters: {}, durationSeconds: 5,
    capabilityId: `synthetic-cap-${role}`, styleId: null, videoInputMode: null, mediaInputs: [], mentions: [], displayMode: "RESULT", version: 7, createdAt, updatedAt: createdAt };
}
const originalFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  if (!url.pathname.startsWith("/api/") || /\/(content|thumbnail)$/.test(url.pathname)) return originalFetch(input, init);
  const method = init?.method ?? (input instanceof Request ? input.method : "GET");
  evidence.requests.push({ method, path: url.pathname });
  let response: unknown;
  if (url.pathname.endsWith("/auth/csrf")) response = { headerName: "X-XSRF-TOKEN", token: "synthetic-token" };
  else if (url.pathname.endsWith("/storyboard") && method === "GET") {
    if (options.has("unauthorized")) { publish(); return new Response(JSON.stringify({ status: 401, title: "Synthetic unauthorized", code: "UNAUTHORIZED" }), { status: 401, headers: { "Content-Type": "application/problem+json" } }); }
    response = saved;
  } else if (url.pathname.endsWith("/storyboard") && method === "PUT") {
    const body = JSON.parse(String(init?.body)) as SaveStoryboardRequest; evidence.saves.push(body);
    if (options.has("conflict") || body.expectedVersion !== saved.version) { publish(); return new Response(JSON.stringify({ status: 409, title: "Synthetic version conflict", code: "VERSION_CONFLICT" }), { status: 409, headers: { "Content-Type": "application/problem+json" } }); }
    saved = persist(body); response = saved;
  } else if (url.pathname.endsWith("/storyboard/media-approvals")) {
    if (directorApproval.status === "APPROVED") finishDirectorApproval();
    response = { items: options.has("director-media") ? [directorApproval] : [] };
  }
  else if (url.pathname.endsWith("/media-approvals") && method === "GET") response = options.has("director-media") ? [directorApproval] : [];
  else if (url.pathname.endsWith("/media-connections")) response = settings;
  else if (url.pathname.endsWith("/artifacts")) response = { items: artifacts };
  else if (url.pathname.endsWith("/media-versions") || url.pathname.endsWith("/versions")) response = { items: versions.get(url.pathname.split("/").at(-2)!) ?? [] };
  else if (url.pathname.endsWith("/media-draft")) { const item = items.find((candidate) => candidate.id === url.pathname.split("/").at(-2)); if (!item) throw new Error("Unknown synthetic node"); response = draftFor(item); }
  else if (url.pathname.endsWith("/run") && method === "GET") response = [];
  else if (url.pathname.endsWith("/run") && method === "POST") {
    const body = JSON.parse(String(init?.body)) as { canvasItemId: string }; evidence.runs.push(body);
    response = { id: `synthetic-task-${evidence.runs.length}`, projectId, runId: null, stepKey: "synthetic", kind: "VIDEO_GENERATION", status: "READY",
      cancelRequested: false, input: body, attemptNo: 1, nextActionAt: createdAt, version: 1, createdAt, updatedAt: createdAt } satisfies Task;
  } else if (url.pathname.includes("/media-approvals/") && method !== "GET") {
    const body = JSON.parse(String(init?.body)) as { expectedVersion: number; decision: "APPROVE" | "REJECT" }; evidence.approvals.push(body);
    if (!options.has("director-media")) throw new Error("Synthetic approval requires an explicit user action");
    if (body.expectedVersion !== directorApproval.version) throw new Error("Synthetic approval version conflict");
    directorApproval = { ...directorApproval, version: 1, status: body.decision === "APPROVE" ? "APPROVED" : "REJECTED",
      taskIds: body.decision === "APPROVE" ? ["synthetic-director-task"] : [] }; response = directorApproval;
  }
  else throw new Error(`Unexpected synthetic request: ${method} ${url.pathname}`);
  publish(); return new Response(JSON.stringify(response), { headers: { "Content-Type": "application/json" } });
};
function Fixture() {
  const [open, setOpen] = useState(true);
  return <main style={{ padding: 24 }}><Button id="open-storyboard-fixture" onClick={() => setOpen(true)}>打开合成分镜制作台</Button>
    <output id="storyboard-browser-evidence" className="sr-only">{JSON.stringify({ ...evidence, saved })}</output>
    {open ? <StoryboardWorkbench projectId={projectId} items={items} onClose={() => setOpen(false)} onOpenNode={(item) => { evidence.openedNodes.push(item.id); publish(); }} /> : null}
  </main>;
}
const fixtureRoot = createRoot(document.getElementById("root")!);
fixtureRoot.render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><Fixture /></QueryClientProvider>);
if (import.meta.hot) import.meta.hot.dispose(() => { fixtureRoot.unmount(); window.fetch = originalFetch; });
