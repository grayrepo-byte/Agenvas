import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Real CSS/layout regression without a backend, credentials or provider calls. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME
  ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/workflow-editor.html";
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
const profile = await mkdtemp(join(tmpdir(), "agenvas-editor-feedback-"));
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
  throw new Error(`Timed out waiting for editor feedback: ${await page(() => document.body.innerText)}`);
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
    for (const state of ["UNKNOWN", "UNKNOWN&longError", "READY", "FAILED", "BLOCKED", "RUNNING"]) {
      await cdp("Page.navigate", { url: new URL(`${route}?openai&task=${state}`, baseUrl).href });
      await waitFor(() => Boolean(document.querySelector(".editor-feedback-row")));
      // Wait for task queries, rather than measuring the initial loading row.
      await waitFor(() => !document.body.innerText.includes("正在检查卡片任务"));
      const layout = await page(() => {
        const feedback = document.querySelector(".media-draft-feedback");
        const editor = document.querySelector(".media-draft-editor");
        const rows = [...feedback.querySelectorAll(".editor-feedback-row")];
        return { text: feedback.innerText, height: feedback.clientHeight, scrollHeight: feedback.scrollHeight,
          width: feedback.clientWidth, scrollWidth: feedback.scrollWidth,
          editorBottom: editor.getBoundingClientRect().bottom,
          nestedCard: Boolean(feedback.querySelector(".agent-chat-panel")),
          rows: rows.map(row => ({ width: row.getBoundingClientRect().width, height: row.getBoundingClientRect().height,
            border: getComputedStyle(row).borderTopWidth, background: getComputedStyle(row).backgroundColor })),
          buttons: [...feedback.querySelectorAll("button")].map(button => {
            const box = button.getBoundingClientRect();
            return { label: button.innerText, height: box.height, right: box.right, bottom: box.bottom };
          }) };
      });
      assert.equal(layout.nestedCard, false, "Feedback must not nest a chat card");
      assert.equal(layout.scrollWidth, layout.width, "Feedback must wrap without horizontal overflow");
      assert.equal(layout.scrollHeight, layout.height, "Normal task feedback must not need a vertical scrollbar");
      assert.ok(layout.editorBottom <= viewport.height, "Editor stays within the viewport");
      for (const row of layout.rows) {
        assert.equal(row.border, "0px");
        assert.equal(row.background, "rgba(0, 0, 0, 0)");
      }
      if (state === "UNKNOWN" && viewport.width === 1440 && process.env.AGENVAS_FEEDBACK_SCREENSHOT) {
        const box = await page(() => {
          const bounds = document.querySelector(".media-draft-editor").getBoundingClientRect();
          return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height, scale: 1 };
        });
        const capture = await cdp("Page.captureScreenshot", { format: "png", clip: box });
        await writeFile(process.env.AGENVAS_FEEDBACK_SCREENSHOT, Buffer.from(capture.data, "base64"), { mode: 0o600 });
      }
      if (state === "UNKNOWN") {
        assert.ok(layout.text.includes("提交结果未确认，需人工核对"));
        if (viewport.width >= 1440) {
          assert.ok(layout.rows[0].height <= 24 && layout.height <= 36, `Short unknown result fits a single row: ${JSON.stringify(layout)}`);
          assert.ok(layout.rows[0].width < layout.width * 0.8, "Status hugs its content");
        }
        assert.equal(layout.buttons[0].label, "重试");
      }
      if (state === "READY") assert.equal(layout.buttons[0].label, "取消排队");
      for (const button of layout.buttons) {
        assert.equal(button.height, 24);
        assert.ok(button.right <= viewport.width && button.bottom <= viewport.height);
      }
    }
  }
  console.log("Editor feedback layout passed: UNKNOWN / long reason / READY / FAILED / BLOCKED / RUNNING, four viewports, compact actions and no nested cards or unnecessary scrollbars; synthetic only");

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
