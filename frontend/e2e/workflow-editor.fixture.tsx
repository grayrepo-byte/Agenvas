import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MediaDraftEditor } from "../src/features/canvas/MediaDraftEditor";
import type { SaveMediaDraftRequest } from "../src/shared/api/client";
import "../src/styles.css";

// Synthetic local fixture: no credentials, backend, user content or provider calls.
const query = new URLSearchParams(window.location.search);
const openAi = query.has("openai");
const runningHub = query.has("runninghub");
const video = query.has("video");
const projectId = "synthetic-project";
const now = "2026-10-04T00:00:00Z";
const referenceLabels = ["人物图", "细节图", "构图"];
const referenceCount = query.has("tenReferences") ? 10 : referenceLabels.length;
const imageFields = Array.from({ length: referenceCount }, (_, index) => ({ key: `reference_${index}`, label: referenceLabels[index] ?? `参考图 ${index + 1}`,
  type: "IMAGE", nodeId: String(index + 1), fieldName: "image", required: true, advanced: false }));
const fields = [
  { key: "prompt", label: "创作提示词", type: "STRING", source: "PROMPT", nodeId: "4", fieldName: "text", required: true, advanced: false },
  ...imageFields,
  { key: "seed", label: "随机种子", type: "INTEGER", nodeId: "5", fieldName: "seed", required: false, advanced: false, defaultValue: 42, minimum: 0 },
  { key: "lora", label: "LoRA", type: "SELECT", nodeId: "6", fieldName: "lora", required: false, advanced: true,
    defaultValue: "none", options: [{ label: "无", value: "none" }, { label: "合成风格", value: "synthetic" }] },
  { key: "strength", label: "风格强度", type: "NUMBER", nodeId: "6", fieldName: "strength", required: false, advanced: true, defaultValue: 0.8, minimum: 0, maximum: 1 },
];
const capability = { id: "synthetic-workflow", name: openAi ? video ? "合成全能参考视频" : "OpenAI 图像" : runningHub ? "RunningHub 创作工作流" : "ComfyUI 创作工作流", enabled: true,
  adapterId: openAi ? video ? "ARK_SEEDANCE_2_I2V" : "OPENAI_GPT_IMAGE_2" : runningHub ? video ? "RUNNINGHUB_VIDEO" : "RUNNINGHUB_IMAGE" : video ? "COMFY_VIDEO_V1" : "COMFY_IMAGE_V1",
  kind: video ? "VIDEO_GENERATION" : "IMAGE_GENERATION", version: 0, capabilityVersion: 1, minimumSeconds: video ? 2 : 0, maximumSeconds: video ? 10 : 0,
  maxReferenceImages: referenceCount, maxReferenceAudios: 0, maxReferenceVideos: 0, supportedVideoInputModes: video ? ["TEXT", "START_END", "GENERAL_REFERENCE"] : [],
  defaultVideoInputMode: video ? "GENERAL_REFERENCE" : null, supportsEndFrame: video, supportedImageAspectRatios: ["AUTO", "16:9"],
  supportedImageResolutions: ["1K"], supportedImageQualities: [], supportsTransparentBackground: false,
  supportsImageMask: false, settings: openAi ? {} : runningHub ? { runningHub: { schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP",
    targetId: "synthetic-target", usePersonalQueue: false, addMetadata: false, fields,
    outputs: [{ kind: video ? "VIDEO" : "IMAGE", primary: true, maxCount: 1 }] } } : { comfyInputs: fields } };
let draft = { projectId, canvasItemId: "synthetic-canvas", prompt: "",
  parameters: {}, durationSeconds: video ? 5 : null, capabilityId: capability.id, styleId: null, videoInputMode: capability.defaultVideoInputMode,
  mediaInputs: [] as ReturnType<typeof savedInputs>, mentions: [], version: 0 };
const revision = (versionNo: number, prefix = "image") => ({ id: `synthetic-${prefix}-v${versionNo}`, versionNo, schemaVersion: 1,
  content: { assetId: `synthetic-${prefix}-asset-${versionNo}` }, inputReferences: [], createdByKind: "USER", createdAt: now });
const versions = [revision(2), revision(1)];
const resource = { id: "synthetic-image", projectId, kind: "IMAGE", title: "合成图片", version: 0,
  resourceDefaultVersionId: versions[0]!.id, resourceDefaultVersion: versions[0], createdAt: now, updatedAt: now };
const audioVersion = revision(1, "audio");
const audioResource = { ...resource, id: "synthetic-audio", kind: "AUDIO", title: "合成音频",
  resourceDefaultVersionId: audioVersion.id, resourceDefaultVersion: audioVersion };
const libraryResource = { ...resource, id: "synthetic-library-image", title: "合成个人资产",
  resourceDefaultVersionId: "synthetic-library-v1", resourceDefaultVersion: { ...revision(1, "library"), id: "synthetic-library-v1" } };
