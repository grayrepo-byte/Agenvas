import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { createRoot } from "react-dom/client";
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
const originalFetch = window.fetch.bind(window);
window.fetch = async (input, init) => {
  const url = new URL(input instanceof Request ? input.url : String(input), location.href);
  if (!url.pathname.startsWith("/api/")) return originalFetch(input, init);
  if (url.pathname.endsWith("/content") || url.pathname.endsWith("/thumbnail")) return originalFetch(input, init);
  if (url.pathname.endsWith("/auth/csrf")) return Response.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" });
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
