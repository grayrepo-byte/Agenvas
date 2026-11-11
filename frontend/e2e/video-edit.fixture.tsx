import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { encodeSubtitleFile } from "../src/features/canvas/subtitles/subtitleExchange";
import { VideoEditDialog } from "../src/features/canvas/VideoEditDialog";
import type { CanvasItem } from "../src/shared/api/client";
import { resolveLocale, setLocale } from "../src/shared/i18n";
import { Button } from "../src/shared/ui/primitives/button";
import "../src/styles.css";

// Synthetic sources and in-memory requests only; no backend or Provider calls.
const params = new URLSearchParams(location.search);
setLocale(resolveLocale(params.get("locale")) ?? "zh");
const projectId = "synthetic-video-edit";
const names = ["城市序章 · 开场", "穿过街角 · 跟随", "落日余温 · 尾声", "城市环境音", "旁白 · 第一章"];
const items: CanvasItem[] = names.map((title, index) => {
  const id = `synthetic-clip-${index}`;
  return { id, title, subjectType: "ARTIFACT", subjectId: id, x: 0, y: 0, width: 640, height: 360,
    zIndex: index, locked: false, version: 1, selectedVersionId: `version-${id}`, selectedVersion: {
      id: `version-${id}`, versionNo: 1, schemaVersion: 1,
      content: { assetId: `synthetic-library-${index < 3 ? index % 2 + 5 : index}`, sourceType: "UPLOAD" },
      createdByKind: "USER", createdAt: "2026-10-24T00:00:00Z", inputReferences: [],
    }, artifact: { id, projectId, title, kind: index < 3 ? "VIDEO" : "AUDIO", resourceDefaultVersionId: null,
      resourceDefaultVersion: null, version: 1, createdAt: "2026-10-24T00:00:00Z", updatedAt: "2026-10-24T00:00:00Z" } };
});
type SubtitleFixture = { id: string; projectId: string; sourceVersionId: string; sourceDurationMs: number; language: string;
  cues: { text: string; startMs: number; endMs: number }[]; version: number; taskId: null; updatedAt: string };
let subtitleFailure = params.has("subtitle-failure");
let subtitleDrafts: SubtitleFixture[] = params.has("subtitles") ? [{ id: "synthetic-subtitle", projectId, sourceVersionId: items[0]!.selectedVersionId!, sourceDurationMs: 4000,
  language: "zh", cues: [{ text: "Synthetic recognized 中文 · 模拟识别草稿", startMs: 500, endMs: 1500 }], version: 0, taskId: null, updatedAt: "2026-11-01T00:00:00Z" }] : [];
