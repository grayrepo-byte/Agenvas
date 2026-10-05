import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Production CSS, native playback and real keyboard/pointer checks with synthetic media. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME
  ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/media-preview.html";
const production = !process.argv.includes("--dev");
const timeoutMs = 15_000;
const pollMs = 50;
const tolerance = 1;
const viewports = [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-media-preview-browser-"));
const screenshots = await mkdtemp(join(tmpdir(), "agenvas-media-preview-screenshots-"));
await chmod(screenshots, 0o700);
const config = { root, configFile: join(root, "vite.config.ts") };
const outDir = join(profile, "build");
const dialog = ".media-preview-dialog";
const close = `${dialog} button[aria-label='关闭预览']`;
const zoomIn = `${dialog} button[aria-label='放大']`;
const zoomOut = `${dialog} button[aria-label='缩小']`;
const enterFullscreen = `${dialog} button[aria-label='进入全屏']`;
const exitFullscreen = `${dialog} button[aria-label='退出全屏']`;
const image = `${dialog} .yarl__slide_image`;
const video = `${dialog} video`;
let server;
let chrome;
let socket;
let nextId = 0;
let networkError;
let syntheticMedia;
const pending = new Map();
const attempts = new Map();
const pause = () => new Promise((resolve) => setTimeout(resolve, pollMs));

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
    awaitPromise: true, returnByValue: true,
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
    await pause();
  }
  throw new Error(`Timed out waiting for media preview: ${fn.toString()}`);
}

async function clickAt(point) {
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
}

async function pointerClick(selector) {
  await waitFor((target) => {
    const element = document.querySelector(target);
    if (!element || element.disabled) return false;
    const bounds = element.getBoundingClientRect();
    return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
  }, selector);
  await clickAt(await page((target) => {
    const bounds = document.querySelector(target).getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  }, selector));
}

async function key(key, code, windowsVirtualKeyCode, modifiers = 0) {
  await cdp("Input.dispatchKeyEvent", { type: "keyDown", key, code, windowsVirtualKeyCode, modifiers });
  await cdp("Input.dispatchKeyEvent", { type: "keyUp", key, code, windowsVirtualKeyCode, modifiers });
}

async function open(button, kind) {
  await pointerClick(button);
  await waitFor((selector) => Boolean(document.querySelector(selector)), dialog);
  await waitFor((selector, type) => {
    const element = document.querySelector(selector);
    return type === "image" ? element?.complete && element.naturalWidth > 0 : element?.readyState >= 2;
  }, kind === "image" ? image : video, kind);
  await waitFor((selector) => document.activeElement === document.querySelector(selector), close);
}

async function closed(button) {
  await waitFor((selector) => !document.querySelector(selector), dialog);
  await waitFor((selector) => document.activeElement === document.querySelector(selector), button);
}

async function layout(viewport, kind, ratio) {
  const result = await page((rootSelector, mediaSelector) => {
    const bounds = (element) => {
      const { x, y, right, bottom, width, height } = element.getBoundingClientRect();
      return { x, y, right, bottom, width, height };
    };
    const root = document.querySelector(rootSelector);
    return {
      media: bounds(document.querySelector(mediaSelector)),
      buttons: [...root.querySelectorAll(".yarl__toolbar button")].map((button) => ({
        label: button.getAttribute("aria-label"), bounds: bounds(button),
      })),
      controls: document.querySelector(mediaSelector).controls,
    };
  }, dialog, kind === "image" ? image : video);
  for (const bounds of [result.media, ...result.buttons.map((button) => button.bounds)]) {
    assert.ok(bounds.width > 0 && bounds.height > 0 && bounds.x >= -tolerance && bounds.y >= -tolerance
      && bounds.right <= viewport.width + tolerance && bounds.bottom <= viewport.height + tolerance,
    `Media and toolbar must fit ${JSON.stringify(viewport)}: ${JSON.stringify(result)}`);
  }
  assert.ok(Math.abs(result.media.width / result.media.height - ratio) < 0.02,
    `The complete ${kind} must retain its aspect ratio`);
  if (kind === "video") assert.ok(result.controls, "Native playback controls must be available");
  return result.media;
}

async function keyboardIsolation() {
  for (let step = 0; step < 12; step++) {
    await key("Tab", "Tab", 9, step < 6 ? 0 : 8);
    assert.ok(await page((selector) => document.querySelector(selector).contains(document.activeElement), dialog),
      "Tab and Shift+Tab must stay in the preview");
  }
  await key("Delete", "Delete", 46);
  assert.equal(await page(() => document.querySelector("#fixture").dataset.deletions), "0",
    "Preview keyboard events must not reach the background canvas deletion handler");
}

