import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Real workspace integration/layout evidence; synthetic data, no backend or Provider. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME
  ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/agent-workspace.html";
const production = !process.argv.includes("--dev");
const timeoutMs = 15_000;
const viewports = [
  { width: 1440, height: 900 }, { width: 1280, height: 720 },
  { width: 390, height: 844 }, { width: 844, height: 390 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-agent-workspace-"));
const proofDir = process.env.AGENVAS_AGENT_WORKSPACE_PROOF ?? "/tmp/agenvas-agent-chat-proof";
const config = { root, configFile: join(root, "vite.config.ts") };
const outDir = join(profile, "build");
const active = ".agent-chat-dock-agent:not([hidden])";
const composer = `${active} .agent-chat-composer textarea`;
let server;
let chrome;
let socket;
let nextId = 0;
const pending = new Map();
const pause = () => new Promise((resolve) => setTimeout(resolve, 50));

function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function page(fn, ...args) {
  const result = await cdp("Runtime.evaluate", {
    expression: `(${fn.toString()})(...${JSON.stringify(args)})`, awaitPromise: true, returnByValue: true,
  });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description
    ?? result.exceptionDetails.text);
  return result.result.value;
}
async function waitFor(fn, ...args) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await page(fn, ...args)) return;
    await pause();
  }
  throw new Error(`Timed out waiting for workspace: ${await page(() => document.body.innerText)}`);
}
async function click(selector) {
  const point = await page((target) => {
    const element = document.querySelector(target);
    if (!element) throw new Error(`Missing click target: ${target}`);
    const rect = element.getBoundingClientRect();
    const x = rect.left + rect.width / 2, y = rect.top + rect.height / 2;
    const hit = document.elementFromPoint(x, y);
    return { x, y, reachable: hit === element || element.contains(hit), hit: hit?.outerHTML.slice(0, 240) };
  }, selector);
  assert.ok(point.reachable, `Workspace control is pointer reachable: ${selector}: ${JSON.stringify(point)}`);
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", x: point.x, y: point.y });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", x: point.x, y: point.y, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: point.x, y: point.y, button: "left", clickCount: 1 });
}
async function typeDraft(text) {
  await click(composer);
  await cdp("Input.insertText", { text });
  await waitFor((selector, expected) => document.querySelector(selector)?.value === expected, composer, text);
}
async function switchAgent(id) {
  await click(`${active} .workspace-agent-controls [role="combobox"]`);
  await waitFor((value) => Boolean(document.querySelector(`[role="option"][data-value="${value}"]`)), id);
  await click(`[role="option"][data-value="${id}"]`);
  await waitFor((selector) => Boolean(document.querySelector(selector)) && !document.querySelector(selector).disabled, composer);
  await waitFor((value, selector) => document.querySelector(`${selector} .workspace-agent-controls [role="combobox"]`)?.getAttribute("value") === value,
    id, active);
}
function inspectLayout(activeSelector) {
  const dialog = document.querySelector(".agent-chat-dock-panel");
  const panel = document.querySelector(activeSelector);
  const rect = (element) => {
    const box = element.getBoundingClientRect();
    return { left: box.left, top: box.top, right: box.right, bottom: box.bottom, width: box.width, height: box.height };
  };
  const input = panel.querySelector(".agent-chat-composer textarea");
  const inputRect = input.getBoundingClientRect();
  const hit = document.elementFromPoint(inputRect.left + inputRect.width / 2, inputRect.top + inputRect.height / 2);
  const controls = [...panel.querySelectorAll(".agent-chat-header button")]
    .filter((element) => element.getBoundingClientRect().width > 0).map((element) => ({
      label: element.getAttribute("aria-label"), ...rect(element),
    }));
  let opacity = 1;
  for (let element = dialog; element; element = element.parentElement) opacity *= Number(getComputedStyle(element).opacity);
  return { viewport: { width: innerWidth, height: innerHeight }, dialog: rect(dialog),
    composer: rect(panel.querySelector(".agent-chat-composer")), input: rect(input),
    bodyHeight: panel.querySelector('[data-slot="message-scroller-viewport"]').clientHeight,
    inputReachable: hit === input || input.contains(hit), controls, opacity,
    warningDisplay: getComputedStyle(document.querySelector(".workspace-narrow-warning")).display,
    overflow: document.documentElement.scrollWidth > innerWidth };
}
function assertLayout(layout) {
  const within = (rect, bounds) => rect.left >= bounds.left - 1 && rect.top >= bounds.top - 1
    && rect.right <= bounds.right + 1 && rect.bottom <= bounds.bottom + 1;
  const viewport = { left: 0, top: 0, right: layout.viewport.width, bottom: layout.viewport.height };
  assert.ok(within(layout.dialog, viewport), "Workspace dock stays inside viewport: " + JSON.stringify(layout));
  assert.ok(within(layout.composer, layout.dialog) && within(layout.input, layout.dialog),
    "Workspace composer stays visible: " + JSON.stringify(layout));
  assert.ok(layout.bodyHeight > 0 && layout.inputReachable, "Transcript has space and composer is pointer reachable: " + JSON.stringify(layout));
  assert.equal(layout.opacity, 1, "Narrow-screen canvas dimming does not dim the dock");
  assert.equal(layout.warningDisplay, "none", "Narrow-screen warning is hidden while chatting");
  assert.equal(layout.overflow, false, "Workspace has no document horizontal overflow");
  assert.equal(layout.controls.length, 4, "Real Agent selector, new-Agent, expand and close controls are rendered");
  for (const [index, control] of layout.controls.entries()) {
    assert.ok(within(control, layout.dialog), "Header control stays inside dock: " + JSON.stringify(control));
    for (const other of layout.controls.slice(index + 1)) {
      const overlaps = Math.min(control.right, other.right) - Math.max(control.left, other.left) > 1
        && Math.min(control.bottom, other.bottom) - Math.max(control.top, other.top) > 1;
      assert.equal(overlaps, false, "Workspace Agent/header controls do not overlap: " + JSON.stringify({ control, other }));
    }
  }
}

