import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

// Real Chrome gestures and computed focus styles, with local synthetic PCM audio.
const root = fileURLToPath(new URL("../", import.meta.url));
const profile = await mkdtemp(join(tmpdir(), "agenvas-audio-node-"));
const screenshots = await mkdtemp(join(tmpdir(), "agenvas-audio-trim-screenshots-"));
await chmod(screenshots, 0o700);
const trimRequests = [];
const server = await createServer({ root, configFile: join(root, "vite.config.ts"),
  server: { host: "127.0.0.1", port: 0, open: false, hmr: false } });
let chrome;
let socket;
let nextId = 0;
const pending = new Map();
function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function page(fn, ...args) {
  const result = await cdp("Runtime.evaluate", { expression: `(${fn.toString()})(...${JSON.stringify(args)})`,
    awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
}
async function waitFor(fn) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await page(fn)) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  const state = await page(() => ({ inputs: [...document.querySelectorAll('input[type="number"]')].map((input) => input.value),
    playback: window.__trimPlayback, audios: [...document.querySelectorAll("audio")].map((audio) => ({
      currentTime: audio.currentTime, paused: audio.paused, readyState: audio.readyState, duration: audio.duration })),
    errors: [...document.querySelectorAll('[role="alert"]')].map((alert) => alert.textContent) }));
  throw new Error(`Timed out: ${fn.toString()}; state=${JSON.stringify(state)}`);
}
async function point(selector) {
  return page((target) => {
    const rect = document.querySelector(target).getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  }, selector);
}
async function gesture(selector, dx = 0) {
  const start = await point(selector);
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...start });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...start, button: "left", clickCount: 1 });
  if (dx) {
    for (const fraction of [0.25, 0.5, 1]) await cdp("Input.dispatchMouseEvent", {
      type: "mouseMoved", x: start.x + dx * fraction, y: start.y, button: "left", buttons: 1,
    });
  }
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: start.x + dx, y: start.y,
    button: "left", clickCount: 1 });
}
async function button(text) {
  const target = await page((label) => {
    const element = [...document.querySelectorAll(".audio-trim-dialog button")].find((item) => item.textContent.trim() === label);
    if (!element) throw new Error(`Missing trim button: ${label}`);
    element.scrollIntoView({ block: "nearest" });
    const rect = element.getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  }, text);
  for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
    await cdp("Input.dispatchMouseEvent", { type, ...target, button: "left", clickCount: 1 });
  }
}
async function fill(selector, value) {
  await gesture(selector);
  await page((target) => document.querySelector(target).select(), selector);
  await cdp("Input.insertText", { text: value });
  for (const type of ["keyDown", "keyUp"]) await cdp("Input.dispatchKeyEvent", { type, key: "Tab", code: "Tab" });
}
async function assertTrimLayout(width, height) {
  await cdp("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: false });
  await page(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await waitFor(() => {
    const rect = document.querySelector(".audio-trim-dialog").getBoundingClientRect();
    return rect.left >= 0 && rect.right <= innerWidth && rect.top >= 0 && rect.bottom <= innerHeight;
  });
  const layout = await page(() => {
    const dialog = document.querySelector(".audio-trim-dialog");
    const bounds = dialog.getBoundingClientRect();
    const body = dialog.querySelector(".ui-dialog-body");
    const footer = dialog.querySelector(".ui-dialog-footer").getBoundingClientRect();
    const player = dialog.querySelector(".audio-player");
    const waveform = player.querySelector(".audio-player-waveform").getBoundingClientRect();
    body.scrollTop = 0;
    return { width: innerWidth, height: innerHeight, documentOverflow: document.documentElement.scrollWidth > innerWidth,
      dialogOverflow: dialog.scrollWidth > dialog.clientWidth, bodyOverflow: body.scrollWidth > body.clientWidth,
      left: bounds.left, right: bounds.right, top: bounds.top, bottom: bounds.bottom,
      footerTop: footer.top, footerBottom: footer.bottom,
      playerHeight: player.clientHeight, playerContentHeight: player.scrollHeight, waveformHeight: waveform.height };
  });
  assert.equal(layout.documentOverflow, false, `Document overflow at ${width}: ${JSON.stringify(layout)}`);
  assert.equal(layout.dialogOverflow, false, `Dialog overflow at ${width}: ${JSON.stringify(layout)}`);
  assert.equal(layout.bodyOverflow, false, `Trim body overflow at ${width}: ${JSON.stringify(layout)}`);
  assert.ok(layout.left >= 0 && layout.right <= width && layout.top >= 0 && layout.bottom <= height,
    `Trim dialog remains within viewport: ${JSON.stringify(layout)}`);
  assert.ok(layout.footerTop >= 0 && layout.footerBottom <= height, "Submit controls remain visible");
  assert.ok(layout.playerHeight >= layout.playerContentHeight - 1,
    `Playback controls and waveform must not collapse into a nested scroller: ${JSON.stringify(layout)}`);
  assert.ok(layout.waveformHeight >= 40, "The decoded waveform retains a useful seek surface");
  const { data } = await cdp("Page.captureScreenshot", { format: "png" });
  await writeFile(join(screenshots, `audio-trim-${width}x${height}.png`), Buffer.from(data, "base64"), { mode: 0o600 });
}
const position = () => page(() => document.querySelector(".react-flow__node").getBoundingClientRect().x);
const outline = () => page(() => getComputedStyle(document.querySelector(".audio-player-progress")).outlineStyle);

try {
  // Ten seconds of a sine wave; no provider, project credentials, or fixture binaries.
  const samples = 8000 * 10;
  const wav = Buffer.alloc(44 + samples * 2);
  wav.write("RIFF", 0); wav.writeUInt32LE(wav.length - 8, 4); wav.write("WAVEfmt ", 8);
  wav.writeUInt32LE(16, 16); wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(8000, 24); wav.writeUInt32LE(16000, 28); wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34); wav.write("data", 36); wav.writeUInt32LE(samples * 2, 40);
  for (let index = 0; index < samples; index++) wav.writeInt16LE(Math.round(Math.sin(index * Math.PI * 440 / 4000) * 8000), 44 + index * 2);
  await server.listen();
  chrome = spawn(process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    ["--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
      "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Chrome did not start")), 15000);
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
      const { request, requestId } = message.params;
      if (new URL(request.url).pathname.startsWith("/api/")) {
        const csrf = new URL(request.url).pathname === "/api/v1/auth/csrf";
        if (!csrf) trimRequests.push({ path: new URL(request.url).pathname, method: request.method,
          headers: request.headers, body: JSON.parse(request.postData) });
        void cdp("Fetch.fulfillRequest", { requestId, responseCode: 200,
          responseHeaders: [{ name: "Content-Type", value: "application/json" }],
          body: Buffer.from(JSON.stringify(csrf ? { headerName: "X-CSRF-TOKEN", token: "synthetic-csrf" }
            : { id: "00000000-0000-4000-8000-000000000305", status: "READY" })).toString("base64") });
        return;
      }
      const range = message.params.request.headers.Range ?? message.params.request.headers.range;
      const bounds = range?.match(/^bytes=(\d+)-(\d*)$/);
      const start = bounds ? Number(bounds[1]) : 0;
      const end = bounds?.[2] ? Math.min(Number(bounds[2]), wav.length - 1) : wav.length - 1;
      const bytes = wav.subarray(start, end + 1);
      void cdp("Fetch.fulfillRequest", { requestId: message.params.requestId, responseCode: bounds ? 206 : 200,
        responseHeaders: [{ name: "Content-Type", value: "audio/wav" },
          { name: "Accept-Ranges", value: "bytes" }, { name: "Content-Length", value: String(bytes.length) },
          ...(bounds ? [{ name: "Content-Range", value: `bytes ${start}-${end}/${wav.length}` }] : [])],
        body: bytes.toString("base64") });
      return;
    }
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Fetch.enable", { patterns: [{ urlPattern: "*/e2e/audio-node.wav", requestStage: "Request" },
    { urlPattern: "*/api/v1/*", requestStage: "Request" }] });
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1280, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp("Page.navigate", { url: `${server.resolvedUrls.local[0]}e2e/audio-node.html` });
  await waitFor(() => document.querySelector("audio")?.duration > 0);
  // Check focus separately so the original inner-border bug has its own red signal.
  await gesture(".audio-player-seek");
  assert.equal(await outline(), "none", "Pointer seeking must not draw an inner focus border");
  for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
    await cdp("Input.dispatchMouseEvent", { type, x: 8, y: 8, button: "left", clickCount: 1 });
  }
  await waitFor(() => !document.querySelector(".react-flow__node").classList.contains("selected"));
  const before = await position();
  await gesture(".audio-player-title");
  await waitFor(() => document.querySelector(".react-flow__node").classList.contains("selected"));
  await waitFor(() => document.querySelectorAll(".audio-player-waveform > span").length === 48);
  await gesture(".audio-player-title", 80);
  await waitFor(() => document.querySelector(".react-flow__node").getBoundingClientRect().x > 160);
  assert.ok(await position() > before + 40, "Dragging audio text must move the node");
  const moved = await position();
  await gesture(".audio-player-play");
  await waitFor(() => !document.querySelector("audio").paused);
  await gesture(".audio-player-play");
  await waitFor(() => document.querySelector("audio").paused);
  await gesture(".audio-player-seek", 50);
  const seekState = await page(() => ({ time: document.querySelector("audio").currentTime,
    value: document.querySelector(".audio-player-seek").value, duration: document.querySelector("audio").duration }));
  assert.ok(seekState.time > 5, `Waveform dragging must seek: ${JSON.stringify(seekState)}`);
  assert.equal(await position(), moved, "Player controls must not drag the node");
  assert.equal(await outline(), "none");
  // Shift+Tab from the range returns to Play; Tab then reaches the native range.
  for (const params of [{ key: "Tab", code: "Tab", modifiers: 8 }, { key: "Tab", code: "Tab" }]) {
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", ...params });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", ...params });
  }
  assert.equal(await page(() => document.activeElement.classList.contains("audio-player-seek")), true);
  assert.equal(await outline(), "solid", "Keyboard seeking retains a visible focus indicator");
  const time = await page(() => document.querySelector("audio").currentTime);
  await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "ArrowRight", code: "ArrowRight" });
  await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "ArrowRight", code: "ArrowRight" });
  assert.ok(await page(() => document.querySelector("audio").currentTime) > time);
  await gesture('[data-testid="audio-trim-open"]');
  await waitFor(() => document.querySelector(".audio-trim-dialog audio")?.duration === 10);
  assert.equal(await page(() => document.querySelector('.audio-trim-dialog button[data-state="on"]').textContent), "WAV",
    "WAV is the initial trim format");
  // Native audio seeks plus real pointer clicks exercise the 'current position' range shortcuts.
  await page(() => { document.querySelector(".audio-trim-dialog audio").currentTime = 2.125; });
  await button("将当前位置设为入点");
  await page(() => { document.querySelector(".audio-trim-dialog audio").currentTime = 3.875; });
  await button("将当前位置设为出点");
  assert.deepEqual(await page(() => [...document.querySelectorAll('.audio-trim-dialog input[type="number"]')].map((input) => input.value)),
    ["2.125", "3.875"]);
  await fill('.audio-trim-dialog input[id$="-start"]', "1.25");
  await fill('.audio-trim-dialog input[id$="-end"]', "1.35");
  assert.deepEqual(await page(() => [...document.querySelectorAll('.audio-trim-dialog input[type="number"]')].map((input) => input.value)),
    ["1.25", "1.35"], "Keyboard input replaces both time values");
  await page(() => {
    window.__trimPlayback = [];
    const audio = document.querySelector(".audio-trim-dialog audio");
    for (const type of ["playing", "pause"]) audio.addEventListener(type, () => window.__trimPlayback.push({ type, time: audio.currentTime }));
  });
  await button("试听所选片段");
  await waitFor(() => window.__trimPlayback.some((event) => event.type === "playing")
    && document.querySelector(".audio-trim-dialog audio").paused
    && document.querySelector(".audio-trim-dialog audio").currentTime >= 1.35);
  const preview = await page(() => ({ events: window.__trimPlayback,
    stoppedAt: document.querySelector(".audio-trim-dialog audio").currentTime }));
  assert.ok(preview.events.some((event) => event.type === "playing" && event.time >= 1.25 && event.time < 1.35),
    `Preview starts inside the selected 100 ms range: ${JSON.stringify(preview)}`);
  assert.equal(preview.stoppedAt, 1.35, "Even a 100 ms preview stops at the selected out point");
  await fill('.audio-trim-dialog input[id$="-start"]', "1.125");
  await fill('.audio-trim-dialog input[id$="-end"]', "3.875");
  assert.equal(trimRequests.length, 0, "Previewing must never create a task");
  await assertTrimLayout(1280, 900);
  await assertTrimLayout(390, 844);
  await assertTrimLayout(844, 390);
  await button("创建音频片段");
  await waitFor(() => !document.querySelector(".audio-trim-dialog"));
  assert.equal(trimRequests.length, 1, "Submit makes exactly one trim command");
  const submitted = trimRequests[0];
  assert.equal(submitted.path, "/api/v1/projects/00000000-0000-4000-8000-000000000301/artifacts/00000000-0000-4000-8000-000000000302/audio-trims");
  assert.equal(submitted.method, "POST");
  assert.deepEqual(submitted.body, { canvasItemId: "00000000-0000-4000-8000-000000000303",
    sourceVersionId: "00000000-0000-4000-8000-000000000304", expectedCanvasItemVersion: 7,
    startMs: 1125, endMs: 3875, format: "WAV" });
  const headers = Object.fromEntries(Object.entries(submitted.headers).map(([key, value]) => [key.toLowerCase(), value]));
  assert.equal(headers["x-csrf-token"], "synthetic-csrf");
  assert.match(headers["idempotency-key"], /^[0-9a-f-]{36}$/);
  console.log("PASS: audio selection, node dragging, playback, pointer seeking, keyboard focus/seek; trim range shortcuts, 100 ms preview stop, WAV command with pinned source/CAS, desktop, 390 px and short landscape layout");
  console.log(`Private screenshots: ${screenshots}`);
} finally {
  socket?.close();
  if (chrome && chrome.exitCode === null) {
    const exited = new Promise((resolve) => chrome.once("exit", resolve));
    chrome.kill();
    await exited;
  }
  await server.close();
  await rm(profile, { recursive: true, force: true });
}
