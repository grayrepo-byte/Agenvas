import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

const root = fileURLToPath(new URL("../", import.meta.url));
const route = "/e2e/director-workbench.html";
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const profile = await mkdtemp(join(tmpdir(), "agenvas-director-browser-"));
const screenshots = await mkdtemp(join(tmpdir(), "agenvas-director-screenshots-"));
await chmod(screenshots, 0o700);
const config = { root, configFile: join(root, "vite.config.ts") };
const outDir = join(profile, "build");
const pending = new Map();
const runtimeErrors = [];
let server, chrome, socket, nextId = 0;
const pause = () => new Promise((resolve) => setTimeout(resolve, 50));
function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => { pending.set(id, { resolve, reject }); socket.send(JSON.stringify({ id, method, params })); });
}
async function page(fn, ...args) {
  const value = await cdp("Runtime.evaluate", { expression: `(${fn.toString()})(...${JSON.stringify(args)})`, awaitPromise: true, returnByValue: true });
  if (value.exceptionDetails) throw new Error(value.exceptionDetails.exception?.description ?? value.exceptionDetails.text);
  return value.result.value;
}
async function waitFor(fn, ...args) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) { if (await page(fn, ...args)) return; await pause(); }
  throw new Error(`Timed out: ${await page(() => document.body.innerText)}`);
}
async function screenshot(name) {
  const capture = await cdp("Page.captureScreenshot", { format: "png" });
  await writeFile(join(screenshots, name + ".png"), Buffer.from(capture.data, "base64"), { mode: 0o600 });
}
async function click(selector) {
  await page((target) => document.querySelector(target).scrollIntoView({ block: "nearest", inline: "nearest" }), selector);
  const point = await page((target) => {
    const bounds = document.querySelector(target).getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  }, selector);
  assert.equal(await page(({ x, y }, target) => document.querySelector(target).contains(document.elementFromPoint(x, y)), point, selector), true,
    `Control can be reached by pointer: ${selector}`);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
}
async function clickNamed(selector, name) {
  await waitFor((query, label) => [...document.querySelectorAll(query)].some((control) =>
    (control.getAttribute("aria-label") ?? control.textContent?.replace(/\s+/g, " ").trim()) === label), selector, name);
  const point = await page((query, label) => {
    const candidates = [...document.querySelectorAll(query)].filter((control) =>
      (control.getAttribute("aria-label") ?? control.textContent?.replace(/\s+/g, " ").trim()) === label);
    if (candidates.length !== 1) throw new Error(`Expected one ${query} named ${label}, found ${candidates.length}`);
    const control = candidates[0];
    control.scrollIntoView({ block: "nearest", inline: "nearest" });
    const bounds = control.getBoundingClientRect();
    const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
    return { x, y, reachable: control.contains(document.elementFromPoint(x, y)) };
  }, selector, name);
  assert.equal(point.reachable, true, `Control can be reached by pointer: ${name}`);
  const { x, y } = point;
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", x, y, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x, y, button: "left", clickCount: 1 });
}
async function selectValue(selector, value) {
  await click(selector);
  await waitFor((option) => Boolean(document.querySelector(`[role="option"][data-value="${option}"]`)), value);
  await click(`[role="option"][data-value="${value}"]`);
}
async function orbitViewport() {
  const center = await page(() => {
    const bounds = document.querySelector(".director-viewport__canvas").getBoundingClientRect();
    // The default object tool now moves subjects; orbit from empty space in this fixed synthetic scene.
    return { x: bounds.x + bounds.width * 0.18, y: bounds.y + bounds.height * 0.18 };
  });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...center, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", x: center.x + 70, y: center.y + 20, button: "left", buttons: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: center.x + 70, y: center.y + 20, button: "left", clickCount: 1 });
}
async function checkControlReachability() {
  // Inspect every visible panel, toolbar and footer; hidden tabs and file inputs are not pointer controls.
  const count = await page(() => [...document.querySelectorAll(".director-workbench button:not(:disabled), .director-workbench input:not(:disabled):not([type=file]), .director-workbench textarea:not(:disabled)")]
    .filter((control) => control.getClientRects().length && !control.closest("[hidden]") && getComputedStyle(control).visibility !== "hidden").length);
  for (let index = 0; index < count; index++) {
    const reachable = await page((controlIndex) => {
      const control = [...document.querySelectorAll(".director-workbench button:not(:disabled), .director-workbench input:not(:disabled):not([type=file]), .director-workbench textarea:not(:disabled)")]
        .filter((candidate) => candidate.getClientRects().length && !candidate.closest("[hidden]") && getComputedStyle(candidate).visibility !== "hidden")[controlIndex];
      control.scrollIntoView({ block: "nearest", inline: "nearest" });
      const bounds = control.getBoundingClientRect();
      const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
      return { label: control.getAttribute("aria-label") ?? control.textContent, width: bounds.width, height: bounds.height,
        reachable: x >= 0 && y >= 0 && x <= innerWidth && y <= innerHeight && control.contains(document.elementFromPoint(x, y)) };
    }, index);
    assert.ok(reachable.reachable && reachable.width > 0 && reachable.height > 0, `Control remains accessible after scrolling: ${JSON.stringify(reachable)}`);
  }
}
const captureButton = '[data-director-action="capture"]';
const generateButton = '[data-director-action="generate-angle"]';
async function ready() { await waitFor((selector) => document.querySelector(selector)?.disabled === false, captureButton); }

