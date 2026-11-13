import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, readFile, writeFile, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, createServer, preview } from "vite";

/** Real CSS/layout regression without a backend, credentials or provider calls. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
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
]
  .filter(() => !process.argv.includes("--preferred-only"))
  .filter(viewport => !process.argv.includes("--landscape-only") || viewport.width === 844);
const pageViewports = process.argv.includes("--preferred-only")
  ? []
  : [
      { width: 2313, height: 694 },
      { width: 1440, height: 900 },
      { width: 390, height: 844 },
    ];
const profile = await mkdtemp(join(tmpdir(), "agenvas-reference-catalog-"));
const image = await readFile(new URL("../../examples/reference-pack/images/neutral-front.png", import.meta.url));
const media = server => {
  server.middlewares.use((request, response, next) => {
    if (
      !request.url?.startsWith("/api/v1/reference-catalog/synthetic-preview/") &&
      request.url !== "/api/v1/reference-catalog/private-images/synthetic-auto/preview" &&
      !request.url?.match(/^\/api\/v1\/reference-catalog\/costumes\/[^/]+\/image$/)
    )
      return next();
    response.setHeader("Content-Type", "image/png");
    response.end(image);
  });
};
const config = {
  root,
  configFile: join(root, "vite.config.ts"),
  configLoader: "runner",
  cacheDir: join(profile, "vite-cache"),
  plugins: [{ name: "synthetic-reference-preview", configureServer: media, configurePreviewServer: media }],
};
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
  throw new Error("Timed out waiting for reference catalog");
}

async function pointerClick(selector) {
  await page(target => document.querySelector(target)?.scrollIntoView({ block: "nearest" }), selector);
  try {
    await waitFor(target => {
      const element = document.querySelector(target);
      if (!element) return false;
      const bounds = element.getBoundingClientRect();
      return element.contains(document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
    }, selector);
  } catch (error) {
    console.log(
      "Click diagnostic",
      selector,
      await page(target => {
        const element = document.querySelector(target);
        const b = element?.getBoundingClientRect();
        return {
          bounds: b?.toJSON(),
          hit: b && document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2)?.outerHTML.slice(0, 400),
          bodyPointer: getComputedStyle(document.body).pointerEvents,
          ancestors:
            element &&
            Array.from(
              (function* () {
                for (let e = element; e; e = e.parentElement) yield e;
              })(),
            )
              .slice(0, 7)
              .map(e => ({
                class: e.className,
                scroll: e.scrollTop,
                height: e.clientHeight,
                scrollHeight: e.scrollHeight,
                pointer: getComputedStyle(e).pointerEvents,
              })),
        };
      }, selector),
    );
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(
        join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, "failed-click.png"),
        Buffer.from(shot.data, "base64"),
      );
    }
    throw error;
  }
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
    await pointerClick("#open-reference");
    await waitFor(() => document.querySelectorAll(".reference-card").length === 2);
    const cardLayout = await page(() => ({
      card: document.querySelector(".reference-card").getBoundingClientRect().height,
      preview: document.querySelector(".reference-card-image").getBoundingClientRect().height,
    }));
    assert.ok(
      cardLayout.card > 170 && cardLayout.preview > 120,
      `Catalog cards must retain visible full-size previews: ${JSON.stringify(cardLayout)}`,
    );
    assert.equal(
      await page(() => document.documentElement.scrollWidth > innerWidth),
      false,
      "Picker must fit viewport width",
    );
    await pointerClick(".reference-card:first-child");
    await waitFor(() => document.querySelector(".reference-preview-dialog img")?.naturalWidth === 240);
    await waitFor(() => getComputedStyle(document.querySelector(".reference-preview-dialog")).pointerEvents === "auto");
    const inspect = () => {
      const dialog = document.querySelector(".reference-preview-dialog");
      const button = dialog.querySelector("button[type=submit]");
      const rect = e => {
        const { x, y, right, bottom } = e.getBoundingClientRect();
        return { x, y, right, bottom };
      };
      const bounds = button.getBoundingClientRect();
      return {
        dialog: rect(dialog),
        button: rect(button),
        image: rect(dialog.querySelector(".reference-preview-image img")),
        imageContainer: rect(dialog.querySelector(".reference-preview-image")),
        overflow:
          dialog.querySelector(".ui-dialog-body").scrollWidth > dialog.querySelector(".ui-dialog-body").clientWidth,
        pointerEvents: getComputedStyle(dialog).pointerEvents,
        hit: document
          .elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
          ?.outerHTML.slice(0, 180),
        clickable: button.contains(
          document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2),
        ),
      };
    };
    const initial = await page(inspect);
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      await mkdir(process.env.AGENVAS_E2E_SCREENSHOT_DIR, { recursive: true });
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(
        join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, `preview-${viewport.width}x${viewport.height}.png`),
        Buffer.from(shot.data, "base64"),
      );
    }
    assert.ok(
      initial.dialog.x >= 0 &&
        initial.dialog.y >= 0 &&
        initial.dialog.right <= viewport.width + tolerance &&
        initial.dialog.bottom <= viewport.height + tolerance,
      JSON.stringify(initial),
    );
    assert.equal(initial.overflow, false, "Preview body must not overflow horizontally");
    assert.ok(
      initial.image.y >= initial.imageContainer.y && initial.image.bottom <= initial.imageContainer.bottom,
      `Image must fit its scrollable container without clipping: ${JSON.stringify(initial)}`,
    );
    assert.ok(initial.clickable, `Append action must remain visible and clickable: ${JSON.stringify(initial)}`);
    await page(() => {
      const body = document.querySelector(".reference-preview-dialog .ui-dialog-body");
      body.scrollTop = body.scrollHeight;
    });
    assert.deepEqual((await page(inspect)).button, initial.button, "Preview action must stay fixed while scrolling");
    await pointerClick(".reference-preview-dialog [role=radio]:nth-child(2)");
    await pointerClick(".reference-preview-dialog button[type=submit]");
    await waitFor(() => document.querySelector("#applied-reference").textContent === "owned-neutral-side");
    assert.equal(
      await page(() => document.documentElement.dataset.importCount),
      "1",
      "Selecting a view must import only one variant",
    );
    await pointerClick(".reference-card:last-child");
    assert.equal(
      await page(() => document.querySelector(".reference-preview-dialog button[type=submit]").disabled),
      true,
      "Metadata-only entries must not import",
    );
    assert.equal(
      await page(() => Boolean(document.querySelector(".reference-preview-dialog img"))),
      false,
      "Restricted entries must not hotlink images",
    );
    await pointerClick(".reference-preview-dialog .ui-dialog-header button");
    await waitFor(() => !document.querySelector(".reference-preview-dialog"));
    await waitFor(() => document.activeElement === document.querySelector(".reference-card:last-child"));
    await pointerClick(".reference-source-settings-trigger");
    await waitFor(() => Boolean(document.querySelector(".reference-sources-dialog")));
    assert.equal(
      await page(() => {
        const e = document.querySelector(".reference-sources-dialog");
        return e.scrollWidth > e.clientWidth;
      }),
      false,
      "Source settings must fit viewport",
    );
    assert.deepEqual(
      await page(() => {
        const dialog = document.querySelector(".reference-sources-dialog");
        return {
          selectors: dialog.querySelectorAll("[role=combobox]").length,
          textInputs: dialog.querySelectorAll("input").length,
          cachePanels: dialog.querySelectorAll(".reference-cache-manager").length,
        };
      }),
      { selectors: 1, textInputs: 0, cachePanels: 0 },
      "Loading a source must need only one choice",
    );
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      await mkdir(process.env.AGENVAS_E2E_SCREENSHOT_DIR, { recursive: true });
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(
        join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, `reference-${viewport.width}x${viewport.height}.png`),
        Buffer.from(shot.data, "base64"),
      );
    }
    await pointerClick(".reference-sources-dialog [role=combobox]");
    await pointerClick("[role=option][data-state=unchecked]:last-child");
    await pointerClick(".reference-sources-dialog button[type=submit]");
    await waitFor(() => !document.querySelector(".reference-sources-dialog"));
    assert.equal(
      await page(() => document.documentElement.dataset.sourceLoadCount),
      undefined,
      "Loading an existing JSON directory must not synchronize it again",
    );
    await pointerClick(".reference-private-cache-trigger");
    await waitFor(() => Boolean(document.querySelector(".reference-private-record")));
    const ownerCache = await page(() => {
      const dialog = document.querySelector(".reference-private-manager-dialog");
      return {
        text: dialog.textContent,
        overflow: dialog.scrollWidth > dialog.clientWidth,
        bottom: dialog.getBoundingClientRect().bottom,
      };
    });
    assert.equal(ownerCache.overflow, false, "Owner cache must fit viewport");
    assert.ok(ownerCache.bottom <= viewport.height + tolerance, "Owner cache footer must fit viewport");
    assert.ok(ownerCache.text.includes("old-snapshot-geometric-study"), "Old snapshot images must remain manageable");
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(
        join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, `owner-cache-${viewport.width}x${viewport.height}.png`),
        Buffer.from(shot.data, "base64"),
      );
    }
    await pointerClick(".reference-private-record button");
    await pointerClick(".reference-private-manager-dialog button[data-variant=destructive]");
    await waitFor(() => !document.querySelector(".reference-private-record"));
    assert.equal(
      await page(() => document.documentElement.dataset.privateClearCount),
      "1",
      "Only the explicitly selected owner record is cleared",
    );
    await pointerClick(".reference-private-manager-dialog .ui-dialog-header button");
    await waitFor(() => !document.querySelector(".reference-private-manager-dialog"));
    await pointerClick(".reference-toolbar [role=radio]:nth-child(2)");
    await waitFor(
      () =>
        document.querySelector(".reference-card")?.getAttribute("aria-label") === "Preview Synthetic private costume",
    );
    await page(() => document.querySelector(".reference-card")?.scrollIntoView({ block: "start" }));
    await waitFor(
      () =>
        document.querySelector(".reference-card-image img")?.naturalWidth === 240 &&
        document.documentElement.dataset.privateDownloadCount === "1",
    );
    await pointerClick(".reference-card");
    await waitFor(() => document.querySelector(".reference-preview-dialog img")?.naturalWidth === 240);
    assert.equal(
      await page(() => document.documentElement.dataset.privateDownloadCount),
      "1",
      "Preview must reuse the automatically cached card image",
    );
    assert.equal(
      await page(() => document.documentElement.dataset.importCount),
      "1",
      "Automatic caching must not import another project image",
    );
    await pointerClick(".reference-preview-dialog .ui-dialog-header button");
    await waitFor(
      () =>
        !document.querySelector(".reference-preview-dialog") &&
        document.activeElement === document.querySelector(".reference-card"),
    );
    assert.equal(
      await page(() => document.querySelectorAll(".reference-card").length),
      1,
      "Explicit external-source selection keeps its scope",
    );
    await pointerClick(".reference-source-choice [role=combobox]");
    await pointerClick('[role=option][data-value=""]');
    await waitFor(() => document.querySelectorAll(".reference-card").length === 2);
    assert.equal(
      await page(() => document.querySelectorAll(".reference-card").length),
      2,
      "Self-created and external costumes share one list",
    );
    await pointerClick(".reference-card:last-child");
    await waitFor(() => document.querySelector(".reference-preview-dialog img")?.naturalWidth === 240);
    await pointerClick(".costume-reference-manager button:first-child");
    await waitFor(() => Boolean(document.querySelector(".costume-reference-dialog")));
    const customLayout = await page(() => {
      const dialog = document.querySelector(".costume-reference-dialog");
      return { overflow: dialog.scrollWidth > dialog.clientWidth, bottom: dialog.getBoundingClientRect().bottom };
    });
    assert.equal(customLayout.overflow, false, "Custom costume editor must fit the viewport");
    assert.ok(
      customLayout.bottom <= viewport.height + tolerance,
      "Custom costume editor actions must remain accessible",
    );
    await page(() => {
      const input = document.querySelector(".costume-reference-dialog input");
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(input, "Synthetic updated outfit");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await pointerClick(".costume-reference-dialog button[type=submit]");
    await waitFor(
      () =>
        !document.querySelector(".costume-reference-dialog") &&
        document.documentElement.dataset.costumeUpdateCount === "1",
    );
    await pointerClick(".reference-preview-dialog button[type=submit]");
    await waitFor(() => document.querySelector("#applied-reference").textContent === "owned-custom-version");
    assert.equal(
      await page(() => document.documentElement.dataset.costumeImportCount),
      "1",
      "Self-created image imports through its own durable endpoint",
    );
    await pointerClick(".reference-picker-dialog .ui-dialog-header button");
    await page(() =>
      [...document.querySelectorAll("button")]
        .find(button => button.textContent === "Save as costume reference")
        .click(),
    );
    await waitFor(() => Boolean(document.querySelector(".costume-reference-dialog")));
    await pointerClick(".costume-reference-dialog button[type=submit]");
    await waitFor(
      () =>
        document.documentElement.dataset.costumeSaveCount === "1" &&
        document.querySelector(".costume-reference-dialog").textContent.includes("Costume reference saved."),
    );
    assert.equal(
      await page(() =>
        document.querySelector(".costume-reference-dialog").textContent.includes("Costume reference saved."),
      ),
      true,
    );
    console.log(
      JSON.stringify({
        viewport,
        append: initial.button,
        sourceSettings: "visible",
        ownerCache: "old snapshot cleared",
      }),
    );
  }
  for (const viewport of pageViewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url: new URL(`${route}?page&locale=zh`, baseUrl).href });
    await waitFor(() => document.querySelectorAll(".reference-card").length === 2);
    const toolbar = await page(() => {
      const filters = document.querySelector(".reference-filters");
      const controls = [...filters.querySelectorAll("input[type=search], [role=combobox]")];
      const labels = controls.map(control => {
        const label = document.querySelector(`label[for="${CSS.escape(control.id)}"]`);
        return {
          text: label?.textContent.trim(),
          above: Boolean(label && label.getBoundingClientRect().bottom <= control.getBoundingClientRect().top + 1),
        };
      });
      const source = document.querySelector(".reference-source-choice [role=combobox]").getBoundingClientRect();
      const manage = document.querySelector(".reference-source-settings-trigger").getBoundingClientRect();
      const cache = document.querySelector(".reference-private-cache-trigger");
      return {
        labels,
        overflow: document.documentElement.scrollWidth > innerWidth,
        manageNextToSource: manage.x >= source.right && Math.abs(manage.y - source.y) <= 1,
        manageVisible: manage.width >= 80 && manage.height >= 32,
        cacheBorder: parseFloat(getComputedStyle(cache).borderTopWidth),
      };
    });
    assert.equal(toolbar.labels.length, 8);
    assert.ok(
      toolbar.labels.every(label => label.text && label.above),
      JSON.stringify(toolbar),
    );
    assert.equal(toolbar.overflow, false, "Labeled reference filters must fit the page");
    assert.equal(toolbar.manageNextToSource, true, "Source management belongs beside the source picker");
    assert.equal(toolbar.manageVisible, true, "Source management needs a visible button-sized target");
    assert.ok(toolbar.cacheBorder >= 1, "Private cache needs a visible button boundary");
    if (process.env.AGENVAS_E2E_SCREENSHOT_DIR) {
      await mkdir(process.env.AGENVAS_E2E_SCREENSHOT_DIR, { recursive: true });
      const shot = await cdp("Page.captureScreenshot", { format: "png" });
      await writeFile(
        join(process.env.AGENVAS_E2E_SCREENSHOT_DIR, `filters-${viewport.width}x${viewport.height}.png`),
        Buffer.from(shot.data, "base64"),
      );
    }
  }
  await cdp("Emulation.setDeviceMetricsOverride", { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cdp("Page.navigate", { url: new URL(`${route}?preferred`, baseUrl).href });
  await pointerClick("#open-reference");
  try {
    await waitFor(
      () =>
        document.querySelectorAll(".reference-card").length === 1 &&
        document.querySelector(".reference-card")?.getAttribute("aria-label") === "Preview Synthetic website pose",
    );
  } catch (failure) {
    console.log(
      "Preferred directory diagnostic",
      await page(() => ({
        url: location.href,
        reads: document.documentElement.dataset.catalogReads,
        labels: [...document.querySelectorAll(".reference-card")].map(card => card.getAttribute("aria-label")),
        source: document.querySelector(".reference-source-choice [role=combobox]")?.textContent,
        text: document.body.textContent.slice(0, 600),
      })),
    );
    throw failure;
  }
  await pointerClick(".reference-toolbar [role=radio]:nth-child(2)");
  await waitFor(() => document.querySelectorAll(".reference-card").length === 2);
  assert.deepEqual(
    await page(() => [...document.querySelectorAll(".reference-card")].map(card => card.getAttribute("aria-label"))),
    ["Preview Synthetic website costume", "Preview Synthetic self-created costume"],
    "Preferred website costumes include independently archived costumes",
  );
  const preferredReads = await page(() => JSON.parse(document.documentElement.dataset.catalogReads));
  assert.ok(
    preferredReads.every(query => new URLSearchParams(query).get("sourceId") === "omilic"),
    "Default reads must never transiently request All or legacy GitHub",
  );
  assert.equal(new URLSearchParams(preferredReads[0]).has("includeSelfCreated"), false, "Pose scope stays unchanged");
  assert.equal(
    new URLSearchParams(preferredReads.at(-1)).get("includeSelfCreated"),
    "true",
    "Only the automatic costume scope adds self-created entries",
  );
  assert.equal(
    await page(() => document.documentElement.dataset.privateDownloadCount),
    undefined,
    "The default website must not start legacy image downloads",
  );
  await pointerClick(".reference-source-choice [role=combobox]");
  await pointerClick('[role=option][data-value="synthetic"]');
  await waitFor(
    () =>
      document.querySelectorAll(".reference-card").length === 1 &&
      document.querySelector(".reference-card")?.getAttribute("aria-label") === "Preview Synthetic private costume",
  );
  assert.equal(
    await page(() =>
      new URLSearchParams(JSON.parse(document.documentElement.dataset.catalogReads).at(-1)).has("includeSelfCreated"),
    ),
    false,
    "Explicit source choices keep their original scope",
  );
  await pointerClick(".reference-source-choice [role=combobox]");
  await pointerClick('[role=option][data-value="omilic"]');
  await waitFor(
    () =>
      document.querySelectorAll(".reference-card").length === 1 &&
      document.querySelector(".reference-card")?.getAttribute("aria-label") === "Preview Synthetic website costume",
  );
  await pointerClick(".reference-source-choice [role=combobox]");
  await pointerClick('[role=option][data-value=""]');
  await waitFor(() => document.querySelectorAll(".reference-card").length === 3);
  console.log(
    `Reference catalog browser regression passed: ${viewports.length} picker viewports, ${pageViewports.length} labeled page toolbars, preferred website with unified costumes${viewports.length ? ", optional saving, metadata editing, automatic private thumbnails, exact imports, source and cache actions" : ""}`,
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