try {
  await mkdir(proofDir, { recursive: true, mode: 0o700 });
  await chmod(proofDir, 0o700);
  if (production) {
    await build({ ...config, logLevel: "warn", build: { outDir, emptyOutDir: true,
      rollupOptions: { input: join(root, route) } } });
    server = await preview({ ...config, build: { outDir }, preview: { host: "127.0.0.1", port: 0, open: false } });
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
  const browserErrors = [];
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown") browserErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Runtime.enable");
  const baseUrl = server.resolvedUrls.local[0];
  for (const viewport of viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(route, baseUrl).href });
    await waitFor(() => document.querySelectorAll(".react-flow__node").length === 3);
    assert.equal(await page(() => document.querySelectorAll(".react-flow__edge").length), 0, "Only explicit media relationships are drawn");
    await click(".agent-chat-launcher");
    await waitFor((selector) => Boolean(document.querySelector(selector)) && !document.querySelector(selector).disabled, composer);
    assertLayout(await page(inspectLayout, active));
    if (viewport.width >= 1280) {
      await click('[data-id="synthetic-story"] .content-card-bar');
      await waitFor(() => document.querySelector('[data-id="synthetic-story"]')?.classList.contains("selected"));
      assert.ok(await page((selector) => document.querySelector(`${selector} .agent-chat-context`).textContent.includes("已选 1"), active), "Actual canvas selection updates the dock context");
      assert.ok(await page(() => !document.querySelector(".agent-chat-dock-panel").hidden), "Selecting a node leaves the nonmodal chat open");
    }
    await typeDraft("导演草稿：雨后城市的午后光线");
    await click(`${active} [aria-label="收起创作导演"]`);
    await waitFor(() => document.querySelector(".agent-chat-dock-panel").hidden);
    await click(".agent-chat-launcher");
    assert.equal(await page((selector) => document.querySelector(selector).value, composer), "导演草稿：雨后城市的午后光线", "Collapse/reopen preserves the actual workspace draft");
    await switchAgent("synthetic-writer");
    assert.equal(await page((selector) => document.querySelector(selector).value, composer), "", "Another Agent starts with its own draft");
    await typeDraft("编剧草稿：先讨论人物冲突");
    await switchAgent("synthetic-director");
    assert.equal(await page((selector) => document.querySelector(selector).value, composer), "导演草稿：雨后城市的午后光线", "Switching back restores the director draft");
    await switchAgent("synthetic-writer");
    assert.equal(await page((selector) => document.querySelector(selector).value, composer), "编剧草稿：先讨论人物冲突", "Agent drafts never leak across selection");
    await switchAgent("synthetic-director");
    assertLayout(await page(inspectLayout, active));
    await click(`${active} [aria-label="展开对话"]`);
    assertLayout(await page(inspectLayout, active));
    await click(`${active} [aria-label="还原对话大小"]`);
    const capture = await cdp("Page.captureScreenshot", { format: "png" });
    const screenshotPath = join(proofDir, `workspace-${viewport.width}x${viewport.height}.png`);
    await writeFile(screenshotPath, Buffer.from(capture.data, "base64"), { mode: 0o600 });
    await chmod(screenshotPath, 0o600);
    const diagnostics = await page(() => window.__agenvasWorkspaceFixture);
    assert.deepEqual(diagnostics.unexpected, [], "All real workspace API reads have explicit synthetic fixtures");
    assert.deepEqual(diagnostics.mutations, [], "No canvas command, Run, generation or Provider mutation was requested");
  }
  assert.deepEqual(browserErrors, [], "Real workspace has no browser runtime errors");
  console.log("Director workspace passed: 4 production-CSS viewports, artifact-only canvas and persisted media relations, real header controls, live canvas selection, per-Agent/collapse draft retention, reachable composer, expand/restore; synthetic HTTP/SSE only");
  console.log(`Private screenshots: ${proofDir}`);
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
