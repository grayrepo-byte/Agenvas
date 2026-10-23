import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, preview } from "vite";

/** Production CSS and real Chromium input; every API request is intercepted with synthetic data. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const profileDir = await mkdtemp(join(tmpdir(), "agenvas-characters-browser-"));
const outDir = join(profileDir, "build");
const pending = new Map();
let nextId = 0, socket, chrome, server, networkError, heldPreview;
const now = "2026-10-23T00:00:00Z";
const projectId = "characters-fixture";
const character = { id: "mira", projectId, version: 1, archived: false, createdAt: now, updatedAt: now,
  content: { name: "Mira", description: "A persistent synthetic character for layout verification.",
    looks: ["Travel", "Formal"].map((name, index) => ({ id: `look-${index}`, name, clothing: index ? "Dark suit" : "Blue coat", expression: "Calm", description: "A stable look with exact project references.", views: [{ versionId: `image-${index}`, purpose: index ? "Side view" : "Front view", description: "Synthetic reference" }] })),
    voice: { versionId: "audio-one", speakerId: "zh_female_xiaohe_uranus_bigtts", description: "Warm synthetic voice" } } };
let draft = { projectId, canvasItemId: "shot-one", version: 1, createdAt: now, updatedAt: now, prompt: "Mira walks through a quiet station.", parameters: {}, durationSeconds: 5, capabilityId: "synthetic-cap", videoInputMode: "TEXT", styleId: null, mediaInputs: [], mentions: [], displayMode: "DRAFT" };
let binding = null;
let applied = 0, reviewed = 0;
const snapshots = [{ characterId: "mira", characterVersion: 1, name: "Mira", description: character.content.description, look: character.content.looks[0], voice: character.content.voice, useAppearance: true, useVoice: false }];
const candidate = () => ({ ...draft, prompt: "Mira walks through a quiet station.\nMira: Blue coat, calm. Front view.", version: draft.version + 1, videoInputMode: "GENERAL_REFERENCE", mediaInputs: [{ versionId: "image-0", artifactId: "image-artifact", role: "REFERENCE", color: "#123456", order: 0, sources: [] }] });
function cdp(method, params = {}) { const id = ++nextId; return new Promise((resolve, reject) => { pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })); }); }
async function page(fn, ...args) { const result = await cdp("Runtime.evaluate", { expression: `(${fn.toString()})(...${JSON.stringify(args)})`, awaitPromise: true, returnByValue: true }); if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text); return result.result.value; }
async function waitFor(fn, ...args) { const until = Date.now() + 15000; while (Date.now() < until) { if (networkError) throw networkError; if (await page(fn, ...args)) return; await new Promise((resolve) => setTimeout(resolve, 50)); } throw new Error(`Timed out: ${fn.toString()}`); }
async function click(selector) { await page((selector) => document.querySelector(selector)?.scrollIntoView({ block: "nearest" }), selector); const point = await page((selector) => { const rect = document.querySelector(selector).getBoundingClientRect(); return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 }; }, selector); await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 }); await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 }); }
async function clickText(text) { const selector = await page((text) => { const button = [...document.querySelectorAll("[role=dialog] button")].find((button) => button.textContent.trim() === text); if (!button) throw new Error(`Missing ${text}`); button.dataset.browserTarget = "true"; return "[data-browser-target=true]"; }, text); await click(selector); await page(() => document.querySelector("[data-browser-target]")?.removeAttribute("data-browser-target")); }
async function key(key, code, windowsVirtualKeyCode, modifiers = 0) { for (const type of ["keyDown", "keyUp"]) await cdp("Input.dispatchKeyEvent", { type, key, code, windowsVirtualKeyCode, modifiers }); }
async function json(requestId, data) { return cdp("Fetch.fulfillRequest", { requestId, responseCode: 200, body: Buffer.from(JSON.stringify(data)).toString("base64"), responseHeaders: [{ name: "Content-Type", value: "application/json" }] }); }
async function respond({ requestId, request }) {
  const pathname = new URL(request.url).pathname;
  if (pathname.endsWith("/auth/csrf")) return json(requestId, { headerName: "X-XSRF-TOKEN", token: "synthetic" });
  if (pathname.endsWith("/characters")) return json(requestId, { characters: [character], bindings: binding ? [binding] : [] });
  if (pathname.endsWith("/canvas/items")) return json(requestId, { items: [{ id: "shot-one", title: "Station shot", selectedVersionId: "result-one" }] });
  if (pathname.endsWith("/canvas/connections")) return json(requestId, { items: [] });
  if (pathname.endsWith("/artifacts")) return json(requestId, { items: [{ id: "image-artifact", title: "Mira reference", kind: "IMAGE" }, { id: "audio-artifact", title: "Mira voice", kind: "AUDIO" }] });
  if (pathname.endsWith("image-artifact/versions")) return json(requestId, { items: [0, 1].map((index) => ({ id: `image-${index}`, versionNo: index + 1, content: { assetId: `image-asset-${index}` } })) });
  if (pathname.endsWith("audio-artifact/versions")) return json(requestId, { items: [{ id: "audio-one", versionNo: 1, content: { assetId: "audio-asset" } }] });
  if (pathname.includes("/assets/")) return cdp("Fetch.fulfillRequest", { requestId, responseCode: 200, body: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lzAAAAAASUVORK5CYII=", responseHeaders: [{ name: "Content-Type", value: "image/png" }] });
  if (pathname.endsWith("/media-draft")) return json(requestId, draft);
  if (pathname.endsWith("/character-bindings/preview")) { heldPreview = requestId; return; }
  if (pathname.endsWith("/character-bindings/apply")) { applied++; draft = candidate(); binding = { canvasItemId: "shot-one", version: 1, draftVersion: draft.version, selectedVersionId: "result-one", snapshots, needsReview: true, reviewReasons: ["NOT_REVIEWED"], contribution: { versionIds: ["image-0"], promptBlock: "Mira", speakerId: null }, reviewedAt: null }; return json(requestId, { binding, draft }); }
  if (pathname.endsWith("/character-bindings/review")) { reviewed++; binding = { ...binding, needsReview: false, reviewReasons: [], reviewedAt: now }; return json(requestId, binding); }
  throw new Error(`Unexpected synthetic request ${request.method} ${pathname}`);
}
async function layout() { const result = await page(() => { const dialog = document.querySelector("[role=dialog]"); const body = dialog.querySelector(".ui-dialog-body"); const footer = dialog.querySelector(".ui-dialog-footer"); return { width: dialog.clientWidth, scrollWidth: dialog.scrollWidth, bodyWidth: body.clientWidth, bodyScrollWidth: body.scrollWidth, footerBottom: footer.getBoundingClientRect().bottom, footerTop: footer.getBoundingClientRect().top, viewportHeight: innerHeight, bodyScroll: body.scrollHeight > body.clientHeight }; }); assert.ok(result.scrollWidth <= result.width + 1 && result.bodyScrollWidth <= result.bodyWidth + 1, `No horizontal overflow: ${JSON.stringify(result)}`); assert.ok(result.footerBottom <= result.viewportHeight && result.footerTop >= 0, `Footer visible: ${JSON.stringify(result)}`); return result; }
try {
  await build({ root, configFile: join(root, "vite.config.ts"), logLevel: "warn", build: { outDir, emptyOutDir: true, rollupOptions: { input: join(root, "e2e/characters.html") } } });
  server = await preview({ root, configFile: join(root, "vite.config.ts"), build: { outDir }, preview: { host: "127.0.0.1", port: 0, open: false, proxy: {} } });
  chrome = spawn(chromePath, ["--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-background-networking", "--remote-debugging-port=0", `--user-data-dir=${profileDir}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const endpoint = await new Promise((resolve, reject) => { const timer = setTimeout(() => reject(new Error("Chrome startup timed out")), 15000); chrome.once("error", reject); chrome.stderr.on("data", (data) => { const match = data.toString().match(/DevTools listening on (ws:\/\/\S+)/); if (match) { clearTimeout(timer); resolve(new URL(match[1]).origin.replace("ws:", "http:")); } }); });
  const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: "PUT" })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl); await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", (event) => { const message = JSON.parse(event.data); if (message.method === "Fetch.requestPaused") { void respond(message.params).catch((error) => { networkError = error; }); return; } const waiting = pending.get(message.id); if (!waiting) return; pending.delete(message.id); if (message.error) waiting.reject(new Error(message.error.message)); else waiting.resolve(message.result); });
  await cdp("Page.enable"); await cdp("Fetch.enable", { patterns: [{ urlPattern: "*/api/*", requestStage: "Request" }] });
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp("Page.navigate", { url: new URL("/e2e/characters.html", server.resolvedUrls.local[0]).href });
  await waitFor(() => Boolean(document.querySelector("#manager-open")));
  await click("#manager-open"); await waitFor(() => [...document.querySelectorAll("button")].some((button) => button.textContent === "Mira"));
  await clickText("Mira"); await waitFor(() => document.querySelectorAll("[role=dialog] input").length >= 8);
  assert.ok((await layout()).bodyScroll, "Multiple looks must scroll inside the modal");
  await page(() => { const body = document.querySelector(".ui-dialog-body"); body.scrollTop = body.scrollHeight; }); await layout();
  const screenshot = await cdp("Page.captureScreenshot", { format: "png" }); await writeFile("/tmp/agenvas-character-ui.png", Buffer.from(screenshot.data, "base64"));
  for (let index = 0; index < 12; index++) { await key("Tab", "Tab", 9, index > 6 ? 8 : 0); assert.ok(await page(() => document.querySelector("[role=dialog]").contains(document.activeElement)), "Focus remains inside character dialog"); }
  await key("Escape", "Escape", 27); await waitFor(() => !document.querySelector("[role=dialog]")); await waitFor(() => document.activeElement.id === "manager-open");
  await click("#roles-open"); await waitFor(() => Boolean(document.querySelector("[role=checkbox]"))); await click("[role=checkbox]");
  await clickText("预览实际草稿变化"); await waitFor(() => Boolean(document.querySelector("[role=dialog] button[aria-label='关闭窗口']")?.disabled)); for (let attempt = 0; !heldPreview && attempt < 100; attempt++) await new Promise((resolve) => setTimeout(resolve, 20));
  assert.ok(heldPreview, "Synthetic preview is waiting");
  await key("Escape", "Escape", 27); assert.ok(await page(() => Boolean(document.querySelector("[role=dialog]"))), "Busy preview cannot close");
  await json(heldPreview, { previewHash: "synthetic-preview", expectedBindingVersion: 0, expectedDraftVersion: 1, snapshots, candidateDraft: candidate(), changes: ["PROMPT_UPDATED", "REFERENCES_ADDED", "INPUT_MODE_CHANGED"], warnings: [] }); heldPreview = null;
  await waitFor(() => [...document.querySelectorAll("button")].some((button) => button.textContent === "明确应用到草稿" && !button.disabled)); await layout(); assert.equal(applied, 0); assert.equal(reviewed, 0);
  await clickText("明确应用到草稿"); await waitFor(() => document.body.textContent.includes("已应用到草稿，尚未生成")); assert.equal(applied, 1); assert.equal(reviewed, 0); assert.ok(await page(() => document.querySelector("#draft-prompt").textContent.includes("Blue coat")));
  await clickText("确认已检查当前结果"); await waitFor(() => document.body.textContent.includes("已记录对此版本结果的复核")); assert.equal(reviewed, 1);
  await key("Escape", "Escape", 27); await waitFor(() => !document.querySelector("[role=dialog]"));
  await new Promise((resolve) => setTimeout(resolve, 800));
  if (networkError) throw networkError;
  console.log("Characters browser passed: desktop form scroll/footer/overflow, modal focus/close, busy isolation, preview → draft apply → independent result review. Screenshot: /tmp/agenvas-character-ui.png");
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) { const exited = new Promise((resolve) => chrome.once("exit", resolve)); chrome.kill(); await exited; }
  if (server) await new Promise((resolve, reject) => server.httpServer.close((error) => error ? reject(error) : resolve()));
  await rm(profileDir, { recursive: true, force: true, maxRetries: 3 });
}
