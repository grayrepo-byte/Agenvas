import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

/** Real CSS/layout regression without a backend, credentials or provider calls. */
const root = fileURLToPath(new URL("../", import.meta.url));
const chromePath = process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const route = "/e2e/project-resources.html";
// Native synthetic media is available only through the serve-only fixture middleware.
const pollMs = 50;
const timeoutMs = 15_000;
const viewports = [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
  { width: 844, height: 390 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-project-resources-"));
const config = { root, configFile: join(root, "vite.config.ts") };
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
  throw new Error(`Timed out waiting for project resources: ${await page(() => document.body.innerText)}`);
}

try {
  server = await createServer({ ...config, server: { host: "127.0.0.1", port: 0, open: false } });
  await server.listen();
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
    if (message.method === "Runtime.exceptionThrown")
      console.error(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
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
    for (const locale of ["zh", "en", "ru", "ja"]) {
      await cdp("Page.navigate", { url: new URL(`${route}?locale=${locale}`, baseUrl).href });
      await waitFor(() => document.querySelectorAll(".project-resource-card").length === 8);
      await waitFor(() =>
        [...document.querySelectorAll(".project-resource-thumbnail img")].every(
          image => image.complete && image.naturalWidth > 0,
        ),
      );
      const layout = await page(() => {
        const drawer = document.querySelector(".workspace-drawer");
        return {
          width: drawer.clientWidth,
          scrollWidth: drawer.scrollWidth,
          screenWidth: innerWidth,
          bounds: drawer.getBoundingClientRect().toJSON(),
          previews: [...document.querySelectorAll(".project-resource-preview")].map(button => {
            const box = button.getBoundingClientRect();
            const thumbnail = button.querySelector(".project-resource-thumbnail").getBoundingClientRect();
            return {
              width: box.width,
              height: box.height,
              top: box.top,
              bottom: box.bottom,
              thumbnail: { top: thumbnail.top, bottom: thumbnail.bottom },
              copyTop: button
                .closest(".project-resource-card")
                .querySelector(".project-resource-copy")
                .getBoundingClientRect().top,
            };
          }),
          autoplay: Boolean(document.querySelector("video[autoplay],audio[autoplay]")),
        };
      });
      assert.equal(layout.width, layout.scrollWidth, `No horizontal overflow at ${viewport.width}/${locale}`);
      assert.ok(layout.bounds.left >= 0 && layout.bounds.right <= viewport.width);
      assert.equal(layout.autoplay, false);
      for (const preview of layout.previews) {
        assert.ok(preview.width > 90 && preview.height > 60, "Thumbnails reserve visible space");
        assert.ok(
          preview.thumbnail.top >= preview.top && preview.thumbnail.bottom <= preview.bottom,
          "The whole thumbnail must fit inside its clickable preview",
        );
        assert.ok(preview.copyTop >= preview.thumbnail.bottom, "Result labels must stay below the thumbnail");
      }
      if (locale === "zh" && viewport.width === 1440 && process.env.AGENVAS_RESOURCES_SCREENSHOT) {
        const capture = await cdp("Page.captureScreenshot", {
          format: "png",
          clip: {
            x: layout.bounds.x,
            y: layout.bounds.y,
            width: layout.bounds.width,
            height: layout.bounds.height,
            scale: 1,
          },
        });
        await writeFile(process.env.AGENVAS_RESOURCES_SCREENSHOT, Buffer.from(capture.data, "base64"), { mode: 0o600 });
      }
      if (locale === "zh") {
        await page(() => document.querySelector('button[aria-label="放到画布：海边的房子 · 草图与光影 · v1"]').click());
        await waitFor(() => Boolean(document.querySelector('[role="alert"]')));
        await page(() => document.querySelector('button[aria-label="放到画布：海边的房子 · 草图与光影 · v1"]').click());
        await waitFor(() => document.documentElement.dataset.placed === "true");
        const evidence = await page(() => window.__projectResources.evidence());
        assert.equal(evidence.writes.length, 2);
        assert.deepEqual(evidence.writes[0], evidence.writes[1]);
        assert.equal(evidence.writes[0].commands[0].versionId, "IMAGE-1");
        assert.deepEqual(evidence.unexpected, []);
      }
    }
  }
  console.log(
    "Project resource browser passed: desktop, mobile and short viewport; four languages; exact-result thumbnails, no overflow/autoplay and stable placement retry; synthetic only",
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
