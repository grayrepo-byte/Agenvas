import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import type { MediaCapability, MediaTemplateKind, ThirdPartyPromptEntry } from "../src/shared/api/client";
import { MediaTemplatePicker } from "../src/features/templates/MediaTemplatePicker";
import { setLocale } from "../src/shared/i18n";
import { Button } from "../src/shared/ui/primitives/button";
import "../src/styles.css";

setLocale("zh");
const cover = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360"><rect width="640" height="360" fill="teal"/><text x="180" y="180" fill="white" font-size="32">Synthetic preview</text></svg>')}`;
const prompt = Array.from({ length: 160 }, (_, index) => `Synthetic prompt line ${index + 1}: preserve the subject and add soft lighting.`).join("\n");
const videoCapability: MediaCapability = {
  id: "synthetic-video", name: "Synthetic video", enabled: true, version: 1, capabilityVersion: 1,
  adapterId: "MOCK_VIDEO", kind: "VIDEO_GENERATION", minimumSeconds: 1, maximumSeconds: 10,
  maxReferenceImages: 0, maxReferenceVideos: 0, maxReferenceAudios: 0,
  supportedVideoInputModes: ["TEXT"], defaultVideoInputMode: "TEXT", supportsEndFrame: false,
  supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [],
  supportsImageMask: false, supportsTransparentBackground: false, mappingSha256: "a".repeat(64), settings: {},
};
function entry(kind: MediaTemplateKind, index: number): ThirdPartyPromptEntry {
  const value = { id: `fixture:${index}`, sourceId: "fixture", title: `Synthetic ${kind} ${index}`,
    prompt, description: "Synthetic detail with a long prompt", coverUrl: cover,
    author: "Fixture author", tags: [], sourceUrl: "", createdAt: "" };
  return { id: value.id, sourceId: "fixture", targetKind: kind, version: 1, cachedAt: "2026-10-08T00:00:00Z", updatedAt: "2026-10-08T00:00:00Z",
    image: kind === "IMAGE" ? { ...value, imageModel: "synthetic-image-model", imageMode: "generate", referenceImageUrls: [] } : null,
    video: kind === "VIDEO" ? { ...value, videoModel: "synthetic-video-model", videoMode: "text_to_video", references: [], imageGeneration: null } : null };
}
// In-memory API data exercises production components without projects, credentials or Providers.
globalThis.fetch = async (input) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.origin);
  if (url.pathname === "/api/v1/auth/csrf") return Response.json({ headerName: "X-CSRF-TOKEN", token: "synthetic" });
  if (url.pathname === "/api/v1/media-templates") return Response.json({ items: [] });
  if (url.pathname === "/api/v1/media-templates/third-party/sources") return Response.json({ items: [] });
  if (url.pathname === "/api/v1/media-templates/third-party") {
    const kind: MediaTemplateKind = url.searchParams.get("targetKind") === "VIDEO" ? "VIDEO" : "IMAGE";
    const offset = Number(url.searchParams.get("offset") ?? 0);
    return Response.json({ items: Array.from({ length: 50 }, (_, index) => entry(kind, offset + index + 1)), total: 150, limit: 50, offset });
  }
  if (url.pathname.endsWith("/third-party/import")) return Response.json({ templateId: "fixture", templateVersion: 1, targetKind: currentKind,
    prompt, images: [], references: [], videoInputMode: currentKind === "VIDEO" ? "TEXT" : null });
  throw new Error(`Unexpected fixture request: ${url.pathname}`);
};
let currentKind: MediaTemplateKind = "IMAGE";
function Fixture() {
  const [kind, setKind] = useState<MediaTemplateKind | null>(null);
  const [applied, setApplied] = useState("");
  return <main className="app-page">
    <Button id="open-image" onClick={() => { currentKind = "IMAGE"; setKind("IMAGE"); }}>打开图片模板</Button>
    <Button id="open-video" onClick={() => { currentKind = "VIDEO"; setKind("VIDEO"); }}>打开视频模板</Button>
    <output id="applied-prompt">{applied}</output>
    {kind ? <MediaTemplatePicker projectId="synthetic-project" targetKind={kind} capability={kind === "VIDEO" ? videoCapability : undefined} fields={{ prompt: "", parameters: {}, durationSeconds: null,
      capabilityId: null, videoInputMode: kind === "VIDEO" ? "TEXT" : null, mediaInputs: [], mentions: [] }} seedImages={[]}
      onBusy={() => undefined} onClose={() => setKind(null)} onApply={async (imported) => { setApplied(imported.prompt); }} /> : null}
  </main>;
}
createRoot(document.getElementById("root")!).render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Fixture /></QueryClientProvider>);