async function screenshot(name) {
  const { data } = await cdp("Page.captureScreenshot", { format: "png" });
  const path = join(screenshots, `${name}.png`);
  await writeFile(path, Buffer.from(data, "base64"), { mode: 0o600 });
}

async function respondToSyntheticFailure({ requestId, request }) {
  const pathname = new URL(request.url).pathname;
  const attempt = (attempts.get(pathname) ?? 0) + 1;
  attempts.set(pathname, attempt);
  const isImage = pathname.endsWith(".png");
  const body = attempt === 1 ? Buffer.from("Synthetic first load failure").toString("base64")
    : syntheticMedia[isImage ? "image" : "video"];
  await cdp("Fetch.fulfillRequest", {
    requestId, responseCode: attempt === 1 ? 500 : 200, body,
    responseHeaders: [
      { name: "Content-Type", value: isImage ? "image/png" : "video/webm" },
      { name: "Cache-Control", value: "no-store" },
    ],
  });
}

try {
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
      void respondToSyntheticFailure(message.params).catch((error) => { networkError = error; });
      return;
    }
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Fetch.enable", { patterns: [{ urlPattern: "*/e2e/retry-*", requestStage: "Request" }] });
  const baseUrl = server.resolvedUrls.local[0];
  for (const viewport of viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(route, baseUrl).href });
    await waitFor(() => Boolean(document.querySelector("#fixture")?.dataset.video));
    syntheticMedia = await page(async () => {
      const fixture = document.querySelector("#fixture");
      const blob = await (await fetch(fixture.dataset.video)).blob();
      const dataUrl = await new Promise((resolve) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.readAsDataURL(blob);
      });
      return { image: fixture.dataset.image.split(",")[1], video: dataUrl.split(",")[1] };
    });
    await open("#image-open", "image");
    const before = await layout(viewport, "image", 16 / 9);
    await screenshot(`image-${viewport.width}x${viewport.height}`);
    await keyboardIsolation();
    await pointerClick(zoomIn);
    await waitFor((selector, width) => document.querySelector(selector).getBoundingClientRect().width > width * 1.05,
      image, before.width);
    await pointerClick(zoomOut);
    await waitFor((selector, width) => Math.abs(document.querySelector(selector).getBoundingClientRect().width - width) <= 1,
      image, before.width);
    if (viewport.width === viewports[0].width) {
      await pointerClick(enterFullscreen);
      await waitFor(() => Boolean(document.fullscreenElement));
      await pointerClick(exitFullscreen);
      await waitFor(() => !document.fullscreenElement);
    }
    await pointerClick(close);
    await closed("#image-open");
    await open("#portrait-open", "image");
    await layout(viewport, "image", 9 / 16);
    await key("Escape", "Escape", 27);
    await closed("#portrait-open");
    await open("#image-open", "image");
    await clickAt({ x: 8, y: viewport.height / 2 });
    await closed("#image-open");

    await open("#video-open", "video");
    await layout(viewport, "video", 16 / 9);
    assert.ok(await page((selector) => document.querySelector(selector).paused, video), "Video must initially be paused");
    await screenshot(`video-${viewport.width}x${viewport.height}`);
    await keyboardIsolation();
    // Space on the focused native player exercises a genuine user playback gesture.
    await page((selector) => document.querySelector(selector).focus(), video);
    await key(" ", "Space", 32);
    await waitFor((selector) => !document.querySelector(selector).paused && document.querySelector(selector).currentTime > 0,
      video);
    await page((selector) => { window.closedPreviewVideo = document.querySelector(selector); }, video);
    await pointerClick(close);
    await closed("#video-open");
    assert.ok(await page(() => !window.closedPreviewVideo.isConnected && window.closedPreviewVideo.paused),
      "Closing the preview must remove and stop the native player");
    console.log(`Media preview passed at ${viewport.width}x${viewport.height}`);
  }
  for (const kind of ["image", "video"]) {
    await pointerClick(`#retry-${kind}-open`);
    await waitFor(() => Boolean(document.querySelector(".media-preview-error[role='alert']")));
    await pointerClick(".media-preview-retry");
    await waitFor((selector, type) => {
      const element = document.querySelector(selector);
      return type === "image" ? element?.complete && element.naturalWidth > 0 : element?.readyState >= 2;
    }, kind === "image" ? image : video, kind);
    assert.equal(await page(() => document.querySelector(".media-preview-error[role='alert']")), null,
      `Retry must recover the failed ${kind}`);
    assert.ok(attempts.get(`/e2e/retry-${kind}.${kind === "image" ? "png" : "webm"}`) >= 2,
      "Retry must issue a new media request");
    await pointerClick(close);
    await closed(`#retry-${kind}-open`);
  }
  console.log(`Media preview browser checks passed (3 viewports, image/portrait/video, zoom, fullscreen, keyboard, playback and retry). Private screenshots: ${screenshots}`);
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
