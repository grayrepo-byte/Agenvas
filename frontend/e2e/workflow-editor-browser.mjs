import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Real CSS/layout regression without a backend, credentials or provider calls. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME
  ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/workflow-editor.html";
// The bug occurs only after production CSS optimization; dev is an optional comparison.
const production = !process.argv.includes("--dev");
const pollMs = 50;
const timeoutMs = 15_000;
const tolerance = 1;
const referenceSourceNames = ["从设备上传", "从资源库选择", "从画布选择", "从我的资产选择"];
const syntheticImage = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="#6670a6"/><circle cx="32" cy="24" r="12" fill="#d2d8f0"/><path d="M12 64 Q32 30 52 64" fill="#d2d8f0"/></svg>').toString("base64");
const viewports = [
  { width: 2560, height: 1179 },
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-workflow-browser-"));
const config = { root, configFile: join(root, "vite.config.ts") };
const outDir = join(profile, "build");
let server;
let chrome;
let socket;
let nextId = 0;
const pending = new Map();
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
    if (await page(fn, ...args)) return;
    await pause();
  }
  throw new Error(`Timed out waiting for the workflow fixture: ${await page(() => document.body.innerText)}`);
}

async function pointerClick(selector) {
  await page((target) => document.querySelector(target)?.scrollIntoView({ block: "nearest", inline: "nearest" }), selector);
  await waitFor((target) => {
    const element = document.querySelector(target);
    if (!element) return false;
    const bounds = element.getBoundingClientRect();
    return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
  }, selector);
  const point = await page((target) => {
    const bounds = document.querySelector(target).getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  }, selector);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
}

async function clickNamed(role, name) {
  await waitFor((targetRole, targetName) => Array.from(document.querySelectorAll(targetRole === "button" ? "button, [role=button]" : `[role=${targetRole}]`))
    .some((element) => (element.getAttribute("aria-label") ?? element.textContent?.trim()) === targetName), role, name);
  await page((targetRole, targetName) => {
    document.querySelector("[data-browser-action]")?.removeAttribute("data-browser-action");
    const element = Array.from(document.querySelectorAll(targetRole === "button" ? "button, [role=button]" : `[role=${targetRole}]`))
      .find((candidate) => (candidate.getAttribute("aria-label") ?? candidate.textContent?.trim()) === targetName);
    element.setAttribute("data-browser-action", "true");
  }, role, name);
  await pointerClick("[data-browser-action]");
}

async function dismissPicker() {
  await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape", windowsVirtualKeyCode: 27 });
  await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Escape", code: "Escape", windowsVirtualKeyCode: 27 });
  await waitFor(() => !document.querySelector(".media-draft-popover[role=dialog], .media-draft-reference-sources[role=menu]"));
}

async function captureReferenceScreenshot(suffix) {
  if (!process.env.AGENVAS_WORKFLOW_SCREENSHOT) return;
  const screenshot = await cdp("Page.captureScreenshot", { format: "png" });
  const { writeFile } = await import("node:fs/promises");
  await writeFile(process.env.AGENVAS_WORKFLOW_SCREENSHOT.replace(".png", `-${suffix}.png`), Buffer.from(screenshot.data, "base64"), { mode: 0o600 });
}

function referenceMenu() {
  const menu = document.querySelector(".media-draft-reference-sources[role=menu]");
  const { x, y, right, bottom } = menu.getBoundingClientRect();
  const style = (element) => {
    const computed = getComputedStyle(element);
    return Object.fromEntries(["backgroundColor", "borderTopWidth", "borderTopColor", "borderRadius", "paddingTop", "paddingRight", "paddingBottom", "paddingLeft", "fontSize"]
      .map((key) => [key, computed[key]]));
  };
  const items = Array.from(menu.querySelectorAll("[role=menuitem]"));
  return { label: menu.getAttribute("aria-label"), classes: menu.className,
    bounds: { x, y, right, bottom }, style: style(menu), itemStyles: items.map(style),
    items: items.map((item) => ({ text: item.textContent.trim(), disabled: item.getAttribute("aria-disabled") === "true" })) };
}

