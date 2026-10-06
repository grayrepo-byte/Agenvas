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
const route = "/e2e/skills-layout.html";
// Verify the same bundled CSS and real Skill page as the shipped frontend.
const production = !process.argv.includes("--dev");
const pollMs = 50;
const timeoutMs = 15_000;
const viewports = [
  { width: 2364, height: 1072 },
  { width: 1440, height: 900 },
  { width: 1024, height: 768 },
  { width: 780, height: 900 },
  { width: 390, height: 844 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-skills-layout-"));
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
  throw new Error(`Timed out waiting for Skill layout: ${await page(() => document.body.innerText)}`);
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
    await waitFor(() => Boolean(document.querySelector(".skills-list button")));
    await page(() => document.querySelector(".skills-list button").click());
    await waitFor(() => Boolean(document.querySelector(".skills-source-preview")));
    const layout = await page(() => {
      const rows = [...document.querySelectorAll(".skills-list button")];
      return {
        overflow: document.documentElement.scrollWidth > window.innerWidth,
        rows: rows.map(button => {
          const box = button.getBoundingClientRect();
          const text = button.querySelector(".skills-list-item-title");
          const range = document.createRange();
          if (text) range.selectNodeContents(text);
          else {
            const node = [...button.childNodes].find(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim());
            range.selectNode(node);
          }
          return { title: button.getAttribute("aria-label"), height: box.height,
            clipped: [...range.getClientRects()].some(rect => rect.top < box.top || rect.bottom > box.bottom || rect.right > box.right),
            badgeOverlaps: Boolean(button.querySelector(".ui-badge") && text && text.getBoundingClientRect().right > button.querySelector(".ui-badge").getBoundingClientRect().left),
            scrollWidth: button.scrollWidth, clientWidth: button.clientWidth };
        })
      };
    });
    assert.equal(layout.overflow, false, `Page overflows at ${viewport.width}px`);
    for (const row of layout.rows) {
      assert.equal(row.clipped, false, `Title escapes its row at ${viewport.width}px: ${JSON.stringify(row)}`);
      assert.ok(row.scrollWidth <= row.clientWidth + 1, "Skill row stays within its column");
      assert.equal(row.badgeOverlaps, false, "Title and builtin badge have separate space");
    }
    const selection = await page(() => {
      const selected = document.querySelector('.skills-list button[aria-pressed="true"]');
      const unselected = document.querySelector('.skills-list button[aria-pressed="false"]');
      return getComputedStyle(selected).backgroundColor !== getComputedStyle(unselected).backgroundColor;
    });
    assert.equal(selection, true, "Current Skill has a visible selected state");
    await page(() => document.querySelectorAll(".skills-resource-list details").forEach(details => { details.open = true; }));
    const resources = await page(() => ({
      overflow: document.documentElement.scrollWidth > window.innerWidth,
      previews: [...document.querySelectorAll(".skills-version-preview pre")].map(pre => ({
        width: pre.clientWidth, scrollWidth: pre.scrollWidth, height: pre.clientHeight,
        lineHeight: parseFloat(getComputedStyle(pre).lineHeight), fontSize: parseFloat(getComputedStyle(pre).fontSize)
      }))
    }));
    assert.equal(resources.overflow, false, "Expanded long resource paths stay within the page");
    for (const preview of resources.previews) {
      assert.ok(preview.scrollWidth <= preview.width + 1, "Document text wraps within its preview");
      assert.ok(preview.height <= 480 && preview.lineHeight >= preview.fontSize * 1.6, "Documents have bounded height and readable line spacing");
    }
    await page(() => document.querySelectorAll(".skills-resource-list details").forEach(details => { details.open = false; }));
    if (process.env.AGENVAS_SKILLS_SCREENSHOT && viewport.width === 1440) {
      const capture = await cdp("Page.captureScreenshot", { format: "png", captureBeyondViewport: true });
      await writeFile(process.env.AGENVAS_SKILLS_SCREENSHOT, Buffer.from(capture.data, "base64"), { mode: 0o600 });
    }
    await page(() => [...document.querySelectorAll(".skills-list button")].find(button => button.getAttribute("aria-label") === "个人创作方法").click());
    await waitFor(() => Boolean(document.querySelector(".skills-personal-editor textarea")));
    const editorLayout = await page(() => ({ overflow: document.documentElement.scrollWidth > window.innerWidth,
      escaping: [...document.querySelectorAll(".skills-personal-editor *")].filter(el => el.getBoundingClientRect().right > window.innerWidth).map(el => ({ tag: el.tagName, class: el.className, width: el.getBoundingClientRect().width })) }));
    assert.equal(editorLayout.overflow, false, `Personal editor stays within ${viewport.width}px: ${JSON.stringify(editorLayout.escaping)}`);
    await page(() => [...document.querySelectorAll('[role="tab"]')].find(tab => tab.textContent === "资源附件").focus());
    await cdp("Input.dispatchKeyEvent", { type: "keyDown", key: "Enter", code: "Enter" });
    await cdp("Input.dispatchKeyEvent", { type: "keyUp", key: "Enter", code: "Enter" });
    await waitFor(() => Boolean(document.querySelector('input[value^="references/"]')));
    assert.equal(await page(() => document.documentElement.scrollWidth > window.innerWidth), false, "Resource editor stays within the page");

  }
  assert.deepEqual(browserErrors, [], "Skill page has no browser runtime errors");
  console.log("Skill layout passed: long builtin/personal titles, badges, selected state, expanded resources and personal editor, five viewports; synthetic only");

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
