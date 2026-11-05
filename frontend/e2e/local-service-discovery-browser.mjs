import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/local-service-discovery.html";
const production = !process.argv.includes("--dev");
const timeoutMs = 15_000;
const profile = await mkdtemp(join(tmpdir(), "agenvas-local-discovery-"));
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
  throw new Error(`Local discovery fixture timed out: ${await page(() => document.body.innerText)}`);
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
    if (message.method === "Runtime.consoleAPICalled" && message.params.type === "error") runtimeErrors.push(message.params.args.map((arg) => arg.value ?? arg.description).join(" "));
    if (message.method === "Runtime.exceptionThrown") runtimeErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id); if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message)); else waiting.resolve(message.result);
  });
  await cdp("Page.enable"); await cdp("Runtime.enable");
  const baseUrl = server.resolvedUrls.local[0];
  const screenshotDir = process.env.AGENVAS_LOCAL_SERVICE_SCREENSHOT_DIR;
  async function fillInput(selector, value) {
    await page((selector, value) => {
      const input = document.querySelector(selector);
      if (!input) throw new Error(`Missing input: ${selector}`);
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, value);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    }, selector, value);
  }
  async function inspectAndCapture(viewport, surface) {
    const layout = await page(() => ({ width: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth,
      height: document.documentElement.scrollHeight, writes: window.localServiceDiscoveryFixture.writes,
      requests: window.localServiceDiscoveryFixture.requests,
    }));
    assert.equal(layout.scrollWidth, layout.width, "Existing settings must fit narrow viewports");
    assert.equal(layout.writes, 0, "Discovery and Fill must never save or diagnose");
    if (screenshotDir) {
      await page(() => { if (document.activeElement instanceof HTMLElement) document.activeElement.blur(); window.scrollTo(0, 0); });
      const capture = await cdp("Page.captureScreenshot", { format: "png", captureBeyondViewport: true,
        clip: { x: 0, y: 0, width: viewport.width, height: surface === "comfy" ? viewport.height : layout.height, scale: 1 } });
      await writeFile(join(screenshotDir, `local-service-discovery-${surface}-${viewport.width}.png`), Buffer.from(capture.data, "base64"), { mode: 0o600 });
    }
    return layout;
  }
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(route + "?surface=llm", baseUrl).href });
    await waitFor(() => Boolean(document.getElementById("llm-endpoint")));
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.deepEqual(await page(() => window.localServiceDiscoveryFixture.requests), [], "No discovery on mount");
    assert.equal(await page(() => document.body.innerText.includes("Custom ports, other containers")), false, "Detailed help stays collapsed");
    await page(() => document.querySelector("button[aria-label='Local discovery help']").click());
    await waitFor(() => document.querySelector("[role='dialog']")?.innerText.includes("Custom ports, other containers"));
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape" });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Escape", code: "Escape" });
    await waitFor(() => !document.querySelector("[role='dialog']"));
    await fillInput("#llm-model", "Unsaved model draft");
    await fillInput("#llm-api-key", "synthetic-placeholder");
    const find = "Find Ollama on this machine";
    await clickText(find);
    await waitFor(() => [...document.querySelectorAll("button")].some((button) => button.textContent === "Fill address"));
    assert.equal(await page(() => document.getElementById("llm-endpoint").value), "https://manual.example.com/ollama", "Finding must not fill");
    await clickText("Fill address");
    await waitFor(() => document.getElementById("llm-endpoint").value === "http://host.docker.internal:11434/v1");
    assert.equal(await page(() => document.getElementById("llm-model").value), "Unsaved model draft");
    assert.equal(await page(() => document.getElementById("llm-api-key").value), "synthetic-placeholder");
    assert.deepEqual(await page(() => {
      const panel = document.querySelector(".llm-diagnostics-panel");
      const button = panel?.querySelector(".llm-diagnostic-action button");
      const consent = panel?.querySelector('[role="checkbox"]');
      return { label: button?.textContent, disabled: button?.disabled,
        consentDisabled: consent?.disabled, consentChecked: consent?.getAttribute("aria-checked"),
        costExplained: panel?.textContent.includes("Charges depend on your provider or proxy") };
    }), { label: "Run potentially billable diagnostics", disabled: true,
      consentDisabled: true, consentChecked: "false", costExplained: true }, "Unsaved discovery drafts keep diagnostics cost-protected");
    await page(() => { window.localServiceDiscoveryFixture.mode = "DELAYED"; });
    await clickText(find);
    await waitFor(() => document.body.innerText.includes("Cancel check"));
    await clickText("Cancel check");
    await waitFor(() => !document.body.innerText.includes("Cancel check"));
    await page(() => { window.localServiceDiscoveryFixture.mode = "BLOCKED"; });
    await clickText(find);
    await waitFor(() => document.body.innerText.includes("this local connection is disabled"));
    await page(() => { window.localServiceDiscoveryFixture.finishPending(); });
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.equal(await page(() => [...document.querySelectorAll("button")].some((button) => button.textContent === "Fill address")), false, "Late success must not replace blocked result");
    const llm = await inspectAndCapture(viewport, "llm");
    assert.deepEqual(llm.requests, ["OLLAMA", "OLLAMA", "OLLAMA"]);

    await page(() => {
      const provider = [...document.querySelectorAll("select")].find(select => [...select.options].some(option => option.value === "OPENROUTER"));
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value").set.call(provider, "OPENROUTER");
      provider.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await fillInput("#llm-endpoint", "https://router.example.invalid/api/v1");
    await fillInput("#llm-model", "synthetic-model");
    await clickText("Read model list");
    await waitFor(() => document.getElementById("llm-context").value === "16384");
    assert.equal(await page(() => document.getElementById("llm-output-limit").value), "2048");
    assert.equal(await page(() => document.getElementById("llm-api-key").value), "synthetic-placeholder");
    await inspectAndCapture(viewport, "models");
    await clickText("Use manual values");
    await fillInput("#llm-context", "8192");
    await clickText("Read model list");
    await waitFor(() => window.localServiceDiscoveryFixture.metadataReads === 2);
    assert.equal(await page(() => document.getElementById("llm-context").value), "8192", "Reading metadata preserves manual overrides");
    assert.equal(await page(() => window.localServiceDiscoveryFixture.writes), 0, "Model reads never save or diagnose");

    await cdp("Page.navigate", { url: new URL(route + "?surface=comfy", baseUrl).href });
    await waitFor(() => [...document.querySelectorAll("button")].some((button) => button.textContent.trim() === "Add connection"));
    await clickText("Add connection");
    await waitFor(() => Boolean(document.querySelector("[role='dialog'] input[type='url']")));
    const address = "[role='dialog'] input[type='url']";
    await fillInput("[role='dialog'] input[maxlength='160']", "Unsaved ComfyUI draft");
    await fillInput(address, "http://192.168.1.2:8188");
    await clickText("Find ComfyUI on this machine");
    await waitFor(() => [...document.querySelectorAll("button")].some((button) => button.textContent === "Fill address"));
    assert.equal(await page((selector) => document.querySelector(selector).value, address), "http://192.168.1.2:8188", "Finding must preserve manual draft");
    await clickText("Fill address");
    await waitFor((selector) => document.querySelector(selector).value === "http://192.168.65.254:8188", address);
    assert.equal(await page(() => document.querySelector("[role='dialog'] input[maxlength='160']").value), "Unsaved ComfyUI draft");
    assert.equal(await page(() => [...document.querySelector("[role='dialog']").querySelectorAll("button")].some((button) => button.textContent.trim() === "Add connection")), true, "Original Save remains");
    const comfy = await inspectAndCapture(viewport, "comfy");
    assert.deepEqual(comfy.requests, ["COMFYUI"]);
  }
  assert.deepEqual(runtimeErrors, []);
  console.log("Local/model discovery browser passed in existing LLM settings and ComfyUI connection dialog: production CSS, 1440/390 layout, explicit model read, route capacity, output limit, manual override, preserved key, no autosave, help/check/cancel/fill, late-response suppression and blocked policy; synthetic API only");
} catch (error) {
  console.error(error);
  throw error;
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) { const exited = new Promise((resolve) => chrome.once("exit", resolve)); chrome.kill(); await exited; }
  if (server?.close) await server.close();
  else if (server) await new Promise((resolve, reject) => server.httpServer.close((error) => error ? reject(error) : resolve()));
  await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}
