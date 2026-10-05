import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

// Real Chrome gestures and computed focus styles, with local synthetic PCM audio.
const root = fileURLToPath(new URL("../", import.meta.url));
const profile = await mkdtemp(join(tmpdir(), "agenvas-audio-node-"));
const server = await createServer({ root, configFile: join(root, "vite.config.ts"),
  server: { host: "127.0.0.1", port: 0, open: false } });
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
  throw new Error(`Timed out: ${fn.toString()}`);
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
  await cdp("Fetch.enable", { patterns: [{ urlPattern: "*/e2e/audio-node.wav", requestStage: "Request" }] });
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
  console.log("PASS: audio selection, node dragging, playback, pointer seeking, and keyboard focus/seek");
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
