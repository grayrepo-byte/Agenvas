import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Real ProjectWorkspace, native Chromium input and production CSS. The fixture intercepts
 * every API/SSE operation in memory; screenshots contain synthetic content only. */
const root = fileURLToPath(new URL("../", import.meta.url));
const route = "/e2e/canvas-editing.html";
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const profile = await mkdtemp(join(tmpdir(), "agenvas-canvas-editing-browser-"));
const screenshots = await mkdtemp(join(tmpdir(), "agenvas-canvas-editing-proof-"));
await chmod(profile, 0o700); await chmod(screenshots, 0o700);
const production = !process.argv.includes("--dev");
const motionOnly = process.argv.includes("--motion-only");
const groupEntryOnly = process.argv.includes("--group-entry-only");
const connectionDropOnly = process.argv.includes("--connection-drop-only");
const selectAllOnly = process.argv.includes("--select-all-only");
const shortcutsOnly = process.argv.includes("--shortcuts-only");
const emptyCreationOnly = process.argv.includes("--empty-creation-only");
const initialGroupOnly = process.argv.includes("--initial-group-only");
const editorCloseOnly = process.argv.includes("--editor-close-only") || process.argv.includes("--editor-close-bottom-only");
const dragCase = process.argv.includes("--hand-drag-only") ? "hand"
  : process.argv.includes("--text-node-drag-only") ? "node" : process.argv.includes("--drag-diagnostics") ? "diagnostics" : null;
const catalogs = Object.fromEntries(await Promise.all(["zh", "ru"].map(async (locale) => [locale,
  JSON.parse(await readFile(join(root, `src/shared/i18n/locales/${locale}.json`), "utf8"))])));
