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
const insecureHttp = process.argv.includes("--insecure-http");
const httpHost = "agenvas-http.test";
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
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function page(fn, ...args) {
  const result = await cdp("Runtime.evaluate", {
    expression: `(${fn.toString()})(...${JSON.stringify(args)})`,
    awaitPromise: true,
    returnByValue: true,
  });
  if (result.exceptionDetails)
    throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
}
async function waitFor(fn, ...args) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await page(fn, ...args)) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error(`MCP fixture timed out: ${await page(() => document.body.innerText)}`);
}
async function clickText(text) {
  await page(text => {
    const button = [...document.querySelectorAll("button")].find(
      button => button.textContent.trim() === text && !button.disabled,
    );
    if (!button) throw new Error(`Missing enabled button: ${text}`);
    button.click();
  }, text);
}

async function copyAndPaste(text, expected) {
  const point = await page(text => {
    const root = document.querySelector('[role="dialog"]') ?? document;
    const button = [...root.querySelectorAll("button")].find(
      button => button.textContent.trim() === text && button.getBoundingClientRect().width > 0,
    );
    button.scrollIntoView({ block: "center" });
    const bounds = button.getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  }, text);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
  await waitFor(() =>
    [...document.querySelectorAll('[role="status"]')].some(status => status.textContent.includes("已复制")),
  );
  assert.equal(await page(() => document.querySelectorAll("textarea").length), 0, "Temporary copy content is removed");
  // Native paste verifies the actual clipboard without an HTTP-only read API.
  await page(() => {
    const input = document.createElement("textarea");
    input.id = "synthetic-http-paste";
    (document.querySelector('[role="dialog"]') ?? document.body).append(input);
    input.focus({ preventScroll: true });
  });
  const modifiers = process.platform === "darwin" ? 4 : 2;
  try {
    await cdp("Input.dispatchKeyEvent", {
      type: "keyDown",
      key: "v",
      code: "KeyV",
      windowsVirtualKeyCode: 86,
      modifiers,
      commands: ["Paste"],
    });
    await cdp("Input.dispatchKeyEvent", {
      type: "keyUp",
      key: "v",
      code: "KeyV",
      windowsVirtualKeyCode: 86,
      modifiers,
    });
    await waitFor(expected => document.querySelector("#synthetic-http-paste").value === expected, expected);
  } finally {
    await page(() => document.querySelector("#synthetic-http-paste").remove());
  }
}

