import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build, preview } from "vite";

/** Production build and real Chrome controls over controlled HTTP; the fixture does not run speech recognition. */
const root = fileURLToPath(new URL("../", import.meta.url));
const profile = await mkdtemp(join(tmpdir(), "agenvas-subtitle-browser-"));
const screenshots = await mkdtemp(join(tmpdir(), "agenvas-subtitle-screenshots-"));
await chmod(screenshots, 0o700);
let server,
  chrome,
  socket,
  serial = 0;
const pending = new Map(),
  errors = [];
const pause = () => new Promise(resolve => setTimeout(resolve, 50));
function cdp(method, params = {}) {
  const id = ++serial;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
async function page(fn, ...args) {
  const value = await cdp("Runtime.evaluate", {
    expression: `(${fn.toString()})(...${JSON.stringify(args)})`,
    awaitPromise: true,
    returnByValue: true,
  });
  if (value.exceptionDetails)
    throw new Error(value.exceptionDetails.exception?.description ?? value.exceptionDetails.text);
  return value.result.value;
}
async function waitFor(fn, ...args) {
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    if (await page(fn, ...args)) return;
    await pause();
  }
  throw new Error(`Timed out: ${await page(() => document.body.innerText)}`);
}
async function click(selector) {
  await page(query => document.querySelector(query).scrollIntoView({ block: "nearest", inline: "nearest" }), selector);
  const point = await page(query => {
    const element = document.querySelector(query),
      box = element.getBoundingClientRect();
    const x = box.x + box.width / 2,
      y = box.y + box.height / 2;
    return { x, y, reachable: element.contains(document.elementFromPoint(x, y)) };
  }, selector);
  assert.ok(point.reachable, `Pointer can reach ${selector}`);
  await cdp("Input.dispatchMouseEvent", {
    type: "mousePressed",
    x: point.x,
    y: point.y,
    button: "left",
    clickCount: 1,
  });
  await cdp("Input.dispatchMouseEvent", {
    type: "mouseReleased",
    x: point.x,
    y: point.y,
    button: "left",
    clickCount: 1,
  });
}
async function clickText(text) {
  const marker = await page(label => {
    const control = [...document.querySelectorAll("button")].find(button => button.textContent.trim() === label);
    if (!control) throw new Error(`Missing button ${label}`);
    control.setAttribute("data-subtitle-browser-target", "true");
    return '[data-subtitle-browser-target="true"]';
  }, text);
  try {
    await click(marker);
  } finally {
    await page(() =>
      document.querySelector("[data-subtitle-browser-target]")?.removeAttribute("data-subtitle-browser-target"),
    );
  }
}
try {
  const importText = "1\n00:00:00,750 --> 00:00:01,750\nBrowser imported 中文\n\n";
  const importFile = join(profile, "synthetic-subtitles.srt");
  await writeFile(importFile, importText, { mode: 0o600 });
  const config = { root, configFile: join(root, "vite.config.ts") },
    outDir = join(profile, "build");
  await build({
    ...config,
    logLevel: "warn",
    build: { outDir, emptyOutDir: true, rollupOptions: { input: join(root, "e2e/video-edit.html") } },
  });
  server = await preview({ ...config, build: { outDir }, preview: { host: "127.0.0.1", port: 0, open: false } });
  chrome = spawn(
    process.env.AGENVAS_E2E_CHROME ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
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
    const timer = setTimeout(() => reject(new Error("Chrome did not start")), 20000);
    chrome.once("error", reject);
    chrome.stderr.on("data", chunk => {
      const match = chunk.toString().match(/DevTools listening on (ws:\/\/\S+)/);
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
  socket.addEventListener("message", ({ data }) => {
    const message = JSON.parse(data);
    if (message.method === "Runtime.exceptionThrown")
      errors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Runtime.enable");
  await cdp("Page.enable");
  await cdp("Browser.setDownloadBehavior", { behavior: "allow", downloadPath: profile });
  for (const viewport of [
    { width: 1440, height: 900 },
    { width: 390, height: 844 },
    { width: 844, height: 390 },
  ]) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", {
      url: `${server.resolvedUrls.local[0]}e2e/video-edit.html?subtitles&subtitle-failure`,
    });
    await waitFor(
      () =>
        document.querySelector('.video-edit-dialog [data-slot="dialog-footer"] button:last-child')?.disabled === false,
    );
    await click('[role="tab"][id$="-trigger-captions"]');
    await waitFor(() => !!document.querySelector(".subtitle-editor textarea"));
    await click(".subtitle-editor textarea");
    await page(() => {
      const input = document.querySelector(".subtitle-editor textarea");
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set.call(input, "Browser corrected 中文");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await click('.video-edit-header button[aria-label="关闭"]');
    await waitFor(() => document.body.innerText.includes("Synthetic subtitle save failure"));
    assert.equal(
      await page(() => document.querySelector(".subtitle-editor textarea")?.value),
      "Browser corrected 中文",
    );
    await page(() => window.subtitleFixture.failSave(false));
    await click('.video-edit-header button[aria-label="关闭"]');
    await waitFor(() => !document.querySelector(".video-edit-dialog"));
    await click("main button");
    await waitFor(() => !!document.querySelector(".video-edit-dialog"));
    await click('[role="tab"][id$="-trigger-captions"]');
    await waitFor(() => document.querySelector(".subtitle-editor textarea")?.value === "Browser corrected 中文");
    await click(".subtitle-editor textarea");
    assert.equal(
      await page(
        () =>
          document.querySelector(".subtitle-editor").scrollWidth <=
          document.querySelector(".subtitle-editor").clientWidth + 1,
      ),
      true,
    );
    const document = await cdp("DOM.getDocument");
    const input = await cdp("DOM.querySelector", { nodeId: document.root.nodeId, selector: ".subtitle-file-input" });
    await cdp("DOM.setFileInputFiles", { nodeId: input.nodeId, files: [importFile] });
    await waitFor(() => document.querySelector(".subtitle-editor textarea")?.value === "Browser imported 中文");
    assert.equal(await page(() => window.subtitleImportedContent), importText);
    await page(() => {
      window.subtitleDownloads = [];
      const native = URL.createObjectURL;
      URL.createObjectURL = blob => {
        if (blob.type.startsWith("text/vtt") || blob.type.startsWith("application/x-subrip"))
          void blob.text().then(text => window.subtitleDownloads.push(text));
        return native(blob);
      };
    });
    await clickText("导出成片 SRT");
    await waitFor(() => window.subtitleDownloads.length === 1);
    await clickText("导出成片 VTT");
    await waitFor(() => window.subtitleDownloads.length === 2);
    assert.deepEqual(await page(() => window.subtitleDownloads), [
      importText,
      "WEBVTT\n\n00:00:00.750 --> 00:00:01.750\nBrowser imported 中文\n\n",
    ]);
    await click(".subtitle-editor textarea");
    const capture = await cdp("Page.captureScreenshot", { format: "png" });
    await writeFile(
      join(screenshots, `${viewport.width}x${viewport.height}.png`),
      Buffer.from(capture.data, "base64"),
      { mode: 0o600 },
    );
  }
  assert.deepEqual(errors, []);
  console.log(
    JSON.stringify(
      {
        checked: [
          "actual Chrome controls",
          "three viewport sizes",
          "save failure retains corrections",
          "close and reopen preserves source corrections",
          "native SRT file read",
          "actual SRT/VTT Blob downloads",
        ],
        screenshots,
        speechRecognition: "not run; controlled fixture only",
      },
      null,
      2,
    ),
  );
} finally {
  socket?.close();
  if (chrome && chrome.exitCode === null) {
    const stopped = new Promise(resolve => chrome.once("exit", resolve));
    chrome.kill();
    await stopped;
  }
  await server?.close();
  await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
}
