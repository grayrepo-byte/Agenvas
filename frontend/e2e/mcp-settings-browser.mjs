import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/mcp-settings.html";
const production = !process.argv.includes("--dev");
const timeoutMs = 15_000;
const profile = await mkdtemp(join(tmpdir(), "agenvas-mcp-settings-"));
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
  throw new Error(`MCP fixture timed out: ${await page(() => document.body.innerText)}`);
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
    await waitFor(() => document.body.innerText.includes("Synthetic desktop client"));
    const layout = await page(() => ({ width: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth,
      panelWidth: document.querySelector("[aria-label='MCP 访问令牌列表']").clientWidth,
      statuses: document.body.innerText, example: document.querySelector("pre").innerText,
    }));
    assert.equal(layout.scrollWidth, layout.width, "Settings must fit narrow viewports without horizontal overflow");
    assert.ok(layout.panelWidth > 0);
    assert.ok(layout.statuses.includes("已过期") && layout.statuses.includes("已撤销") && layout.statuses.includes("有效"));
    assert.ok(layout.example.includes("${AGENVAS_MCP_TOKEN}"));
    await page(() => {
      const label = [...document.querySelectorAll("label")].find((label) => label.textContent === "令牌名称");
      const input = document.getElementById(label.htmlFor);
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, "Browser-created client");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await clickText("创建访问令牌");
    await waitFor(() => Boolean([...document.querySelectorAll("input")].find((input) => input.value === "agv_mcp_synthetic_browser_fixture_not_a_real_credential")));
    const secretSafety = await page(() => ({ stored: JSON.stringify(localStorage) + JSON.stringify(sessionStorage), example: document.querySelector("pre").innerText }));
    assert.ok(!secretSafety.stored.includes("agv_mcp_synthetic_browser_fixture_not_a_real_credential"));
    assert.ok(!secretSafety.example.includes("agv_mcp_synthetic_browser_fixture_not_a_real_credential"));
    await clickText("我已保存，隐藏令牌");
    await waitFor(() => !document.body.innerText.includes("完整令牌仅此次显示"));
    await page(() => document.querySelector("button[aria-label='撤销令牌 Browser-created client']").click());
    await waitFor(() => {
      const button = document.querySelector("button[aria-label='撤销令牌 Browser-created client']");
      return button?.disabled && button.closest("li").innerText.includes("已撤销") && !button.innerText.includes("正在撤销");
    });
    if (viewport.width === 1440 && process.env.AGENVAS_MCP_SCREENSHOT) {
      await page(() => document.querySelector("[aria-label='MCP 访问令牌列表']").closest("section").scrollIntoView({ block: "start" }));
      const capture = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(process.env.AGENVAS_MCP_SCREENSHOT, Buffer.from(capture.data, "base64"), { mode: 0o600 });
    }
  }
  assert.deepEqual(runtimeErrors, []);
  console.log("MCP settings browser passed: desktop/mobile layout, safe metadata, one-time secret, environment placeholder, create and revoke; synthetic only");
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) { const exited = new Promise((resolve) => chrome.once("exit", resolve)); chrome.kill(); await exited; }
  if (server?.close) await server.close();
  else if (server) await new Promise((resolve, reject) => server.httpServer.close((error) => error ? reject(error) : resolve()));
  await rm(profile, { recursive: true, force: true });
}
