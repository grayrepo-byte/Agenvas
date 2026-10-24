import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/director-settings.html";
const production = !process.argv.includes("--dev");
const timeoutMs = 15_000;
const profile = await mkdtemp(join(tmpdir(), "agenvas-director-settings-"));
const outDir = join(profile, "build");
const config = { root, configFile: join(root, "vite.config.ts") };
let server;
let chrome;
let socket;
let nextId = 0;
const pending = new Map();
const runtimeErrors = [];

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
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await page(fn, ...args)) return;
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw new Error(`Director fixture timed out: ${await page(() => document.body.innerText)}`);
}
async function clickText(text) {
  await page((text) => {
    const button = [...document.querySelectorAll("button")].find((button) => button.textContent.trim() === text && !button.disabled);
    if (!button) throw new Error(`Missing enabled button: ${text}`);
    button.click();
  }, text);
}

try {
  if (production) {
    await build({ ...config, logLevel: "warn", build: { outDir, emptyOutDir: true, rollupOptions: { input: join(root, route) } } });
    server = await preview({ ...config, build: { outDir }, preview: { host: "127.0.0.1", port: 0, open: false } });
  } else {
    server = await createServer({ ...config, server: { host: "127.0.0.1", port: 0, open: false } }); await server.listen();
  }
  chrome = spawn(chromePath, ["--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-background-networking", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
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
  await new Promise((resolve, reject) => { socket.addEventListener("open", resolve, { once: true }); socket.addEventListener("error", reject, { once: true }); });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown") runtimeErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id); if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message)); else waiting.resolve(message.result);
  });
  await cdp("Page.enable"); await cdp("Runtime.enable");
  const baseUrl = server.resolvedUrls.local[0];
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(route, baseUrl).href });
    await waitFor(() => document.getElementById("director-contextWindowTokens")?.value === "1000000");
    await page(async () => { await Promise.all(document.getAnimations().map((animation) => animation.finished)); return true; });
    const layout = await page(() => ({ width: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth,
      fields: [...document.querySelectorAll("input[type='number']")].map((input) => ({ width: input.getBoundingClientRect().width,
        right: input.getBoundingClientRect().right, left: input.getBoundingClientRect().left })), text: document.body.innerText,
    }));
    assert.equal(layout.scrollWidth, layout.width, "Director settings must fit desktop and mobile viewports");
    assert.equal(layout.fields.length, 9);
    assert.ok(layout.fields.every((field) => field.width > 100 && field.left >= 0 && field.right <= layout.width));
    assert.ok(layout.text.includes("500,000 个估算 Token"));
    const screenshotDirectory = process.env.AGENVAS_DIRECTOR_SCREENSHOTS;
    if (screenshotDirectory) {
      const metrics = await cdp("Page.getLayoutMetrics");
      const capture = await cdp("Page.captureScreenshot", { format: "png", captureBeyondViewport: true,
        clip: { x: 0, y: 0, width: viewport.width, height: metrics.cssContentSize.height, scale: 1 } });
      await writeFile(join(screenshotDirectory, `director-${viewport.width}.png`), Buffer.from(capture.data, "base64"), { mode: 0o600 });
    }
    await page(() => {
      const input = document.getElementById("director-compactionThresholdPercent");
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, "25");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await waitFor(() => document.body.innerText.includes("250,000 个估算 Token"));
    await clickText("保存导演设置");
    await waitFor(() => document.body.innerText.includes("导演设置已保存，无需重启。"));
    await page(() => {
      const input = document.getElementById("director-contextWindowTokens");
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, "8192");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await waitFor(() => document.getElementById("director-responseReserveTokens").getAttribute("aria-invalid") === "true");
    assert.ok(await page(() => document.querySelector("button[type='submit']").disabled));

  }
  assert.deepEqual(runtimeErrors, []);
  console.log("Director settings browser passed: desktop/mobile layout, all nine controls, live trigger, save feedback and budget validation; synthetic only");
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) { const exited = new Promise((resolve) => chrome.once("exit", resolve)); chrome.kill(); await exited; }
  if (server?.close) await server.close();
  else if (server) await new Promise((resolve, reject) => server.httpServer.close((error) => error ? reject(error) : resolve()));
  await rm(profile, { recursive: true, force: true });
}
