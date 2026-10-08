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
const route = "/e2e/media-template-detail.html";
// Exercise the shipped modal layout with long details and a paged catalogue.
const production = !process.argv.includes("--dev");
const pollMs = 50;
const timeoutMs = 15_000;
const tolerance = 1;
const viewports = [
  { width: 2560, height: 1179 },
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-template-detail-"));
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
  throw new Error("Timed out waiting for template details");
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
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  const baseUrl = server.resolvedUrls.local[0];
  for (const viewport of viewports) {
    for (const kind of ["IMAGE", "VIDEO"]) {
      await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
      await cdp("Page.navigate", { url: new URL(route, baseUrl).href });
      const opener = kind === "IMAGE" ? "#open-image" : "#open-video";
      await waitFor((selector) => Boolean(document.querySelector(selector)), opener);
      await pointerClick(opener);
      await pointerClick(".media-template-picker [role=tab]:nth-child(3)");
      await waitFor(() => document.querySelectorAll(".media-template-card").length === 50);
      const catalogueScroll = await page(() => {
        const body = document.querySelector(".media-template-picker .ui-dialog-body");
        body.scrollTop = body.scrollHeight;
        return body.scrollTop;
      });
      assert.ok(catalogueScroll > 0, "The fixture must exercise a scrolled catalogue");
      await pointerClick(".media-template-catalog > .ui-form-actions button:last-child");
      await waitFor((value) => document.querySelector(".media-template-card")?.getAttribute("aria-label") === `Synthetic ${value} 51`, kind);
      const card = ".media-template-card:last-child";
      await page((selector) => document.querySelector(selector).scrollIntoView({ block: "center" }), card);
      const retainedScroll = await page(() => document.querySelector(".media-template-picker .ui-dialog-body").scrollTop);
      await pointerClick(card);
      await waitFor(() => Boolean(document.querySelector(".media-template-detail img")?.complete));
      await waitFor(() => getComputedStyle(document.querySelector(".media-template-detail")).pointerEvents === "auto");
      const inspect = () => {
        const detail = document.querySelector(".media-template-detail");
        const body = detail.querySelector(".ui-dialog-body");
        const button = detail.querySelector(".ui-dialog-footer button");
        const rect = element => { const { x, y, right, bottom } = element.getBoundingClientRect(); return { x, y, right, bottom }; };
        const bounds = button.getBoundingClientRect();
        return { detail: rect(detail), button: rect(button), overflow: body.scrollWidth > body.clientWidth,
          scrollable: body.scrollHeight > body.clientHeight,
          clickable: button.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)),
          hit: document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)?.outerHTML.slice(0, 200),
          pointerEvents: getComputedStyle(detail).pointerEvents };
      };
      const initial = await page(inspect);
      assert.ok(initial.detail.x >= 0 && initial.detail.y >= 0 && initial.detail.right <= viewport.width + tolerance
        && initial.detail.bottom <= viewport.height + tolerance, `Detail must fit viewport: ${JSON.stringify(initial)}`);
      assert.ok(initial.scrollable && !initial.overflow, "Long details must scroll vertically without horizontal overflow");
      assert.ok(initial.clickable, `Use must be visible and clickable immediately after opening the detail: ${JSON.stringify(initial)}`);
      await page(() => { const body = document.querySelector(".media-template-detail .ui-dialog-body"); body.scrollTop = body.scrollHeight; });
      const scrolled = await page(inspect);
      assert.deepEqual(scrolled.button, initial.button, "Scrolling details must keep the use button fixed");
      await pointerClick(".media-template-detail .ui-dialog-header button");
      await waitFor(() => !document.querySelector(".media-template-detail"));
      assert.equal(await page(() => document.querySelector(".media-template-picker .ui-dialog-body").scrollTop), retainedScroll,
        "Closing details must preserve catalogue scroll position");
      await waitFor((selector) => document.activeElement === document.querySelector(selector), card);
      assert.equal(await page(() => document.querySelector(".media-template-catalog > .ui-form-actions span").textContent), "第 2 / 3 页");
      await pointerClick(card);
      await pointerClick(".media-template-detail .ui-dialog-footer button");
      await waitFor(() => !document.querySelector(".media-template-picker"));
      assert.ok((await page(() => document.querySelector("#applied-prompt").textContent)).startsWith("Synthetic prompt line 1"),
        "Use must return the template prompt to the editor");
      console.log(JSON.stringify({ viewport, kind, useButton: initial.button }));
    }
  }
  console.log("Template detail regression passed (4 viewports, image and video)");
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