try {
  if (process.argv.includes("--dev")) {
    server = await createServer({ ...config, server: { host: "127.0.0.1", port: 0, open: false } }); await server.listen();
  } else {
    await build({ ...config, logLevel: "warn", build: { outDir, emptyOutDir: true, rollupOptions: { input: join(root, route) } } });
    server = await preview({ ...config, build: { outDir }, preview: { host: "127.0.0.1", port: 0, open: false } });
  }
  chrome = spawn(chromePath, ["--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
    "--enable-unsafe-swiftshader", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Chrome startup timed out")), 20000);
    chrome.once("error", (error) => { clearTimeout(timer); reject(error); });
    chrome.stderr.on("data", (data) => { const match = data.toString().match(/DevTools listening on (ws:\/\/\S+)/);
      if (match) { clearTimeout(timer); resolve(new URL(match[1]).origin.replace("ws:", "http:")); } });
  });
  const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: "PUT" })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", ({ data }) => {
    const message = JSON.parse(data);
    if (message.method === "Runtime.exceptionThrown") runtimeErrors.push(message.params.exceptionDetails.text);
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message)); else waiting.resolve(message.result);
  });
  await cdp("Page.enable"); await cdp("Runtime.enable");
  const baseUrl = server.resolvedUrls.local[0];
  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }, { width: 844, height: 390 }]) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(route, baseUrl).href }); await ready();
    const layout = await page(() => {
      const dialog = document.querySelector(".director-workbench").getBoundingClientRect();
      const canvas = document.querySelector(".director-viewport__canvas").getBoundingClientRect();
      return { left: dialog.left, top: dialog.top, right: dialog.right, bottom: dialog.bottom, width: innerWidth, height: innerHeight,
        overflow: document.documentElement.scrollWidth > innerWidth, canvasWidth: canvas.width, canvasHeight: canvas.height,
        body: document.querySelector(".director-body").getBoundingClientRect().toJSON(),
        regions: [...document.querySelector(".director-body").children].map((region) => ({
          name: region.className, bounds: region.getBoundingClientRect().toJSON(),
        })) };
    });
    assert.ok(layout.left >= -1 && layout.top >= -1 && layout.right <= layout.width + 1 && layout.bottom <= layout.height + 1, JSON.stringify(layout));
    assert.equal(layout.overflow, false, "No horizontal document overflow");
    assert.ok(layout.canvasWidth > 100 && layout.canvasHeight > 100, "WebGL composition has useful dimensions");
    for (const { name, bounds } of layout.regions) {
      assert.ok(bounds.width > 0 && bounds.height > 0 && bounds.left >= layout.left - 1 && bounds.right <= layout.right + 1,
        `Workspace region stays within the dialog width: ${name} ${JSON.stringify(bounds)}`);
    }
    await screenshot(`workbench-${viewport.width}x${viewport.height}`);
    await checkControlReachability();
    await click('button[aria-label="添加物体"]');
    await clickNamed('[role="menuitem"]', "方体");
    await clickNamed('[role="tab"]', "物体");
    assert.equal(await page(() => [...document.querySelectorAll("label")]
      .find((label) => label.textContent.trim() === "物体名称")?.control?.value), "方体 2");
    await click('input[aria-label="位置 X"]');
    await page(() => document.querySelector('input[aria-label="位置 X"]').select());
    await cdp("Input.insertText", { text: "1.5" });
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Tab", code: "Tab" });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Tab", code: "Tab" });
    await clickNamed('[role="radio"]', "图片换视角");
    await waitFor((selector) => Boolean(document.querySelector(selector)), generateButton);
    assert.equal(await page((selector) => Boolean(document.querySelector(selector)), captureButton), false,
      "Angle mode presents its explicit generation action instead of local capture");
    await screenshot(`workbench-angle-${viewport.width}x${viewport.height}`);
    await checkControlReachability();
    await clickNamed('[role="radio"]', "场景摆位");
    await click('button[aria-label="关闭"]');
    await clickNamed("button", "继续编辑");
  }
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp("Page.navigate", { url: new URL(route, baseUrl).href }); await ready();
  assert.equal(await page(() => document.querySelector(".director-viewport").dataset.viewMode), "director",
    "The workbench opens in the director observation view");
  await clickNamed("button", "高级机位");
  const readCameraDraft = () => page(() => Object.fromEntries([...document.querySelectorAll('.director-inspector input[type="number"]')]
    .map((input) => [input.getAttribute("aria-label"), Number(input.value)])));
  const observationDraft = await readCameraDraft();
  for (const field of ["水平角度 (°)", "俯仰角度 (°)", "距离 (m)", "焦距 (mm)", "目标 X", "目标 Y", "目标 Z"]) {
    assert.ok(Object.hasOwn(observationDraft, field) && Number.isFinite(observationDraft[field]),
      `Observation check includes the camera field: ${field}`);
  }
  await orbitViewport();
  assert.deepEqual(await readCameraDraft(), observationDraft, "Orbiting the director observation view does not alter the scene camera");
  await clickNamed('[role="radio"]', "摄影机视图");
  assert.equal(await page(() => document.querySelector(".director-viewport").dataset.viewMode), "camera");
  assert.deepEqual(await readCameraDraft(), observationDraft, "Switching to the camera view preserves the scene camera");
  await clickNamed('[role="radio"]', "导演视图");
  assert.equal(await page(() => document.querySelector(".director-viewport").dataset.viewMode), "director");
  assert.deepEqual(await readCameraDraft(), observationDraft, "Switching back to the director view preserves the scene camera");
  assert.deepEqual(await page(() => {
    const evidence = JSON.parse(document.querySelector("#director-browser-evidence").textContent);
    return { uploads: evidence.uploads.length, scenes: evidence.scenes.length, angles: evidence.angles.length };
  }), { uploads: 0, scenes: 0, angles: 0 }, "Observation and view switching do not submit capture or generation requests");
  await clickNamed('[role="radio"]', "摄影机视图");
  const before = await page(() => Number(document.querySelector('input[aria-label="水平角度 (°)"]').value));
  await orbitViewport();
  await waitFor((previous) => Number(document.querySelector('input[aria-label="水平角度 (°)"]').value) !== previous, before);
  await page(() => document.querySelector("canvas").getContext("webgl2").getExtension("WEBGL_lose_context").loseContext());
  await waitFor(() => [...document.querySelectorAll("button")].some((button) => button.textContent.trim() === "重试 3D 预览"));
  assert.equal(await page((selector) => document.querySelector(selector).disabled, captureButton), true, "Context loss blocks capture");
  await clickNamed("button", "重试 3D 预览");
  await ready();
  assert.notEqual(await page(() => Number(document.querySelector('input[aria-label="水平角度 (°)"]').value)), before, "WebGL retry retains the camera draft");
  const sizes = [["16:9", 1600, 900], ["9:16", 900, 1600], ["1:1", 1600, 1600], ["4:3", 1600, 1200]];
  for (const [ratio, width, height] of sizes) {
    await selectValue('button[role="combobox"][aria-label="画幅"]', ratio);
    await click(captureButton);
    await waitFor(() => !document.querySelector(".director-workbench"));
    const evidence = await page(() => JSON.parse(document.querySelector("#director-browser-evidence").textContent));
    const capture = evidence.uploads.at(-1), saved = evidence.scenes.at(-1);
    assert.equal(capture.width, width); assert.equal(capture.height, height);
    assert.ok(capture.bytes > 1000 && capture.colors > 10, `Real nonempty WebGL PNG for ${ratio}: ${JSON.stringify(capture)}`);
    assert.equal(saved.purpose, "DIRECTOR_3D"); assert.equal(saved.directorScene.aspectRatio, ratio);
    assert.equal(saved.sourceVersionId, "synthetic-source");
    const capturedPng = await page(async () => {
      const image = document.querySelector("#director-browser-capture");
      const blob = await (await fetch(image.src)).blob();
      return new Promise((resolve, reject) => {
        const reader = new FileReader(); reader.onload = () => resolve(reader.result); reader.onerror = reject; reader.readAsDataURL(blob);
      });
    });
    await writeFile(join(screenshots, `captured-frame-${ratio.replace(":", "x")}.png`), Buffer.from(capturedPng.split(",")[1], "base64"), { mode: 0o600 });
    await click("#open-director-fixture"); await ready();
  }
  await clickNamed('[role="radio"]', "图片换视角");
  await waitFor((selector) => document.querySelector(selector)?.disabled === false, generateButton);
  await click(generateButton);
  await waitFor(() => !document.querySelector(".director-workbench"));
  const angleEvidence = await page(() => JSON.parse(document.querySelector("#director-browser-evidence").textContent));
  assert.equal(angleEvidence.angles.length, 1, "Exactly one explicit synthetic angle submission");
  assert.equal(angleEvidence.angles[0].operation, "VIEW_ANGLE");
  assert.equal(angleEvidence.angles[0].sourceVersionId, "synthetic-source");
  assert.equal(angleEvidence.angles[0].parameters.viewAngle, "RIGHT_THREE_QUARTER");
  assert.ok(angleEvidence.angles[0].instruction.includes("following camera direction"));
  assert.equal(angleEvidence.uploads.length, 4, "Angle submission does not recapture or upload a fake source frame");
  assert.deepEqual(runtimeErrors, [], "No uncaught browser errors");
  console.log(JSON.stringify({ result: "passed", renderer: "Chrome WebGL (SwiftShader allowed)", screenshots,
    checks: ["three viewport sizes", "reachable object and camera controls", "angle configuration", "dirty close", "director observation preserves the scene camera",
      "view switching does not submit requests", "camera view mouse orbit synchronization",
      "WebGL loss and retained-draft retry", "four exact PNG dimensions with actual colored scene pixels", "scene upload metadata",
      "explicit existing angle task submission with exact source and camera guidance"] }, null, 2));
} finally {
  socket?.close();
  if (chrome && chrome.exitCode === null) {
    const stopped = new Promise((resolve) => chrome.once("exit", resolve));
    chrome.kill(); await stopped;
  }
  await server?.close(); await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}
