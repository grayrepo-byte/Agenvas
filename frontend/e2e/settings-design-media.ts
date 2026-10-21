import { Buffer } from "node:buffer";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Plugin } from "vite";

/**
 * Development-server-only bytes for the synthetic studio fixture. Native img/video/audio
 * requests bypass window.fetch, so these exact synthetic namespaces must stop before the
 * API proxy. This module is imported by Vite config only: apply: "serve" excludes the
 * middleware from builds, and no fixture bytes enter the production client bundle.
 * All content is independently generated geometry/text or silence; no user/Provider media.
 */
const LIBRARY_MEDIA_PATH = /^\/api\/v1\/library\/entries\/(synthetic-(?:library|resource)-[^/]+)\/(content|thumbnail)\/?$/;
const PROJECT_MEDIA_PATH = /^\/api\/v1\/projects\/synthetic-[^/]+\/assets\/(synthetic-(?:library|resource)-[^/]+)\/(content|thumbnail)\/?$/;
const AUDIO_INDEXES = new Set([3, 4, 8]);
const VIDEO_INDEXES = new Set([5, 6]);
const IMAGE_INDEXES = new Set([0, 1, 2, 7]);

// 160×90, one-second H.264 Baseline/yuv420p clip, with only two geometric rectangles.
// Generated locally with FFmpeg lavfi color/drawbox, no input file, audio or external media.
const SYNTHETIC_VIDEO = "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAOLbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAld0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAKAAAABaAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAHPbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABem1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAATpzdGJsAAAAunN0c2QAAAAAAAAAAQAAAKphdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAKAAWgBIAAAASAAAAAAAAAABDExhdmMgbGlieDI2NAAAAAAAAAAAAAAAAAAAAAAAAAAAGP//AAAAMGF2Y0MBQsAK/+EAGGdCwArZAo35MBEAAAMAAQAAAwAEDxImSAEABWjLg8sgAAAAEHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAHNgAAAAAAAAAGHN0dHMAAAAAAAAAAQAAAAIAACAAAAAAFHN0c3MAAAAAAAAAAQAAAAEAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAIAAAABAAAAHHN0c3oAAAAAAAAAAAAAAAIAAAOEAAAAFwAAABRzdGNvAAAAAAAAAAEAAAO7AAAAwHVkdGEAAAC4bWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAACLaWxzdAAAADCpbmFtAAAAKGRhdGEAAAABAAAAAFN5bnRoZXRpYyBkZXNpZ24gcHJldmlldwAAAFOpY210AAAAS2RhdGEAAAABAAAAAEluZGVwZW5kZW50bHkgZ2VuZXJhdGVkIGdlb21ldHJ5OyBubyBQcm92aWRlciBvciB1c2VyIG1lZGlhAAAACGZyZWUAAAOjbWRhdAAAAnAGBf//bNxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjUgcjMyMjIgYjM1NjA1YSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjUgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0wIHJlZj0zIGRlYmxvY2s9MTowOjAgYW5hbHlzZT0weDE6MHgxMTEgbWU9aGV4IHN1Ym1lPTcgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MSBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTEgOHg4ZGN0PTAgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0zIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAga2V5aW50PTI1MCBrZXlpbnRfbWluPTIgc2NlbmVjdXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD00MCByYz1jcmYgbWJ0cmVlPTEgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjAwAIAAAAEMZYiEBTxGKAANuMcAAQMI4AAgBycnJycnJycnJ1/44fwVgk4AdJGYBhCAFBtqAQgGIE2/tRF8si+5gQvXaiFUshVc///BAu4HAAIAAKBzLAMgIPjsvwrlnUxLp11Ovp114//CgVSoA/GpfCzL/w8OgWcAOCZEAQUkoNtXitgfcCF1drZC65Amz+1siu4mCWuZgkL70zH50CuOAa1tbWZgqP5Uz1BLXT/h/wCCoFC8W+Ks66tbW1/D4/gqlWcW+KumWoJa+EcA/4KwRcDgAgBDGREdAEXv++iL5f4BKzpd9EKpf51LlOjr6ddTr6ddTr7xhD/oFWsAZX+ov34AxZXsv30wR130tdLXS10teAAAABNBmjgJeFzsFB+XrKP6P1derr0d";

function silentWave() {
  const sampleRate = 8000;
  const samples = sampleRate / 2;
  const pcmBytes = samples * 2;
  const bytes = Buffer.alloc(44 + pcmBytes);
  bytes.write("RIFF", 0);
  bytes.writeUInt32LE(36 + pcmBytes, 4);
  bytes.write("WAVEfmt ", 8);
  bytes.writeUInt32LE(16, 16);
  bytes.writeUInt16LE(1, 20);
  bytes.writeUInt16LE(1, 22);
  bytes.writeUInt32LE(sampleRate, 24);
  bytes.writeUInt32LE(sampleRate * 2, 28);
  bytes.writeUInt16LE(2, 32);
  bytes.writeUInt16LE(16, 34);
  bytes.write("data", 36);
  bytes.writeUInt32LE(pcmBytes, 40);
  return bytes;
}

