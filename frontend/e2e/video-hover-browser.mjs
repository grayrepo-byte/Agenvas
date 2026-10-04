import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Native Chrome playback, a fresh profile and real pointer gestures; no provider or media mocks. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME
  ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const production = !process.argv.includes("--dev");
const route = "/e2e/video-hover.html";
const timeoutMs = 15_000;
const profile = await mkdtemp(join(tmpdir(), "agenvas-video-hover-browser-"));
const outDir = join(profile, "build");
const config = { root, configFile: join(root, "vite.config.ts") };
const attempts = new Map();
const pending = new Map();
let nextId = 0;
let server;
let chrome;
let socket;
let networkError;
let media;

function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}

async function page(fn, ...args) {
  const result = await cdp("Runtime.evaluate", {
    expression: `(${fn.toString()})(...${JSON.stringify(args)})`,
    awaitPromise: true, returnByValue: true, userGesture: false,
  });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description
    ?? result.exceptionDetails.text);
  return result.result.value;
}

async function waitFor(fn, ...args) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (networkError) throw networkError;
    if (await page(fn, ...args)) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Timed out waiting for video hover: ${fn.toString()}`);
}

async function moveTo(selector) {
  const point = selector ? await page((target) => {
    const bounds = document.querySelector(target).getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 3 };
  }, selector) : { x: 8, y: 8 };
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...point });
}

async function click(selector) {
  const point = await page((target) => {
    const bounds = document.querySelector(target).getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  }, selector);
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...point });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
}

async function fulfillMedia({ requestId, request }) {
  const pathname = new URL(request.url).pathname;
  const attempt = (attempts.get(pathname) ?? 0) + 1;
  attempts.set(pathname, attempt);
  // HTTP succeeds but invalid bytes produce a genuine native media/decode error.
  const damaged = pathname.endsWith("retry.mp4") && attempt === 1;
  await cdp("Fetch.fulfillRequest", {
    requestId, responseCode: 200,
    body: damaged ? Buffer.from("Synthetic damaged video").toString("base64") : media,
    responseHeaders: [
      { name: "Content-Type", value: "video/mp4" },
      { name: "Cache-Control", value: "no-store" },
    ],
  });
}

try {
  const source = join(profile, "sound.mp4");
  const encoding = spawnSync(process.env.AGENVAS_E2E_FFMPEG ?? "ffmpeg", [
    "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "color=c=teal:s=320x180:r=10",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "8",
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", source,
  ]);
  assert.equal(encoding.status, 0,
    `Existing ffmpeg is required to generate local video with real sound: ${encoding.error?.message ?? encoding.stderr?.toString()}`);
  media = (await readFile(source)).toString("base64");
  if (production) {
    await build({ ...config, logLevel: "warn", build: {
      outDir, emptyOutDir: true, rollupOptions: { input: join(root, route) },
    } });
    server = await preview({ ...config, build: { outDir },
      preview: { host: "127.0.0.1", port: 0, open: false },
    });
  } else {
    server = await createServer({ ...config, server: { host: "127.0.0.1", port: 0, open: false } });
    await server.listen();
  }
  chrome = spawn(chromePath, ["--headless=new", "--no-first-run", "--no-default-browser-check",
    "--disable-background-networking", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"],
  { stdio: ["ignore", "ignore", "pipe"] });
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Chrome did not start")), timeoutMs);
    chrome.once("error", (error) => { clearTimeout(timer); reject(error); });
    chrome.stderr.on("data", (data) => {
      const match = data.toString().match(/DevTools listening on (ws:\/\/\S+)/);
      if (match) { clearTimeout(timer); resolve(new URL(match[1]).origin.replace("ws:", "http:")); }
    });
  });
  const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: "PUT" })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.method === "Fetch.requestPaused") {
      void fulfillMedia(message.params).catch((error) => { networkError = error; });
      return;
    }
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Fetch.enable", { patterns: [{ urlPattern: "*/e2e/video-hover-*.mp4", requestStage: "Request" }] });
  await cdp("Page.addScriptToEvaluateOnNewDocument", { source: `
    window.nativePlaybackRejections = [];
    const nativePlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function(...args) {
      // Observe actual native rejections without changing playback or permissions.
      return nativePlay.apply(this, args).catch(error => {
        window.nativePlaybackRejections.push({ name: error.name, mediaError: this.error?.code ?? null });
        throw error;
      });
    };
  ` });
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1000, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp("Page.navigate", { url: new URL(route, server.resolvedUrls.local[0]).href });
  await waitFor(() => Boolean(document.querySelector("#valid-preview img")));
  assert.equal(await page(() => navigator.userActivation.hasBeenActive), false,
    "Initial hover must run before any genuine click in a fresh Chrome profile");
  await moveTo("#valid-preview");
  await waitFor(() => document.querySelector("#valid-preview video")?.currentTime > 0
    || document.querySelector("#valid-preview [role='alert']"));
  const initial = await page(() => ({
    alert: document.querySelector("#valid-preview [role='alert']")?.textContent ?? null,
    muted: document.querySelector("#valid-preview video")?.muted,
    rejections: window.nativePlaybackRejections,
    userActivation: navigator.userActivation.hasBeenActive,
  }));
  console.log(`Initial native hover: ${JSON.stringify(initial)}`);
  assert.equal(initial.alert, null, "Initial hover must not falsely report video playback failure");
  assert.equal(initial.muted, true, "Hover must start muted before the user allows sound");
  assert.equal(initial.userActivation, false, "Hover must not manufacture a playback gesture");
  await click("#valid-preview button[aria-label='开启视频声音']");
  await waitFor(() => {
    const video = document.querySelector("#valid-preview video");
    return video && !video.muted && !video.paused && video.currentTime > 0;
  });
  assert.equal(await page(() => document.querySelector("#fixture").dataset.selections), "0",
    "The sound control must not select the surrounding canvas node");
  await moveTo(null);
  await waitFor(() => document.querySelector("#valid-preview video").paused);
  const pausedAt = await page(() => document.querySelector("#valid-preview video").currentTime);
  await moveTo("#valid-preview");
  await waitFor((time) => {
    const video = document.querySelector("#valid-preview video");
    return !video.paused && !video.muted && video.currentTime > time + 0.1;
  }, pausedAt);
  assert.equal(await page(() => document.querySelector("#valid-preview [role='alert']")), null,
    "Hover after a real click must resume playback with the user's sound preference");
  await moveTo(null);
  await click("#page-gesture");
  await moveTo("#retry-preview");
  await waitFor(() => Boolean(document.querySelector("#retry-preview [role='alert']")));
  assert.ok(await page(() => window.nativePlaybackRejections.some((error) => error.mediaError > 0)),
    "Damaged bytes must produce a native media error, not an autoplay-permission rejection");
  await click("#retry-preview .media-playback-error button");
  await waitFor(() => document.querySelector("#retry-preview video")?.currentTime > 0);
  assert.equal(await page(() => document.querySelector("#retry-preview [role='alert']")), null,
    "Retry must recover a real damaged-media failure");
  assert.ok(attempts.get("/e2e/video-hover-retry.mp4") >= 2, "Retry must make a new media request");
  console.log("Video hover browser checks passed (fresh-profile native autoplay, sound gesture, pause/resume, damaged media and retry).");
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) {
    const exited = new Promise((resolve) => chrome.once("exit", resolve));
    chrome.kill();
    await exited;
  }
  if (server?.close) await server.close();
  else if (server) await new Promise((resolve, reject) => server.httpServer.close((error) => error ? reject(error) : resolve()));
  await rm(profile, { recursive: true, force: true });
}
