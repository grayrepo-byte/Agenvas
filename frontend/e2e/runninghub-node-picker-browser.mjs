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
const route = "/e2e/runninghub-node-picker.html";
// Verify the same bundled CSS and real modal isolation as the shipped frontend.
const production = !process.argv.includes("--dev");
const pollMs = 50;
const timeoutMs = 15_000;
const WHEEL_STEP = 240;
const WHEEL_TO_END = 5000;
const viewports = [
  { width: 2560, height: 1179 },
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-runninghub-node-picker-"));
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
  throw new Error(`Timed out waiting for the node picker fixture: ${await page(() => document.body.innerText)}`);
}

async function pointerClick(selector) {
  await waitFor((target) => {
    const element = document.querySelector(target);
    if (!element) return false;
    const bounds = element.getBoundingClientRect();
    return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
  }, selector);
  const point = await page((target) => {
    const bounds = document.querySelector(target).getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  }, selector);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
}

async function wheel(deltaY) {
  const point = await page(() => {
    const bounds = document.querySelector('[role="menu"][aria-label="选择节点"]').getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  });
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...point });
  await cdp("Input.dispatchMouseEvent", { type: "mouseWheel", ...point, deltaX: 0, deltaY });
}
function scrollState() {
  const menu = document.querySelector('[role="menu"][aria-label="选择节点"]');
  return { top: menu.scrollTop, height: menu.clientHeight, total: menu.scrollHeight,
    bodyTop: document.querySelector(".ui-dialog-body").scrollTop, pageTop: window.scrollY,
    locked: document.body.hasAttribute("data-scroll-locked") };
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

    await cdp("Page.navigate", { url: new URL(route, baseUrl).href });
    await waitFor(() => Boolean(document.querySelector("main > button")));
    await pointerClick("main > button");
    await waitFor(() => Boolean(document.querySelector("button[aria-label='选择节点']")));
    await page(() => document.querySelector("button[aria-label='选择节点']").scrollIntoView({block:"center"}));
    await pointerClick("button[aria-label='选择节点']");
    await waitFor(() => Boolean(document.querySelector('[role="menu"][aria-label="选择节点"]')));
    const before = await page(scrollState);
    assert.ok(before.locked, "Test must exercise the actual dialog scroll lock");
    assert.ok(before.total > before.height, "Many nodes must overflow the menu");
    await wheel(WHEEL_STEP);
    try { await waitFor(() => document.querySelector('[role="menu"][aria-label="选择节点"]').scrollTop > 0); }
    catch { console.log(JSON.stringify({viewport,before,after:await page(scrollState)})); throw new Error("Mouse wheel did not scroll the node picker"); }
    const down = await page(scrollState);
    assert.equal(down.bodyTop,before.bodyTop,"Wheel inside menu must not scroll dialog body");
    assert.equal(down.pageTop,before.pageTop,"Wheel inside menu must not scroll background");
    await wheel(-WHEEL_STEP);
    await waitFor(top => document.querySelector('[role="menu"][aria-label="选择节点"]').scrollTop < top,down.top);
    const up = await page(scrollState);
    assert.equal(up.bodyTop,before.bodyTop,"Upward wheel must not scroll dialog body");
    await wheel(WHEEL_TO_END);
    await waitFor(() => { const menu=document.querySelector('[role="menu"][aria-label="选择节点"]');return menu.scrollTop+menu.clientHeight>=menu.scrollHeight-1; });
    await pointerClick('[role="menuitemcheckbox"]:last-child');
    assert.equal(await page(() => document.querySelector('[role="menuitemcheckbox"]:last-child').getAttribute("aria-checked")),"false","Last node must remain selectable after scrolling");
    await wheel(WHEEL_STEP);
    await pause();
    assert.equal((await page(scrollState)).bodyTop,before.bodyTop,"At menu bottom wheel must not escape to dialog body");
    await cdp("Input.dispatchKeyEvent",{type:"keyDown",key:"Escape",code:"Escape",windowsVirtualKeyCode:27});
    await cdp("Input.dispatchKeyEvent",{type:"keyUp",key:"Escape",code:"Escape",windowsVirtualKeyCode:27});
    await waitFor(() => !document.querySelector('[role="menu"][aria-label="选择节点"]'));
    assert.equal(await page(() => Boolean(document.querySelector('[role="dialog"]'))),true,"Escape must close only the node menu");
    await waitFor(() => document.activeElement?.getAttribute("aria-label") === "选择节点");
    console.log(JSON.stringify({viewport,before,down,up}));
  }
  console.log("RunningHub node picker mouse wheel regression passed (four production-CSS viewports)");

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
