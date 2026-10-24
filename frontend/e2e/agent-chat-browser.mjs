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
const route = "/e2e/agent-chat.html";
// Verify the same bundled CSS and real Skill page as the shipped frontend.
const production = !process.argv.includes("--dev");
const pollMs = 50;
const timeoutMs = 15_000;
const viewports = [
  { width: 1440, height: 900 },
  { width: 1280, height: 720 },
  { width: 780, height: 600 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-agent-chat-"));
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
  throw new Error(`Timed out waiting for Director chat: ${await page(() => document.body.innerText)}`);
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
  const browserErrors = [];
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown") browserErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
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
    await cdp("Page.navigate", { url: new URL(route, baseUrl).href });
    await waitFor(() => Boolean(document.querySelector(".agent-chat-launcher")));
    await page(() => document.querySelector(".agent-chat-launcher").click());
    await waitFor(() => Boolean(document.querySelector(".agent-chat-composer textarea:not(:disabled)")));
    assert.equal(await page(() => document.querySelector(".agent-chat-heading h3").textContent), "创作导演", "The built-in director has a stable identity");
    assert.equal(await page(() => document.querySelector('[aria-label="切换 Agent"], [aria-label="从预设新建 Agent"]') === null), true,
      "Conversation controls do not require selecting or creating an Agent");
    assert.equal(await page(() => [...document.querySelectorAll(".agent-chat-surface button")]
      .some((button) => /查看产物|绑定到 Agent|清空 Agent 输入/.test(button.textContent))), false,
    "The director manages the canvas without an output attribution or binding action");
    const layout = await page(() => {
      const dialog = document.querySelector('.agent-chat-dock-panel');
      const composer = dialog.querySelector('.agent-chat-composer');
      const body = dialog.querySelector('[data-slot="message-scroller-viewport"]');
      const rect = dialog.getBoundingClientRect(), input = composer.getBoundingClientRect();
      return { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom,
        inputTop: input.top, inputBottom: input.bottom, bodyHeight: body.clientHeight,
        overflow: document.documentElement.scrollWidth > innerWidth,
        width: innerWidth, height: innerHeight, scrollX,
        dockRight: getComputedStyle(dialog.parentElement).right,
        dockRect: dialog.parentElement.getBoundingClientRect().toJSON(),
        mainRect: document.querySelector('main').getBoundingClientRect().toJSON() };
    });
    if (process.env.AGENVAS_AGENT_CHAT_SCREENSHOT) {
      const capture = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(process.env.AGENVAS_AGENT_CHAT_SCREENSHOT.replace(".png", `-${viewport.width}.png`), Buffer.from(capture.data, "base64"), { mode: 0o600 });
    }
    assert.ok(layout.left >= 0 && layout.top >= 0 && layout.right <= layout.width && layout.bottom <= layout.height,
      "Chat stays within the viewport: " + JSON.stringify(layout));
    assert.ok(layout.inputTop >= layout.top && layout.inputBottom <= layout.bottom && layout.bodyHeight > 0,
      "Composer remains visible and transcript can scroll: " + JSON.stringify(layout));
    assert.equal(layout.overflow, false, "No document horizontal overflow");
    if (viewport.width >= 1280) {
      await page(() => document.querySelector("#synthetic-canvas-node").click());
      assert.equal(await page(() => document.querySelector("#synthetic-canvas-node").getAttribute("aria-pressed")), "true", "Canvas remains interactive");
      assert.equal(await page(() => document.querySelector(".agent-chat-context").textContent.includes("已选 1")), true, "Selection context updates");
    }
    await page(() => document.querySelector(".agent-chat-composer textarea").focus());
    await cdp("Input.insertText", { text: "还没有发送的创作想法" });
    await page(() => document.querySelector('[aria-label="展开对话"]').click());
    assert.equal(await page(() => document.querySelector(".agent-chat-composer textarea").value), "还没有发送的创作想法", "Expand preserves draft");
    await page(() => document.querySelector('[aria-label="还原对话大小"]').click());
    await page(() => document.querySelector('[aria-label="收起创作导演"]').click());
    assert.equal(await page(() => document.querySelector(".agent-chat-dock-panel").hidden), true, "Collapse hides the dialog");
    await page(() => document.querySelector(".agent-chat-launcher").click());
    assert.equal(await page(() => document.querySelector(".agent-chat-composer textarea").value), "还没有发送的创作想法", "Reopen preserves draft");
    await page(() => document.querySelector(".agent-chat-composer textarea").focus());
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape" });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Escape", code: "Escape" });
    await waitFor(() => document.querySelector(".agent-chat-dock-panel").hidden);
    assert.equal(await page(() => document.activeElement === document.querySelector(".agent-chat-launcher")), true, "Escape restores launcher focus");
    await page(() => document.querySelector(".agent-chat-launcher").click());
    if (process.env.AGENVAS_AGENT_CHAT_SCREENSHOT && viewport.width === 1440) {
      const capture = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(process.env.AGENVAS_AGENT_CHAT_SCREENSHOT, Buffer.from(capture.data, "base64"), { mode: 0o600 });
    }
  }
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp("Page.navigate", { url: new URL(route + "?history=1", baseUrl).href });
  await waitFor(() => Boolean(document.querySelector(".agent-chat-launcher")));
  await page(() => document.querySelector(".agent-chat-launcher").click());
  await waitFor(() => document.querySelectorAll(".agent-run-conversation").length === 6);
  await waitFor(() => document.querySelectorAll(".agent-chat-message--assistant").length === 6);
  await waitFor(() => {
    const body = document.querySelector('[data-slot="message-scroller-viewport"]');
    return body.scrollHeight - body.clientHeight - body.scrollTop < 4;
  });
  const scroll = await page(() => {
    const body = document.querySelector('[data-slot="message-scroller-viewport"]');
    const rect = body.getBoundingClientRect();
    return { scrollHeight: body.scrollHeight, height: body.clientHeight, x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  });
  assert.ok(scroll.scrollHeight > scroll.height, "Long persisted conversation scrolls");
  await cdp("Input.dispatchMouseEvent", { type: "mouseWheel", x: scroll.x, y: scroll.y, deltaX: 0, deltaY: -10000 });
  await waitFor(() => document.querySelector('[aria-label="跳到最新消息"]').getAttribute("data-active") === "true");
  const anchor = await page(() => {
    const body = document.querySelector('[data-slot="message-scroller-viewport"]');
    return document.querySelector('[data-message-id="synthetic-run-6"]').getBoundingClientRect().top - body.getBoundingClientRect().top;
  });
  await page(() => document.querySelector('.agent-chat-earlier').click());
  await waitFor(() => document.querySelectorAll(".agent-run-conversation").length === 12);
  await waitFor(() => document.querySelectorAll(".agent-chat-message--assistant").length === 12);
  const restoredAnchor = await page(() => {
    const body = document.querySelector('[data-slot="message-scroller-viewport"]');
    return document.querySelector('[data-message-id="synthetic-run-6"]').getBoundingClientRect().top - body.getBoundingClientRect().top;
  });
  assert.ok(Math.abs(anchor - restoredAnchor) < 5, `Loading earlier messages preserves the reading position: ${anchor} -> ${restoredAnchor}`);
  await page(() => document.querySelector('[aria-label="跳到最新消息"]').click());
  await waitFor(() => {
    const body = document.querySelector('[data-slot="message-scroller-viewport"]');
    return body.scrollHeight - body.clientHeight - body.scrollTop < 4;
  });
  if (process.env.AGENVAS_AGENT_CHAT_SCREENSHOT) {
    const capture = await cdp("Page.captureScreenshot", { format: "png" });
    await writeFile(process.env.AGENVAS_AGENT_CHAT_SCREENSHOT.replace(".png", "-history.png"),
      Buffer.from(capture.data, "base64"), { mode: 0o600 });
  }
  for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(route + "?approval=1", baseUrl).href });
    await waitFor(() => [...document.querySelectorAll("button")].some(button => button.textContent === "调整模型与参数"));
    await page(() => [...document.querySelectorAll("button")].find(button => button.textContent === "调整模型与参数").click());
    await waitFor(() => Boolean(document.querySelector('[role="dialog"] [aria-label="分辨率"]')));
    const geometry = await page(() => {
      const dialog = document.querySelector('[role="dialog"]');
      const footer = dialog.querySelector('.ui-dialog-footer').getBoundingClientRect();
      const rect = dialog.getBoundingClientRect();
      return { left: rect.left, right: rect.right, top: rect.top, bottom: footer.bottom,
        overflow: document.documentElement.scrollWidth - innerWidth };
    });
    assert.ok(geometry.left >= 0 && geometry.right <= viewport.width + 1 && geometry.top >= 0
      && geometry.bottom <= viewport.height + 1 && geometry.overflow <= 1, `Approval settings fit ${JSON.stringify({ viewport, geometry })}`);
    await page(() => document.querySelector('[role="dialog"] [aria-label="分辨率"]').click());
    await waitFor(() => [...document.querySelectorAll('[role="option"]')].some(option => option.textContent === "2K"));
    await page(() => [...document.querySelectorAll('[role="option"]')].find(option => option.textContent === "2K").click());
    if (process.env.AGENVAS_AGENT_CHAT_SCREENSHOT && viewport.width === 1440) {
      const capture = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(process.env.AGENVAS_AGENT_CHAT_SCREENSHOT.replace(".png", "-approval.png"), Buffer.from(capture.data, "base64"), { mode: 0o600 });
    }
    await page(() => [...document.querySelectorAll('[role="dialog"] button')].find(button => button.textContent === "保存并重新估算").click());
    await waitFor(() => !document.querySelector('[role="dialog"]') && document.body.innerText.includes("1440p"));
    assert.ok(await page(() => [...document.querySelectorAll("button")].some(button => button.textContent === "批准并生成" && !button.disabled)),
      "Saving revised parameters preserves a separate user approval step");
  }
  assert.deepEqual(browserErrors, [], "Director chat has no browser runtime errors");
  console.log("Director chat passed: five viewports, canvas interaction, selection context, expand/collapse draft retention, Escape focus, paged history anchoring jump to latest and video approval resolution editing on desktop/mobile; synthetic only");
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