const resources = [resource, audioResource];
const libraryEntry = { id: "synthetic-library-entry", name: "合成个人资产", category: "OTHER", kind: "IMAGE", textContent: null,
  contentType: "image/png", byteSize: 128, width: 64, height: 64, durationMs: null, hasThumbnail: false, source: {},
  favorite: false, trashedAt: null, version: 0, createdAt: now, updatedAt: now };
let libraryCommand: unknown = null;
function savedInputs(inputs: SaveMediaDraftRequest["mediaInputs"]) {
  return inputs.map((input, order) => ({ ...input, order,
    artifactId: input.versionId.startsWith("synthetic-library") ? libraryResource.id : input.versionId.startsWith("synthetic-audio") ? audioResource.id : resource.id,
    sources: [{ id: `synthetic-manual-${order}`, type: "MANUAL" as const, connectionId: null }] }));
}
Object.defineProperty(window, "__workflowFixtureDraft", { get: () => draft });
window.fetch = async (input, init) => {
  const path = new URL(input instanceof Request ? input.url : String(input), window.location.href).pathname;
  let value: unknown;
  if (path.endsWith("/csrf")) value = { headerName: "X-XSRF-TOKEN", token: "synthetic-csrf" };
  else if (path.endsWith("/media-connections")) value = { connections: [{ id: "synthetic-connection", name: openAi ? "OpenAI" : runningHub ? "RunningHub" : "ComfyUI", platform: openAi ? "OPENAI" : runningHub ? "RUNNINGHUB" : "COMFYUI",
    enabled: true, version: 0, connectionVersion: 1, capabilities: [capability] }], defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] };
  else if (path.endsWith("/media-draft")) {
    if (init?.method === "PUT") {
      const request = JSON.parse(String(init.body)) as SaveMediaDraftRequest;
      draft = { ...draft, ...request, mediaInputs: savedInputs(request.mediaInputs), version: draft.version + 1 };
    }
    value = draft;
  } else if (path.endsWith("/artifacts")) value = { items: resources };
  else if (path.endsWith("/synthetic-image/versions")) value = { items: versions };
  else if (path.endsWith("/synthetic-audio/versions")) value = { items: [audioVersion] };
  else if (path.endsWith("/synthetic-library-image/versions")) value = { items: [libraryResource.resourceDefaultVersion] };
  else if (path.endsWith("/canvas/items")) value = { items: [
    { id: "synthetic-source-canvas", subjectType: "ARTIFACT", subjectId: resource.id, title: "合成画布图片", artifact: resource,
      selectedVersionId: versions[1]!.id, selectedVersion: versions[1], version: 0 },
    { id: "synthetic-audio-canvas", subjectType: "ARTIFACT", subjectId: audioResource.id, title: "合成画布音频", artifact: audioResource,
      selectedVersionId: audioVersion.id, selectedVersion: audioVersion, version: 0 },
  ] };
  else if (path.endsWith("/library/entries")) value = { items: [libraryEntry], total: 1, nextCursor: null, categoryCounts: { OTHER: 1 } };
  else if (path.endsWith("/library-references")) {
    const request = JSON.parse(String(init?.body));
    const versionId = libraryResource.resourceDefaultVersionId;
    resources.push(libraryResource);
    draft = { ...draft, ...request.draft,
      mediaInputs: savedInputs([...request.draft.mediaInputs, { versionId, role: request.role, color: request.color }]), version: draft.version + 1 };
    if (request.slotKey) draft.parameters = { ...draft.parameters,
      dynamicValues: { ...request.draft.parameters.dynamicValues, [request.slotKey]: versionId } };
    libraryCommand = { id: "synthetic-library-command", status: "SUCCEEDED", errorCode: null, errorDetail: null,
      result: { artifactId: libraryResource.id, versionId, draftVersion: draft.version } };
    value = libraryCommand;
  }
  else if (path.endsWith("/library/commands/synthetic-library-command")) value = libraryCommand;
  else if (path.endsWith("/queue")) value = { waitingAhead: 2, reason: "WAITING_WORKER" };
  else if (path.endsWith("/run")) value = query.has("task") ? [{
    id: "synthetic-task", projectId, runId: null, kind: capability.kind,
    status: query.get("task"), version: 0, createdAt: now, updatedAt: now,
    errorCode: query.has("longError") ? "Synthetic detail ".repeat(18) : "PROVIDER_SUBMISSION_UNKNOWN",
  }] : [];
  else if (path.endsWith("/tasks") || path.endsWith("/media-styles")) value = [];
  else value = { items: [] };
  return Response.json(value);
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={client}>
  <div className="workflow-layout-fixture" style={{ width: "min(680px, calc(100vw - 24px))", margin: "auto" }}>
    <MediaDraftEditor artifact={{ id: "synthetic-artifact", projectId, kind: video ? "VIDEO" : "IMAGE", title: "合成图片",
      version: 0, resourceDefaultVersionId: null, archivedAt: null, createdAt: now, updatedAt: now }} canvasItemId="synthetic-canvas" />
  </div>
</QueryClientProvider>);
document.body.style.cssText = "margin:0;display:flex;align-items:center;justify-content:center;min-height:100dvh;background:var(--ui-background);";
