import { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { MediaPreviewDialog } from "../src/features/canvas/MediaPreviewDialog";
import { VideoComparisonDialog } from "../src/features/canvas/VideoComparisonDialog";
import { VideoEditDialog } from "../src/features/canvas/VideoEditDialog";
import { QueryClientProvider } from "@tanstack/react-query";
import { createQueryClient } from "../src/app/queryClient";
import type { CanvasItem } from "../src/shared/api/client";
import { setLocale } from "../src/shared/i18n";
import "../src/styles.css";

// All pixels and video frames are generated locally; no backend or provider is used.
const IMAGE_WIDTH = 1600;
const IMAGE_HEIGHT = 900;
const VIDEO_WIDTH = 320;
const VIDEO_HEIGHT = 180;
const FRAME_RATE = 10;
const VIDEO_DURATION_MS = 1200;
setLocale("zh");

function image(width: number, height: number): string {
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d")!;
  const gradient = context.createLinearGradient(0, 0, width, height);
  gradient.addColorStop(0, "#105e70");
  gradient.addColorStop(1, "#8dc4ab");
  context.fillStyle = gradient;
  context.fillRect(0, 0, width, height);
  context.strokeStyle = "#ffffff";
  context.lineWidth = 8;
  context.strokeRect(12, 12, width - 24, height - 24);
  context.fillStyle = "#ffffff";
  context.font = "48px sans-serif";
  context.fillText("Synthetic media", 60, 100);
  return canvas.toDataURL("image/png");
}

const imageUrl = image(IMAGE_WIDTH, IMAGE_HEIGHT);
const portraitUrl = image(IMAGE_HEIGHT, IMAGE_WIDTH);

async function video(): Promise<string> {
  const canvas = document.createElement("canvas");
  canvas.width = VIDEO_WIDTH;
  canvas.height = VIDEO_HEIGHT;
  const context = canvas.getContext("2d")!;
  const stream = canvas.captureStream(FRAME_RATE);
  const recorder = new MediaRecorder(stream, { mimeType: "video/webm;codecs=vp8" });
  const chunks: Blob[] = [];
  recorder.addEventListener("dataavailable", (event) => chunks.push(event.data));
  const complete = new Promise<Blob>((resolve) => {
    recorder.addEventListener("stop", () => resolve(new Blob(chunks, { type: "video/webm" })), { once: true });
  });
  let frame = 0;
  const draw = () => {
    context.fillStyle = frame++ % 2 ? "#105e70" : "#8dc4ab";
    context.fillRect(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT);
    context.fillStyle = "#ffffff";
    context.font = "20px sans-serif";
    context.fillText("Synthetic video", 20, 50);
  };
  draw();
  recorder.start();
  const interval = window.setInterval(draw, 1000 / FRAME_RATE);
  await new Promise((resolve) => window.setTimeout(resolve, VIDEO_DURATION_MS));
  window.clearInterval(interval);
  recorder.stop();
  const blob = await complete;
  stream.getTracks().forEach((track) => track.stop());
  return URL.createObjectURL(blob);
}

type Preview = "image" | "portrait" | "video" | "retry-image" | "retry-video";

function Fixture() {
  const [sourceVideo, setSourceVideo] = useState<string>();
  const [preview, setPreview] = useState<Preview>();
  const [deletions, setDeletions] = useState(0);
  const [comparing, setComparing] = useState(false);
  const [editing, setEditing] = useState(false);
  const [queryClient] = useState(createQueryClient);
  useEffect(() => {
    let disposed = false;
    let objectUrl: string | undefined;
    void video().then((url) => {
      objectUrl = url;
      if (disposed) URL.revokeObjectURL(url);
      else setSourceVideo(url);
    });
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Delete") setDeletions((count) => count + 1);
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      disposed = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, []);
  const isVideo = preview === "video" || preview === "retry-video";
  const isPortrait = preview === "portrait";
  const sourceUrl = preview === "retry-image" ? "/e2e/retry-image.png"
    : preview === "retry-video" ? "/e2e/retry-video.webm"
    : isVideo ? sourceVideo! : isPortrait ? portraitUrl : imageUrl;
  return <>
    <main id="fixture" data-image={imageUrl} data-video={sourceVideo} data-deletions={deletions}>
      <button id="image-open" onClick={() => setPreview("image")}>Open image</button>
      <button id="portrait-open" onClick={() => setPreview("portrait")}>Open portrait image</button>
      <button id="video-open" disabled={!sourceVideo} onClick={() => setPreview("video")}>Open video</button>
      <button id="retry-image-open" onClick={() => setPreview("retry-image")}>Open failing image</button>
      <button id="retry-video-open" disabled={!sourceVideo} onClick={() => setPreview("retry-video")}>Open failing video</button>
      <button id="compare-open" disabled={!sourceVideo} onClick={() => setComparing(true)}>Compare videos</button>
      <button id="edit-open" disabled={!sourceVideo} onClick={() => setEditing(true)}>Edit videos</button>
      <input aria-label="Background input" />
    </main>
    {preview && sourceVideo ? <MediaPreviewDialog kind={isVideo ? "video" : "image"}
      title={isVideo ? "Synthetic video" : "Synthetic image"} sourceUrl={sourceUrl}
      posterSrc={isVideo ? imageUrl : undefined} contentType={isVideo ? "video/webm" : "image/png"}
      width={isVideo ? VIDEO_WIDTH : isPortrait ? IMAGE_HEIGHT : IMAGE_WIDTH}
      height={isVideo ? VIDEO_HEIGHT : isPortrait ? IMAGE_WIDTH : IMAGE_HEIGHT}
      onClose={() => setPreview(undefined)} /> : null}
    {comparing ? <VideoComparisonDialog projectId="review" title="Synthetic comparison" selectedVersionId="v2" onClose={() => setComparing(false)}
      versions={[2, 1].map((number) => ({ nodeVersionNo: number, version: { id: `v${number}`, versionNo: number, schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: `asset-${number}` }, inputReferences: [], createdByKind: "USER", createdAt: "2026-10-22T00:00:00Z" } }))} /> : null}
    {editing ? <QueryClientProvider client={queryClient}><VideoEditDialog projectId="review" initialIds={["item-1", "item-2"]} onClose={() => setEditing(false)}
      items={[1, 2].map((number): CanvasItem => ({ id: `item-${number}`, subjectType: "ARTIFACT", subjectId: `artifact-${number}`, title: `Synthetic ${number}`,
        x: 0, y: 0, width: 640, height: 360, zIndex: number, locked: false, version: 1, selectedVersionId: `v${number}`,
        selectedVersion: { id: `v${number}`, versionNo: number, schemaVersion: 1, content: { sourceType: "UPLOAD", assetId: `asset-${number}` },
          inputReferences: [], createdByKind: "USER", createdAt: "2026-10-22T00:00:00Z" }, artifact: { id: `artifact-${number}`, projectId: "review", kind: "VIDEO",
          title: `Synthetic ${number}`, resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 1, createdAt: "2026-10-22T00:00:00Z", updatedAt: "2026-10-22T00:00:00Z" } }))} />
    </QueryClientProvider> : null}
  </>;
}

createRoot(document.getElementById("root")!).render(<Fixture />);
