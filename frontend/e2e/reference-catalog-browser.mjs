import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, readFile, writeFile, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Real CSS/layout regression without a backend, credentials or provider calls. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME
  ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/reference-catalog.html";
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
].filter((viewport) => !process.argv.includes("--landscape-only") || viewport.width === 844);
const profile = await mkdtemp(join(tmpdir(), "agenvas-reference-catalog-"));
const image = await readFile(new URL("../../examples/reference-pack/images/neutral-front.png", import.meta.url));
const media = (server) => { server.middlewares.use((request, response, next) => {
  if (!request.url?.startsWith("/api/v1/reference-catalog/synthetic-preview/")) return next();
  response.setHeader("Content-Type", "image/png"); response.end(image);
}); };
const config = { root, configFile: join(root, "vite.config.ts"), configLoader: "runner", cacheDir: join(profile, "vite-cache"),
  plugins: [{ name: "synthetic-reference-preview", configureServer: media, configurePreviewServer: media }] };
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
  throw new Error("Timed out waiting for reference catalog");
}

async function pointerClick(selector) {
  await page((target) => document.querySelector(target)?.scrollIntoView({ block: "nearest" }), selector);
  try { await waitFor((target) => {
    const element = document.querySelector(target);
    if (!element) return false;
    const bounds = element.getBoundingClientRect();
    return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
  }, selector); } catch (error) {
    console.log("Click diagnostic", selector, await page((target) => {
      const element = document.querySelector(target); const b = element?.getBoundingClientRect();
      return { bounds: b?.toJSON(), hit: b && document.elementFromPoint(b.x+b.width/2,b.y+b.height/2)?.outerHTML.slice(0,400),
        bodyPointer: getComputedStyle(document.body).pointerEvents,
        ancestors: element && Array.from((function*(){ for(let e=element;e;e=e.parentElement)yield e; })()).slice(0,7).map(e=>({class:e.className,scroll:e.scrollTop,height:e.clientHeight,scrollHeight:e.scrollHeight,pointer:getComputedStyle(e).pointerEvents})) };
    }, selector));
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, "failed-click.png"), Buffer.from(shot.data,"base64"));
    }
    throw error;
  }
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
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(route, baseUrl).href });
    await pointerClick("#open-reference");
    await waitFor(() => document.querySelectorAll(".reference-card").length === 2);
    const cardLayout = await page(() => ({ card: document.querySelector(".reference-card").getBoundingClientRect().height, preview: document.querySelector(".reference-card-image").getBoundingClientRect().height }));
    assert.ok(cardLayout.card > 170 && cardLayout.preview > 120, `Catalog cards must retain visible full-size previews: ${JSON.stringify(cardLayout)}`);
    assert.equal(await page(() => document.documentElement.scrollWidth > innerWidth), false, "Picker must fit viewport width");
    await pointerClick(".reference-card:first-child");
    await waitFor(() => document.querySelector(".reference-preview-dialog img")?.naturalWidth === 240);
    await waitFor(() => getComputedStyle(document.querySelector(".reference-preview-dialog")).pointerEvents === "auto");
    const inspect = () => {
      const dialog = document.querySelector(".reference-preview-dialog");
      const button = dialog.querySelector("button[type=submit]");
      const rect = (e) => { const { x, y, right, bottom } = e.getBoundingClientRect(); return { x, y, right, bottom }; };
      const bounds = button.getBoundingClientRect();
      return { dialog: rect(dialog), button: rect(button), image: rect(dialog.querySelector(".reference-preview-image img")), imageContainer: rect(dialog.querySelector(".reference-preview-image")),
        overflow: dialog.querySelector(".ui-dialog-body").scrollWidth > dialog.querySelector(".ui-dialog-body").clientWidth,
        pointerEvents: getComputedStyle(dialog).pointerEvents, hit: document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)?.outerHTML.slice(0, 180),
        clickable: button.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)) };
    };
    const initial = await page(inspect);
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      await mkdir(process.env.AGENVAS_E2E_SCREENSHOT_DIR, { recursive: true });
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, `preview-${viewport.width}x${viewport.height}.png`), Buffer.from(shot.data, "base64"));
    }
    assert.ok(initial.dialog.x >= 0 && initial.dialog.y >= 0 && initial.dialog.right <= viewport.width + tolerance && initial.dialog.bottom <= viewport.height + tolerance, JSON.stringify(initial));
    assert.equal(initial.overflow, false, "Preview body must not overflow horizontally");
    assert.ok(initial.image.y >= initial.imageContainer.y && initial.image.bottom <= initial.imageContainer.bottom, `Image must fit its scrollable container without clipping: ${JSON.stringify(initial)}`);
    assert.ok(initial.clickable, `Append action must remain visible and clickable: ${JSON.stringify(initial)}`);
    await page(() => { const body = document.querySelector(".reference-preview-dialog .ui-dialog-body"); body.scrollTop = body.scrollHeight; });
    assert.deepEqual((await page(inspect)).button, initial.button, "Preview action must stay fixed while scrolling");
    await pointerClick('.reference-preview-dialog [role=radio]:nth-child(2)');
    await pointerClick('.reference-preview-dialog button[type=submit]');
    await waitFor(() => document.querySelector("#applied-reference").textContent === "owned-neutral-side");
    assert.equal(await page(() => document.documentElement.dataset.importCount), "1", "Selecting a view must import only one variant");
    await pointerClick(".reference-card:last-child");
    assert.equal(await page(() => document.querySelector('.reference-preview-dialog button[type=submit]').disabled), true, "Metadata-only entries must not import");
    assert.equal(await page(() => Boolean(document.querySelector('.reference-preview-dialog img'))), false, "Restricted entries must not hotlink images");
    await pointerClick('.reference-preview-dialog .ui-dialog-header button');
    await waitFor(() => !document.querySelector('.reference-preview-dialog'));
    await waitFor(() => document.activeElement === document.querySelector('.reference-card:last-child'));
    await pointerClick('.reference-source-settings-trigger');
    await waitFor(() => Boolean(document.querySelector('.reference-sources-dialog')));
    assert.equal(await page(() => { const e = document.querySelector('.reference-sources-dialog'); return e.scrollWidth > e.clientWidth; }), false, "Source settings must fit viewport");
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      await mkdir(process.env.AGENVAS_E2E_SCREENSHOT_DIR, { recursive: true });
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, `reference-${viewport.width}x${viewport.height}.png`), Buffer.from(shot.data, "base64"));
    }
    await pointerClick('.reference-sources-dialog .ui-dialog-header button');
    await waitFor(() => !document.querySelector('.reference-sources-dialog'));
    await pointerClick('.reference-private-cache-trigger');
    await waitFor(() => Boolean(document.querySelector('.reference-private-record')));
    const ownerCache = await page(() => {
      const dialog = document.querySelector('.reference-private-manager-dialog');
      return { text: dialog.textContent, overflow: dialog.scrollWidth > dialog.clientWidth,
        bottom: dialog.getBoundingClientRect().bottom };
    });
    assert.equal(ownerCache.overflow, false, "Owner cache must fit viewport");
    assert.ok(ownerCache.bottom <= viewport.height + tolerance, "Owner cache footer must fit viewport");
    assert.ok(ownerCache.text.includes("old-snapshot-geometric-study"), "Old snapshot images must remain manageable");
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, `owner-cache-${viewport.width}x${viewport.height}.png`), Buffer.from(shot.data, "base64"));
    }
    await pointerClick('.reference-private-record button');
    await pointerClick('.reference-private-manager-dialog button[data-variant=destructive]');
    await waitFor(() => !document.querySelector('.reference-private-record'));
    assert.equal(await page(() => document.documentElement.dataset.privateClearCount), "1", "Only the explicitly selected owner record is cleared");
    console.log(JSON.stringify({ viewport, append: initial.button, sourceSettings: "visible", ownerCache: "old snapshot cleared" }));
  }
  console.log(`Reference catalog browser regression passed: ${viewports.length} viewports, exact view import, metadata-only gating, source settings, owner old-snapshot cleanup`);

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
