import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

const root = fileURLToPath(new URL("../", import.meta.url));
const route = "/e2e/storyboard-workbench.html";
const labels = JSON.parse(await readFile(join(root, "src/shared/i18n/locales/zh.json"), "utf8"));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const profile = await mkdtemp(join(tmpdir(), "agenvas-storyboard-browser-"));
const screenshots = await mkdtemp(join(tmpdir(), "agenvas-storyboard-screenshots-"));
const viewports = [{ width: 1440, height: 900 }, { width: 1024, height: 768 }, { width: 390, height: 844 }];
await chmod(profile, 0o700); await chmod(screenshots, 0o700);
const pending = new Map();
const runtimeErrors = [];
let server, chrome, socket, nextId = 0;
const pause = () => new Promise((resolve) => setTimeout(resolve, 50));
function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP ${method} did not respond within 10 seconds`)); }, 10000);
    pending.set(id, { resolve: (value) => { clearTimeout(timer); resolve(value); }, reject: (error) => { clearTimeout(timer); reject(error); } });
    socket.send(JSON.stringify({ id, method, params }));
  });
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
  await writeFile(join(screenshots, `${name}.png`), Buffer.from(capture.data, "base64"), { mode: 0o600 });
}
async function click(selector) {
  // Radix keeps closing portals during their CSS animation; wait for the active surface to settle.
  await waitFor((target) => {
    const control = document.querySelector(target);
    if (!control || control.matches(":disabled")) return false;
    const dialog = control.closest('[data-slot="dialog-content"]');
    return dialog?.getAttribute("data-state") !== "closed" && !(dialog?.getAnimations().some((animation) => animation.playState === "running"));
  }, selector);
  await waitFor((target) => {
    const control = document.querySelector(target); if (!control || control.matches(":disabled")) return false;
    control.scrollIntoView({ block: "center", inline: "nearest" });
    const bounds = control.getBoundingClientRect();
    return control.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
  }, selector);
  const point = await page((target) => {
    const control = document.querySelector(target);
    if (!control) throw new Error(`Missing control ${target}`);
    control.scrollIntoView({ block: "center", inline: "nearest" });
    const bounds = control.getBoundingClientRect();
    const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
    return { x, y, reachable: control.contains(document.elementFromPoint(x, y)) };
  }, selector);
  assert.equal(point.reachable, true, `Pointer can reach ${selector}`);
  const { x, y } = point;
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", x, y, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x, y, button: "left", clickCount: 1 });
}
async function namedSelector(query, name) {
  await waitFor((selector, label) => [...document.querySelectorAll(selector)].some((control) =>
    (control.getAttribute("aria-label") ?? control.textContent?.replace(/\s+/g, " ").trim()) === label), query, name);
  return page((selector, label) => {
    const controls = [...document.querySelectorAll(selector)].filter((control) =>
      (control.getAttribute("aria-label") ?? control.textContent?.replace(/\s+/g, " ").trim()) === label);
    if (controls.length !== 1) throw new Error(`Expected one ${selector} named ${label}, got ${controls.length}`);
    controls[0].setAttribute("data-browser-target", "current");
    return '[data-browser-target="current"]';
  }, query, name);
}
async function clickNamed(query, name) {
  await page(() => document.querySelectorAll("[data-browser-target]").forEach((control) => control.removeAttribute("data-browser-target")));
  await click(await namedSelector(query, name));
}
const message = (key) => labels[`storyboard.${key}`];
async function field(label) {
  return page((text) => {
    const labels = [...document.querySelectorAll("label")].filter((candidate) => candidate.textContent?.trim() === text);
    if (labels.length !== 1 || !labels[0].htmlFor) throw new Error(`Expected one labeled control ${text}`);
    return `[id=${JSON.stringify(labels[0].htmlFor)}]`;
  }, label);
}
async function typeField(label, text) {
  const selector = await field(label); await click(selector);
  await page((target) => document.querySelector(target).select(), selector);
  await cdp("Input.insertText", { text });
  await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Tab", code: "Tab" });
  await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Tab", code: "Tab" });
}
async function readField(label) { const selector = await field(label); return page((target) => document.querySelector(target).value, selector); }
async function selectValue(selector, value) {
  await click(selector);
  const option = `[role="option"][data-value=${JSON.stringify(value)}]`;
  await waitFor((target) => Boolean(document.querySelector(target)), option);
  await click(option);
  await waitFor(() => !document.querySelector('[role="listbox"][data-state="open"]'));
}
const evidence = () => page(() => JSON.parse(document.querySelector("#storyboard-browser-evidence").textContent));
const shotButton = (index) => `.storyboard-shot-row[data-shot-id="synthetic-shot-${index}"] button:not([role="checkbox"])`;
async function ready() { await waitFor(() => document.querySelectorAll(".storyboard-shot-row").length === 8 && document.querySelectorAll(".storyboard-production-grid [role=combobox]").length === 6); }
async function navigate(baseUrl, query = "") {
  await waitFor(() => {
    const save = document.querySelector('[data-storyboard-action="save"]');
    const fields = document.querySelector(".storyboard-shot-editor");
    return !save || save.matches(":disabled") && !fields?.matches(":disabled");
  });
  await cdp("Page.navigate", { url: new URL(`${route}${query}`, baseUrl).href }); await ready();
}
async function checkLayout() {
  const layout = await page(() => {
    const dialog = document.querySelector(".storyboard-workbench").getBoundingClientRect();
    const regions = [".storyboard-header", ".storyboard-main", ".storyboard-shot-list", ".storyboard-editor", ".storyboard-composition"];
    return { dialog: dialog.toJSON(), width: innerWidth, height: innerHeight, overflow: document.documentElement.scrollWidth > innerWidth,
      regions: regions.map((selector) => { const region = document.querySelector(selector); return { selector, ...region.getBoundingClientRect().toJSON(),
        scrollWidth: region.scrollWidth, clientWidth: region.clientWidth }; }) };
  });
  assert.ok(layout.dialog.left >= -1 && layout.dialog.top >= -1 && layout.dialog.right <= layout.width + 1 && layout.dialog.bottom <= layout.height + 1, JSON.stringify(layout));
  assert.equal(layout.overflow, false, "Document has no horizontal overflow");
  for (const region of layout.regions) {
    assert.ok(region.width > 0 && region.height > 0 && region.left >= -1 && region.right <= layout.width + 1, JSON.stringify(region));
    assert.ok(region.scrollWidth <= region.clientWidth + 1, `Region has no horizontal overflow: ${JSON.stringify(region)}`);
  }
  const controls = await page(() => [...document.querySelectorAll(".storyboard-workbench button:not(:disabled), .storyboard-workbench input:not(:disabled), .storyboard-workbench textarea:not(:disabled)")]
    .filter((control) => control.getClientRects().length && !control.closest("[hidden]") && getComputedStyle(control).visibility !== "hidden").length);
  for (let index = 0; index < controls; index++) {
    const control = await page((offset) => {
      const element = [...document.querySelectorAll(".storyboard-workbench button:not(:disabled), .storyboard-workbench input:not(:disabled), .storyboard-workbench textarea:not(:disabled)")]
        .filter((candidate) => candidate.getClientRects().length && !candidate.closest("[hidden]") && getComputedStyle(candidate).visibility !== "hidden")[offset];
      element.scrollIntoView({ block: "nearest", inline: "nearest" });
      const bounds = element.getBoundingClientRect(); const x = bounds.x + bounds.width / 2, y = bounds.y + bounds.height / 2;
      return { label: element.getAttribute("aria-label") ?? element.textContent, width: bounds.width, height: bounds.height,
        reachable: x >= 0 && x <= innerWidth && y >= 0 && y <= innerHeight && element.contains(document.elementFromPoint(x, y)) };
    }, index);
    assert.ok(control.reachable && control.width > 0 && control.height > 0, `Visible control remains reachable after scrolling: ${JSON.stringify(control)}`);
  }
}
try {
  // A dev server is required because the fixture's native media middleware is intentionally serve-only.
  server = await createServer({ root, configFile: join(root, "vite.config.ts"), server: { host: "127.0.0.1", port: 0, open: false } }); await server.listen();
  chrome = spawn(chromePath, ["--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
    "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
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
    if (message.method === "Runtime.exceptionThrown") runtimeErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id); if (!waiting) return;
    pending.delete(message.id); if (message.error) waiting.reject(new Error(message.error.message)); else waiting.resolve(message.result);
  });
  await cdp("Page.enable"); await cdp("Runtime.enable");
  const baseUrl = server.resolvedUrls.local[0];
  if (!process.argv.includes("--director-only")) {
  for (const viewport of process.argv.includes("--desktop-only") ? viewports.slice(0, 1) : viewports) {
    for (const locale of ["zh", "ru"]) {
      await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
      await navigate(baseUrl, `?locale=${locale}`); await checkLayout();
      await click(shotButton(3));
      await page(() => document.querySelector(".storyboard-editor-heading").scrollIntoView({ block: "nearest", inline: "nearest" }));
      await screenshot(`storyboard-${locale}-${viewport.width}x${viewport.height}`);
      assert.equal((await evidence()).runs.length, 0, "Browsing, choosing shots and scrolling never generates media");
    }
  }
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await navigate(baseUrl);
  const original = (await evidence()).saved;
  await click(shotButton(3));
  await typeField(message("field.dialogue"), "林岚：修改后的第三镜台词，明天再见。");
  await typeField(message("field.duration"), "7");
  assert.equal(await page((review) => document.querySelectorAll(".storyboard-shot-row").length === 8 && [...document.querySelectorAll(".storyboard-shot-row")]
    .filter((row) => row.textContent.includes(review)).length === 1, message("status.review")), true, "Only the third shot needs review in the draft");
  assert.equal(await page((review) => document.querySelector(".storyboard-composition").textContent.includes(review), message("status.review")), true, "Composition displays the edit impact immediately");
  await screenshot("storyboard-third-shot-edit-impact");
  await click('[data-storyboard-action="save"]');
  await waitFor(() => JSON.parse(document.querySelector("#storyboard-browser-evidence").textContent).saved.version === 2);
  const saved = (await evidence()).saved;
  assert.equal(saved.shots[2].dialogue, "林岚：修改后的第三镜台词，明天再见。"); assert.equal(saved.shots[2].durationSeconds, 7);
  assert.equal(saved.shots[2].revision, 2); assert.ok(saved.shots[2].productions.every((production) => production.needsReview)); assert.equal(saved.composition.needsReview, true);
  for (const [index, shot] of saved.shots.entries()) {
    assert.deepEqual(shot.productions.map((production) => production.selectedVersionId), original.shots[index].productions.map((production) => production.selectedVersionId), "Paid immutable results survive creative edits");
    if (index !== 2) assert.deepEqual(shot, original.shots[index], "Unchanged shot keeps all revisions and review state");
  }
  assert.equal(saved.composition.selectedVersionId, original.composition.selectedVersionId);
  await click('[data-storyboard-action="close"]'); await waitFor(() => !document.querySelector(".storyboard-workbench"));
  await click("#open-storyboard-fixture"); await ready(); await click(shotButton(3));
  assert.equal(await readField(message("field.dialogue")), saved.shots[2].dialogue); assert.equal(await readField(message("field.duration")), "7");
  await clickNamed('[role="tab"]', message("approvals").replace("{0}", "0"));
  await waitFor((text) => document.body.innerText.includes(text), message("noApprovals"));
  assert.equal((await evidence()).approvals.length, 0, "Opening the approval view never decides an approval");
  await clickNamed('[role="tab"]', message("shots").replace("{0}", "8"));

  // Native image/video/audio decoders read synthetic HTTP bytes through the same media URLs as the app.
  for (const [role, kind] of [["keyframe", "img"], ["video", "video"], ["audio", "audio"]]) {
    const label = message(`role.${role}`);
    await clickNamed(`.storyboard-production-grid section[aria-label=${JSON.stringify(label)}] > .storyboard-inline button`, message("preview"));
    await waitFor((selector, image) => { const media = document.querySelector(selector); return media && (image ? media.naturalWidth > 0 : media.readyState >= 1); }, kind === "audio" ? "audio" : `.media-preview-dialog ${kind}`, kind === "img");
    await screenshot(`storyboard-preview-${role}`);
    await click(kind === "audio" ? '[role="dialog"]:last-of-type button[data-slot="dialog-close"]' : `.media-preview-dialog button[aria-label=${JSON.stringify(labels["media.preview.close"])}]`);
    await waitFor(() => !document.querySelector(".media-preview-dialog, audio"));
  }
  await click('button[role="checkbox"][aria-label="选择镜头 SHOT-01"]');
  await click('button[role="checkbox"][aria-label="选择镜头 SHOT-02"]');
  await click('[data-storyboard-action="batchGenerate"]');
  await waitFor((text) => document.querySelector(".storyboard-batch")?.textContent.includes(text), message("batchConfirm"));
  assert.equal(await page(() => document.querySelectorAll(".storyboard-batch-list section").length), 2);
  assert.equal((await evidence()).runs.length, 0, "Batch preflight and its cost preview issue no generation requests");
  await screenshot("storyboard-batch-explicit-cost-confirmation");
  await clickNamed(".storyboard-batch button", message("cancel"));
  assert.equal((await evidence()).runs.length, 0, "Canceling batch confirmation leaves all paid requests untouched");
  await click('[data-storyboard-action="batchGenerate"]');
  await waitFor(() => Boolean(document.querySelector(".storyboard-batch")));
  await clickNamed(".storyboard-batch button", message("confirmPaidGeneration"));
  await waitFor(() => JSON.parse(document.querySelector("#storyboard-browser-evidence").textContent).runs.length === 2);
  assert.ok((await evidence()).runs.every((run) => run.expectedDraftVersion === 7), "Confirmed batch freezes each observed media draft version");

  await navigate(baseUrl, "?conflict=1"); await click(shotButton(3));
  await typeField(message("field.dialogue"), "保存冲突仍保留的第三镜草稿");
  await click('[data-storyboard-action="save"]');
  await waitFor((text) => document.body.innerText.includes(text), message("conflict"));
  assert.equal(await readField(message("field.dialogue")), "保存冲突仍保留的第三镜草稿");
  assert.equal((await evidence()).saved.version, 1);
  await screenshot("storyboard-conflict-retains-draft");
  await click('[data-storyboard-action="reload"]');
  await waitFor((text) => document.body.innerText.includes(text), message("discardConfirm"));
  await clickNamed('[role="dialog"][data-state="open"]:last-of-type button', message("cancel"));
  assert.equal(await readField(message("field.dialogue")), "保存冲突仍保留的第三镜草稿", "Canceling explicit reload retains the conflict draft");
  await click('[data-storyboard-action="reload"]');
  await clickNamed('[role="dialog"][data-state="open"]:last-of-type button', message("discard"));
  await waitFor((text) => [...document.querySelectorAll("textarea")].some((control) => control.value === text), original.shots[2].dialogue);
  assert.equal(await readField(message("field.dialogue")), original.shots[2].dialogue, "Explicit reload accepts the saved database projection");

  await navigate(baseUrl); await click('[data-storyboard-action="import"]');
  await typeField(message("importJson"), JSON.stringify([{ code: "DUPLICATE" }, { code: "DUPLICATE" }]));
  await clickNamed(".storyboard-import button", message("validateImport"));
  await waitFor((text) => document.body.innerText.includes(text), message("importInvalid"));
  assert.equal(await page((text) => [...document.querySelectorAll(".storyboard-import button")].find((button) => button.textContent.trim() === text)?.disabled, message("applyImport")), true);
  const imported = [{ code: "SHOT-09", scene: "SC-03", title: "合成补充镜头", dialogue: "林岚：再见。", durationSeconds: 3,
    productions: [{ role: "VIDEO", canvasItemId: "untrusted", selectedVersionId: "untrusted", confirmReviewed: true }], sourceVersionId: "untrusted", referenceVersionIds: ["untrusted"], approved: true }];
  await typeField(message("importJson"), JSON.stringify(imported));
  await clickNamed(".storyboard-import button", message("validateImport"));
  await waitFor((text) => document.querySelector(".storyboard-import")?.textContent.includes(text), "SHOT-09");
  assert.equal((await evidence()).saves.length, 0, "Import preview changes neither the saved board nor permissions");
  await screenshot("storyboard-import-validated-preview");
  await clickNamed(".storyboard-import button", message("applyImport"));
  await waitFor(() => document.querySelectorAll(".storyboard-shot-row").length === 9);
  await click('[data-storyboard-action="save"]');
  await waitFor(() => JSON.parse(document.querySelector("#storyboard-browser-evidence").textContent).saved.shots.length === 9);
  const importedShot = (await evidence()).saves.at(-1).shots.at(-1);
  assert.equal(importedShot.code, "SHOT-09"); assert.deepEqual(importedShot.productions, []); assert.deepEqual(importedShot.referenceVersionIds, []); assert.equal(importedShot.sourceVersionId, null);
  assert.equal((await evidence()).runs.length, 0, "Importing creative text does not authorize any media call");
  assert.equal((await evidence()).approvals.length, 0);

  await navigate(baseUrl); await click(shotButton(3));
  await clickNamed("button", message("chooseReference"));
  await selectValue('[role="dialog"][data-state="open"]:not(.storyboard-workbench) button[role="combobox"]', "synthetic-character-lin-artifact");
  await waitFor(() => document.querySelectorAll(".storyboard-version-list button").length === 2);
  await click(".storyboard-version-list button:last-child");
  await waitFor(() => !document.querySelector(".storyboard-version-list"));
  await selectValue(`.storyboard-production-grid section[aria-label=${JSON.stringify(message("role.keyframe"))}] button[role="combobox"][aria-label=${JSON.stringify(message("selectedResult"))}]`, "synthetic-shot-3-keyframe-v1");
  await click('[data-storyboard-action="save"]');
  await waitFor(() => JSON.parse(document.querySelector("#storyboard-browser-evidence").textContent).saved.version === 2);
  const choices = (await evidence()).saved.shots[2];
  assert.ok(choices.referenceVersionIds.includes("synthetic-character-lin-v1"), "The resource picker stores the explicitly chosen immutable reference version");
  assert.equal(choices.productions.find((production) => production.role === "KEYFRAME").selectedVersionId, "synthetic-shot-3-keyframe-v1", "The result picker stores the explicitly chosen historic media version");
  assert.equal((await evidence()).runs.length, 0, "Reference and result choices do not invoke generation");
  await screenshot("storyboard-exact-reference-result-choices");
  }

  await navigate(baseUrl, "?director-media=1"); await click(shotButton(3));
  await waitFor(() => Boolean(document.querySelector(".storyboard-media-return")));
  assert.equal((await evidence()).approvals.length, 0, "Reading a shot-linked proposal never approves generation");
  const priorVideo = (await evidence()).saved.shots[2].productions.find((production) => production.role === "VIDEO").selectedVersionId;
  await clickNamed(".storyboard-media-return button", message("candidate.reviewBatch"));
  await waitFor(() => Boolean(document.querySelector(".agent-media-approval__decision")));
  assert.equal(await page((text) => document.querySelector(".agent-media-approval")?.textContent.includes(text), message("candidate.frozenShot").replace("{0}", "SHOT-03").replace("{1}", "1")), true);
  await clickNamed(".agent-media-approval button", labels["agent.approval.approve"]);
  await waitFor(() => JSON.parse(document.querySelector("#storyboard-browser-evidence").textContent).approvals.length === 1);
  await clickNamed('[role="tab"]', message("shots").replace("{0}", "8"));
  await waitFor(() => document.querySelectorAll(".storyboard-media-candidate").length === 2
    && [...document.querySelectorAll(".storyboard-media-candidate button")].filter((button) => !button.disabled).length === 4);
  assert.equal((await evidence()).saved.shots[2].productions.find((production) => production.role === "VIDEO").selectedVersionId, priorVideo,
    "Generation completion never changes the storyboard's prior paid selection");
  assert.equal((await evidence()).saves.length, 0, "Approval and automatic result return never save the storyboard");
  await screenshot("storyboard-director-results-await-explicit-choice");
  const directorVideo = '[data-result-version="synthetic-director-video-v2"]';
  await clickNamed(`${directorVideo} button`, message("preview"));
  await waitFor(() => document.querySelector(".media-preview-dialog video")?.readyState >= 2);
  await click(`.media-preview-dialog button[aria-label=${JSON.stringify(labels["media.preview.close"])}]`);
  await waitFor(() => !document.querySelector(".media-preview-dialog"));
  await clickNamed(`${directorVideo} button`, message("candidate.choose"));
  assert.equal((await evidence()).saves.length, 0, "Explicit result choice stays in the local draft until save");
  assert.equal(await page((text) => document.querySelector(".storyboard-editor-heading")?.textContent.includes(text), message("status.review")), true);
  await click('[data-storyboard-action="save"]');
  await waitFor(() => JSON.parse(document.querySelector("#storyboard-browser-evidence").textContent).saved.version === 2);
  await waitFor(() => document.querySelector('[data-storyboard-action="save"]').matches(":disabled")
    && !document.querySelector(".storyboard-shot-editor").matches(":disabled"));
  const directorChoice = (await evidence()).saved.shots[2].productions.find((production) => production.role === "VIDEO");
  assert.equal(directorChoice.canvasItemId, "synthetic-director-video"); assert.equal(directorChoice.selectedVersionId, "synthetic-director-video-v2");
  assert.equal(directorChoice.reviewedRevision, 1); assert.equal(directorChoice.needsReview, true, "Late results preserve the generation's older shot revision");
  await clickNamed('[data-result-version="synthetic-director-audio-v2"] button', message("candidate.choose"));
  await click('[data-storyboard-action="save"]');
  await waitFor(() => JSON.parse(document.querySelector("#storyboard-browser-evidence").textContent).saved.version === 3);
  assert.equal((await evidence()).saved.shots[2].productions.find((production) => production.role === "AUDIO").selectedVersionId, "synthetic-director-audio-v2",
    "Additional results bind the role matching the actual node's media kind");
  assert.equal((await evidence()).runs.length, 0, "Choosing results never invokes direct generation");
  assert.equal((await evidence()).approvals.length, 1, "Only the single explicit approval submits the whole batch");
  for (const viewport of process.argv.includes("--desktop-only") ? viewports.slice(0, 1) : viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await navigate(baseUrl, "?director-media=ready&locale=ru"); await click(shotButton(3));
    await waitFor(() => document.querySelectorAll(".storyboard-media-candidate").length === 2);
    await checkLayout(); await screenshot(`storyboard-director-results-ru-${viewport.width}x${viewport.height}`);
  }

  await cdp("Page.navigate", { url: new URL(`${route}?unauthorized=1`, baseUrl).href });
  await waitFor((text) => document.body.innerText.includes(text), message("unauthorized"));
  assert.equal(await page(() => document.querySelectorAll(".storyboard-shot-row").length), 0, "Unauthorized board content is not rendered");
  assert.deepEqual(runtimeErrors, [], "No uncaught browser errors");
  const result = { result: "passed", renderer: "Headless Chrome with real DOM interaction and native synthetic HTTP media", screenshots,
    checks: process.argv.includes("--director-only") ? [] : [process.argv.includes("--desktop-only") ? "1440×900 in Chinese and Russian (desktop diagnostic run)" : "1440×900, 1024×768 and 390×844 in Chinese and Russian", "no horizontal overflow and reachable visible controls", "8 shots and 2 fixed character references",
      "third-shot dialogue/duration saved and reopened", "only changed-shot and composition review impact", "all paid selected versions retained",
      "real native image/video/audio decoding", "explicit immutable reference and historic result selection", "409 keeps draft until explicit reload", "approval tab is read-only until action",
      "two-shot batch preflight/cancel make zero run requests and explicit confirmation freezes draft versions", "invalid JSON rejected and valid JSON imports creative fields only", "unauthorized read"] };
  result.checks.push("shot-linked approval and automatic archived result return preserve the paid selection until explicit choose and save",
    "late-result revision provenance remains pending review", "additional audio result uses its actual node kind",
    process.argv.includes("--desktop-only") ? "director candidates fit the Russian desktop layout" : "director candidates fit Russian desktop, tablet and mobile layouts");
  await writeFile(join(screenshots, "result.json"), JSON.stringify(result, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(result, null, 2));
} catch (error) {
  if (socket?.readyState === WebSocket.OPEN) await screenshot("storyboard-failure").catch(() => undefined);
  console.error(`Synthetic storyboard browser evidence: ${screenshots}`);
  throw error;
} finally {
  socket?.close();
  if (chrome && chrome.exitCode === null) { const stopped = new Promise((resolve) => chrome.once("exit", resolve)); chrome.kill(); await stopped; }
  await server?.close(); await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}
