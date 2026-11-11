import { DIRECTOR_CAPTURE_SIZES, parseDirectorScene, type DirectorScene } from "./directorScene";
import type { DirectorRenderer } from "./directorRenderer";

export const DIRECTOR_VIDEO_LIMITS = { maxEdge: 960, frameRate: 24, maxDuration: 120,
  bitsPerSecond: 2_000_000, maxBytes: 48 * 1024 * 1024, graceSeconds: 15 } as const;
export type DirectorVideoErrorCode = "UNSUPPORTED" | "CANCELLED" | "FAILED" | "LIMIT" | "HIDDEN";
export class DirectorVideoExportError extends Error {
  constructor(readonly code: DirectorVideoErrorCode) { super(`DIRECTOR_VIDEO_${code}`); }
}
const WEBM_TYPES = ["video/webm;codecs=vp9", "video/webm;codecs=vp8", "video/webm"] as const;

/** The filename and media type stay WebM; never label browser-recorded bytes as MP4. */
function webmType(): string {
  if (typeof MediaRecorder !== "function" || typeof MediaRecorder.isTypeSupported !== "function"
    || typeof HTMLCanvasElement.prototype.captureStream !== "function") throw new DirectorVideoExportError("UNSUPPORTED");
  const type = WEBM_TYPES.find((candidate) => MediaRecorder.isTypeSupported(candidate));
  if (!type) throw new DirectorVideoExportError("UNSUPPORTED");
  return type;
}

/** Real-time local recording owns a detached renderer, stream, recorder and clock for exactly one export.
 * No input scene, visible preview, server state or provider submission is changed by this operation. */
export async function exportDirectorVideo(input: DirectorScene, options: {
  signal: AbortSignal; onProgress: (percent: number) => void;
}): Promise<File> {
  const mimeType = webmType();
  const scene = parseDirectorScene(input);
  if (!scene || scene.motion.keyframes.length < 2 || scene.motion.durationSeconds > DIRECTOR_VIDEO_LIMITS.maxDuration)
    throw new DirectorVideoExportError("LIMIT");
  const checkAbort = () => { if (options.signal.aborted) throw new DirectorVideoExportError("CANCELLED"); };
  checkAbort();
  const { createDirectorRenderer } = await import("./directorRenderer");
  checkAbort();
  const size = DIRECTOR_CAPTURE_SIZES[scene.aspectRatio];
  const scale = DIRECTOR_VIDEO_LIMITS.maxEdge / Math.max(size.width, size.height);
  const width = Math.round(size.width * scale / 2) * 2, height = Math.round(size.height * scale / 2) * 2;
  const canvas = document.createElement("canvas");
  return new Promise<File>((resolve, reject) => {
    let renderer: DirectorRenderer | null = null, stream: MediaStream | null = null, recorder: MediaRecorder | null = null;
    let clock = 0, deadline = 0, finishTimer = 0, settled = false, completed = false, bytes = 0;
    let chunks: Blob[] = [];
    let lastPercent = -1;
    const progress = (percent: number) => { if (percent !== lastPercent) { lastPercent = percent; options.onProgress(percent); } };
    const cleanup = () => {
      cancelAnimationFrame(clock); window.clearTimeout(deadline); window.clearTimeout(finishTimer);
      options.signal.removeEventListener("abort", abort);
      document.removeEventListener("visibilitychange", visibility);
      if (recorder) {
        recorder.removeEventListener("dataavailable", data); recorder.removeEventListener("stop", stopped); recorder.removeEventListener("error", failed);
        if (recorder.state !== "inactive") { try { recorder.stop(); } catch { /* Already unavailable; remaining resources still belong to us. */ } }
      }
      stream?.getTracks().forEach((track) => { try { track.stop(); } catch { /* Release the other tracks as well. */ } });
      try { renderer?.dispose(); } catch { /* Encoding failure must still settle the export promise. */ }
      renderer = null; stream = null; recorder = null; chunks = [];
      canvas.width = 1; canvas.height = 1;
    };
    const fail = (code: DirectorVideoErrorCode) => {
      if (settled) return;
      settled = true; cleanup(); reject(new DirectorVideoExportError(code));
    };
    const abort = () => fail("CANCELLED");
    const visibility = () => { if (document.hidden) fail("HIDDEN"); };
    const failed = () => fail("FAILED");
    const data = (event: BlobEvent) => {
      if (settled || !event.data.size) return;
      bytes += event.data.size;
      if (bytes > DIRECTOR_VIDEO_LIMITS.maxBytes) { fail("LIMIT"); return; }
      chunks.push(event.data);
    };
    const stopped = () => {
      if (settled) return;
      if (!completed || !bytes) { fail("FAILED"); return; }
      const file = new File(chunks, "director-motion-reference.webm", { type: "video/webm" });
      settled = true; cleanup(); progress(100); resolve(file);
    };
    options.signal.addEventListener("abort", abort, { once: true });
    document.addEventListener("visibilitychange", visibility);
    try {
      checkAbort();
      if (document.hidden) { fail("HIDDEN"); return; }
      renderer = createDirectorRenderer(canvas, scene, { onCameraChange() {}, onUnavailable: failed });
      renderer.setDisabled(true);
      renderer.renderReferenceFrame(0, width, height);
      stream = canvas.captureStream(DIRECTOR_VIDEO_LIMITS.frameRate);
      if (!stream.getVideoTracks().length) { fail("UNSUPPORTED"); return; }
      recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: DIRECTOR_VIDEO_LIMITS.bitsPerSecond });
      recorder.addEventListener("dataavailable", data); recorder.addEventListener("stop", stopped); recorder.addEventListener("error", failed);
      recorder.start(250);
      progress(0);
      const start = performance.now();
      let lastFrame = start;
      const tick = (now: number) => {
        if (settled) return;
        const elapsed = (now - start) / 1000;
        if (elapsed >= scene.motion.durationSeconds || now - lastFrame >= 1000 / DIRECTOR_VIDEO_LIMITS.frameRate) {
          try { renderer?.renderReferenceFrame(Math.min(elapsed, scene.motion.durationSeconds), width, height); }
          catch { fail("FAILED"); return; }
          lastFrame = now;
          progress(Math.min(99, Math.floor(elapsed / scene.motion.durationSeconds * 100)));
        }
        if (elapsed >= scene.motion.durationSeconds) {
          completed = true;
          // Let the final canvas paint reach the encoder before requesting its final data chunk.
          finishTimer = window.setTimeout(() => {
            try { if (recorder?.state === "recording") recorder.stop(); else fail("FAILED"); }
            catch { fail("FAILED"); }
          }, 1000 / DIRECTOR_VIDEO_LIMITS.frameRate);
        } else clock = requestAnimationFrame(tick);
      };
      deadline = window.setTimeout(failed, (scene.motion.durationSeconds + DIRECTOR_VIDEO_LIMITS.graceSeconds) * 1000);
      clock = requestAnimationFrame(tick);
    } catch { fail(options.signal.aborted ? "CANCELLED" : "FAILED"); }
  });
}
