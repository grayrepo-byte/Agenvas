import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Real CSS/layout regression without a backend, credentials or provider calls. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/smart-edit-dialog.html";
// The bug occurs only after production CSS optimization; dev is an optional comparison.
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
const profile = await mkdtemp(join(tmpdir(), "agenvas-smart-edit-browser-"));
const config = { root, configFile: join(root, "vite.config.ts") };
const outDir = join(profile, "build");
let server;
let chrome;
let socket;
let nextId = 0;
const pending = new Map();
const pause = () => new Promise(resolve => setTimeout(resolve, pollMs));

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
    await pause();
  }
  throw new Error("Timed out waiting for the smart edit fixture");
}

async function pointerClick(selector) {
  await waitFor(target => {
    const element = document.querySelector(target);
    if (!element) return false;
    const bounds = element.getBoundingClientRect();
    return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
  }, selector);
  const point = await page(target => {
    const bounds = document.querySelector(target).getBoundingClientRect();
    return { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 };
  }, selector);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
}

try {
  if (production) {
    await build({
      ...config,
      logLevel: "warn",
      build: {
        outDir,
        emptyOutDir: true,
        rollupOptions: { input: join(root, route) },
      },
    });
    server = await preview({ ...config, build: { outDir }, preview: { host: "127.0.0.1", port: 0, open: false } });
  } else {
    server = await createServer({ ...config, server: { host: "127.0.0.1", port: 0, open: false } });
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
    await waitFor(() => Boolean(document.querySelector("#root > button")));
    await pointerClick("#root > button");
    await waitFor(() => Boolean(document.querySelector(".smart-edit-dialog img")?.complete));
    const layout = await page(() => {
      const dialog = document.querySelector(".smart-edit-dialog");
      const bounds = element => {
        const { x, y, right, bottom, width, height } = element.getBoundingClientRect();
        return { x, y, right, bottom, width, height };
      };
      return {
        dialog: bounds(dialog),
        toolbar: bounds(dialog.querySelector(".smart-edit-toolbar")),
        composer: bounds(dialog.querySelector(".smart-edit-composer")),
        model: bounds(dialog.querySelector(".smart-edit-model [role=combobox]")),
        cost: bounds(dialog.querySelector(".smart-edit-cost")),
        submit: bounds(dialog.querySelector(".smart-edit-submit")),
        translate: getComputedStyle(dialog).translate,
      };
    });
    console.log(JSON.stringify({ viewport, ...layout }));
    assert.ok(
      Math.abs(layout.dialog.x) <= tolerance && Math.abs(layout.dialog.y) <= tolerance,
      `Smart edit is shifted outside the viewport: ${JSON.stringify(layout.dialog)}`,
    );
    assert.ok(
      Math.abs(layout.dialog.width - viewport.width) <= tolerance &&
        Math.abs(layout.dialog.height - viewport.height) <= tolerance,
      "Editor must fill the viewport",
    );
    for (const name of ["toolbar", "composer", "model", "cost", "submit"]) {
      const bounds = layout[name];
      assert.ok(
        bounds.x >= -tolerance &&
          bounds.y >= -tolerance &&
          bounds.right <= viewport.width + tolerance &&
          bounds.bottom <= viewport.height + tolerance,
        `${name} must remain visible inside the viewport`,
      );
    }
    assert.ok(
      layout.model.right <= layout.cost.x && layout.cost.right <= layout.submit.x,
      "Model, estimated cost and send button must not overlap",
    );
    const middle = bounds => bounds.y + bounds.height / 2;
    assert.ok(
      Math.abs(middle(layout.model) - middle(layout.cost)) <= tolerance &&
        Math.abs(middle(layout.cost) - middle(layout.submit)) <= tolerance,
      "Model, estimated cost and send button must share one row",
    );
    await page(() => document.querySelector(".smart-edit-close").click());
    await waitFor(() => !document.querySelector(".smart-edit-dialog"));
    await waitFor(() => document.activeElement === document.querySelector("#root > button"));

    await pointerClick("#root > button");
    await waitFor(() => Boolean(document.querySelector(".smart-edit-composer textarea")));
    await pointerClick(".smart-edit-composer textarea");
    await cdp("Input.insertText", { text: "Synthetic edit instruction" });
    await pointerClick(".smart-edit-close");
    await waitFor(() => Boolean(document.querySelector(".ui-dialog")));
    const confirmation = await page(() => {
      const bounds = document.querySelector(".ui-dialog").getBoundingClientRect();
      return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height };
    });
    assert.ok(
      Math.abs(confirmation.x + confirmation.width / 2 - viewport.width / 2) <= tolerance &&
        Math.abs(confirmation.y + confirmation.height / 2 - viewport.height / 2) <= tolerance,
      "The unsaved changes confirmation must keep the shared dialog's centered layout",
    );
    await pointerClick(".ui-dialog-footer button[type='button']");
    await waitFor(() => !document.querySelector(".ui-dialog"));
    assert.equal(
      await page(() => document.querySelector(".smart-edit-composer textarea").value),
      "Synthetic edit instruction",
      "Continue editing must retain the instruction",
    );
    await pointerClick(".smart-edit-close");
    await waitFor(() => Boolean(document.querySelector(".ui-dialog")));
    await pointerClick(".ui-dialog-footer button[type='submit']");
    await waitFor(() => !document.querySelector(".smart-edit-dialog"));
    await waitFor(() => document.activeElement === document.querySelector("#root > button"));
  }
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp("Page.navigate", { url: new URL(`${route}?exercise=1`, baseUrl).href });
  await waitFor(() => Boolean(document.querySelector("#root > button")));
  await pointerClick("#root > button");
  await waitFor(() => document.querySelector(".smart-edit-image-wrap canvas")?.width === 640);
  await page(() => {
    const original = window.fetch;
    window.__layerUploads = [];
    window.fetch = async (url, options) => {
      if (String(url).endsWith("/auth/csrf")) return Response.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" });
      if (String(url).endsWith("/assets") && options?.method === "POST") {
        const file = options.body.get("file");
        const image = await createImageBitmap(file);
        const canvas = document.createElement("canvas");
        canvas.width = image.width;
        canvas.height = image.height;
        const context = canvas.getContext("2d");
        context.drawImage(image, 0, 0);
        const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
        let clear = 0,
          ink = 0;
        for (let i = 3; i < data.length; i += 4) {
          if (data[i] === 0) clear++;
          else ink++;
        }
        window.__layerUploads.push({ width: image.width, height: image.height, type: file.type, clear, ink });
        return Response.json({ id: "synthetic-layer" });
      }
      return original(url, options);
    };
  });
  await pointerClick('[data-guidance="MARKUP"]');
  const point = await page(() => {
    const box = document.querySelector(".smart-edit-image-wrap canvas").getBoundingClientRect();
    return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point, button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point, button: "left", clickCount: 1 });
  await pointerClick(".smart-edit-composer textarea");
  await cdp("Input.insertText", { text: "Recolor the marked area and remove the annotations" });
  await pointerClick(".smart-edit-submit");
  await waitFor(() => JSON.parse(document.querySelector('[data-testid="submissions"]').textContent).length === 1);
  await pointerClick(".smart-edit-submit");
  await waitFor(() => JSON.parse(document.querySelector('[data-testid="submissions"]').textContent).length === 2);
  const native = await page(() => ({
    uploads: window.__layerUploads,
    submissions: JSON.parse(document.querySelector('[data-testid="submissions"]').textContent),
  }));
  assert.equal(native.uploads.length, 1, "Retry must reuse the uploaded immutable layer");
  assert.deepEqual(native.submissions[0], native.submissions[1], "Retry must preserve the accepted payload");
  assert.equal(native.submissions[0].markupAssetId, "synthetic-layer");
  assert.equal(native.submissions[0].maskAssetId, undefined);
  assert.equal(native.uploads[0].width, 640);
  assert.equal(native.uploads[0].height, 360);
  assert.equal(native.uploads[0].type, "image/png");
  assert.ok(
    native.uploads[0].clear > 0 && native.uploads[0].ink > 0,
    "Markup must contain visible ink and transparent pixels",
  );
  console.log("Native markup browser export and retry passed", JSON.stringify(native.uploads[0]));
  console.log("Smart edit viewport regression passed (4 viewports)");
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