Object.assign(window, { subtitleFixture: { failSave: (value: boolean) => { subtitleFailure = value; }, drafts: () => subtitleDrafts } });
const originalFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  if (!url.pathname.startsWith("/api/")) return originalFetch(input, init);
  if (url.pathname.endsWith("/content") || url.pathname.endsWith("/thumbnail")) return originalFetch(input, init);
  if (url.pathname.endsWith("/auth/csrf")) return Response.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" });
  if (url.pathname.endsWith("/subtitles/capabilities")) return Response.json({ availability: "NOT_CONFIGURED", maxCues: 5000, maxFileBytes: 2097152, maxSourceDurationMs: 600000 });
  if (url.pathname.endsWith("/subtitles")) {
    const ids = url.searchParams.getAll("sourceVersionId");
    return Response.json(subtitleDrafts.filter((draft) => ids.includes(draft.sourceVersionId)));
  }
  if (url.pathname.endsWith("/subtitles/synthetic-subtitle/import")) {
    const body = JSON.parse(String(init?.body)) as { contents: string; expectedVersion: number; format: string };
    const draft = subtitleDrafts[0]!;
    const saved = { ...draft, version: draft.version + 1, cues: [{ text: body.contents.trim().split("\n")[2]!, startMs: 750, endMs: 1750 }] };
    subtitleDrafts = [saved];
    Object.assign(window, { subtitleImportedContent: body.contents });
    return Response.json(saved);
  }
  if (url.pathname.endsWith("/subtitles/synthetic-subtitle/export")) {
    const format = url.searchParams.get("format") === "VTT" ? "VTT" : "SRT";
    return new Response(encodeSubtitleFile(subtitleDrafts[0]!.cues, 4000, format), { headers: { "Content-Type": format === "VTT" ? "text/vtt;charset=UTF-8" : "application/x-subrip;charset=UTF-8" } });
  }
  if (url.pathname.includes("/subtitles/")) {
    const draft = subtitleDrafts.find((entry) => entry.id === url.pathname.split("/").at(-1));
    if (draft && init?.method === "PUT") {
      if (subtitleFailure) return Response.json({ code: "SUBTITLE_SAVE_FAILED", detail: "Synthetic subtitle save failure", retryable: true }, { status: 503, headers: { "Content-Type": "application/problem+json" } });
      const body = JSON.parse(String(init.body)) as { expectedVersion: number; cues: SubtitleFixture["cues"] };
      if (body.expectedVersion !== draft.version) return Response.json({ code: "SUBTITLE_VERSION_CONFLICT", detail: "Synthetic conflict" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
      const saved = { ...draft, cues: body.cues, version: draft.version + 1 };
      subtitleDrafts = subtitleDrafts.map((entry) => entry.id === draft.id ? saved : entry);
      return Response.json(saved);
    }
    if (draft) return Response.json(draft);
  }
  if (url.pathname.endsWith("/video-compositions/capabilities")) return Response.json({ maxDurationMs: 600000, maxFrames: 18000,
    maxShots: 60, maxLines: 300, maxSubtitles: 2000, maxSourceDurationMs: 600000, maxSourceBytes: 2147483648,
    maxOutputBytes: 524288000, frameRate: 30, timeoutSeconds: 900, resolutions: ["720P", "1080P", "2160P"] });
  if (url.pathname.endsWith("/video-compositions/preview")) {
    const request = JSON.parse(String(init?.body)) as { shots: { videoVersionId: string; sourceStartMs: number; durationMs: number; speed: number; lines: { audioVersionId: string }[] }[] };
    let frames = 0;
    const shots = request.shots.map((shot) => { const count = Math.floor(shot.durationMs * 30 / (1000 * shot.speed));
      const result = { videoVersionId: shot.videoVersionId, timelineStartMs: Math.floor(frames * 1000 / 30), durationMs: Math.floor(count * 1000 / 30),
        lines: shot.lines.map((line, index) => ({ audioVersionId: line.audioVersionId, startMs: index * 1200, endMs: index * 1200 + 1100, trimStartMs: 0, trimEndMs: 1100, speed: 1 })) }; frames += count; return result; });
    return Response.json({ durationMs: Math.floor(frames * 1000 / 30), frames, fontFamily: 'sans-serif', shots });
  }
  if (url.pathname.endsWith("/video-compositions")) {
    const output = document.getElementById("video-edit-evidence");
    if (output) output.textContent = String(init?.body ?? "");
    if (params.has("failure")) return Response.json({ detail: "合成验收：受理失败，编辑内容已保留。" }, { status: 503 });
    return Response.json({ id: "synthetic-edit-task", status: "READY" });
  }
  const assetId = url.pathname.split("/").at(-1) ?? "";
  if (url.pathname.includes("/assets/")) return Response.json({ id: assetId, projectId,
    mediaKind: /[34]$/.test(assetId) ? "AUDIO" : "VIDEO", contentType: /[34]$/.test(assetId) ? "audio/mp4" : "video/mp4",
    byteSize: 1024, sha256: "a".repeat(64), width: 160, height: 90, durationMs: assetId.endsWith("3") ? 12000 : assetId.endsWith("4") ? 1100 : 4000,
    createdAt: "2026-10-24T00:00:00Z" });
  return Response.json({ detail: "Unknown synthetic route" }, { status: 404 });
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
const dragEvents: string[] = [];
for (const type of ["dragstart", "drop", "dragend"]) document.addEventListener(type, () => {
  dragEvents.push(type);
  const output = document.getElementById("video-edit-drag-evidence");
  if (output) output.textContent = JSON.stringify(dragEvents);
});
function Fixture() {
  const [open, setOpen] = useState(true);
  return <QueryClientProvider client={client}>
    <main style={{ padding: 32 }}><p>合成素材 · 视频剪辑设计验收</p>
      <Button onClick={() => setOpen(true)}>打开视频剪辑与导出</Button>
      <output id="video-edit-evidence" hidden />
      <output id="video-edit-drag-evidence" hidden />
    </main>
    {open ? <VideoEditDialog projectId={projectId} items={params.has("empty") ? [] : items}
      initialIds={items.slice(0, 3).map((item) => item.id)} onClose={() => setOpen(false)} /> : null}
  </QueryClientProvider>;
}
createRoot(document.getElementById("root")!).render(<Fixture />);
