import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRoot } from "react-dom/client";
import { MediaDraftEditor } from "../src/features/canvas/MediaDraftEditor";
import "../src/styles.css";

// Synthetic local fixture: no credentials, backend, user content or provider calls.
const openAi = new URLSearchParams(window.location.search).has("openai");
const imageFields = [0, 1, 2].map((index) => ({ key: `reference_${index}`, label: `参考图片 ${index + 1}`,
  type: "IMAGE", nodeId: String(index + 1), fieldName: "image", required: true, advanced: false }));
const fields = [
  { key: "prompt", label: "创作提示词", type: "STRING", source: "PROMPT", nodeId: "4", fieldName: "text", required: true, advanced: false },
  ...imageFields,
  { key: "seed", label: "随机种子", type: "INTEGER", nodeId: "5", fieldName: "seed", required: false, advanced: false, defaultValue: 42, minimum: 0 },
  { key: "lora", label: "LoRA", type: "SELECT", nodeId: "6", fieldName: "lora", required: false, advanced: true,
    defaultValue: "none", options: [{ label: "无", value: "none" }, { label: "合成风格", value: "synthetic" }] },
  { key: "strength", label: "风格强度", type: "NUMBER", nodeId: "6", fieldName: "strength", required: false, advanced: true, defaultValue: 0.8, minimum: 0, maximum: 1 },
];
const capability = { id: "synthetic-workflow", name: openAi ? "OpenAI 图像" : "ComfyUI 创作工作流", enabled: true,
  adapterId: openAi ? "OPENAI_GPT_IMAGE_2" : "COMFY_IMAGE_V1",
  kind: "IMAGE_GENERATION", version: 0, capabilityVersion: 1, minimumSeconds: 0, maximumSeconds: 0,
  maxReferenceImages: 3, maxReferenceAudios: 0, maxReferenceVideos: 0, supportedVideoInputModes: [],
  defaultVideoInputMode: null, supportsEndFrame: false, supportedImageAspectRatios: ["AUTO", "16:9"],
  supportedImageResolutions: ["1K"], supportedImageQualities: [], supportsTransparentBackground: false,
  supportsImageMask: false, settings: openAi ? {} : { comfyInputs: fields } };
let draft = { projectId: "synthetic-project", canvasItemId: "synthetic-canvas", prompt: "",
  parameters: {}, durationSeconds: null, capabilityId: capability.id, styleId: null, videoInputMode: null,
  mediaInputs: [], mentions: [], version: 0 };
window.fetch = async (input, init) => {
  const path = new URL(input instanceof Request ? input.url : String(input), window.location.href).pathname;
  let value: unknown;
  if (path.endsWith("/csrf")) value = { headerName: "X-XSRF-TOKEN", token: "synthetic-csrf" };
  else if (path.endsWith("/media-connections")) value = { connections: [{ id: "synthetic-connection", name: openAi ? "OpenAI" : "ComfyUI", platform: openAi ? "OPENAI" : "COMFYUI",
    enabled: true, version: 0, connectionVersion: 1, capabilities: [capability] }], defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] };
  else if (path.endsWith("/media-draft")) {
    if (init?.method === "PUT") draft = { ...draft, ...JSON.parse(String(init.body)), version: draft.version + 1 };
    value = draft;
  } else if (path.endsWith("/run") || path.endsWith("/tasks") || path.endsWith("/media-styles")) value = [];
  else value = { items: [] };
  return Response.json(value);
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={client}>
  <div className="workflow-layout-fixture" style={{ width: "min(680px, calc(100vw - 24px))", margin: "auto" }}>
    <MediaDraftEditor artifact={{ id: "synthetic-artifact", projectId: "synthetic-project", kind: "IMAGE", title: "合成图片",
      version: 0, resourceDefaultVersionId: null, archivedAt: null, createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z" }} canvasItemId="synthetic-canvas" />
  </div>
</QueryClientProvider>);
document.body.style.cssText = "margin:0;display:flex;align-items:center;justify-content:center;min-height:100dvh;background:var(--ui-background);";
