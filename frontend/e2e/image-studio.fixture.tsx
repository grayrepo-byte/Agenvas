import { createRoot } from "react-dom/client";
import { App } from "../src/app/App";
import type { ImageStudioBatch, ImageStudioCapability } from "../src/shared/api/imageStudio";
import { resolveLocale, setLocale } from "../src/shared/i18n";
import "../src/styles.css";

const parameters = new URL(location.href).searchParams;
setLocale(resolveLocale(parameters.get("locale")) ?? "zh");
const projectId = "synthetic-image-studio";
const scenario = parameters.get("scenario") ?? "grid";
const shape = scenario === "portrait" ? "portrait" : scenario === "landscape" ? "landscape" : "square";
const [width, height] = shape === "portrait" ? [900, 1600] : shape === "landscape" ? [1600, 900] : [1024, 1024];
const initialCount = scenario === "portrait" || scenario === "landscape" ? 1 : 4;
const photoMode = parameters.has("photos");
const photoSources = [
  { key: "lake", width: 1920, height: 1280, contentType: "image/jpeg" },
  { key: "rocket", width: 640, height: 427, contentType: "image/jpeg" },
  { key: "coffee", width: 600, height: 400, contentType: "image/png" },
  { key: "eagle", width: 1826, height: 2019, contentType: "image/png" },
] as const;
function fixtureResult(index: number) {
  const photo = photoSources[scenario === "portrait" ? 3 : scenario === "landscape" ? 0 : index % photoSources.length]!;
  return {
    artifactId: `synthetic-artifact-${index}`,
    artifactVersionId: `synthetic-version-${index}`,
    assetId: photoMode ? `synthetic-photo-${photo.key}-${index}` : `synthetic-studio-${shape}-${index}`,
    width: photoMode ? photo.width : width,
    height: photoMode ? photo.height : height,
    contentType: photoMode ? photo.contentType : "image/svg+xml",
  };
}
const referenceAssetId = photoMode ? "synthetic-photo-lake-0" : "synthetic-studio-square-0";
const capability: ImageStudioCapability = {
  capabilityId: "synthetic-image-capability",
  capabilityVersion: 2,
  name: photoMode ? "摄影验收 · Mock" : "Studio Image · Synthetic",
  connectionName: "Local synthetic fixture",
  adapterId: "OPENAI_GPT_IMAGE_2",
  defaultParameters: { aspectRatio: "1:1", resolution: "1K", quality: "medium", generationCount: 1 },
  aspectRatios: ["1:1", "16:9", "9:16"],
  resolutions: ["1K", "2K"],
  qualities: ["low", "medium", "high"],
  maxReferenceImages: 4,
  supportsTransparentBackground: true,
  complexity: null,
};
const saved: ImageStudioBatch = {
  id: "synthetic-saved-batch",
  projectId,
  capabilityId: capability.capabilityId,
  capabilityVersion: 2,
  prompt: photoMode
    ? "湖畔群山与静谧森林，柔和自然光。\n完整保留构图、层次与真实材质。\n公开授权示例素材 · Mock 验收，非真实模型生成。"
    : "清晨海边的小屋，柔和自然光与手绘质感 · A seaside cabin in soft morning light",
  parameters: {
    aspectRatio: shape === "portrait" ? "9:16" : shape === "landscape" ? "16:9" : "1:1",
    resolution: "2K",
    quality: "high",
    generationCount: initialCount,
  },
  referenceVersionIds: photoMode ? ["synthetic-reference-version"] : [],
  references: photoMode
    ? [
        {
          artifactId: "synthetic-reference",
          versionId: "synthetic-reference-version",
          assetId: "synthetic-photo-lake-0",
          projectId,
          width: 1920,
          height: 1280,
        },
      ]
    : [],
  createdAt: "2026-11-19T08:00:00Z",
  tasks: Array.from({ length: initialCount }, (_, index) => ({
    id: `synthetic-saved-${index}`,
    status: scenario === "polling" && index === 3 ? "RUNNING" : "SUCCEEDED",
    version: 4,
    errorCode: null,
    results: scenario === "polling" && index === 3 ? [] : [fixtureResult(index)],
  })),
};
const pendingBatch: ImageStudioBatch = {
  ...saved,
  id: "synthetic-pending-batch",
  prompt: "Uncertain acceptance · synthetic test",
  createdAt: "2026-11-18T08:00:00Z",
  tasks: [{ id: "synthetic-pending-task", status: "UNKNOWN", version: 2, errorCode: null, results: [] }],
};
const partialBatch: ImageStudioBatch = {
  ...saved,
  id: "synthetic-partial-batch",
  prompt: "Partial success · synthetic test",
  createdAt: "2026-11-17T08:00:00Z",
  tasks: [
    saved.tasks[0]!,
    {
      id: "synthetic-failed-task",
      status: "FAILED",
      version: 3,
      errorCode: "SYNTHETIC_PROVIDER_REJECTED",
      results: [],
    },
  ],
};
const previousBatch: ImageStudioBatch = {
  ...saved,
  id: "synthetic-previous-batch",
  prompt: "光影摄影练习 · 公开授权示例 · Mock",
  parameters: { ...saved.parameters, generationCount: 1 },
  createdAt: "2026-11-18T07:00:00Z",
  tasks: [
    {
      ...saved.tasks[0]!,
      id: "synthetic-previous-task",
      results: [{ ...fixtureResult(2), artifactVersionId: "synthetic-previous-version" }],
    },
  ],
};
const olderBatch: ImageStudioBatch = {
  ...saved,
  id: "synthetic-older-batch",
  prompt: "暮色与自然光 · 公开授权示例 · Mock",
  parameters: { ...saved.parameters, generationCount: 1 },
  createdAt: "2026-11-17T06:00:00Z",
  tasks: [
    {
      ...saved.tasks[0]!,
      id: "synthetic-older-task",
      results: [{ ...fixtureResult(1), artifactVersionId: "synthetic-older-version" }],
    },
  ],
};
const writes: { key: string | null; request: unknown }[] = [];
const unexpected: string[] = [];
let uploads = 0;
let failAcceptance = !parameters.has("ok");
let batch: ImageStudioBatch | undefined;
let acknowledge: (() => void) | undefined;
let leading = false;
window.fetch = async (input, init) => {
  const url = new URL(
    typeof input === "string" ? input : input instanceof URL ? input.href : input.url,
    location.origin,
  );
  const method = init?.method ?? "GET";
  if (url.pathname === "/api/v1/auth/me")
    return Response.json({ id: "synthetic-owner", loginName: "creator", role: "ADMIN" });
  if (url.pathname === "/api/v1/auth/csrf") return Response.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" });
  if (url.pathname === "/api/v1/image-studio/context")
    return Response.json({ projectId, defaultCapabilityId: capability.capabilityId, capabilities: [capability] });
  if (url.pathname === "/api/v1/image-studio/references" && method === "POST") {
    uploads++;
    return Response.json({
      artifactId: "synthetic-reference",
      versionId: "synthetic-reference-version",
      assetId: referenceAssetId,
      projectId,
      width: 1024,
      height: 1024,
    });
  }
  if (url.pathname === "/api/v1/image-studio/batches" && method === "POST") {
    const request = JSON.parse(String(init?.body));
    writes.push({ key: new Headers(init?.headers).get("Idempotency-Key"), request });
    if (failAcceptance) {
      failAcceptance = false;
      return Response.json(
        { code: "SYNTHETIC_UNAVAILABLE", detail: "Synthetic response lost", retryable: true },
        { status: 503 },
      );
    }
    if (scenario === "feedback")
      await new Promise<void>(resolve => {
        acknowledge = resolve;
      });
    batch = {
      ...saved,
      id: "5ba708e3-0000-4000-8000-000000000001",
      tasks: Array.from({ length: request.parameters.generationCount ?? 1 }, (_, index) => ({
        id: `synthetic-new-task-${index}`,
        status: scenario === "feedback" ? "READY" : "SUCCEEDED",
        version: 4,
        errorCode: null,
        results:
          scenario === "feedback"
            ? []
            : [{ ...fixtureResult(index), artifactVersionId: `synthetic-new-version-${index}` }],
      })),
      prompt: request.prompt,
      parameters: request.parameters,
      referenceVersionIds: request.referenceVersionIds,
      references: request.referenceVersionIds.map((versionId: string) => ({
        artifactId: "synthetic-reference",
        versionId,
        assetId: referenceAssetId,
        projectId,
        width: 1024,
        height: 1024,
      })),
    };
    return Response.json(batch);
  }
  if (url.pathname === "/api/v1/image-studio/batches")
    return Response.json({
      items: [
        ...(leading ? [{ ...olderBatch, id: "synthetic-leading-batch", createdAt: "2026-11-20T08:00:00Z" }] : []),
        ...(batch ? [batch, saved, previousBatch, olderBatch] : [saved, previousBatch, olderBatch]),
        ...(photoMode ? [] : [pendingBatch, partialBatch]),
      ],
      nextCursor: null,
    });
  if (batch && url.pathname === `/api/v1/image-studio/batches/${batch.id}`) return Response.json(batch);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-saved-batch") return Response.json(saved);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-pending-batch") return Response.json(pendingBatch);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-partial-batch") return Response.json(partialBatch);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-previous-batch") return Response.json(previousBatch);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-older-batch") return Response.json(olderBatch);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-leading-batch")
    return Response.json({ ...olderBatch, id: "synthetic-leading-batch" });
  unexpected.push(`${method} ${url.pathname}`);
  return new Response(null, { status: 404 });
};
Object.assign(window, {
  __imageStudio: {
    evidence: () => ({ writes, uploads, unexpected }),
    acknowledge: () => acknowledge?.(),
    startGeneration: () => {
      if (batch)
        batch.tasks = batch.tasks.map(task =>
          task.status === "READY" ? { ...task, status: "WAITING_PROVIDER", version: task.version + 1 } : task,
        );
    },
    finishNextOutput: () => {
      if (!batch) return;
      const next = batch.tasks.findLastIndex(task => task.status !== "SUCCEEDED");
      batch.tasks = batch.tasks.map((task, index) => {
        if (task.status === "SUCCEEDED") return task;
        if (index !== next) return { ...task, status: "WAITING_PROVIDER", version: task.version + 1 };
        return {
          ...task,
          status: "SUCCEEDED",
          version: task.version + 1,
          results: [{ ...fixtureResult(index), artifactVersionId: `synthetic-new-version-${index}` }],
        };
      });
    },
    finishAndLead: () => {
      saved.tasks = saved.tasks
        .map((task, index) =>
          task.status === "RUNNING" ? { ...task, status: "SUCCEEDED", results: [fixtureResult(index)] } : task,
        )
        .reverse();
      leading = true;
    },
    reorder: () => {
      saved.tasks = [...saved.tasks].reverse();
    },
    prepend: () => {
      saved.tasks = [
        {
          ...saved.tasks[0]!,
          id: "synthetic-late-task",
          results: [{ ...fixtureResult(0), artifactVersionId: "synthetic-late-version" }],
        },
        ...saved.tasks,
      ];
    },
    reset: () => {
      writes.splice(0);
      uploads = 0;
    },
  },
});
window.history.replaceState({}, "", `/image-studio${location.search}`);
createRoot(document.getElementById("root")!).render(<App />);