async function openReferenceMenu(selector, viewport) {
  await pointerClick(selector);
  await waitFor(() => Boolean(document.querySelector(".media-draft-reference-sources[role=menu]")));
  const menu = await page(referenceMenu);
  assert.equal(menu.label, "图片来源", "Both ordinary and workflow slots expose the existing source menu");
  assert.ok(menu.classes.includes("media-draft-reference-sources"), "Source menus share the existing CSS class");
  assert.deepEqual(menu.items.slice(0, referenceSourceNames.length), referenceSourceNames.map((text) => ({ text, disabled: false })));
  assert.equal(menu.items.length, referenceSourceNames.length + 1);
  assert.ok(menu.items.at(-1).text.includes("绘制引用图") && menu.items.at(-1).disabled,
    "The unavailable sketch action stays disabled for every adapter");
  const bounds = menu.bounds;
  assert.ok(bounds.x >= -tolerance && bounds.y >= -tolerance && bounds.right <= viewport.width + tolerance
    && bounds.bottom <= viewport.height + tolerance, "Reference source menu must fit the viewport");
  return menu;
}

async function assertReferencePickerFits(viewport) {
  const bounds = await page(() => {
    const picker = document.querySelector(".media-draft-reference-picker-anchor > .media-draft-popover[role=dialog]");
    const { x, y, right, bottom } = picker.getBoundingClientRect();
    return { x, y, right, bottom };
  });
  assert.ok(bounds.x >= -tolerance && bounds.y >= -tolerance && bounds.right <= viewport.width + tolerance
    && bounds.bottom <= viewport.height + tolerance, `Reference picker must fit the viewport: ${JSON.stringify({ viewport, bounds })}`);
}

async function verifyWorkflowPickers(baseUrl, query, expectedMenu, viewport) {
  await cdp("Page.navigate", { url: new URL(`${route}?${query}`, baseUrl).href });
  await waitFor(() => document.querySelectorAll(".workflow-media-tile").length === 3);
  const menu = await openReferenceMenu("button[aria-label='选择人物图']", viewport);
  assert.deepEqual(menu.style, expectedMenu.style, "Workflow source menus must use the ordinary editor surface");
  assert.deepEqual(menu.itemStyles, expectedMenu.itemStyles, "Workflow and ordinary source actions must share CSS");
  const adapter = query.includes("runninghub") ? "runninghub" : "comfyui";
  await captureReferenceScreenshot(`${adapter}-source-menu`);
  await clickNamed("menuitem", "从资源库选择");
  await waitFor(() => Boolean(document.querySelector(".media-draft-references[role=dialog] input[type=search]")));
  await assertReferencePickerFits(viewport);
  assert.equal(await page(() => document.querySelectorAll(".media-draft-reference-option").length), 2,
    "Image slots filter out audio project resources and expose both immutable image versions");
  await captureReferenceScreenshot(`${adapter}-resources`);
  await clickNamed("button", "选择 合成图片 · v2");
  await waitFor(() => window.__workflowFixtureDraft.parameters.dynamicValues?.reference_0 === "synthetic-image-v2");
  assert.deepEqual(await page(() => window.__workflowFixtureDraft.mediaInputs.map((input) => input.versionId)), ["synthetic-image-v2"]);

  await openReferenceMenu("button[aria-label='选择人物图']", viewport);
  await clickNamed("menuitem", "从画布选择");
  await waitFor(() => Boolean(document.querySelector(".media-draft-references[role=dialog] .media-draft-reference-option")));
  await assertReferencePickerFits(viewport);
  assert.equal(await page(() => document.querySelectorAll(".media-draft-reference-option").length), 1,
    "Image slots also filter canvas audio cards");
  assert.ok(await page(() => document.querySelector(".media-draft-reference-option").textContent.includes("v1")),
    "Canvas picker exposes its selected immutable revision rather than the resource default");
  await pointerClick(".media-draft-references[role=dialog] .media-draft-reference-option");
  await waitFor(() => window.__workflowFixtureDraft.parameters.dynamicValues?.reference_0 === "synthetic-image-v1");
  assert.deepEqual(await page(() => window.__workflowFixtureDraft.mediaInputs.map((input) => input.versionId)), ["synthetic-image-v1"],
    "Replacing a slot preserves the picked exact version and removes its prior unassigned reference");

  await openReferenceMenu("button[aria-label='选择细节图']", viewport);
  await clickNamed("menuitem", "从我的资产选择");
  await waitFor(() => Boolean(document.querySelector(".media-draft-library-popover .library-browser")));
  await assertReferencePickerFits(viewport);
  await captureReferenceScreenshot(`${adapter}-library`);
  await clickNamed("button", "用作参考：合成个人资产");
  await waitFor(() => window.__workflowFixtureDraft.parameters.dynamicValues?.reference_1 === "synthetic-library-v1");
  assert.deepEqual(await page(() => window.__workflowFixtureDraft.parameters.dynamicValues),
    { reference_0: "synthetic-image-v1", reference_1: "synthetic-library-v1" }, "Personal asset transfer atomically binds only the intended named slot");
  assert.deepEqual(await page(() => window.__workflowFixtureDraft.mediaInputs.map((input) => input.versionId)),
    ["synthetic-image-v1", "synthetic-library-v1"]);
}

