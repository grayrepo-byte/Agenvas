import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

// Real ProjectWorkspace, native Chromium gestures and production CSS; synthetic API only.
const root = fileURLToPath(new URL("../", import.meta.url));
const profile = await mkdtemp(join(tmpdir(), "agenvas-prompt-browser-"));
const screenshots = await mkdtemp(join(tmpdir(), "agenvas-prompt-proof-"));
await chmod(profile, 0o700); await chmod(screenshots, 0o700);
const server = await createServer({ root, configFile: join(root, "vite.config.ts"),
  server: { host: "127.0.0.1", port: 0, open: false, hmr: false } });
let chrome, socket, nextId = 0;
const pending = new Map(); const runtimeErrors = [];
function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => { pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })); });
}
async function page(fn, ...args) {
  const result = await cdp("Runtime.evaluate", { expression: `(${fn.toString()})(...${JSON.stringify(args)})`, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
}
async function waitFor(fn, ...args) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await page(fn, ...args)) return;
    await new Promise((resolve) => setTimeout(resolve, 60));
  }
  throw new Error(`Timed out: ${fn.toString()}`);
}
const card = (index) => `.react-flow__node[data-id="synthetic-card-${index}"]`;
async function point(selector) { return page((target) => {
  const node = document.querySelector(target); if (!node) throw new Error(`Missing ${target}`);
  const rect = node.getBoundingClientRect(); return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
}, selector); }
async function at(location, clicks = 1) {
  for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) await cdp("Input.dispatchMouseEvent", {
    type, ...location, button: "left", clickCount: clicks });
}
async function click(selector, clicks = 1) { await at(await point(selector), clicks); }
async function named(text) {
  await waitFor((label) => [...document.querySelectorAll("button")].some((node) =>
    (node.textContent.trim() === label || node.getAttribute("aria-label") === label) && node.getBoundingClientRect().width > 0), text);
  const selector = await page((label) => {
    document.querySelectorAll("[data-prompt-browser]").forEach((node) => node.removeAttribute("data-prompt-browser"));
    const node = [...document.querySelectorAll("button")].find((node) => (node.textContent.trim() === label || node.getAttribute("aria-label") === label) && node.getBoundingClientRect().width > 0);
    if (!node) throw new Error(`Missing button: ${label}`);
    node.setAttribute("data-prompt-browser", ""); return "[data-prompt-browser]";
  }, text); await click(selector);
}
async function key(key, code, modifiers = 0, commands) {
  for (const type of ["keyDown", "keyUp"]) await cdp("Input.dispatchKeyEvent", { type, key, code, modifiers,
    ...(type === "keyDown" && commands ? { commands } : {}) });
}
async function setText(selector, value) {
  await click(selector); await key("a", "KeyA", 4, ["selectAll"]); await cdp("Input.insertText", { text: value });
}
async function evidence() { return page(() => window.__agenvasCanvasEditingFixture.evidence()); }
async function shot(name) {
  const result = await cdp("Page.captureScreenshot", { format: "png" });
  await writeFile(join(screenshots, `${name}.png`), Buffer.from(result.data, "base64"), { mode: 0o600 });
}
async function connect(sourceIndex, targetIndex) {
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...await point(`${card(sourceIndex)} .artifact-card-title`) });
  const start = await point(`${card(sourceIndex)} .canvas-handle--out`);
  const end = await point(`${card(targetIndex)} .artifact-card-title`);
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...start });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...start, button: "left", clickCount: 1 });
  for (let step = 1; step <= 10; step++) await cdp("Input.dispatchMouseEvent", { type: "mouseMoved",
    x: start.x + (end.x - start.x) * step / 10, y: start.y + (end.y - start.y) * step / 10, button: "left", buttons: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...end, button: "left", clickCount: 1 });
}
try {
  await server.listen();
  chrome = spawn(process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", ["--headless=new", "--no-first-run",
    "--no-default-browser-check", "--disable-background-networking", `--user-data-dir=${profile}`, "--remote-debugging-port=0", "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Chrome startup timed out")), 20000);
    chrome.once("error", reject); chrome.stderr.on("data", (data) => {
      const match = data.toString().match(/DevTools listening on (ws:\/\/\S+)/);
      if (match) { clearTimeout(timer); resolve(new URL(match[1]).origin.replace("ws:", "http:")); }
    });
  });
  const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: "PUT" })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", ({ data }) => {
    const message = JSON.parse(data);
    if (message.method === "Runtime.exceptionThrown") runtimeErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id); if (!waiting) return; pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message)); else waiting.resolve(message.result);
  });
  await cdp("Page.enable"); await cdp("Runtime.enable");
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  const url = new URL("/e2e/canvas-editing.html?prompt-links=1", server.resolvedUrls.local[0]).href;
  await cdp("Page.navigate", { url });
  await waitFor(() => document.querySelectorAll(".react-flow__node-canvasCard").length === 5);
  for (const [index, targetIndex] of [2, 3, 4].entries()) {
    await connect(0, targetIndex);
    await waitFor((count) => window.__agenvasCanvasEditingFixture.evidence().connections.length === count, index + 1);
  }
  await connect(1, 2);
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().connections.length === 4);
  assert((await evidence()).connections.every((edge) => edge.relationType === "PROMPT_INPUT"));
  assert((await evidence()).mediaDrafts.every((draft) => draft.mediaInputs.length === 0));
  await click(`${card(0)} .content-card-text`, 2);
  await waitFor(() => Boolean(document.querySelector(".text-card-editor textarea")));
  await setText(".text-card-editor textarea", "Common revised"); await named("保存新版本");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().textSaves.length === 1);
  await click(`${card(2)} .artifact-card-title-icon`);
  const prompt = '[role="textbox"][aria-label="图片提示词"]';
  await waitFor((selector) => Boolean(document.querySelector(selector)), prompt);
  await setText(prompt, "Local subject");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().draftSaves.some((draft) => draft.prompt === "Local subject"));
  await named("公共提示词 · 2");
  await waitFor(() => document.querySelector('[aria-label="合并预览"]')?.textContent === "Common revised\n\nSecondary tone\n\nLocal subject");
  await shot("combined-preview"); await key("Escape", "Escape"); await named("运行");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().generationRequests.length === 1);
  const request = (await evidence()).generationRequests[0].body;
  assert.equal(request.canvasItemId, "synthetic-card-2"); assert.equal(Object.hasOwn(request, "prompt"), false);
  await cdp("Page.reload");
  await waitFor(() => document.querySelectorAll(".react-flow__node-canvasCard").length === 5
    && window.__agenvasCanvasEditingFixture?.evidence().connections.length === 4);
  await click(`${card(2)} .artifact-card-title-icon`); await named("公共提示词 · 2");
  await waitFor(() => document.querySelector('[aria-label="合并预览"]')?.textContent === "Common revised\n\nSecondary tone\n\nLocal subject");
  await key("Escape", "Escape");
  const edge = (await evidence()).connections.find((edge) => edge.sourceCanvasItemId === "synthetic-card-0" && edge.targetCanvasItemId === "synthetic-card-2");
  // The image/video edges overlap on this synthetic layout. Focus the exact edge,
  // then use its native Enter/Backspace keyboard path instead of an ambiguous hit.
  await page((id) => document.querySelector(`.react-flow__edge[data-id="canvas-connection:${id}"]`).focus(), edge.id);
  await key("Enter", "Enter"); await key("Backspace", "Backspace");
  await waitFor((id) => !window.__agenvasCanvasEditingFixture.evidence().connections.some((edge) => edge.id === id), edge.id);
  await click(`${card(2)} .artifact-card-title-icon`); await named("公共提示词 · 1");
  await waitFor(() => document.querySelector('[aria-label="合并预览"]')?.textContent === "Secondary tone\n\nLocal subject");
  await shot("after-disconnect");
  await key("Escape", "Escape");
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1024, height: 768, deviceScaleFactor: 1, mobile: false });
  await named("公共提示词 · 1");
  await waitFor(() => { const r = document.querySelector(".canvas-prompt-inputs")?.getBoundingClientRect(); return r && r.left >= 0 && r.right <= innerWidth && r.height <= innerHeight; });
  await shot("minimum-width-preview");
  assert.deepEqual(runtimeErrors, []); assert.deepEqual((await evidence()).unexpected, []);
  console.log(`Shared prompt browser passed: image/video/audio drag connections, multiple sources, saved text edit, local edit, run request, reload, keyboard disconnect, 1024px preview. Synthetic screenshots: ${screenshots}`);
} catch (error) {
  if (socket) {
    await shot("failure");
    console.error(await page(() => ({ buttons: [...document.querySelectorAll("button")].filter((node) => node.getBoundingClientRect().width > 0)
      .map((node) => node.getAttribute("aria-label") || node.textContent.trim()),
      selected: [...document.querySelectorAll(".react-flow__node.selected")].map((node) => node.getAttribute("data-id")),
      preview: document.querySelector(".canvas-prompt-inputs")?.getBoundingClientRect().toJSON(), viewport: { width: innerWidth, height: innerHeight },
      connections: window.__agenvasCanvasEditingFixture?.evidence().connections })));
  }
  throw error;
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) { const exited = new Promise((resolve) => chrome.once("exit", resolve)); chrome.kill(); await exited; }
  await server.close(); await rm(profile, { recursive: true, force: true });
}
