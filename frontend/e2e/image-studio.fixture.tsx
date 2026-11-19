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
const referenceAssetId = "synthetic-studio-square-0";
const capability: ImageStudioCapability = {
  capabilityId: "synthetic-image-capability",
  capabilityVersion: 2,
  name: "Studio Image · Synthetic",
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
  prompt: "清晨海边的小屋，柔和自然光与手绘质感 · A seaside cabin in soft morning light",
  parameters: {
    aspectRatio: shape === "portrait" ? "9:16" : shape === "landscape" ? "16:9" : "1:1",
    resolution: "2K",
    quality: "high",
    generationCount: initialCount,
  },
  referenceVersionIds: [],
  references: [],
  createdAt: "2026-11-19T08:00:00Z",
  tasks: Array.from({ length: initialCount }, (_, index) => ({
    id: `synthetic-saved-${index}`,
    status: "SUCCEEDED",
    version: 4,
    errorCode: null,
    results: [
      {
        artifactId: `synthetic-artifact-${index}`,
        artifactVersionId: `synthetic-version-${index}`,
        assetId: `synthetic-studio-${shape}-${index}`,
        width,
        height,
        contentType: "image/svg+xml",
      },
    ],
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
const writes: { key: string | null; request: unknown }[] = [];
const unexpected: string[] = [];
let uploads = 0;
let failAcceptance = true;
let batch: ImageStudioBatch | undefined;
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
    batch = {
      ...saved,
      id: "synthetic-new-batch",
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
      items: batch ? [batch, saved, pendingBatch, partialBatch] : [saved, pendingBatch, partialBatch],
      nextCursor: null,
    });
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-new-batch" && batch) return Response.json(batch);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-saved-batch") return Response.json(saved);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-pending-batch") return Response.json(pendingBatch);
  if (url.pathname === "/api/v1/image-studio/batches/synthetic-partial-batch") return Response.json(partialBatch);
  unexpected.push(`${method} ${url.pathname}`);
  return new Response(null, { status: 404 });
};
Object.assign(window, {
  __imageStudio: {
    evidence: () => ({ writes, uploads, unexpected }),
    reset: () => {
      writes.splice(0);
      uploads = 0;
    },
  },
});
window.history.replaceState({}, "", `/image-studio${location.search}`);
createRoot(document.getElementById("root")!).render(<App />);