function placeholder(index: number, kind: string) {
  // The label contains only a validated numeric index and a fixed media kind, never user text.
  return Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360">
  <rect width="640" height="360" fill="#20242c"/>
  <rect x="72" y="52" width="496" height="224" rx="8" fill="#2b3440" stroke="#526577"/>
  <circle cx="438" cy="112" r="22" fill="#8296a8"/>
  <path d="M100 248 244 108 350 206 410 152 540 248Z" fill="#647c91"/>
  <text x="72" y="312" fill="#d5dce4" font-family="sans-serif" font-size="16">SYNTHETIC ${kind} ${index} · DESIGN PREVIEW</text>
</svg>`, "utf8");
}

function sendProblem(response: ServerResponse, method: string, status: number, detail: string) {
  const bytes = Buffer.from(JSON.stringify({ status, code: "SYNTHETIC_PREVIEW_MEDIA", title: "Synthetic design preview", detail }));
  response.statusCode = status;
  response.setHeader("Content-Type", "application/problem+json");
  response.setHeader("Content-Length", bytes.length);
  response.end(method === "HEAD" ? undefined : bytes);
}

/** Single byte ranges let native media controls read metadata and seek without a real API. */
function sendMedia(request: IncomingMessage, response: ServerResponse, bytes: Buffer, type: string) {
  response.setHeader("Content-Type", type);
  response.setHeader("Accept-Ranges", "bytes");
  const range = request.headers.range;
  let start = 0;
  let end = bytes.length - 1;
  if (range) {
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);
    const suffix = match && !match[1] ? Number(match[2]) : null;
    start = match?.[1] ? Number(match[1]) : suffix !== null ? Math.max(0, bytes.length - suffix) : Number.NaN;
    end = match?.[1] && match[2] ? Math.min(Number(match[2]), end) : end;
    if (!match || (!match[1] && !match[2]) || suffix === 0 || (suffix !== null && !Number.isSafeInteger(suffix)) || !Number.isSafeInteger(start)
        || !Number.isSafeInteger(end) || start > end || start >= bytes.length) {
      response.setHeader("Content-Range", `bytes */${bytes.length}`);
      sendProblem(response, request.method ?? "GET", 416, "This synthetic preview supports one valid byte range at a time.");
      return;
    }
    response.statusCode = 206;
    response.setHeader("Content-Range", `bytes ${start}-${end}/${bytes.length}`);
  } else response.statusCode = 200;
  response.setHeader("Content-Length", end - start + 1);
  response.end(request.method === "HEAD" ? undefined : bytes.subarray(start, end + 1));
}

export function settingsDesignMediaPreview(): Plugin {
  return {
    name: "agenvas-synthetic-design-media",
    apply: "serve",
    configureServer(server) {
      const video = Buffer.from(SYNTHETIC_VIDEO, "base64");
      const audio = silentWave();
      // Register directly here, before Vite installs its proxy. Never register a post hook.
      server.middlewares.use((request, response, next) => {
        const pathname = request.url?.split("?")[0] ?? "/";
        const match = LIBRARY_MEDIA_PATH.exec(pathname) ?? PROJECT_MEDIA_PATH.exec(pathname);
        if (!match) { next(); return; }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Agenvas-Synthetic-Preview", "true");
        response.setHeader("X-Content-Type-Options", "nosniff");
        const method = request.method ?? "GET";
        if (method !== "GET" && method !== "HEAD") {
          response.setHeader("Allow", "GET, HEAD");
          sendProblem(response, method, 405, "Synthetic media preview is read-only.");
          return;
        }
        const syntheticId = match[1]!.replace(/^synthetic-resource-/, "");
        const number = /^(?:synthetic-library-)?(\d+)$/.exec(syntheticId)?.[1];
        const index = number === undefined ? Number.NaN : Number(number);
        const kind = AUDIO_INDEXES.has(index) ? "AUDIO" : VIDEO_INDEXES.has(index) ? "VIDEO" : IMAGE_INDEXES.has(index) ? "IMAGE" : null;
        if (!kind) {
          sendProblem(response, method, 404, "This synthetic fixture media ID is unsupported; no backend request was sent.");
          return;
        }
        if (match[2] === "thumbnail" || kind === "IMAGE") {
          response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
          sendMedia(request, response, placeholder(index, kind), "image/svg+xml");
        } else sendMedia(request, response, kind === "AUDIO" ? audio : video, kind === "AUDIO" ? "audio/wav" : "video/mp4");
      });
    },
  };
}
