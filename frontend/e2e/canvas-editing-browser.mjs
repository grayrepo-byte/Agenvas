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
async function key(key, code = `Key${key.toUpperCase()}`, modifiers = 0, windowsVirtualKeyCode = key.toUpperCase().charCodeAt(0)) {
  for (const type of ["keyDown", "keyUp"]) await cdp("Input.dispatchKeyEvent", { type, key, code, modifiers, windowsVirtualKeyCode });
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
async function ready(expected = dragCase || motionOnly ? 4 : 100) {
  await waitFor((expected) => document.querySelectorAll(".react-flow__node-canvasCard").length === expected
    && window.__agenvasCanvasEditingFixture.evidence().requests.some((request) => request.path.endsWith("/canvas/editor")), expected);
  assert.equal((await evidence()).unexpected.length, 0);
}
async function navigate(baseUrl, nextLocale = "zh", { motionLarge = false } = {}) {
  locale = nextLocale;
  await cdp("Page.navigate", { url: new URL(`${route}?locale=${locale}${dragCase || motionOnly ? "&hand-drag=1" : ""}${motionLarge ? "&motion-large=1" : ""}`, baseUrl).href });
  await ready(motionLarge ? 100 : undefined);
}
async function zoomToFirstCard() {
  // Native wheel zoom stays anchored to the first card, even with another 99 cards in view.
  for (let attempt = 0; attempt < 6; attempt++) {
    const box = await page((selector) => document.querySelector(selector).getBoundingClientRect().toJSON(), card("synthetic-card-0"));
    if (box.width >= 250) break;
    await cdp("Input.dispatchMouseEvent", { type: "mouseWheel", x: box.x + box.width / 2, y: box.y + box.height / 2, deltaX: 0, deltaY: -280 });
    await pause(120);
  }
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
  if (motionOnly) await runMotionRegression(baseUrl);
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