try {
  if (production) {
    await build({
      ...config,
      logLevel: "warn",
      build: { outDir, emptyOutDir: true, rollupOptions: { input: join(root, route) } },
    });
    server = await preview({
      ...config,
      build: { outDir },
      preview: { host: "127.0.0.1", port: 0, open: false, allowedHosts: [httpHost] },
    });
  } else {
    server = await createServer({
      ...config,
      server: { host: "127.0.0.1", port: 0, open: false, allowedHosts: [httpHost] },
    });
    await server.listen();
  }
  chrome = spawn(
    chromePath,
    [
      "--headless=new",
      "--no-first-run",
      "--no-default-browser-check",
      "--disable-background-networking",
      "--remote-debugging-port=0",
      ...(insecureHttp ? [`--host-resolver-rules=MAP ${httpHost} 127.0.0.1`, "--no-proxy-server"] : []),
      `--user-data-dir=${profile}`,
      "about:blank",
    ],
    { stdio: ["ignore", "ignore", "pipe"] },
  );
  const endpoint = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Chrome did not start")), timeoutMs);
    chrome.once("error", error => {
      clearTimeout(timer);
      reject(error);
    });
    chrome.stderr.on("data", data => {
      const match = data.toString().match(/DevTools listening on (ws:\/\/\S+)/);
      if (match) {
        clearTimeout(timer);
        resolve(new URL(match[1]).origin.replace("ws:", "http:"));
      }
    });
  });
  const target = await (await fetch(`${endpoint}/json/new?about:blank`, { method: "PUT" })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown")
      runtimeErrors.push(
        message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text,
      );
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Runtime.enable");
  const baseUrl = new URL(server.resolvedUrls.local[0]);
  if (insecureHttp) baseUrl.hostname = httpHost;
  for (const viewport of [
    { width: 1440, height: 1000 },
    { width: 390, height: 844 },
  ]) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(`${route}${insecureHttp ? "?http-compatibility" : ""}`, baseUrl).href });
    await waitFor(() => document.body.innerText.includes("Synthetic desktop client"));
    if (insecureHttp) assert.deepEqual(await page(() => [isSecureContext, "clipboard" in navigator]), [false, false]);
    const layout = await page(() => ({
      width: document.documentElement.clientWidth,
      scrollWidth: document.documentElement.scrollWidth,
      panelWidth: document.querySelector("[aria-label='MCP 访问令牌列表']").clientWidth,
      statuses: document.body.innerText,
      example: document.querySelector("pre").innerText,
    }));
    assert.equal(layout.scrollWidth, layout.width, "Settings must fit narrow viewports without horizontal overflow");
    assert.ok(layout.panelWidth > 0);
    assert.ok(
      layout.statuses.includes("已过期") && layout.statuses.includes("已撤销") && layout.statuses.includes("有效"),
    );
    assert.ok(layout.example.includes("${AGENVAS_MCP_TOKEN}"));
    await page(() => {
      const label = [...document.querySelectorAll("label")].find(label => label.textContent === "令牌名称");
      const input = document.getElementById(label.htmlFor);
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, "Browser-created client");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await clickText("创建访问令牌");
    await waitFor(() =>
      Boolean(
        [...document.querySelectorAll("input")].find(
          input => input.value === "agv_mcp_synthetic_browser_fixture_not_a_real_credential",
        ),
      ),
    );
    const secretSafety = await page(() => ({
      stored: JSON.stringify(localStorage) + JSON.stringify(sessionStorage),
      example: document.querySelector("pre").innerText,
    }));
    assert.ok(!secretSafety.stored.includes("agv_mcp_synthetic_browser_fixture_not_a_real_credential"));
    assert.ok(!secretSafety.example.includes("agv_mcp_synthetic_browser_fixture_not_a_real_credential"));
    if (insecureHttp) await copyAndPaste("复制令牌", "agv_mcp_synthetic_browser_fixture_not_a_real_credential");
    await clickText("我已保存，隐藏令牌");
    await waitFor(() => !document.body.innerText.includes("完整令牌仅此次显示"));
    await page(() => document.querySelector("button[aria-label='撤销令牌 Browser-created client']").click());
    await waitFor(() => {
      const button = document.querySelector("button[aria-label='撤销令牌 Browser-created client']");
      return (
        button?.disabled && button.closest("tr").innerText.includes("已撤销") && !button.innerText.includes("正在撤销")
      );
    });
    if (viewport.width === 1440 && process.env.AGENVAS_MCP_SCREENSHOT) {
      await page(() =>
        document.querySelector("[aria-label='MCP 访问令牌列表']").closest("section").scrollIntoView({ block: "start" }),
      );
      const capture = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(process.env.AGENVAS_MCP_SCREENSHOT, Buffer.from(capture.data, "base64"), { mode: 0o600 });
    }
  }
  if (insecureHttp) {
    const decoded = await page(async () => {
      const bytes = new ArrayBuffer(44 + 1600);
      const view = new DataView(bytes);
      for (const [offset, text] of [
        [0, "RIFF"],
        [8, "WAVEfmt "],
        [36, "data"],
      ]) {
        for (let index = 0; index < text.length; index++) view.setUint8(offset + index, text.charCodeAt(index));
      }
      for (const [offset, value] of [
        [4, 1636],
        [16, 16],
        [24, 8000],
        [28, 16000],
        [40, 1600],
      ])
        view.setUint32(offset, value, true);
      for (const [offset, value] of [
        [20, 1],
        [22, 1],
        [32, 2],
        [34, 16],
      ])
        view.setUint16(offset, value, true);
      for (let sample = 0; sample < 800; sample++) view.setInt16(44 + sample * 2, Math.sin(sample / 5) * 12000, true);
      const context = new AudioContext();
      try {
        const buffer = await context.decodeAudioData(bytes);
        return { duration: buffer.duration, channels: buffer.numberOfChannels, samples: buffer.length };
      } finally {
        await context.close();
      }
    });
    assert.equal(decoded.channels, 1);
    assert.ok(
      Math.abs(decoded.duration - 0.1) < 0.001 && decoded.samples > 0,
      "Web Audio decodes actual PCM over HTTP",
    );
    await page(() => document.querySelector('button[aria-label="展开 Prompt"]').click());
    await copyAndPaste("复制", "Synthetic HTTP log\n完整正文");
  }
  assert.deepEqual(runtimeErrors, []);
  console.log(
    "MCP settings browser passed: desktop/mobile layout, safe metadata, one-time secret, environment placeholder, create and revoke; synthetic only",
  );
} finally {
  socket?.close();
  if (chrome && chrome.exitCode == null) {
    const exited = new Promise(resolve => chrome.once("exit", resolve));
    chrome.kill();
    await exited;
  }
  if (server?.close) await server.close();
  else if (server)
    await new Promise((resolve, reject) => server.httpServer.close(error => (error ? reject(error) : resolve())));
  await rm(profile, { recursive: true, force: true });
}