let locale = "zh", server, chrome, socket, nextId = 0;
const pending = new Map();
const runtimeErrors = [];
const pause = (duration = 50) => new Promise((resolve) => setTimeout(resolve, duration));
const message = (key) => catalogs[locale][key];
const card = (id) => `.react-flow__node[data-id=${JSON.stringify(id)}]`;
const group = (id) => `.canvas-group[data-group-id=${JSON.stringify(id)}]`;
const evidence = () => page(() => window.__agenvasCanvasEditingFixture.evidence());
function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP ${method} timed out`)); }, 15000);
    pending.set(id, { resolve: (value) => { clearTimeout(timer); resolve(value); }, reject: (error) => { clearTimeout(timer); reject(error); } });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function page(fn, ...args) {
  const result = await cdp("Runtime.evaluate", { expression: `(${fn.toString()})(...${JSON.stringify(args)})`, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
}
async function waitFor(fn, ...args) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) { if (await page(fn, ...args)) return; await pause(); }
  throw new Error(`Timed out: ${fn.toString()}\n${await page(() => document.body.innerText)}`);
}
async function point(selector, canvasSurface = false) {
  await waitFor((target, surface) => {
    const element = document.querySelector(target); if (!element || element.matches(":disabled")) return false;
    const rect = element.getBoundingClientRect();
    const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2;
    const hit = document.elementFromPoint(x, y);
    return rect.width > 0 && rect.height > 0 && (element.contains(hit)
      || surface && element.closest(".react-flow") === hit?.closest(".react-flow"));
  }, selector, canvasSurface);
  return page((target) => {
    const rect = document.querySelector(target).getBoundingClientRect();
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  }, selector);
}
async function click(selector, { modifiers = 0, clicks = 1 } = {}) {
  const location = await point(selector);
  if (modifiers & 4) await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Meta", code: "MetaLeft", modifiers: 4, windowsVirtualKeyCode: 91 });
  if (modifiers & 2) await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Control", code: "ControlLeft", modifiers: 2, windowsVirtualKeyCode: 17 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...location, modifiers });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...location, button: "left", clickCount: clicks, modifiers });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...location, button: "left", clickCount: clicks, modifiers });
  if (modifiers & 4) await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Meta", code: "MetaLeft", modifiers: 0, windowsVirtualKeyCode: 91 });
  if (modifiers & 2) await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Control", code: "ControlLeft", modifiers: 0, windowsVirtualKeyCode: 17 });
}
async function namedSelector(query, name) {
  await page(() => document.querySelectorAll("[data-browser-target]").forEach((element) => element.removeAttribute("data-browser-target")));
  await waitFor((selector, text) => [...document.querySelectorAll(selector)].some((control) =>
    [control.getAttribute("aria-label"), control.textContent?.replace(/\s+/g, " ").trim()].includes(text)
      && control.getBoundingClientRect().width > 0 && getComputedStyle(control).visibility !== "hidden"), query, name);
  return page((selector, text) => {
    const elements = [...document.querySelectorAll(selector)].filter((control) =>
      [control.getAttribute("aria-label"), control.textContent?.replace(/\s+/g, " ").trim()].includes(text)
        && control.getBoundingClientRect().width > 0 && getComputedStyle(control).visibility !== "hidden");
    if (elements.length !== 1) throw new Error(`Expected one ${selector} named ${text}, got ${elements.length}`);
    elements[0].setAttribute("data-browser-target", "true"); return '[data-browser-target="true"]';
  }, query, name);
}
async function clickNamed(query, key) { await click(await namedSelector(query, message(key))); }
async function waitForHistoryAction(key) {
  await waitFor((label) => [...document.querySelectorAll("button")].some((button) => button.getAttribute("aria-label") === label && !button.disabled), message(key));
}
async function key(key, code = `Key${key.toUpperCase()}`, modifiers = 0, windowsVirtualKeyCode = key.toUpperCase().charCodeAt(0), commands) {
  for (const type of ["keyDown", "keyUp"]) await cdp("Input.dispatchKeyEvent", { type, key, code, modifiers, windowsVirtualKeyCode,
    ...(type === "keyDown" && commands ? { commands } : {}) });
}
async function text(selector, value) {
  await click(selector);
  await page((target) => document.querySelector(target).select(), selector);
  await cdp("Input.insertText", { text: value });
  await waitFor((target, expected) => document.querySelector(target)?.value === expected, selector, value);
}
async function screenshot(name) {
  const image = await cdp("Page.captureScreenshot", { format: "png" });
  await writeFile(join(screenshots, `${name}.png`), Buffer.from(image.data, "base64"), { mode: 0o600 });
}
async function ready(expected = connectionDropOnly ? 2 : groupEntryOnly ? 3 : dragCase || motionOnly || editorCloseOnly ? 4 : 100) {
  await waitFor((expected) => document.querySelectorAll(".react-flow__node-canvasCard").length === expected
    && window.__agenvasCanvasEditingFixture.evidence().requests.some((request) => request.path.endsWith("/canvas/editor")), expected);
  assert.equal((await evidence()).unexpected.length, 0);
}
async function navigate(baseUrl, nextLocale = "zh", { motionLarge = false } = {}) {
  locale = nextLocale;
  await cdp("Page.navigate", { url: new URL(`${route}?locale=${locale}${dragCase || motionOnly ? "&hand-drag=1" : ""}${groupEntryOnly ? "&group-entry=1" : ""}${connectionDropOnly ? "&connection-drop=1" : ""}${motionLarge ? "&motion-large=1" : ""}${editorCloseOnly ? "&editor-close=1" : ""}`, baseUrl).href });
  await ready(motionLarge ? 100 : undefined);
}
async function zoomToFirstCard() {
  // A large-canvas fit can leave the first title under the fixed header. Pan from
  // blank canvas before zooming on its title; text bodies intentionally own wheel input.
  const placement = await page((selector) => {
    const box = document.querySelector(selector).getBoundingClientRect();
    let pane;
    for (let y = 120; y < innerHeight - 120 && !pane; y += 40)
      for (let x = 120; x < innerWidth - 120; x += 40)
        if (document.elementFromPoint(x, y)?.classList.contains("react-flow__pane")) { pane = { x, y }; break; }
    return { pane, dx: innerWidth / 2 - box.x - box.width / 2,
      dy: innerHeight / 3 - box.y - box.height / 2 };
  }, card("synthetic-card-0"));
  assert.ok(placement.pane, "The close-up starts from a hittable blank canvas region");
  await clickNamed("button", "canvas.tools.handTool");
  await dragFromPoint(placement.pane, placement.dx, placement.dy);
  await waitForStableViewport();
  await clickNamed("button", "canvas.tools.selectionTool");
  for (let attempt = 0; attempt < 6; attempt++) {
    const box = await page((selector) => document.querySelector(selector).getBoundingClientRect().toJSON(), card("synthetic-card-0"));
    if (box.width >= 250) break;
    const anchor = await point(`${card("synthetic-card-0")} .artifact-card-title-icon`);
    await cdp("Input.dispatchMouseEvent", { type: "mouseWheel", ...anchor, deltaX: 0, deltaY: -280 });
    await waitForStableViewport();
  }
  assert.ok(await page((selector) => document.querySelector(selector).getBoundingClientRect().width >= 250,
    card("synthetic-card-0")), "Native wheel reaches a usable card close-up");
  await click(`${card("synthetic-card-0")} .artifact-card-title-icon`);
  await waitFor(() => document.querySelector('.react-flow__node[data-id="synthetic-card-0"]')?.classList.contains("selected"));
  await waitForStableViewport();
}
async function selectFirstPair() {
  await zoomToFirstCard();
  await click(`${card("synthetic-card-1")} .artifact-card-title-icon`, { modifiers: 4 });
  await waitFor(() => document.querySelectorAll(".react-flow__node-canvasCard.selected").length === 2);
}
async function createGroup(name) {
  await selectFirstPair();
  await clickNamed(".workspace-selection-toolbar button", "canvas.group.create");
  await waitFor(() => Boolean(document.querySelector('[role="dialog"]:not([hidden]) input')));
  await text('[role="dialog"]:not([hidden]) input', name);
  await clickNamed('[role="dialog"]:not([hidden]) button', "canvas.group.save");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().groups.length === 1 && !document.querySelector('[role="dialog"]:not([hidden])'));
  return (await evidence()).groups[0].id;
}
async function groupMenu(groupId, key) {
  await click(`${group(groupId)} button[aria-label=${JSON.stringify(message("canvas.group.actions"))}]`);
  await clickNamed('[role="menuitem"]', key);
}
async function drag(selector, dx, dy, canvasSurface = false) {
  const start = await point(selector, canvasSurface);
  await dragFromPoint(start, dx, dy);
}
async function dragFromPoint(start, dx, dy) {
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...start });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...start, button: "left", clickCount: 1 });
  for (let step = 1; step <= 10; step++) {
    await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", x: start.x + dx * step / 10, y: start.y + dy * step / 10, button: "left", buttons: 1 });
    await pause(16);
  }
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: start.x + dx, y: start.y + dy, button: "left", clickCount: 1 });
}
async function checkLayout() {
  const layout = await page(() => {
    const regions = [".workspace-header", ".workspace-selection-toolbar", '[role="dialog"]:not([hidden])', ".ui-dialog-body", ".ui-dialog-footer"];
    return { width: innerWidth, height: innerHeight, documentWidth: document.documentElement.scrollWidth,
      regions: regions.flatMap((selector) => [...document.querySelectorAll(selector)].filter((element) => element.getBoundingClientRect().width > 0)
        .map((element) => ({ selector, ...element.getBoundingClientRect().toJSON(), clientWidth: element.clientWidth, scrollWidth: element.scrollWidth }))) };
  });
  assert.ok(layout.documentWidth <= layout.width + 1, `Document has no horizontal overflow: ${JSON.stringify(layout)}`);
  for (const region of layout.regions) {
    assert.ok(region.left >= -1 && region.right <= layout.width + 1, `Workspace control stays inside viewport: ${JSON.stringify(region)}`);
    assert.ok(region.scrollWidth <= region.clientWidth + 1, `Workspace controls have no horizontal overflow: ${JSON.stringify(region)}`);
    if (region.selector === ".ui-dialog-footer") assert.ok(region.top >= 0 && region.bottom <= layout.height + 1, "Dialog footer stays visible");
  }
}

async function runGroupEntryRegression(baseUrl) {
  await navigate(baseUrl);
  const groupId = await createGroup("Synthetic group entry");
  await click(`button[aria-label=${JSON.stringify(message("canvas.workspace.fitView"))}]`);
  await waitForStableViewport();
  const original = (await evidence()).items.find((item) => item.id === "synthetic-card-2");
  const start = await point(`${card(original.id)} .artifact-card-title-icon`);
  const delta = await page((selector, groupSelector) => {
    const node = document.querySelector(selector).getBoundingClientRect();
    const frame = document.querySelector(groupSelector).getBoundingClientRect();
    return { x: frame.x + frame.width / 2 - node.x - node.width / 2,
      y: frame.y + frame.height / 2 - node.y - node.height / 2 };
  }, card(original.id), group(groupId));
  await page(() => window.__agenvasCanvasEditingFixture.failNextEdit(503));
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...start });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...start, button: "left", clickCount: 1 });
  for (let step = 1; step <= 10; step++) {
    await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", x: start.x + delta.x * step / 10,
      y: start.y + delta.y * step / 10, button: "left", buttons: 1 });
    await pause(20);
  }
  await waitFor((selector) => document.querySelector(selector)?.classList.contains("canvas-group--drop-target"), group(groupId));
  await screenshot("group-drop-highlight");
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: start.x + delta.x, y: start.y + delta.y, button: "left", clickCount: 1 });
  await waitFor(() => Boolean(document.querySelector(".canvas-error-notice")));
  const failed = await evidence();
  assert.equal(failed.layoutDrafts[original.id].groupId, groupId, "Failed drops retain their membership draft");
  assert.equal(failed.items.find((item) => item.id === original.id).groupId, null);
  const failedRequest = failed.edits.at(-1);
  await clickNamed(".canvas-feedback button", "canvas.edit.retry");
  await waitFor((id, target) => {
    const saved = window.__agenvasCanvasEditingFixture.evidence();
    return saved.items.find((item) => item.id === id)?.groupId === target && !saved.layoutDrafts[id]
      && !document.querySelector(".canvas-error-notice");
  }, original.id, groupId);
  const joined = await evidence();
  assert.deepEqual(joined.edits.at(-1), failedRequest, "Drop retry retains its command, group and position");
  assert.deepEqual(joined.layoutDrafts, {});
  await click(`button[aria-label=${JSON.stringify(message("canvas.edit.undo"))}]`);
  await waitFor((id, x) => { const item = window.__agenvasCanvasEditingFixture.evidence().items.find((item) => item.id === id);
    return item.groupId === null && item.x === x; }, original.id, original.x);
  await click(`button[aria-label=${JSON.stringify(message("canvas.edit.redo"))}]`);
  await waitFor((id, target) => window.__agenvasCanvasEditingFixture.evidence().items.find((item) => item.id === id)?.groupId === target, original.id, groupId);

  for (const [kind, label] of [["TEXT", "common.text"], ["IMAGE", "common.image"], ["AUDIO", "common.audio"], ["VIDEO", "common.video"]]) {
    await click(`button[aria-label=${JSON.stringify(message("canvas.workspace.fitView"))}]`);
    await waitForStableViewport();
    if (kind === "TEXT") {
      await click(`${group(groupId)} button[aria-label=${JSON.stringify(message("canvas.group.collapse"))}]`);
      await waitFor((id) => window.__agenvasCanvasEditingFixture.evidence().groups.find((group) => group.id === id)?.collapsed, groupId);
    }
    const before = await evidence();
    const memberBottom = Math.max(...before.items.filter((item) => item.groupId === groupId).map((item) => item.y + item.height));
    await click(`${group(groupId)} button[aria-label=${JSON.stringify(message("canvas.group.addNode"))}]`);
    if (kind === "IMAGE") await page(() => window.__agenvasCanvasEditingFixture.failNextPlacement(503));
    await clickNamed('[role="option"]', label);
    if (kind === "IMAGE") {
      await waitFor(() => Boolean(document.querySelector(".canvas-error-notice")));
      await clickNamed(".canvas-feedback button", "common.retry");
    }
    await waitFor((count) => window.__agenvasCanvasEditingFixture.evidence().items.length === count
      && !document.querySelector(".canvas-error-notice"), before.items.length + 1);
    const after = await evidence();
    const added = after.items.find((item) => !before.items.some((previous) => item.id === previous.id));
    assert.equal(added.artifact.kind, kind); assert.equal(added.groupId, groupId);
    assert.ok(added.y > memberBottom, "Creation leaves room below existing members");
    assert.equal(after.groups.find((group) => group.id === groupId).collapsed, false, "Creation expands folded groups");
    if (kind === "IMAGE") assert.deepEqual(after.placements.at(-1), after.placements.at(-2), "Lost placement response reuses the node, group and coordinates");
  }
  await click(`button[aria-label=${JSON.stringify(message("canvas.workspace.fitView"))}]`);
  await waitForStableViewport(); await checkLayout(); await screenshot("group-direct-creation");
  assert.deepEqual((await evidence()).unexpected, []);
  assert.deepEqual(runtimeErrors, []);
  console.log(`Group entry browser passed: native drop highlight, atomic membership/position history, failure retry, all four creation kinds, folded creation and lost placement response. Synthetic screenshots: ${screenshots}`);
}
async function openTextEditor(itemId) {
  await click(`${card(itemId)} .content-card-body`, { clicks: 2 });
  const selector = `${card(itemId)} .text-card-editor textarea`;
  await waitFor((target) => Boolean(document.querySelector(target)), selector);
  await waitForStableViewport();
  return selector;
}
async function viewport() {
  return page(() => { const matrix = new DOMMatrixReadOnly(getComputedStyle(document.querySelector(".react-flow__viewport")).transform);
    return { x: matrix.m41, y: matrix.m42, zoom: matrix.m11 }; });
}
async function waitForStableViewport() {
  let previous = await viewport(); let stable = 0;
  for (let attempt = 0; attempt < 30; attempt++) {
    await pause(50); const current = await viewport();
    stable = Math.abs(previous.x - current.x) < 0.01 && Math.abs(previous.y - current.y) < 0.01
      && Math.abs(previous.zoom - current.zoom) < 0.0001 ? stable + 1 : 0;
    if (stable >= 3) return;
    previous = current;
  }
  throw new Error("Viewport did not settle before the native drag probe");
}
async function runDragRegression(baseUrl) {
  const probes = [
    { kind: "IMAGE", id: "synthetic-card-2", region: ".media-card-empty" },
    { kind: "VIDEO", id: "synthetic-card-3", region: ".media-card-empty" },
    { kind: "TEXT", id: "synthetic-card-0", region: ".content-card-body" },
    { kind: "EMPTY_TEXT", id: "synthetic-card-1", region: ".content-card-empty" },
    { kind: "EMPTY_TEXT_ICON", id: "synthetic-card-1", region: ".content-card-empty > svg" },
    { kind: "TEXT_LINE", id: "synthetic-card-0", region: ".content-card-text", textLine: true },
    { kind: "TEXT_TITLE", id: "synthetic-card-0", region: ".artifact-card-title-icon" },
    { kind: "TEXT_BORDER", id: "synthetic-card-0", region: ".artifact-canvas-card", border: true },
  ];
  const observations = [];
  for (const tool of ["hand", "select"]) for (const probe of probes) {
    await navigate(baseUrl);
    await click(`button[aria-label=${JSON.stringify(message("canvas.workspace.fitView"))}]`);
    await waitFor(() => [...document.querySelectorAll(".react-flow__node-canvasCard")].every((element) => {
      const bounds = element.getBoundingClientRect();
      return bounds.x >= 80 && bounds.y >= 80 && bounds.right <= innerWidth - 5 && bounds.bottom <= innerHeight - 80;
    }));
    await click(`button[aria-label=${JSON.stringify(message(tool === "hand" ? "canvas.tools.handTool" : "canvas.tools.selectionTool"))}]`);
    await waitFor((expected) => document.querySelector(".workspace-canvas").classList.contains(expected), tool === "hand" ? "is-hand-tool" : "is-select-tool");
    await waitForStableViewport();
    const before = await viewport();
    const beforeItem = (await evidence()).items.find((item) => item.id === probe.id);
    if (probe.border) {
      const start = await page((selector) => { const bounds = document.querySelector(selector).getBoundingClientRect();
        return { x: bounds.x + 3, y: bounds.y + 3 }; }, `${card(probe.id)} ${probe.region}`);
      await dragFromPoint(start, 100, 65);
    } else if (probe.textLine) {
      const start = await page((selector) => {
        const node = document.querySelector(selector).firstChild;
        const range = document.createRange(); range.setStart(node, 0); range.setEnd(node, Math.min(12, node.length));
        const bounds = range.getClientRects()[0]; return { x: bounds.x + 2, y: bounds.y + bounds.height / 2 };
      }, `${card(probe.id)} ${probe.region}`);
      await dragFromPoint(start, 100, tool === "hand" ? 65 : 0);
    } else await drag(`${card(probe.id)} ${probe.region}`, 100, 65, tool === "hand");
    await page(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const after = await viewport();
    const afterItem = (await evidence()).items.find((item) => item.id === probe.id);
    const observation = { tool, kind: probe.kind, region: probe.region,
      viewport: { x: after.x - before.x, y: after.y - before.y },
      item: { x: afterItem.x - beforeItem.x, y: afterItem.y - beforeItem.y },
      saves: (await evidence()).edits.length,
      selection: await page(() => window.getSelection()?.toString() ?? "") };
    observations.push(observation);
    console.log(`Canvas drag probe: ${JSON.stringify(observation)}`);
    if (probe.kind === "TEXT") await screenshot(`${tool}-text-body-drag`);
  }
  for (const probe of observations.filter((probe) => probe.tool === "hand" && ["IMAGE", "VIDEO"].includes(probe.kind))) {
    assert.ok(probe.viewport.x > 50 && probe.viewport.y > 30,
      `Hand-tool ${probe.kind} control pans the viewport: ${JSON.stringify(probe)}`);
    assert.deepEqual(probe.item, { x: 0, y: 0 }, "Hand tool never persists a node move");
  }
  for (const probe of observations.filter((probe) => probe.tool === "select" && ["IMAGE", "VIDEO", "TEXT_TITLE", "TEXT_BORDER"].includes(probe.kind))) {
    assert.ok(Math.abs(probe.item.x) > 1 && Math.abs(probe.item.y) > 1,
      `Selection-tool ${probe.kind} control moves its node: ${JSON.stringify(probe)}`);
    assert.equal(probe.saves, 1, "A finished drag persists one layout edit");
  }
  const textSelection = observations.find((probe) => probe.tool === "select" && probe.kind === "TEXT_LINE");
  assert.deepEqual(textSelection.item, { x: 0, y: 0 }, "Selecting actual document text never drags the node");
  assert.equal(textSelection.saves, 0, "Text selection never persists layout");
  assert.ok(textSelection.selection.length > 0, "Native document text selection remains available");
  await navigate(baseUrl);
  await click(`button[aria-label=${JSON.stringify(message("canvas.workspace.fitView"))}]`);
  await waitForStableViewport();
  const editor = await openTextEditor("synthetic-card-0");
  await text(editor, "Synthetic editable draft remains protected.");
  await waitForStableViewport();
  const beforeInput = (await evidence()).items[0];
  await drag(editor, 100, 30);
  const afterInput = await evidence();
  assert.deepEqual({ x: afterInput.items[0].x, y: afterInput.items[0].y }, { x: beforeInput.x, y: beforeInput.y }, "Textarea drag never moves the node");
  assert.equal(afterInput.edits.length, 0); assert.equal(afterInput.textSaves.length, 0);
  assert.equal(await page((selector) => document.querySelector(selector).value, editor), "Synthetic editable draft remains protected.");
  console.log("Canvas drag probe: native textarea editing/selection protected; no layout or content write");
  for (const probe of observations.filter((probe) => probe.tool === "hand")) {
    assert.ok(probe.viewport.x > 50 && probe.viewport.y > 30,
      `Hand-tool ${probe.kind} body drag must pan like the IMAGE/VIDEO controls: ${JSON.stringify(probe)}`);
    assert.deepEqual(probe.item, { x: 0, y: 0 }, "Hand tool never persists a text node move");
  }
  if (dragCase === "node") for (const probe of observations.filter((probe) => probe.tool === "select" && ["TEXT", "EMPTY_TEXT", "EMPTY_TEXT_ICON"].includes(probe.kind))) {
    assert.ok(Math.abs(probe.item.x) > 1 && Math.abs(probe.item.y) > 1,
      `Selection-tool ${probe.kind} body drag must move its node like the IMAGE/VIDEO controls: ${JSON.stringify(probe)}`);
    assert.equal(probe.saves, 1, "A finished text body drag persists one layout edit");
  }
  console.log(`Canvas drag ${dragCase} check passed. Synthetic screenshots: ${screenshots}`);
}
async function beginMotionTrace(label, duration = 800) {
  await page((name, milliseconds) => {
    const trace = { name, samples: [], done: false, startedAt: performance.now() };
    window.__agenvasMotionTrace = trace;
    const started = performance.now();
    function sample(now) {
      const matrix = new DOMMatrixReadOnly(getComputedStyle(document.querySelector(".react-flow__viewport")).transform);
      trace.samples.push({ time: now - started, x: matrix.m41, y: matrix.m42, zoom: matrix.m11 });
      if (now - started < milliseconds) requestAnimationFrame(sample);
      else trace.done = true;
    }
    requestAnimationFrame(sample);
  }, label, duration);
  await page(() => new Promise((resolve) => requestAnimationFrame(resolve)));
}
async function finishMotionTrace(label) {
  await waitFor(() => window.__agenvasMotionTrace?.done);
  const trace = await page(() => window.__agenvasMotionTrace);
  await writeFile(join(screenshots, `${label}.json`), JSON.stringify(trace, null, 2), { mode: 0o600 });
  return trace;
}
function analyzeMotion(trace) {
  const first = trace.samples[0], last = trace.samples.at(-1);
  const differs = (a, b) => Math.abs(a.x - b.x) > 0.5 || Math.abs(a.y - b.y) > 0.5 || Math.abs(a.zoom - b.zoom) > 0.0005;
  const intermediate = trace.samples.filter((sample) => differs(sample, first) && differs(sample, last));
  const distinct = new Set(intermediate.map((sample) => `${sample.x.toFixed(2)}:${sample.y.toFixed(2)}:${sample.zoom.toFixed(4)}`));
  const gaps = trace.samples.slice(1).map((sample, index) => sample.time - trace.samples[index].time).sort((a, b) => a - b);
  return { name: trace.name, frames: trace.samples.length, intermediateFrames: distinct.size, first, last,
    frameIntervals: { median: gaps[Math.floor(gaps.length / 2)], maximum: gaps.at(-1) } };
}
async function expectedCenter(itemId) {
  return page((selector) => {
    const flow = document.querySelector(".react-flow").getBoundingClientRect();
    const node = document.querySelector(selector).getBoundingClientRect();
    const matrix = new DOMMatrixReadOnly(getComputedStyle(document.querySelector(".react-flow__viewport")).transform);
    return { x: matrix.m41 + flow.x + flow.width / 2 - node.x - node.width / 2,
      y: matrix.m42 + flow.y + flow.height / 2 - node.y - node.height / 2, zoom: matrix.m11 };
  }, card(itemId));
}
function assertViewport(actual, expected, description) {
  assert.ok(Math.abs(actual.x - expected.x) < 1 && Math.abs(actual.y - expected.y) < 1
    && Math.abs(actual.zoom - expected.zoom) < 0.001, `${description}: ${JSON.stringify({ actual, expected })}`);
}
function assertNoOvershoot(trace) {
  const first = trace.samples[0], last = trace.samples.at(-1);
  for (const axis of ["x", "y", "zoom"]) for (const sample of trace.samples) {
    const tolerance = axis === "zoom" ? 0.001 : 1;
    assert.ok(sample[axis] >= Math.min(first[axis], last[axis]) - tolerance && sample[axis] <= Math.max(first[axis], last[axis]) + tolerance,
      `${trace.name} has no ${axis} overshoot`);
  }
  const tail = trace.samples.filter((sample) => sample.time > last.time - 150);
  tail.forEach((sample) => assertViewport(sample, last, `${trace.name} remains settled without rebound`));
}
async function assertViewportOnly(before, description) {
  const after = await evidence();
  assert.deepEqual(after.items, before.items, `${description} never changes business node coordinates or content`);
  assert.equal(after.edits.length, 0, `${description} never saves layout`);
  assert.equal(after.textSaves.length, 0, `${description} never writes document text`);
  assert.deepEqual(after.layoutDrafts, before.layoutDrafts, `${description} never changes local layout drafts`);
  assert.equal(after.mediaDraftRecoveryCount, before.mediaDraftRecoveryCount, `${description} never creates unsaved media recovery`);
}
async function fitFixture() {
  await click(`button[aria-label=${JSON.stringify(message("canvas.workspace.fitView"))}]`);
  await waitForStableViewport();
}
async function runMotionRegression(baseUrl) {
  const results = [];
  for (const probe of [{ kind: "IMAGE", id: "synthetic-card-2", region: ".media-card-empty" },
    { kind: "TEXT", id: "synthetic-card-0", region: ".artifact-card-title-icon" }]) {
    await navigate(baseUrl); await fitFixture();
    const before = await evidence();
    const expected = await expectedCenter(probe.id);
    const label = `motion-click-${probe.kind.toLowerCase()}`;
    await screenshot(`${label}-before`); await beginMotionTrace(label);
    await click(`${card(probe.id)} ${probe.region}`);
    const trace = await finishMotionTrace(label);
    const result = analyzeMotion(trace); results.push(result);
    console.log(`Canvas motion probe: ${JSON.stringify({ ...result, expected })}`);
    assertViewport(result.last, expected, `${probe.kind} focus reaches its independently computed center`);
    assertNoOvershoot(trace); await assertViewportOnly(before, "Viewport focus");
    await screenshot(`${label}-after`);
  }
  for (const result of results) assert.ok(result.intermediateFrames >= 3,
    `${result.name} must render at least three real intermediate viewport frames, observed ${result.intermediateFrames}`);

  // The second native click replaces the first target while its motion is still active.
  await navigate(baseUrl); await fitFixture();
  const continuousBefore = await evidence();
  await beginMotionTrace("motion-last-click-wins", 1000);
  await click(`${card("synthetic-card-2")} .media-card-empty`); await pause(90);
  const lastTarget = await expectedCenter("synthetic-card-3");
  await page(() => { window.__agenvasMotionTrace.switchedAt = performance.now() - window.__agenvasMotionTrace.startedAt; });
  await click(`${card("synthetic-card-3")} .media-card-empty`);
  const continuous = await finishMotionTrace("motion-last-click-wins");
  assertViewport(continuous.samples.at(-1), lastTarget, "The last clicked node owns the final viewport");
  assertNoOvershoot({ ...continuous, name: `${continuous.name}-after-switch`,
    samples: continuous.samples.filter((sample) => sample.time >= continuous.switchedAt + 35) });
  await assertViewportOnly(continuousBefore, "Replacing a focus target");
  console.log(`Canvas motion probe: ${JSON.stringify(analyzeMotion(continuous))}`);

  // A native hand gesture owns the viewport after it interrupts programmatic movement.
  await navigate(baseUrl); await fitFixture();
  const panBefore = await evidence();
  await beginMotionTrace("motion-native-pan-takeover", 1200);
  await click(`${card("synthetic-card-2")} .media-card-empty`); await pause(90);
  await click(`button[aria-label=${JSON.stringify(message("canvas.tools.handTool"))}]`);
  const panStart = await viewport();
  await dragFromPoint({ x: 1050, y: 160 }, 120, 50);
  const panEnd = await viewport();
  assert.ok(Math.abs(panEnd.x - panStart.x) > 50, "A native hand drag visibly takes over the moving viewport");
  const panTrace = await finishMotionTrace("motion-native-pan-takeover");
  assertViewport(panTrace.samples.at(-1), panEnd, "Old programmatic motion never takes back a completed hand gesture");
  await assertViewportOnly(panBefore, "Native hand takeover");
  console.log(`Canvas motion probe: ${JSON.stringify(analyzeMotion(panTrace))}`);

  await navigate(baseUrl); await fitFixture();
  const wheelBefore = await evidence();
  await beginMotionTrace("motion-native-wheel-takeover", 1200);
  await click(`${card("synthetic-card-2")} .media-card-empty`); await pause(90);
  const wheelStart = await viewport();
  const wheelPoint = await page(() => [{ x: 180, y: 160 }, { x: innerWidth - 120, y: innerHeight - 160 }, { x: 180, y: innerHeight - 180 }]
    .find(({ x, y }) => document.elementFromPoint(x, y)?.classList.contains("react-flow__pane")));
  assert.ok(wheelPoint, "The native wheel probe starts on blank canvas, outside scrollable node content");
  await cdp("Input.dispatchMouseEvent", { type: "mouseWheel", ...wheelPoint, deltaX: 0, deltaY: -180 });
  await waitForStableViewport();
  const wheelEnd = await viewport();
  assert.ok(wheelEnd.zoom > wheelStart.zoom + 0.05, "A native wheel gesture keeps its requested zoom");
  const wheelTrace = await finishMotionTrace("motion-native-wheel-takeover");
  assertViewport(wheelTrace.samples.at(-1), wheelEnd, "Old focus motion never restores the pre-wheel viewport");
  await assertViewportOnly(wheelBefore, "Native wheel takeover");
  console.log(`Canvas motion probe: ${JSON.stringify(analyzeMotion(wheelTrace))}`);

  // Toggle the live media preference on the already-mounted workspace in both directions.
  await cdp("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-motion", value: "reduce" }] });
  await waitFor(() => matchMedia("(prefers-reduced-motion: reduce)").matches);
  await fitFixture();
  const reducedTarget = await expectedCenter("synthetic-card-2");
  await beginMotionTrace("motion-dynamic-reduced", 500);
  await click(`${card("synthetic-card-2")} .media-card-empty`);
  const reduced = await finishMotionTrace("motion-dynamic-reduced");
  const reducedResult = analyzeMotion(reduced);
  assert.equal(reducedResult.intermediateFrames, 0, "Reduced motion disables the focus animation immediately");
  assertViewport(reducedResult.last, reducedTarget, "Reduced motion still reaches the requested center");
  await cdp("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-motion", value: "no-preference" }] });
  await waitFor(() => !matchMedia("(prefers-reduced-motion: reduce)").matches);
  await fitFixture();
  await beginMotionTrace("motion-dynamic-restored");
  await click(`${card("synthetic-card-2")} .media-card-empty`);
  const restored = await finishMotionTrace("motion-dynamic-restored");
  assert.ok(analyzeMotion(restored).intermediateFrames >= 3, "Restoring the preference animates the same mounted workspace");
  console.log(`Canvas motion probe: ${JSON.stringify(reducedResult)}`);
  console.log(`Canvas motion probe: ${JSON.stringify(analyzeMotion(restored))}`);

  // Record large-canvas frame cadence as evidence; do not invent a device-specific FPS limit.
  await navigate(baseUrl, "zh", { motionLarge: true }); await fitFixture();
  const largeBefore = await evidence();
  const largeTarget = await expectedCenter("synthetic-card-2");
  await beginMotionTrace("motion-100-nodes", 1000);
  await click(`${card("synthetic-card-2")} .media-card-empty`);
  const largeTrace = await finishMotionTrace("motion-100-nodes");
  const largeResult = analyzeMotion(largeTrace);
  assert.ok(largeResult.intermediateFrames >= 3, "The 100-node canvas renders real intermediate focus frames");
  assertViewport(largeResult.last, largeTarget, "The 100-node canvas reaches the requested center");
  await assertViewportOnly(largeBefore, "Large-canvas focus");
  console.log(`Canvas motion observation (100 nodes; no FPS threshold): ${JSON.stringify(largeResult)}`);
  await screenshot("motion-100-nodes-after");
  console.log(`Canvas motion check passed. Synthetic frame traces and screenshots: ${screenshots}`);
}
async function runConnectionDropRegression(baseUrl) {
  async function releaseAt(destination) {
    const source = card("synthetic-card-0");
    await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...await point(`${source} .artifact-card-title`) });
    const start = await point(`${source} .canvas-handle--out`);
    await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...start });
    await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...start, button: "left", clickCount: 1 });
    for (let step = 1; step <= 8; step++) {
      await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", x: start.x + (destination.x - start.x) * step / 8,
        y: start.y + (destination.y - start.y) * step / 8, button: "left", buttons: 1 });
    }
    await waitFor(() => Boolean(document.querySelector(".react-flow__connection")));
    await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...destination, button: "left", clickCount: 1 });
  }
  for (const kind of ["IMAGE", "VIDEO", "AUDIO"]) {
    await navigate(baseUrl);
    await waitForStableViewport();
    const destination = await page(() => {
      const bounds = document.querySelector(".react-flow").getBoundingClientRect();
      return { x: bounds.right - 180, y: bounds.top + 180 };
    });
    await releaseAt(destination);
    await waitFor(() => Boolean(document.querySelector(".workspace-create-menu")));
    assert.equal((await evidence()).placements.length, 0, "Releasing only opens the menu");
    assert.equal(await page(() => document.querySelector('.workspace-create-menu [data-value="TEXT"]').getAttribute("aria-disabled")), "true");
    const expected = await page((destination) => {
      const flowBounds = document.querySelector(".react-flow").getBoundingClientRect();
      const matrix = new DOMMatrix(getComputedStyle(document.querySelector(".react-flow__viewport")).transform).inverse();
      const point = matrix.transformPoint(new DOMPoint(destination.x - flowBounds.left, destination.y - flowBounds.top));
      return { x: point.x, y: point.y };
    }, destination);
    await click(`.workspace-create-menu [data-value="${kind}"]`);
    await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().connections.length === 1
      && Boolean(document.querySelector(".react-flow__edge-canvasRelation")));
    const result = await evidence();
    assert.equal(result.items.length, 3);
    assert.equal(result.placements.length, 1);
    assert.ok(Math.abs(result.placements[0].x - expected.x) < 0.1);
    assert.ok(Math.abs(result.placements[0].y - expected.y) < 0.1);
    assert.equal(result.connections[0].sourceCanvasItemId, "synthetic-card-0");
    assert.equal(result.connections[0].targetCanvasItemId, result.placements[0].itemId);
    assert.equal(result.connections[0].sourceArtifactVersionId, "synthetic-card-0-v1");
    const targetDraft = result.mediaDrafts.find((draft) => draft.canvasItemId === result.placements[0].itemId);
    assert.equal(targetDraft.capabilityId, `synthetic-${kind.toLowerCase()}-input`, "The new draft persists a compatible model");
    if (kind === "VIDEO") assert.equal(targetDraft.videoInputMode, "GENERAL_REFERENCE");
    assert.equal(targetDraft.mediaInputs[0].versionId, "synthetic-card-0-v1");
    assert.deepEqual(result.unexpected, []);
    await screenshot(`connection-drop-${kind.toLowerCase()}`);
  }
  await navigate(baseUrl);
  await waitForStableViewport();
  await releaseAt(await point(`${card("synthetic-card-1")} .artifact-card-title`));
  assert.equal(await page(() => Boolean(document.querySelector(".workspace-create-menu"))), false,
    "Releasing on an incompatible node does not open creation");
  const destination = await page(() => {
    const bounds = document.querySelector(".react-flow").getBoundingClientRect();
    return { x: bounds.right - 180, y: bounds.top + 180 };
  });
  await releaseAt(destination);
  await waitFor(() => Boolean(document.querySelector(".workspace-create-menu")));
  await key("Escape", "Escape", 0, 27);
  await waitFor(() => !document.querySelector(".workspace-create-menu"));
  assert.equal((await evidence()).placements.length, 0);
  assert.equal((await evidence()).connections.length, 0);
  assert.deepEqual(runtimeErrors, []);
  console.log(`Canvas connection drop passed: native Chrome drag, image/video/audio creation and persisted edges, coordinates, invalid node and Escape. Synthetic screenshots: ${screenshots}`);
}
async function runSelectAllRegression(baseUrl) {
  await navigate(baseUrl);
  await zoomToFirstCard();
  const before = await evidence();
  for (const modifiers of [4, 2]) {
    await click(`${card("synthetic-card-0")} .artifact-card-title-icon`);
    await key("a", "KeyA", modifiers, 65, ["selectAll"]);
    await waitFor(() => document.querySelectorAll(".react-flow__node-canvasCard.selected").length === 100);
    assert.equal(await page(() => window.getSelection()?.toString() ?? ""), "", "Select all owns nodes instead of document text");
    assert.equal(await page(() => Boolean(document.querySelector(".workspace-selection-toolbar"))), true);
  }
  await screenshot("select-all-nodes");
  await assertViewportOnly(before, "Select all");
  const editor = await openTextEditor("synthetic-card-0");
  const draft = "Synthetic draft stays editable with native select all.";
  await text(editor, draft);
  // Headless CDP needs the platform editing command to exercise the native default action.
  await key("a", "KeyA", 4, 65, ["selectAll"]);
  assert.deepEqual(await page((selector) => {
    const input = document.querySelector(selector);
    return { value: input.value, start: input.selectionStart, end: input.selectionEnd,
      selectedNodes: document.querySelectorAll(".react-flow__node-canvasCard.selected").length };
  }, editor), { value: draft, start: 0, end: draft.length, selectedNodes: 1 });
  await screenshot("select-all-editor-text");
  assert.deepEqual(runtimeErrors, []);
  assert.deepEqual((await evidence()).unexpected, []);
  console.log(`Canvas select all passed: native Cmd/Ctrl+A selects 100 nodes without document selection or writes; Cmd+A preserves native textarea selection and draft. Synthetic screenshots: ${screenshots}`);
}
async function runShortcutRegression(baseUrl) {
  await navigate(baseUrl);
  await waitForStableViewport();
  await page(() => {
    window.__syntheticShortcutEvents = [];
    window.addEventListener("keydown", (event) => {
      setTimeout(() => window.__syntheticShortcutEvents.push({ key: event.key, prevented: event.defaultPrevented }), 0);
    });
  });
  async function shortcut(value, code, modifiers, prevented, commands) {
    await page(() => { window.__syntheticShortcutEvents.length = 0; });
    await key(value, code, modifiers, value === "Backspace" ? 8 : value === "Delete" ? 46 : value.toUpperCase().charCodeAt(0), commands);
    await waitFor(() => window.__syntheticShortcutEvents.length > 0);
    assert.equal(await page(() => window.__syntheticShortcutEvents.at(-1).prevented), prevented,
      `${value} modifiers=${modifiers} has the correct keyboard owner`);
  }
  for (const [value, code, modifiers] of [["c", "KeyC", 4], ["v", "KeyV", 2], ["d", "KeyD", 4],
    ["z", "KeyZ", 4], ["Z", "KeyZ", 12], ["y", "KeyY", 2], ["Backspace", "Backspace", 0], ["Delete", "Delete", 0]]) {
    await shortcut(value, code, modifiers, true);
  }
  assert.equal((await evidence()).edits.length, 0, "Unavailable commands stay read-only");
  assert.equal((await evidence()).removals.length, 0);
  await zoomToFirstCard();
  await shortcut("d", "KeyD", 4, true);
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().items.length === 101);
  await waitForHistoryAction("canvas.edit.undo");
  const firstCopy = (await evidence()).edits.at(-1).copies[0].targetItemId;
  const reads = (await evidence()).requests.filter((request) => request.path.endsWith("/canvas/editor")).length;
  await shortcut("c", "KeyC", 4, true);
  await waitFor((before) => window.__agenvasCanvasEditingFixture.evidence().requests.filter((request) => request.path.endsWith("/canvas/editor")).length > before, reads);
  await waitForHistoryAction("canvas.edit.undo");
  await shortcut("v", "KeyV", 2, true);
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().items.length === 102);
  await waitForHistoryAction("canvas.edit.undo");
  const pasted = (await evidence()).edits.at(-1).copies[0].targetItemId;
  for (const [value, modifiers, count, next] of [["z", 4, 101, "redo"], ["y", 2, 102, "undo"],
    ["z", 2, 101, "redo"], ["Z", 12, 102, "undo"]]) {
    await shortcut(value, `Key${value.toUpperCase()}`, modifiers, true, [count === 101 ? "undo" : "redo"]);
    await waitFor((expected) => window.__agenvasCanvasEditingFixture.evidence().items.length === expected, count);
    await waitForHistoryAction(`canvas.edit.${next}`);
  }
  await screenshot("shortcuts-copy-paste-history");
  for (const [id, value, count] of [[pasted, "Backspace", 101], [firstCopy, "Delete", 100]]) {
    await click(`${card(id)} .artifact-card-title-icon`);
    await shortcut(value, value, 0, true);
    await waitFor((expected) => window.__agenvasCanvasEditingFixture.evidence().items.length === expected, count);
  }
  assert.equal((await evidence()).removals.length, 2);
  await zoomToFirstCard();
  const editor = await openTextEditor("synthetic-card-0");
  const draft = "Synthetic native text history";
  await text(editor, draft);
  await cdp("Input.insertText", { text: " plus" });
  await shortcut("z", "KeyZ", 4, false, ["undo"]);
  await waitFor((target, value) => document.querySelector(target).value === value, editor, draft);
  await shortcut("Z", "KeyZ", 12, false, ["redo"]);
  await waitFor((target, value) => document.querySelector(target).value === value, editor, `${draft} plus`);
  await shortcut("Backspace", "Backspace", 0, false, ["deleteBackward"]);
  await waitFor((target, value) => document.querySelector(target).value === value, editor, `${draft} plu`);
  await page((target) => document.querySelector(target).setSelectionRange(0, 0), editor);
  await shortcut("Delete", "Delete", 0, false, ["deleteForward"]);
  await waitFor((target, value) => document.querySelector(target).value === value, editor, `${draft.slice(1)} plu`);
  // Copy/paste keyboard ownership is checked without reading or writing the system clipboard.
  await shortcut("c", "KeyC", 4, false);
  await shortcut("v", "KeyV", 2, false);
  assert.equal((await evidence()).textSaves.length, 0);
  assert.equal((await evidence()).items.length, 100);
  assert.equal((await evidence()).edits.length, 2);
  assert.equal((await evidence()).replays.length, 4);
  assert.equal((await evidence()).removals.length, 2);
  assert.deepEqual((await evidence()).unexpected, []);
  assert.deepEqual(runtimeErrors, []);
  await screenshot("shortcuts-native-text-history");
  console.log(`Canvas shortcuts passed: native Cmd/Ctrl copy, paste, duplicate, undo/redo and both delete keys; unavailable commands prevent browser defaults; textarea undo/redo/deletion and clipboard-key isolation preserve the draft. Synthetic screenshots: ${screenshots}`);
}
async function runEmptyCreationRegression(baseUrl) {
  await cdp("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-motion", value: "reduce" }] });
  for (const name of ["文字", "图片", "音频", "视频"]) {
    await cdp("Page.navigate", { url: new URL(`${route}?locale=zh&empty-creation=1`, baseUrl).href });
    await waitFor(() => document.querySelectorAll(".canvas-empty-types button").length === 4);
    if (name === "图片") await clickNamed("button", "canvas.workspace.zoomOut");
    if (name === "视频") await clickNamed("button", "canvas.workspace.zoomIn");
    const before = await page(() => document.querySelector(".react-flow__viewport").style.transform);
    await click(await namedSelector(".canvas-empty-types button", name));
    await waitFor(() => {
      const node = document.querySelector(".react-flow__node");
      const canvas = document.querySelector(".react-flow");
      if (!node || !canvas) return false;
      const n = node.getBoundingClientRect(), c = canvas.getBoundingClientRect();
      return n.width > 0 && n.left >= c.left && n.right <= c.right && n.top >= c.top && n.bottom <= c.bottom;
    });
    await page(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const after = await page(() => {
      const node = document.querySelector(".react-flow__node").getBoundingClientRect();
      const canvas = document.querySelector(".react-flow").getBoundingClientRect();
      return { dx: node.x + node.width / 2 - canvas.x - canvas.width / 2,
        dy: node.y + node.height / 2 - canvas.y - canvas.height / 2,
        viewport: document.querySelector(".react-flow__viewport").style.transform };
    });
    assert.ok(Math.abs(after.dx) < 1 && Math.abs(after.dy) < 1, `The first ${name} card must stay centered: ${JSON.stringify(after)}`);
    assert.equal(after.viewport, before, "Creating a centered card must not move the empty canvas viewport");
    assert.equal((await evidence()).items.length, 1);
  }
  assert.deepEqual(runtimeErrors, []);
  console.log("Empty canvas creation passed: first text/image/audio/video card remains centered, visible and persisted without a viewport jump; synthetic only");
}

async function runInitialGroupRegression(baseUrl) {
  for (const failedRead of [false, true]) {
    await cdp("Page.navigate", { url: new URL(`${route}?locale=zh&initial-group=1${failedRead ? "&group-read-failure=1" : ""}`, baseUrl).href });
    if (failedRead) {
      await waitFor(() => Boolean(document.querySelector('[role="alert"]')));
      await clickNamed("button", "common.retryRead");
    }
    await waitFor(() => Boolean(document.querySelector(".canvas-group--collapsed")));
    await page(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const layout = await page(() => {
      const canvas = document.querySelector(".react-flow").getBoundingClientRect();
      const visible = [...document.querySelectorAll(".react-flow__node")]
        .filter((node) => getComputedStyle(node).visibility !== "hidden")
        .map((node) => ({ id: node.dataset.id, rect: node.getBoundingClientRect().toJSON() }));
      return { canvas: canvas.toJSON(), visible,
        zoom: new DOMMatrixReadOnly(getComputedStyle(document.querySelector(".react-flow__viewport")).transform).a };
    });
    assert.equal(layout.visible.length, 2);
    for (const { id, rect } of layout.visible) assert.ok(rect.left >= layout.canvas.left
      && rect.right <= layout.canvas.right && rect.top >= layout.canvas.top && rect.bottom <= layout.canvas.bottom,
    `Initial fit must use visible group contents after delayed metadata, ${id}: ${JSON.stringify(layout)}`);
    assert.ok((await evidence()).requests.every((request) => request.method === "GET"), "Reading or retrying groups must not write canvas data");
  }
  assert.deepEqual(runtimeErrors, []);
  console.log("Initial collapsed-group fit passed: delayed metadata and explicit read retry, hidden members excluded, visible nodes remain in view without writes; synthetic only");
}

async function runEditorCloseRegression(baseUrl) {
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1024, height: 900, deviceScaleFactor: 1, mobile: false });
  await navigate(baseUrl);
  await waitFor(() => Boolean(window.__agenvasCanvasEditingFixture.setViewport));
  const original = (await evidence()).items;
  const promptSelector = ".workspace-media-editor .media-draft-prompt";
  const closeSelector = ".workspace-media-editor .workspace-bottom-close";
  async function openAtEdge(item, zoom, editorTop) {
    await page(async (item, zoom, editorTop) => {
      const canvas = document.querySelector(".react-flow").getBoundingClientRect();
      const gap = item.artifact.kind === "TEXT" ? 16 : 32;
      await window.__agenvasCanvasEditingFixture.setViewport({
        x: canvas.width - 120 - (item.x + item.width / 2) * zoom,
        y: editorTop == null ? 100 - item.y * zoom : editorTop - canvas.top - (item.y + item.height) * zoom - gap,
        zoom,
      }, { duration: 0 });
      window.__agenvasCanvasEditingFixture.select(item.id);
    }, item, zoom, editorTop);
    await waitFor((selector) => Boolean(document.querySelector(selector))
      && document.querySelector(".workspace-media-editor .media-draft-prompt")?.getAttribute("aria-readonly") !== "true", closeSelector);
    await waitForStableViewport();
  }
  async function closeLayout(item) {
    const layout = await page((item) => {
      const button = document.querySelector(".workspace-media-editor .workspace-bottom-close");
      const rect = button.getBoundingClientRect();
      const editor = document.querySelector(".workspace-media-editor");
      const panel = editor.querySelector(".media-draft-editor");
      const node = document.querySelector(`.react-flow__node[data-id="${item.id}"]`);
      return { close: rect.toJSON(), viewportWidth: innerWidth, viewportHeight: innerHeight,
        translation: { inline: button.style.translate, computed: getComputedStyle(button).translate, duration: getComputedStyle(button).transitionDuration },
        director: document.querySelector(".agent-chat-dock").getBoundingClientRect().toJSON(),
        canvas: document.querySelector(".react-flow").getBoundingClientRect().toJSON(),
        editor: editor.getBoundingClientRect().toJSON(), node: node.getBoundingClientRect().toJSON(),
        scrollTop: panel.scrollTop, scrollHeight: panel.scrollHeight, clientHeight: panel.clientHeight,
        centerHit: (() => { const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
          return { tag: hit?.tagName, label: hit?.getAttribute("aria-label"), text: hit?.textContent }; })(),
        hittable: [[rect.left + 2, rect.y + rect.height / 2], [rect.right - 2, rect.y + rect.height / 2],
          [rect.x + rect.width / 2, rect.top + 2], [rect.x + rect.width / 2, rect.bottom - 2],
          [rect.x + rect.width / 2, rect.y + rect.height / 2]]
          .every(([x, y]) => button.contains(document.elementFromPoint(x, y))) };
    }, item);
    const { close, canvas, editor, node, viewportWidth, viewportHeight } = layout;
    assert.ok(close.width >= 24 && close.height >= 24, `Complete close target is measurable: ${JSON.stringify(layout)}`);
    assert.ok(close.left >= Math.max(0, canvas.left) && close.right <= Math.min(viewportWidth, canvas.right)
      && close.top >= Math.max(0, canvas.top) && close.bottom <= Math.min(viewportHeight, canvas.bottom) && layout.hittable,
    `Shared editor close button remains fully visible and hittable: ${JSON.stringify(layout)}`);
    assert.ok(Math.abs(editor.left + editor.width / 2 - node.left - node.width / 2) < 1,
      "The editor retains its horizontal node anchor");
    assert.ok(Math.abs(editor.top - node.bottom - (item.artifact.kind === "TEXT" ? 16 : 32)) < 1,
      "The editor stays below its node rather than shifting upward");
    return layout;
  }
  async function closeByPointer(item) {
    const before = await viewport();
    await click(closeSelector);
    await waitFor(() => !document.querySelector(".workspace-media-editor"));
    assert.equal(await page((selector) => document.activeElement === document.querySelector(selector), card(item.id)), true,
      "Closing returns keyboard focus to the selected node");
    assert.deepEqual(await viewport(), before, "Closing does not move or zoom the canvas");
  }
  if (process.argv.includes("--editor-close-bottom-only")) {
    await cdp("Emulation.setDeviceMetricsOverride", { width: 1024, height: 560, deviceScaleFactor: 1, mobile: false });
    await openAtEdge(original[0], 1.8, 650);
    await closeLayout(original[0]);
    await closeByPointer(original[0]);
    return;
  }
  for (const width of [1024, 1280, 1440, 1920]) {
    await cdp("Emulation.setDeviceMetricsOverride", { width, height: 900, deviceScaleFactor: 1, mobile: false });
    for (const zoom of [0.6, 1, 1.8]) {
      for (const item of original) {
        await openAtEdge(item, zoom);
        const layout = await closeLayout(item);
        if (width === 1024 && zoom === 1 && item.artifact.kind === "IMAGE") {
          console.log(`Editor close edge probe: ${JSON.stringify(layout)}`);
          await screenshot("editor-close-right-edge");
        }
        await closeByPointer(item);
      }
    }
  }
  // Native keyboard input, Escape, focus restoration and reopening the same draft.
  for (const item of original) {
    await openAtEdge(item, 1);
    const before = await viewport();
    const draft = `Synthetic preserved ${item.artifact.kind} prompt`;
    await page((selector) => document.querySelector(selector).focus({ preventScroll: true }), promptSelector);
    await cdp("Input.insertText", { text: draft });
    await waitFor((selector, expected) => {
      const element = document.querySelector(selector); return (element?.value ?? element?.textContent) === expected;
    }, promptSelector, draft);
    await key("Escape", "Escape", 0, 27);
    await waitFor(() => !document.querySelector(".workspace-media-editor"));
    assert.equal(await page((selector) => document.activeElement === document.querySelector(selector), card(item.id)), true);
    assert.deepEqual(await viewport(), before, "Escape returns focus without panning");
    await key("Enter", "Enter", 0, 13);
    await waitFor((selector) => Boolean(document.querySelector(selector)), promptSelector);
    await waitFor((selector, expected) => {
      const element = document.querySelector(selector); return (element?.value ?? element?.textContent) === expected;
    }, promptSelector, draft);
    await closeLayout(item);
    await closeByPointer(item);
  }
  // The panel may shrink after opening, but the camera and node anchoring stay put.
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1920, height: 900, deviceScaleFactor: 1, mobile: false });
  await openAtEdge(original[0], 1);
  const beforeResize = await viewport();
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1024, height: 560, deviceScaleFactor: 1, mobile: false });
  await waitForStableViewport();
  await closeLayout(original[0]);
  assert.deepEqual(await viewport(), beforeResize, "Window narrowing does not move the camera");
  await closeByPointer(original[0]);

  // Constrained panels really scroll; their close control remains outside that scroll.
  for (const item of original) {
    await openAtEdge(item, 1.8, 400);
    const beforeScroll = await closeLayout(item);
    assert.ok(beforeScroll.scrollHeight > beforeScroll.clientHeight, `Short ${item.artifact.kind} panel is scrollable`);
    await page(() => { document.querySelector(".workspace-media-editor .media-draft-editor").scrollTop = 10000; });
    await waitFor(() => document.querySelector(".workspace-media-editor .media-draft-editor").scrollTop > 0);
    const scrolled = await closeLayout(item);
    assert.ok(Object.entries(scrolled.close).every(([key, value]) => Math.abs(value - beforeScroll.close[key]) < 0.5),
      "Panel scrolling keeps the close button stationary within browser subpixel rounding");
    assert.deepEqual(scrolled.editor, beforeScroll.editor, "Panel scrolling does not reposition the editor");
    await closeByPointer(item);
  }
  // A scrolling host can move the canvas without changing its native transform.
  await page(() => {
    document.body.style.paddingTop = "180px";
    document.body.style.minHeight = "1100px";
    window.scrollTo(0, 100);
  });
  await waitFor(() => window.scrollY === 100);
  await openAtEdge(original[0], 1, 100);
  await closeLayout(original[0]);
  await closeByPointer(original[0]);
  assert.equal(await page(() => window.scrollY), 100, "Closing does not scroll the host page");
  await page(() => { window.scrollTo(0, 0); document.body.style.paddingTop = ""; document.body.style.minHeight = ""; });
  await waitFor(() => window.scrollY === 0);
  // A tall zoomed node can place the whole editor below the canvas. Its close
  // control remains reachable without moving that editor over the source node.
  await openAtEdge(original[0], 1.8, 650);
  await closeLayout(original[0]);
  await screenshot("editor-close-bottom-edge");
  await closeByPointer(original[0]);
  // Real dock controls and stacking, backed by an empty synthetic director.
  // At this height the expanded dock and bottom-left viewport controls leave
  // only a diagonal gap, so one-axis edge correction cannot make the X usable.
  await openAtEdge(original[0], 1.8, 650);
  await clickNamed("button", "agent.chat.openDirector");
  await clickNamed("button", "agent.chat.expandDirector");
  await waitFor(() => Boolean(document.querySelector(".agent-chat-dock-panel--expanded:not([hidden])")));
  await waitForStableViewport();
  // The dock resize and the close button's native 150ms CSS transition settle
  // independently of the camera. Observe both rather than guessing a delay.
  const expandedTrace = [];
  let steady = 0;
  for (let attempt = 0; attempt < 40; attempt++) {
    const sample = await page(() => {
      const button = document.querySelector(".workspace-media-editor .workspace-bottom-close");
      const close = button.getBoundingClientRect();
      const dock = document.querySelector(".agent-chat-dock").getBoundingClientRect();
      return { close: [close.x, close.y, close.width, close.height], dock: [dock.x, dock.y, dock.width, dock.height],
        hittable: button.contains(document.elementFromPoint(close.x + close.width / 2, close.y + close.height / 2)) };
    });
    const prior = expandedTrace.at(-1);
    steady = prior && sample.hittable && [...sample.close, ...sample.dock].every((value, index) =>
      Math.abs(value - [...prior.close, ...prior.dock][index]) < 0.1) ? steady + 1 : 0;
    expandedTrace.push(sample);
    if (steady >= 3) break;
    await pause(50);
  }
  console.log(`Expanded director geometry settling: ${JSON.stringify(expandedTrace)}`);
  assert.ok(steady >= 3, "Expanded director and close button settle into a hittable gap");
  const expanded = await closeLayout(original[0]);
  console.log(`Expanded director close probe: ${JSON.stringify(expanded)}`);
  await screenshot("editor-close-expanded-director");
  await closeByPointer(original[0]);
  assert.equal(await page(() => Boolean(document.querySelector(".agent-chat-dock-panel--expanded:not([hidden])"))), true,
    "Closing the editor leaves the nonmodal director open");
  await clickNamed("button", "agent.chat.closeDirector");
  assert.deepEqual((await evidence()).items, original, "No node layout or content is changed by edge protection");
  assert.deepEqual((await evidence()).unexpected, []);
  assert.deepEqual(runtimeErrors, [], "No uncaught application runtime errors");
  assert.equal((await evidence()).requests.some((request) => request.method === "POST"), false,
    "The synthetic browser regression never invokes generation or another command");
  console.log(`Editor close browser passed: 48 right-edge cases (four media/text kinds, four desktop widths, three canvas zooms), native pointer and Escape/Enter focus/draft restoration, window narrowing, four scrolled panels, page scrolling/top edge, bottom edge, and real expanded director dock. Synthetic screenshots: ${screenshots}`);
}

try {
  const config = { root, configFile: join(root, "vite.config.ts") };
  if (production) {
    const outDir = join(profile, "build");
    await build({ ...config, logLevel: "warn", build: { outDir, emptyOutDir: true, rollupOptions: { input: join(root, "e2e/canvas-editing.html") } } });
    server = await preview({ ...config, build: { outDir }, preview: { host: "127.0.0.1", port: 0, open: false, proxy: {} } });
  } else { server = await createServer({ ...config, server: { host: "127.0.0.1", port: 0, open: false } }); await server.listen(); }
  chrome = spawn(chromePath, ["--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
    "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Chrome startup timed out")), 20000);
    chrome.once("error", (failure) => { clearTimeout(timer); reject(failure); });
    chrome.stderr.on("data", (data) => { const match = data.toString().match(/DevTools listening on (ws:\/\/\S+)/);
      if (match) { clearTimeout(timer); resolve(new URL(match[1]).origin.replace("ws:", "http:")); } });
  });
  const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: "PUT" })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", ({ data }) => {
    const message = JSON.parse(data);
    if (message.method === "Runtime.exceptionThrown") runtimeErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id); if (!waiting) return;
    pending.delete(message.id); if (message.error) waiting.reject(new Error(message.error.message)); else waiting.resolve(message.result);
  });
  await cdp("Page.enable"); await cdp("Runtime.enable");
  const baseUrl = server.resolvedUrls.local[0];
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  if (editorCloseOnly) await runEditorCloseRegression(baseUrl);
  else if (initialGroupOnly) await runInitialGroupRegression(baseUrl);
  else if (emptyCreationOnly) await runEmptyCreationRegression(baseUrl);
  else if (shortcutsOnly) await runShortcutRegression(baseUrl);
  else if (selectAllOnly) await runSelectAllRegression(baseUrl);
  else if (connectionDropOnly) await runConnectionDropRegression(baseUrl);
  else if (groupEntryOnly) await runGroupEntryRegression(baseUrl);
  else if (motionOnly) await runMotionRegression(baseUrl);
  else if (dragCase) await runDragRegression(baseUrl);
  else {
  await navigate(baseUrl);
  const groupId = await createGroup("Synthetic sequence");
  console.log("Canvas browser: group created from two real selected cards");
  assert.equal((await evidence()).items.filter((item) => item.groupId === groupId).length, 2);
  await groupMenu(groupId, "canvas.group.rename");
  await text('[role="dialog"]:not([hidden]) input', "Synthetic sequence / named");
  await clickNamed('[role="dialog"]:not([hidden]) button', "canvas.group.save");
  await waitFor((id) => window.__agenvasCanvasEditingFixture.evidence().groups.find((group) => group.id === id)?.name === "Synthetic sequence / named", groupId);
  const beforeDrag = (await evidence()).items.slice(0, 2);
  await drag(`${group(groupId)} .canvas-group__name`, 100, 70);
  await waitFor((before) => window.__agenvasCanvasEditingFixture.evidence().items[0].x !== before, beforeDrag[0].x);
  const afterDrag = (await evidence()).items.slice(0, 2);
  assert.ok(Math.abs(afterDrag[0].x - beforeDrag[0].x) > 1, "Group moves through a persisted layout edit");
  assert.ok(Math.abs((afterDrag[0].x - beforeDrag[0].x) - (afterDrag[1].x - beforeDrag[1].x)) < 0.01, "Members move together horizontally");
  assert.ok(Math.abs((afterDrag[0].y - beforeDrag[0].y) - (afterDrag[1].y - beforeDrag[1].y)) < 0.01, "Members move together vertically");
  console.log("Canvas browser: rename and complete group drag persisted");
  const editor = await openTextEditor("synthetic-card-0");
  await text(editor, "Unsaved synthetic draft survives folding.");
  const inputEditCount = (await evidence()).edits.length;
  for (const [value, modifiers] of [["d", 4], ["c", 4], ["v", 4], ["z", 4], ["Z", 12], ["z", 2], ["y", 2]]) await key(value, `Key${value.toUpperCase()}`, modifiers);
  assert.equal((await evidence()).edits.length, inputEditCount, "Editor shortcuts never mutate canvas history or clipboard copies");
  console.log("Canvas browser: native editor shortcut isolation passed");
  await text(editor, "Unsaved synthetic draft survives folding.");
  await page((selector) => { window.__syntheticOriginalTextEditor = document.querySelector(selector); }, editor);
  await click(`${group(groupId)} button[aria-label=${JSON.stringify(message("canvas.group.collapse"))}]`);
  await waitFor((id) => window.__agenvasCanvasEditingFixture.evidence().groups.find((group) => group.id === id)?.collapsed, groupId);
  assert.equal(await page((selector) => document.querySelector(selector) === window.__syntheticOriginalTextEditor
    && document.querySelector(selector)?.value === "Unsaved synthetic draft survives folding.", editor), true, "Folding keeps the actual editor DOM and unsaved content mounted");
  assert.equal(await page(() => getComputedStyle(document.querySelector('.react-flow__node[data-id="synthetic-card-0"]')).visibility), "hidden");
  await waitFor(() => !document.querySelector('[data-id="canvas-connection:synthetic-internal-connection"]'));
  await waitFor((id) => {
    const path = document.querySelector('[data-id="canvas-connection:synthetic-external-connection"] .react-flow__edge-path');
    const handle = document.querySelector(`.canvas-group[data-group-id="${id}"] [data-handleid="group-output"]`);
    if (!path || !handle) return false;
    const first = path.getPointAtLength(0);
    const source = new DOMPoint(first.x, first.y).matrixTransform(path.getScreenCTM());
    const bounds = handle.getBoundingClientRect();
    // React Flow attaches a right-facing relation to the handle's outside edge.
    return Math.abs(source.x - bounds.right) < 2 && Math.abs(source.y - bounds.y - bounds.height / 2) < 2;
  }, groupId);
  console.log("Canvas browser: folded live text draft and internal/external edge projection passed");
  await screenshot("folded-group-retains-text-draft");
  await click(`${group(groupId)} button[aria-label=${JSON.stringify(message("canvas.group.expand"))}]`);
  await waitFor((selector) => document.querySelector(selector)?.value === "Unsaved synthetic draft survives folding."
    && getComputedStyle(document.querySelector('.react-flow__node[data-id="synthetic-card-0"]')).visibility !== "hidden", editor);
  await page(() => window.__agenvasCanvasEditingFixture.failNextTextSave(409));
  await clickNamed(`${card("synthetic-card-0")} button`, "text.editor.saveVersion");
  await waitFor(() => Boolean(document.querySelector(".text-card-error")));
  assert.equal(await page((selector) => document.querySelector(selector).value, editor), "Unsaved synthetic draft survives folding.", "A text CAS failure retains the draft");
  await clickNamed(`${card("synthetic-card-0")} button`, "text.editor.saveVersion");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().items[0].artifact.version === 1);
  await groupMenu(groupId, "canvas.group.duplicate");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().items.length === 102 && window.__agenvasCanvasEditingFixture.evidence().groups.length === 2);
  console.log("Canvas browser: independent group copied");
  const duplicateState = await evidence();
  const copies = duplicateState.items.filter((item) => !item.id.startsWith("synthetic-card-"));
  assert.equal(copies.length, 2);
  assert.ok(copies.every((item) => !duplicateState.items.slice(0, 100).some((source) => source.artifact.id === item.artifact.id)), "Copied text owns independent artifacts");
  await waitForHistoryAction("canvas.edit.undo");
  await key("z", "KeyZ", 4);
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().items.length === 100 && window.__agenvasCanvasEditingFixture.evidence().groups.length === 1);
  await waitForHistoryAction("canvas.edit.redo");
  await key("Z", "KeyZ", 12);
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().items.length === 102 && window.__agenvasCanvasEditingFixture.evidence().groups.length === 2);
  const copiedEditor = await openTextEditor(copies[0].id);
  await text(copiedEditor, "Independent copied character notes.");
  await clickNamed(`${card(copies[0].id)} button`, "text.editor.saveVersion");
  await waitFor((id) => window.__agenvasCanvasEditingFixture.evidence().items.find((item) => item.id === id)?.artifact.resourceDefaultVersion.content.text === "Independent copied character notes.", copies[0].id);
  assert.equal((await evidence()).items[0].artifact.resourceDefaultVersion.content.text, "Unsaved synthetic draft survives folding.", "Editing a clone leaves original text unchanged");
  await click(`${card(copies[0].id)} .artifact-card-title-icon`);
  const beforeDuplicate = (await evidence()).items.length;
  await waitForHistoryAction("canvas.edit.undo");
  await key("d", "KeyD", 4);
  await waitFor((count) => window.__agenvasCanvasEditingFixture.evidence().items.length > count, beforeDuplicate);
  await waitForHistoryAction("canvas.edit.undo");
  const beforeCopy = (await evidence()).items.length;
  const readsBeforeCopy = (await evidence()).requests.filter((request) => request.path.endsWith("/canvas/editor")).length;
  await key("c", "KeyC", 4);
  await waitFor((count) => window.__agenvasCanvasEditingFixture.evidence().requests.filter((request) => request.path.endsWith("/canvas/editor")).length > count, readsBeforeCopy);
  await waitForHistoryAction("canvas.edit.undo");
  assert.equal((await evidence()).items.length, beforeCopy, "Copy alone is read only");
  await key("v", "KeyV", 4);
  await waitFor((count) => window.__agenvasCanvasEditingFixture.evidence().items.length > count, beforeCopy);
  await screenshot("large-canvas-group-copy-history");
  await checkLayout();
  assert.equal((await evidence()).unexpected.length, 0);

  // A failed grouping leaves its naming form and source membership intact. It adds no history.
  await navigate(baseUrl);
  await selectFirstPair(); await clickNamed(".workspace-selection-toolbar button", "canvas.group.create");
  await text('[role="dialog"]:not([hidden]) input', "Retained group draft after conflict");
  await page(() => window.__agenvasCanvasEditingFixture.failNextEdit(409));
  await clickNamed('[role="dialog"]:not([hidden]) button', "canvas.group.save");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().edits.length === 1
    && document.querySelector('[role="dialog"]:not([hidden]) input')?.disabled);
  assert.equal(await page(() => document.querySelector('[role="dialog"]:not([hidden]) input')?.value), "Retained group draft after conflict");
  assert.equal((await evidence()).groups.length, 0); assert.equal((await evidence()).receipts.length, 0);
  await screenshot("canvas-group-conflict-retains-name");
  await checkLayout();
  await clickNamed('[role="dialog"]:not([hidden]) button', "canvas.edit.retry");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().groups.length === 1 && !document.querySelector('[role="dialog"]:not([hidden])'));
  const retried = await evidence();
  assert.equal(retried.edits[0].commandId, retried.edits[1].commandId, "An explicit retry reuses the frozen idempotency key");
  assert.equal(retried.receipts.length, 1, "Only one successful edit enters history");
  const retryGroupId = retried.groups[0].id;
  await groupMenu(retryGroupId, "canvas.group.rename");
  await text('[role="dialog"]:not([hidden]) input', "Retained name after unavailable save");
  await page(() => window.__agenvasCanvasEditingFixture.failNextEdit(503));
  await clickNamed('[role="dialog"]:not([hidden]) button', "canvas.group.save");
  await waitFor(() => Boolean(document.querySelector('[role="dialog"]:not([hidden]) [role="alert"]')));
  assert.equal(await page(() => document.querySelector('[role="dialog"]:not([hidden]) input').value), "Retained name after unavailable save");
  assert.equal(await page(() => document.querySelector('[role="dialog"]:not([hidden]) input').disabled), true, "A pending failed intent freezes its original name");
  await clickNamed('[role="dialog"]:not([hidden]) button', "common.cancel");
  await waitFor(() => !document.querySelector('[role="dialog"]:not([hidden])'));
  await click(`button[aria-label=${JSON.stringify(message("canvas.group.create"))}]`);
  await page(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await page(() => Boolean(document.querySelector('[role="dialog"]:not([hidden])'))), false,
    "A failed pending intent cannot open a new frozen empty group form");
  await groupMenu(retryGroupId, "canvas.group.rename");
  await page(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await page(() => Boolean(document.querySelector('[role="dialog"]:not([hidden])'))), false,
    "A failed pending intent cannot open a different rename form");
  await clickNamed(".canvas-feedback button", "canvas.edit.retry");
  await waitFor(() => window.__agenvasCanvasEditingFixture.evidence().groups[0]?.name === "Retained name after unavailable save"
    && !document.querySelector('[role="dialog"]:not([hidden])'));

  for (const viewport of [{ width: 1440, height: 900 }, { width: 1024, height: 768 }]) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await navigate(baseUrl, "ru"); await selectFirstPair();
    await checkLayout(); await clickNamed(".workspace-selection-toolbar button", "canvas.group.create");
    await text('[role="dialog"]:not([hidden]) input', "Длинное название группы для проверки переноса и доступности элементов управления");
    await checkLayout(); await screenshot(`canvas-editing-ru-${viewport.width}x${viewport.height}`);
  }
  assert.deepEqual(runtimeErrors, [], "No uncaught application runtime errors");
  console.log(`Canvas editing browser passed: 100 nodes, grouping/rename/group drag/folded live editor and edges, independent text copies, undo/redo, keyboard editing isolation, CAS preservation and Russian desktop layouts. Synthetic screenshots: ${screenshots}`);
  }
} catch (failure) {
  try { await screenshot("failure"); } catch { /* Preserve the original verification failure. */ }
  try { console.error(JSON.stringify(await page(() => ({
    groups: window.__agenvasCanvasEditingFixture?.evidence().groups,
    dialogs: [...document.querySelectorAll('[role="dialog"]')].map((element) => element.outerHTML.slice(0, 500)),
    unexpected: window.__agenvasCanvasEditingFixture?.evidence().unexpected,
    replayCount: window.__agenvasCanvasEditingFixture?.evidence().replays.length,
    activeElement: document.activeElement?.outerHTML.slice(0, 400),
    foldedEndpoints: [...document.querySelectorAll('.canvas-group [data-handleid="group-output"]')].map((element) => element.getBoundingClientRect().toJSON()),
    externalPath: (() => {
      const path = document.querySelector('[data-id="canvas-connection:synthetic-external-connection"] .react-flow__edge-path');
      if (!path) return null;
      const first = path.getPointAtLength(0); const source = new DOMPoint(first.x, first.y).matrixTransform(path.getScreenCTM());
      return { d: path.getAttribute("d"), x: source.x, y: source.y };
    })(),
  })), null, 2)); } catch { /* Failure may precede fixture navigation. */ }
  console.error(`Synthetic canvas screenshots: ${screenshots}`); throw failure;
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) { const exited = new Promise((resolve) => chrome.once("exit", resolve)); chrome.kill(); await exited; }
  if (server) {
    if (production) await new Promise((resolve, reject) => server.httpServer.close((error) => error ? reject(error) : resolve()));
    else await server.close();
  }
  await rm(profile, { recursive: true, force: true, maxRetries: 3 });
}
