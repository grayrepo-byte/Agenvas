import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createServer } from "vite";

// Real workspace components and native input; every API request uses synthetic
// in-memory data. The serve-only media middleware supplies original geometry.
const root = fileURLToPath(new URL("../", import.meta.url));
const profile = await mkdtemp(join(tmpdir(), "agenvas-light-actions-browser-"));
const screenshots = await mkdtemp(join(tmpdir(), "agenvas-light-actions-proof-"));
await chmod(profile, 0o700);
await chmod(screenshots, 0o700);
let server,
  chrome,
  socket,
  nextId = 0;
const pending = new Map(),
  errors = [];
const pause = () => new Promise(resolve => setTimeout(resolve, 50));
function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${method} timed out`));
    }, 20_000);
    pending.set(id, {
      resolve: value => {
        clearTimeout(timer);
        resolve(value);
      },
      reject,
    });
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
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (await page(fn, ...args)) return;
    await pause();
  }
  throw new Error(`Timed out: ${fn.toString()}`);
}
async function key(key, code = key, windowsVirtualKeyCode) {
  const params = { key, code, ...(windowsVirtualKeyCode ? { windowsVirtualKeyCode } : {}) };
  await cdp("Input.dispatchKeyEvent", { type: "keyDown", ...params });
  await cdp("Input.dispatchKeyEvent", { type: "keyUp", ...params });
}
async function move(selector, click = false) {
  if (click)
    await waitFor(selector => {
      const element = document.querySelector(selector);
      if (!element) return false;
      element.scrollIntoView({ block: "nearest", inline: "nearest" });
      const rect = element.getBoundingClientRect();
      return element.contains(document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2));
    }, selector);
  const point = await page(
    (selector, click) => {
      const element = document.querySelector(selector);
      if (!element) throw new Error(`Missing ${selector}`);
      element.scrollIntoView({ block: "nearest", inline: "nearest" });
      const rect = element.getBoundingClientRect();
      const x = rect.x + rect.width / 2,
        y = rect.y + rect.height / 2;
      if (click && !element.contains(document.elementFromPoint(x, y))) throw new Error(`Occluded ${selector}`);
      return { x, y };
    },
    selector,
    click,
  );
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...point });
  if (click) {
    await cdp("Input.dispatchMouseEvent", { type: "mousePressed", button: "left", clickCount: 1, ...point });
    await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", button: "left", clickCount: 1, ...point });
  }
}
async function capture(name) {
  const result = await cdp("Page.captureScreenshot", { format: "png" });
  await writeFile(join(screenshots, `${name}.png`), Buffer.from(result.data, "base64"), { mode: 0o600 });
}
async function navigate(route, width, height, touch = false) {
  await cdp("Emulation.setDeviceMetricsOverride", { width, height, deviceScaleFactor: 1, mobile: false });
  await cdp("Emulation.setTouchEmulationEnabled", { enabled: touch, maxTouchPoints: 1 });
  await cdp("Page.navigate", { url: new URL(route, server.resolvedUrls.local[0]).href });
}
try {
  server = await createServer({
    root,
    configFile: join(root, "vite.config.ts"),
    server: { host: "127.0.0.1", port: 0, open: false },
  });
  await server.listen();
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
    const timer = setTimeout(() => reject(new Error("Chrome did not start")), 20_000);
    chrome.once("error", reject);
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
  await new Promise(resolve => socket.addEventListener("open", resolve, { once: true }));
  socket.addEventListener("message", event => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown")
      errors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Runtime.enable");
  const route = "/e2e/canvas-studio.html?light-actions";
  for (const width of [1440, 1100]) {
    await navigate(route, width, 900);
    await waitFor(
      () =>
        document.querySelectorAll(".react-flow__node").length === 5 &&
        document.querySelector(".character-reference-image")?.complete &&
        document.querySelector(".media-card-preview > img")?.complete,
    );
    await move(".character-node-surface");
    await waitFor(() => getComputedStyle(document.querySelector(".character-node-edge-actions")).opacity === "1");
    const actions = await page(() =>
      [...document.querySelectorAll('[data-variant="media-overlay"]')].map(element => {
        const css = getComputedStyle(element);
        return {
          width: element.offsetWidth,
          height: element.offsetHeight,
          background: css.backgroundColor,
          shadow: css.boxShadow,
          label: element.getAttribute("aria-label"),
        };
      }),
    );
    assert.equal(actions.length, 4);
    for (const action of actions) {
      assert.equal(action.width, 44);
      assert.equal(action.height, 44);
      assert.equal(action.background, "rgba(0, 0, 0, 0)");
      assert.equal(action.shadow, "none");
      assert.ok(action.label);
    }
    await capture(`workspace-hover-${width}`);
    for (let attempt = 0; attempt < 2; attempt++) {
      await move('.media-expand-button[aria-label="放大图片"]', true);
      await waitFor(() => !!document.querySelector(".yarl__root"));
      await key("Escape", "Escape", 27);
      await waitFor(
        () =>
          !document.querySelector(".yarl__root") &&
          document.activeElement?.matches('.media-expand-button[aria-label="放大图片"]'),
      );
    }
    await move(".character-node-surface");
    await move('.character-node-edge-actions [aria-haspopup="menu"]', true);
    await waitFor(() => !!document.querySelector('[role="menu"]'));
    await capture(`character-menu-${width}`);
    await key("Escape", "Escape", 27);
    await waitFor(
      () =>
        !document.querySelector('[role="menu"]') &&
        document.activeElement?.matches('.character-node-edge-actions [aria-haspopup="menu"]'),
    );
    await key("Tab", "Tab", 9);
    await page(() => document.querySelector(".character-node-edge-actions button").focus());
    await waitFor(
      () => getComputedStyle(document.querySelector(".character-node-edge-actions button"), "::before").opacity === "1",
    );
    assert.equal(await page(() => document.activeElement.matches(":focus-visible")), true);
    await capture(`character-focus-${width}`);
    await move('.react-flow__node[data-id="brief"] .content-card-body', true);
    await waitFor(() => !!document.querySelector(".artifact-card-toolbar"));
    await capture(`text-selected-${width}`);
    // Start this independent scenario at the overview; the text editor owns a
    // measured camera position which can place other nodes behind its panel.
    await navigate(route, width, 900);
    await waitFor(() => document.querySelectorAll(".react-flow__node").length === 5);
    await move('.react-flow__node[data-id="video"] .artifact-canvas-card', true);
    await waitFor(() => !!document.querySelector(".media-draft-reference-chip > .media-draft-reference-remove"));
    const add = await page(() => {
      const button = document.querySelector(".media-draft-reference-add"),
        style = getComputedStyle(button);
      return { width: button.offsetWidth, height: button.offsetHeight, border: style.borderStyle };
    });
    assert.deepEqual(add, { width: 46, height: 46, border: "dashed" });
    await move(".media-draft-reference-chip");
    await waitFor(() => getComputedStyle(document.querySelector(".media-draft-reference-remove")).opacity === "1");
    assert.equal(await page(() => document.querySelector(".media-draft-reference-remove").offsetWidth), 32);
    await cdp("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 1 });
    await waitFor(() => document.querySelector(".media-draft-reference-remove").offsetWidth === 44);
    assert.equal(await page(() => document.querySelector(".media-draft-reference-chip").offsetWidth), 64);
    await capture(`reference-touch-${width}`);
    await move(".media-draft-reference-remove", true);
    await waitFor(() => !document.querySelector(".media-draft-reference-remove"));
    console.log(
      `Five nodes, media previews twice, toolbar, hover, keyboard focus and menu restore passed at ${width}px`,
    );
  }
  // The mobile canvas stays read-only; touch visibility can be checked without
  // changing the product's edit breakpoint or creating a gesture in user data.
  await navigate(route, 390, 844, true);
  await waitFor(() => !!document.querySelector(".character-node-edge-actions"));
  assert.equal(await page(() => matchMedia("(pointer: coarse)").matches), true);
  assert.equal(await page(() => getComputedStyle(document.querySelector(".character-node-edge-actions")).opacity), "1");
  await capture("workspace-touch-390");
  await navigate("/e2e/system-design.html?route=/projects", 1440, 900);
  await waitFor(() => document.querySelectorAll(".project-card").length > 0);
  await capture("project-cards-1440");
  assert.equal(
    await page(() => getComputedStyle(document.querySelector(".projects-filter-actions [aria-label]")).backgroundColor),
    "rgba(0, 0, 0, 0)",
  );
  for (const width of [1440, 390]) {
    await navigate("/e2e/settings-design.html?route=/library", width, width === 390 ? 844 : 900, width === 390);
    await waitFor(() => !!document.querySelector(".library-favorite"));
    const favorite = await page(() => {
      const button = document.querySelector(".library-favorite"),
        style = getComputedStyle(button);
      const bounds = button.getBoundingClientRect(),
        icon = button.querySelector("svg").getBoundingClientRect();
      return {
        width: button.offsetWidth,
        height: button.offsetHeight,
        background: style.backgroundColor,
        pressed: button.getAttribute("aria-pressed"),
        dx: icon.x + icon.width / 2 - bounds.x - bounds.width / 2,
        dy: icon.y + icon.height / 2 - bounds.y - bounds.height / 2,
      };
    });
    assert.equal(favorite.width, 44);
    assert.equal(favorite.height, 44);
    assert.ok(
      Math.abs(favorite.dx) < 1 && Math.abs(favorite.dy) < 1,
      `Centered favorite icon: ${JSON.stringify(favorite)}`,
    );
    assert.equal(favorite.background, "rgba(0, 0, 0, 0)");
    await move(".library-favorite", true);
    await waitFor(
      before => document.querySelector(".library-favorite")?.getAttribute("aria-pressed") !== before,
      favorite.pressed,
    );
    assert.equal(await page(() => document.querySelectorAll('[role="dialog"]').length), 0);
    await capture(`asset-cards-${width}`);
    await move(".library-favorite", true);
    await waitFor(
      before => document.querySelector(".library-favorite")?.getAttribute("aria-pressed") === before,
      favorite.pressed,
    );
  }
  await navigate("/e2e/settings-design.html?route=/settings/providers", 1440, 900);
  await waitFor(() => !!document.querySelector(".app-shell--settings"));
  await capture("settings-secondary-actions-1440");
  assert.deepEqual(errors, []);
  console.log(`Light actions passed; synthetic screenshots: ${screenshots}`);
} finally {
  socket?.close();
  if (chrome && chrome.exitCode === null && chrome.signalCode === null) {
    const exited = new Promise(resolve => chrome.once("exit", resolve));
    chrome.kill();
    await exited;
  }
  await server?.close();
  await rm(profile, { recursive: true, force: true, maxRetries: 4, retryDelay: 100 });
}
