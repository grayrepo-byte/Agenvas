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
const route = "/e2e/media-function-parameters.html";
// Verify the same bundled CSS and real modal isolation as the shipped frontend.
const production = !process.argv.includes("--dev");
const pollMs = 50;
const timeoutMs = 15_000;
const viewports = [
  { width: 2560, height: 1179 },
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-media-function-parameters-"));
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
  throw new Error(`Timed out waiting for the media function parameter fixture: ${await page(() => document.body.innerText)}`);
}

async function pointerClick(selector) {
  function visiblePoint(target) {
    const element = document.querySelector(target);
    if (!element) return false;
    const bounds = element.getBoundingClientRect();
    const left = Math.max(0, bounds.left), right = Math.min(innerWidth, bounds.right);
    const top = Math.max(0, bounds.top), bottom = Math.min(innerHeight, bounds.bottom);
    if (right <= left || bottom <= top) return false;
    const x = (left + right) / 2;
    // A wrapped option can be taller than the popup's scroll viewport. Click its
    // visible portion, accounting for clipping and the select's scroll buttons.
    const candidates = [(top + bottom) / 2];
    for (let y = top + 4; y < bottom; y += 8) candidates.push(y);
    const y = candidates.find((candidate) => element.contains(document.elementFromPoint(x, candidate)));
    return y === undefined ? false : { x, y };
  }
  await waitFor(visiblePoint, selector);
  const point = await page(visiblePoint, selector);
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...point });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
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
    if (message.method === "Runtime.exceptionThrown") console.error(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
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
    for (const mode of ["image", "video", "preview"]) {
      await cdp("Page.navigate", { url: new URL(`${route}?mode=${mode}`, baseUrl).href });
      await waitFor(() => document.querySelectorAll('.runninghub-form [role="combobox"]').length >= 3);
      const layout = await page(() => {
        const form = document.querySelector(".runninghub-form");
        const panel = document.querySelector(".media-operation-panel") ?? document.querySelector("main");
        return { text: panel.innerText, panelWidth: panel.clientWidth, scrollWidth: panel.scrollWidth,
          controls: [...form.querySelectorAll('[role="combobox"]')].map(control => {
            const style = getComputedStyle(control);
            return { width: control.getBoundingClientRect().width, fieldWidth: control.closest('[data-slot="field"]').getBoundingClientRect().width,
              border: style.borderTopWidth, background: style.backgroundColor, radius: style.borderRadius };
          }),
          regularSelect: panel.querySelector('label > .ui-select')?.getBoundingClientRect().width,
          regularLabel: panel.querySelector('label > .ui-select')?.parentElement.getBoundingClientRect().width,
          hints: [...form.querySelectorAll('[data-slot="field-label"]')].map(label => label.title),
          actions: [...document.querySelectorAll('.artifact-card-toolbar > button, .artifact-card-toolbar > .media-version-picker > button, .artifact-card-toolbar > .text-card-version-picker > button')]
            .map(control => ({ height: control.getBoundingClientRect().height, radius: getComputedStyle(control).borderRadius })) };
      });
      assert.equal(layout.panelWidth, layout.scrollWidth, `${mode} panel must not overflow horizontally`);
      assert.ok(!layout.text.includes("PrimitiveBoolean"), "Technical names stay in hover hints");
      assert.ok(layout.text.includes("保留声音"));
      assert.ok(layout.hints.some(hint => hint.includes("PrimitiveBoolean · 4.value")));
      for (const control of layout.controls) {
        assert.ok(Math.abs(control.width - control.fieldWidth) < 1, "Parameter select fills the field");
        assert.equal(control.border, "1px");
        assert.equal(control.radius, "8px");
        assert.notEqual(control.background, "rgba(0, 0, 0, 0)");
      }
      if (mode === "image") assert.ok(Math.abs(layout.regularSelect - layout.regularLabel) < 1, "Ordinary operation select fills its label");
      for (const action of layout.actions) { assert.equal(action.height, 32); assert.equal(action.radius, "999px"); }
      console.log(JSON.stringify({ viewport, mode, controls: layout.controls.length, toolbarActions: layout.actions.length }));
    }
  }
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  for (const mode of ["image", "video"]) {
    await cdp("Page.navigate", { url: new URL(`${route}?mode=${mode}`, baseUrl).href });
    await waitFor(() => document.querySelectorAll('.runninghub-form [role="combobox"]').length >= 3);
    await pointerClick('.runninghub-form [role="combobox"]');
    await waitFor(() => Boolean(document.querySelector('[role="listbox"]')));
    await pointerClick('[role="option"][data-value="1"]');
    await pointerClick('.media-operation-panel footer button');
    const input = await page(() => JSON.parse(document.body.dataset.submitted));
    assert.equal(input.parameters.dynamicValues.scale, 4);
    assert.equal(input.parameters.dynamicValues[mode], `synthetic-${mode}`);
    await cdp("Page.navigate", { url: new URL(`${route}?mode=${mode}&busy`, baseUrl).href });
    await waitFor(() => document.querySelectorAll('.runninghub-form [role="combobox"]').length >= 3);
    assert.equal(await page(() => [...document.querySelectorAll('.runninghub-form [role="combobox"], .media-operation-panel footer button')].every(control => control.disabled)), true);
  }
  console.log("Media function parameter production layout passed (image / video / preview, four viewports, toolbar isolation, exact source/scale submission, busy controls; synthetic only)");

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
