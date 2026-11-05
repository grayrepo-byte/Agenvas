import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, preview } from "vite";
import { seededPrompts, seededStyles } from "../src/test/seededCatalogs.ts";

// Actual components and bundled CSS, isolated synthetic responses, no backend.
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/interface-locales.html";
const evidence = process.env.AGENVAS_I18N_EVIDENCE ?? "/tmp/agenvas-i18n-evidence";
const profile = await mkdtemp(join(tmpdir(), "agenvas-interface-locales-"));
const outDir = join(profile, "build");
const timeoutMs = 15_000;
const viewports = [{ width: 1440, height: 900 }, { width: 390, height: 844 }];
const screens = [
  ["login", "/login", ".auth-page form"],
  ["projects", "/projects", ".project-card"],
  ["skills", "/skills", ".skills-list-item"],
  ["media", "/settings/media", ".media-capabilities-table tbody tr"],
  ["functions", "/settings/functions", ".media-function-row"],
  ["general", "/settings/general", ".system-settings-language-panel"],
  ["prompts", "/settings/general?tab=prompts", ".prompt-management-record"],
  ["styles", "/settings/general?tab=styles", ".media-style-settings-card"],
  ["tools", "/tools", ".agent-run-conversation [role=status]"],
];
let server, chrome, socket, nextId = 0, baseUrl;
const pending = new Map(), runtimeErrors = [], blocked = [], results = [];
const pause = () => new Promise(resolve => setTimeout(resolve, 50));
function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function page(fn, ...args) {
  const result = await cdp("Runtime.evaluate", { expression: `(${fn.toString()})(...${JSON.stringify(args)})`, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
}
async function waitFor(fn, ...args) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) { if (await page(fn, ...args)) return; await pause(); }
  throw new Error(`Timed out: ${await page(() => document.body.innerText)}`);
}
async function click(selector) {
  await page(target => document.querySelector(target)?.scrollIntoView({ block: "center", inline: "nearest" }), selector);
  await waitFor(target => {
    const element = document.querySelector(target); if (!element) return false;
    const bounds = element.getBoundingClientRect();
    return bounds.width > 0 && bounds.height > 0;
  }, selector);
  const point = await page(target => {
    const element = document.querySelector(target), bounds = element.getBoundingClientRect();
    const left = Math.max(0, bounds.left), right = Math.min(innerWidth, bounds.right);
    const top = Math.max(0, bounds.top), bottom = Math.min(innerHeight, bounds.bottom);
    const xs = [(left + right) / 2], ys = [(top + bottom) / 2];
    for (let x = left + 4; x < right; x += 8) xs.push(x);
    for (let y = top + 4; y < bottom; y += 8) ys.push(y);
    for (const y of ys) for (const x of xs) if (element.contains(document.elementFromPoint(x, y))) return { x, y };
    throw new Error(`Click target is covered: ${target}`);
  }, selector);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
}
async function input(selector, value) {
  await click(selector);
  await page(target => document.querySelector(target).select(), selector);
  await cdp("Input.insertText", { text: value });
}
async function snapshot(label, locale = "en") {
  const state = await page(() => {
    const visible = el => el.checkVisibility() && el.getClientRects().length > 0;
    return { text: document.body.innerText,
      attributes: [...document.querySelectorAll("[title], [aria-label], [placeholder]")].filter(visible).flatMap(el =>
        ["title", "aria-label", "placeholder"].map(name => ({ name, value: el.getAttribute(name) })).filter(item => item.value)),
      values: [...document.querySelectorAll("input, textarea")].filter(visible).map(el => ({ tag: el.tagName, value: el.value })),
      unexpected: JSON.parse(document.body.dataset.unexpected ?? "[]"),
      requests: JSON.parse(document.body.dataset.requests ?? "[]"),
      viewport: innerWidth, scrollWidth: document.documentElement.scrollWidth, lang: document.documentElement.lang,
      seedCounts: JSON.parse(document.body.dataset.seedCounts ?? "{}") };
  });
  assert.equal(state.lang, locale, `${label}: locale applied`);
  assert.deepEqual(state.unexpected, [], `${label}: unexpected synthetic API call`);
  if (locale !== "ja" && locale !== "zh") {
    assert.doesNotMatch(state.text, /\p{Script=Han}/u, `${label}: visible default text contains Han`);
    for (const { name, value } of state.attributes) assert.doesNotMatch(value, /\p{Script=Han}/u, `${label}: ${name} contains Han`);
    for (const { value } of state.values) assert.doesNotMatch(value, /\p{Script=Han}/u, `${label}: visible field value contains Han`);
  }
  assert.ok(state.scrollWidth <= state.viewport + 1, `${label}: horizontal page overflow ${state.scrollWidth}/${state.viewport}`);
  assert.deepEqual(state.seedCounts, { prompts: 8, styles: 8 });
  results.push({ label, locale, fields: state.values.length, attributes: state.attributes.length, requests: state.requests.length,
    viewport: state.viewport, scrollWidth: state.scrollWidth });
  return state;
}
async function screenshot(name) {
  const { data } = await cdp("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  const path = join(evidence, `${name}.png`);
  await writeFile(path, Buffer.from(data, "base64"), { mode: 0o600 });
  await chmod(path, 0o600);
}
async function navigate(path, locale = "en", ready) {
  const url = new URL(route, baseUrl); url.searchParams.set("screen", path); url.searchParams.set("locale", locale);
  await cdp("Page.navigate", { url: url.href });
  await waitFor(selector => Boolean(document.querySelector(selector)), ready ?? "h1");
  await waitFor(() => document.querySelector(".app-page-loading") === null && document.body.dataset.unexpected !== undefined);
}
async function chooseLocale(index, locale) {
  await click(".ui-language-trigger");
  await waitFor(() => document.querySelectorAll(".ui-language-option").length === 4);
  await click(`.ui-language-option:nth-child(${index})`);
  await waitFor(value => document.documentElement.lang === value && !document.querySelector(".ui-language-options"), locale);
}
try {
  assert.equal(process.versions.node.split(".")[0], "24", "Use the project's direct Node 24 binary");
  await mkdir(evidence, { recursive: true, mode: 0o700 }); await chmod(evidence, 0o700);
  await build({ root, configFile: join(root, "vite.config.ts"), logLevel: "warn", plugins: [{
    name: "synthetic-installed-catalogs", enforce: "pre",
    load(id) { if (id === join(root, "src/test/seededCatalogs.ts")) return `export const seededPrompts=${JSON.stringify(seededPrompts)};export const seededStyles=${JSON.stringify(seededStyles)};`; },
  }], build: { outDir, emptyOutDir: true, rollupOptions: { input: join(root, route) } } });
  server = await preview({ root, configFile: join(root, "vite.config.ts"), build: { outDir }, preview: { host: "127.0.0.1", port: 0, open: false } });
  baseUrl = server.resolvedUrls.local[0];
  chrome = spawn(chromePath, ["--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-background-networking", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Chrome did not start")), timeoutMs);
    chrome.once("error", error => { clearTimeout(timer); reject(error); });
    chrome.stderr.on("data", data => { const match = data.toString().match(/DevTools listening on (ws:\/\/\S+)/); if (match) { clearTimeout(timer); resolve(new URL(match[1]).origin.replace("ws:", "http:")); } });
  });
  const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: "PUT" })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown") runtimeErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    if (message.method === "Fetch.requestPaused") {
      const { requestId, request } = message.params, url = new URL(request.url);
      if (url.origin === new URL(baseUrl).origin && !url.pathname.startsWith("/api/")) void cdp("Fetch.continueRequest", { requestId });
      else { blocked.push(request.url); void cdp("Fetch.failRequest", { requestId, errorReason: "BlockedByClient" }); }
    }
    const waiting = pending.get(message.id); if (!waiting) return; pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message)); else waiting.resolve(message.result);
  });
  await cdp("Page.enable"); await cdp("Runtime.enable"); await cdp("Fetch.enable", { patterns: [{ urlPattern: "*", requestStage: "Request" }] });
  await cdp("Emulation.setEmulatedMedia", { features: [{ name: "prefers-reduced-motion", value: "reduce" }] });
  for (const viewport of viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: viewport.width < 500 });
    for (const [name, path, ready] of screens) {
      await navigate(path, "en", ready);
      const state = await snapshot(`en-${name}-${viewport.width}`);
      if (name === "skills") {
        assert.equal(await page(() => document.querySelectorAll(".skills-list-item").length), 17);
        await click(".skills-list-item");
        await waitFor(() => Boolean(document.querySelector(".skills-source-preview")));
        await snapshot(`en-built-in-skill-viewer-${viewport.width}`);
        assert.equal(await page(() => document.querySelector(".skills-source-preview").textContent), "# Synthetic creative method\nExisting runtime instructions remain user content.");
      }
      if (name === "media") {
        assert.match(state.text, /Local image processing|Local audio trimming/);
        await page(() => document.querySelector(".media-capabilities-table").scrollIntoView({ block: "start" }));
        await screenshot(`en-local-capabilities-${viewport.width}`);
        await click('.media-catalog [role="tab"]:nth-child(2)');
        await waitFor(() => document.querySelector('.media-catalog [role="tab"]:nth-child(2)').getAttribute("aria-selected") === "true");
        await snapshot(`en-local-connection-${viewport.width}`);
        await screenshot(`en-local-connection-${viewport.width}`);
        await click('.media-catalog [role="tab"]:nth-child(1)');
      }
      if (name === "prompts") {
        assert.equal(await page(() => document.querySelectorAll(".prompt-management-record").length), 8);
        for (let index = 1; index <= 8; index++) {
          await click(`.prompt-management-record:nth-of-type(${index + 1})`);
          await snapshot(`en-prompt-${index}-${viewport.width}`);
        }
      }
      if (name === "styles") {
        assert.equal(await page(() => document.querySelectorAll(".media-style-settings-card").length), 8);
        await click(".media-style-settings-card button");
        await waitFor(() => Boolean(document.querySelector('[role="dialog"]')));
        await snapshot(`en-style-editor-${viewport.width}`);
        await screenshot(`en-style-editor-${viewport.width}`);
        await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape" });
        await waitFor(() => !document.querySelector('[role="dialog"]'));
      }
      if (name === "functions") {
        await click('.media-function-row[aria-label="Resize"] [role="combobox"]');
        await waitFor(() => Boolean(document.querySelector('[role="listbox"]')));
        const options = await snapshot(`en-local-function-options-${viewport.width}`);
        assert.match(options.text, /Local media processing · Local image processing/);
        await screenshot(`en-local-function-options-${viewport.width}`);
        await click('[role="option"][data-value=""]');
        await waitFor(() => !document.querySelector('[role="listbox"]'));
        await click('.media-function-row[aria-label="Resize"] [role="combobox"]');
        await waitFor(() => Boolean(document.querySelector('[role="listbox"]')));
        await click('[role="option"][data-value="00000000-0000-4000-8000-000000000202"]');
        await waitFor(() => !document.querySelector('[role="listbox"]'));
        await waitFor(() => document.querySelector('.media-function-row[aria-label="Resize"] .media-function-controls > button').disabled);
        await click('.media-function-workspace [role="tab"]:nth-child(2)');
        await waitFor(() => document.querySelector('.media-function-workspace [role="tab"]:nth-child(2)').getAttribute("aria-selected") === "true");
        await snapshot(`en-video-functions-${viewport.width}`);
      }
      if (name === "tools") {
        await click('.runninghub-form [role="combobox"]'); await waitFor(() => Boolean(document.querySelector('[role="listbox"]')));
        const boolean = await snapshot(`en-boolean-options-${viewport.width}`); assert.match(boolean.text, /Disabled/);
        await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape" });
        for (let index = 1; index <= 3; index++) {
          await click(`.audio-prompt-tools button:nth-child(${index})`); await waitFor(() => Boolean(document.querySelector('[role="dialog"]')));
          await snapshot(`en-audio-dialog-${index}-${viewport.width}`); await screenshot(`en-audio-dialog-${index}-${viewport.width}`);
          await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape" }); await waitFor(() => !document.querySelector('[role="dialog"]'));
        }
        await click("[data-open-voices]");
        await waitFor(() => document.querySelectorAll(".voice-library-list > div").length === 19);
        await snapshot(`en-voice-library-${viewport.width}`);
        assert.equal(await page(() => [...document.querySelectorAll(".voice-library audio")].every(audio => audio.preload === "none" && audio.paused && audio.currentTime === 0)), true);
        // The second curated voice is Xiaohe. Select its actual row, never the
        // favorite or audition buttons, and keep the provider speaker ID exact.
        await click(".voice-library-list > div:nth-child(2) > button:first-child");
        await waitFor(() => document.querySelector("[data-selected-speaker]").dataset.selectedSpeaker === "zh_female_xiaohe_uranus_bigtts");
        assert.equal(await page(() => document.querySelector(".voice-library-list > div:nth-child(2) > button:first-child").getAttribute("aria-pressed")), "true");
        await snapshot(`en-selected-voice-${viewport.width}`);
        await screenshot(`en-voice-library-${viewport.width}`);
        await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape" });
        await waitFor(() => !document.querySelector(".voice-library"));
      }
      await page(() => window.scrollTo(0, 0)); await screenshot(`en-${name}-${viewport.width}`);
      console.log(`PASS en ${name} ${viewport.width}×${viewport.height}`);
    }
    await navigate("/projects", "en", ".project-card");
    if (viewport.width > 500) {
      await click(".app-sidebar-toggle");
      await waitFor(() => Boolean(document.querySelector(".app-shell--collapsed")));
      await snapshot(`en-collapsed-navigation-${viewport.width}`);
      await screenshot(`en-collapsed-navigation-${viewport.width}`);
      await click(".app-sidebar-toggle");
      await waitFor(() => !document.querySelector(".app-shell--collapsed"));
    }
    await click(".ui-language-trigger"); await waitFor(() => document.querySelectorAll(".ui-language-option").length === 4);
    const dialog = await snapshot(`en-language-dialog-${viewport.width}`);
    assert.match(dialog.text, /English/); assert.match(dialog.text, /Chinese/); assert.match(dialog.text, /Russian/); assert.match(dialog.text, /Japanese/);
    await screenshot(`en-language-dialog-${viewport.width}`);
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape" }); await waitFor(() => !document.querySelector(".ui-language-options"));
    await waitFor(() => document.activeElement?.classList.contains("ui-language-trigger"));
    const draft = "用户输入草稿：保留原文";
    await input('.projects-create input:not([type="hidden"])', draft);
    await chooseLocale(3, "ru");
    assert.equal(await page(() => document.querySelector('.projects-create input:not([type="hidden"])').value), draft);
    await chooseLocale(1, "en");
    assert.equal(await page(() => document.querySelector('.projects-create input:not([type="hidden"])').value), draft);
    await input('.projects-create input:not([type="hidden"])', "Synthetic user draft");
    await snapshot(`en-retained-draft-${viewport.width}`);
    for (const locale of ["ru", "ja"]) for (const [name, path, ready] of screens.filter(([name]) => name !== "login")) {
      await navigate(path, locale, ready); await snapshot(`${locale}-${name}-${viewport.width}`, locale);
      await screenshot(`${locale}-${name}-${viewport.width}`);
    }
  }
  assert.deepEqual(runtimeErrors, [], "Browser runtime errors"); assert.deepEqual(blocked, [], "Fixture must not attempt real network traffic");
  await writeFile(join(evidence, "results.json"), JSON.stringify({ syntheticOnly: true, viewports, results, runtimeErrors, blocked }, null, 2), { mode: 0o600 });
  console.log(`Interface locale production browser regression passed: ${results.length} scans; evidence ${evidence}`);
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) { const exited = new Promise(resolve => chrome.once("exit", resolve)); chrome.kill(); await exited; }
  if (server) await new Promise((resolve, reject) => server.httpServer.close(error => error ? reject(error) : resolve()));
  // Chrome utility processes can finish writing just after its parent exits.
  await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
}