function editorLayout() {
  const editor = document.querySelector(".media-draft-editor");
  const toolbar = editor.querySelector(".media-draft-toolbar");
  const prompt = editor.querySelector(".media-draft-prompt");
  const row = editor.querySelector(".media-draft-reference-row");
  const tile = row.querySelector(".workflow-media-tile, .media-draft-reference-add");
  const box = (element) => { const { x, y, right, bottom, width, height } = element.getBoundingClientRect(); return { x, y, right, bottom, width, height }; };
  const styles = (element, keys) => {
    const computed = getComputedStyle(element);
    return Object.fromEntries(keys.map((key) => [key, computed[key]]));
  };
  const editorBox = box(editor);
  const tileBox = box(tile);
  const rowBox = box(row);
  const promptBox = box(prompt);
  const tiles = Array.from(row.querySelectorAll(".workflow-media-tile"), box);
  return { editor: editorBox, toolbar: box(toolbar), prompt: promptBox,
    inlineParameters: editor.querySelectorAll("input[type=number]").length,
    geometry: { editorWidth: editorBox.width, editorHeight: editorBox.height,
      tileX: tileBox.x - editorBox.x, tileY: tileBox.y - editorBox.y, tileWidth: tileBox.width, tileHeight: tileBox.height,
      rowHeight: rowBox.height, promptX: promptBox.x - editorBox.x, promptY: promptBox.y - editorBox.y, promptHeight: promptBox.height },
    editorStyle: styles(editor, ["borderTopWidth", "borderTopColor", "borderRadius", "paddingTop", "paddingRight", "paddingBottom", "paddingLeft"]),
    tileStyle: styles(tile, ["borderTopWidth", "borderTopStyle", "borderTopColor", "borderRadius", "backgroundColor"]),
    referenceGap: Number.parseFloat(getComputedStyle(row).columnGap),
    tileGaps: tiles.slice(1).map((bounds, index) => bounds.x - tiles[index].right),
  };
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
  chrome = spawn(chromePath, ["--headless=new", "--no-first-run",
    "--no-default-browser-check", "--disable-background-networking",
    "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"],
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
      void cdp("Fetch.fulfillRequest", { requestId: message.params.requestId, responseCode: 200,
        responseHeaders: [{ name: "Content-Type", value: "image/svg+xml" }], body: syntheticImage }).catch(console.error);
      return;
    }
    if (message.method === "Runtime.exceptionThrown") console.error(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Runtime.enable");
  await cdp("Fetch.enable", { patterns: [{ urlPattern: "*/api/v1/projects/synthetic-project/assets/*", requestStage: "Request" }] });
  const baseUrl = server.resolvedUrls.local[0];
  for (const viewport of viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(`${route}?openai`, baseUrl).href });
    await waitFor(() => Boolean(document.querySelector(".media-draft-reference-add")));
    const ordinary = await page(editorLayout);
    const ordinaryMenu = await openReferenceMenu(".media-draft-reference-add", viewport);
    await dismissPicker();
    await cdp("Page.navigate", { url: new URL(route, baseUrl).href });
    await waitFor(() => document.querySelectorAll(".workflow-media-tile").length === 3);
    const initial = await page(editorLayout);
    console.log(JSON.stringify({ viewport, openAiGeometry: ordinary.geometry, workflowGeometry: initial.geometry }));
    assert.deepEqual(initial.editorStyle, ordinary.editorStyle, "Workflow editor border and padding must match OpenAI");
    assert.deepEqual(initial.tileStyle, ordinary.tileStyle, "Empty workflow slots must use the OpenAI reference button appearance");
    for (const [key, value] of Object.entries(initial.geometry)) assert.ok(Math.abs(value - ordinary.geometry[key]) <= tolerance,
      `${key} must match OpenAI: ${value} vs ${ordinary.geometry[key]}`);
    for (const gap of initial.tileGaps) assert.ok(Math.abs(gap - ordinary.referenceGap) <= tolerance, "Workflow slots must use the OpenAI reference spacing");
    assert.equal(initial.inlineParameters, 0, "Scalar parameters must stay out of the main editor");
    assert.ok(initial.prompt.height > 40 && initial.toolbar.bottom <= initial.editor.bottom + tolerance,
      "Prompt and toolbar must remain visible in the compact editor");
    const workflowMenu = await openReferenceMenu("button[aria-label='选择人物图']", viewport);
    assert.deepEqual(workflowMenu.style, ordinaryMenu.style, "Workflow source menu shares the ordinary editor CSS surface");
    assert.deepEqual(workflowMenu.itemStyles, ordinaryMenu.itemStyles, "Workflow source actions share the ordinary editor CSS");
    await clickNamed("menuitem", "从我的资产选择");
    await waitFor(() => Boolean(document.querySelector(".media-draft-library-popover .library-browser")));
    await assertReferencePickerFits(viewport);
    await dismissPicker();
    if (process.env.AGENVAS_WORKFLOW_SCREENSHOT && viewport.width === 1440) {
      const screenshot = await cdp("Page.captureScreenshot", { format: "png" });
      const { writeFile } = await import("node:fs/promises");
      await writeFile(process.env.AGENVAS_WORKFLOW_SCREENSHOT.replace(".png", "-editor.png"), Buffer.from(screenshot.data, "base64"), { mode: 0o600 });
    }
    await pointerClick("button[aria-label='扩展参数']");
    await waitFor(() => Boolean(document.querySelector(".workflow-parameters-table")));
    const layout = await page(() => {
      const dialog = document.querySelector(".workflow-parameters-dialog");
      const box = (element) => { const { x, y, right, bottom, width, height } = element.getBoundingClientRect(); return { x, y, right, bottom, width, height }; };
      return { dialog: box(dialog), footer: box(dialog.querySelector(".ui-dialog-footer")), rows: dialog.querySelectorAll("tbody tr").length };
    });
    assert.equal(layout.rows, 3, "All exposed scalar fields share one table");
    for (const bounds of [layout.dialog, layout.footer]) assert.ok(bounds.x >= -tolerance && bounds.y >= -tolerance
      && bounds.right <= viewport.width + tolerance && bounds.bottom <= viewport.height + tolerance, "Dialog and close action must fit the viewport");
    console.log(JSON.stringify({ viewport, initial, ...layout }));
    if (process.env.AGENVAS_WORKFLOW_SCREENSHOT && viewport.width === 1440) {
      const screenshot = await cdp("Page.captureScreenshot", { format: "png" });
      const { writeFile } = await import("node:fs/promises");
      await writeFile(process.env.AGENVAS_WORKFLOW_SCREENSHOT, Buffer.from(screenshot.data, "base64"), { mode: 0o600 });
    }
    await pointerClick(".workflow-parameters-dialog .ui-dialog-footer button");
    await waitFor(() => !document.querySelector(".workflow-parameters-dialog"));
    await waitFor(() => document.activeElement?.getAttribute("aria-label") === "扩展参数");
  }

  const pickerViewport = viewports[1];
  await cdp("Emulation.setDeviceMetricsOverride", { ...pickerViewport, deviceScaleFactor: 1, mobile: false });
  await cdp("Page.navigate", { url: new URL(`${route}?openai&video`, baseUrl).href });
  await waitFor(() => Boolean(document.querySelector(".media-draft-reference-add")));
  const videoMenu = await openReferenceMenu(".media-draft-reference-add", pickerViewport);
  await dismissPicker();
  for (const query of ["video", "runninghub&video"]) await verifyWorkflowPickers(baseUrl, query, videoMenu, pickerViewport);

  console.log("Workflow editor shared source menus, exact slot references and viewport regression passed (4 viewports, ordinary video / ComfyUI / RunningHub; synthetic fixture only)");
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
