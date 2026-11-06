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
const route = "/e2e/controls.html";
// Verify the same bundled CSS and real shared controls as the shipped frontend.
const production = !process.argv.includes("--dev");
const pollMs = 50;
const timeoutMs = 15_000;
const viewports = [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
];
const profile = await mkdtemp(join(tmpdir(), "agenvas-controls-"));
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
  throw new Error(`Timed out waiting for controls: ${await page(() => document.body.innerText)}`);
}


async function key(key, code = key, windowsVirtualKeyCode) {
  const params = { key, code, ...(windowsVirtualKeyCode ? { windowsVirtualKeyCode } : {}) };
  await cdp("Input.dispatchKeyEvent", { type: "keyDown", ...params });
  await cdp("Input.dispatchKeyEvent", { type: "keyUp", ...params });
}
async function focus(id) {
  await page((id) => { const element = document.getElementById(id); element.scrollIntoView({ block: "center" }); element.focus(); }, id);
}
async function settled() {
  await page(async () => {
    await document.fonts.ready;
    await Promise.all(document.getAnimations().filter(animation => Number.isFinite(animation.effect?.getComputedTiming().iterations))
      .map(animation => animation.finished.catch(() => undefined)));
  });
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
  const apiRequests = [];
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.method === "Runtime.exceptionThrown") browserErrors.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
    if (message.method === "Network.requestWillBeSent" && new URL(message.params.request.url).pathname.startsWith("/api/")) apiRequests.push(message.params.request.url);
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Runtime.enable");
  await cdp("Network.enable");
  const url = new URL(route, server.resolvedUrls.local[0]).href;
  for (const viewport of viewports) {
    await cdp("Emulation.setDeviceMetricsOverride", { ...viewport, deviceScaleFactor: 1, mobile: false });
    await cdp("Page.navigate", { url });
    await waitFor(() => Boolean(document.getElementById("control-checkbox-mixed")));
    await settled();
    const dimensions = await page(() => {
      const ids = ["control-input", "control-format", "control-button", "control-search", "control-input-compact", "control-compact", "control-button-compact", "control-search-compact"];
      return { heights: Object.fromEntries(ids.map(id => [id, document.getElementById(id).getBoundingClientRect().height])),
        font: parseFloat(getComputedStyle(document.getElementById("control-textarea")).fontSize),
        contentHeight: document.getElementById("control-content-button").getBoundingClientRect().height,
        lineHeight: parseFloat(getComputedStyle(document.getElementById("control-textarea")).lineHeight),
        overflow: document.documentElement.scrollWidth > innerWidth };
    });
    for (const [id, height] of Object.entries(dimensions.heights)) assert.equal(height, id.includes("compact") ? 32 : 36, `${id} height at ${viewport.width}px`);
    assert.equal(dimensions.font, 14, "Editor body uses a readable 14px type size");
    assert.ok(dimensions.lineHeight >= 14 * 1.6, "Editor retains comfortable line spacing");
    assert.ok(dimensions.contentHeight >= 56, "Rich choice rows grow to fit both text lines");
    assert.equal(dimensions.overflow, false, `No horizontal overflow at ${viewport.width}px`);
    // Shared search controls retain the system's neutral, internal focus cue.
    await key("Tab", "Tab", 9);
    await focus("control-search-input");
    const searchFocus = () => {
      const search = document.getElementById("control-search");
      const probe = document.createElement("span");
      probe.style.color = "var(--ui-focus-border)";
      document.body.append(probe);
      const expected = getComputedStyle(probe).color;
      probe.remove();
      const style = getComputedStyle(search);
      return { visible: search.matches(":has(:focus-visible)"), border: style.borderColor,
        expected, outline: style.outlineWidth, outlineStyle: style.outlineStyle, active: document.activeElement?.id };
    };
    try {
      await waitFor(() => {
        const search = document.getElementById("control-search");
        const probe = document.createElement("span");
        probe.style.color = "var(--ui-focus-border)";
        document.body.append(probe);
        const expected = getComputedStyle(probe).color;
        probe.remove();
        const style = getComputedStyle(search);
        return search.matches(":has(:focus-visible)") && (style.outlineStyle === "none" || style.outlineWidth === "0px")
          && style.borderColor === expected;
      });
      const focused = await page(searchFocus);
      assert.equal(focused.border, focused.expected, JSON.stringify(focused));
    } catch (error) {
      throw new Error(`Neutral search focus at ${viewport.width}px: ${JSON.stringify(await page(searchFocus))}`, { cause: error });
    }
    const mixed = await page(() => {
      const checkbox = document.getElementById("control-checkbox-mixed");
      return { state: checkbox.getAttribute("aria-checked"), minus: getComputedStyle(checkbox.querySelector("[data-mixed-icon]")).display,
        check: getComputedStyle(checkbox.querySelector("[data-check-icon]")).display };
    });
    assert.equal(mixed.state, "mixed", "Partial selection is announced as mixed");
    assert.notEqual(mixed.minus, "none", "Partial selection shows a minus");
    assert.equal(mixed.check, "none", "Partial selection does not show a check");
    await focus("control-checkbox");
    await key(" ", "Space", 32);
    await waitFor(() => document.getElementById("control-checkbox").getAttribute("aria-checked") === "false");
    await key(" ", "Space", 32);
    await waitFor(() => document.getElementById("control-checkbox").getAttribute("aria-checked") === "true");
    await focus("control-checkbox-mixed");
    await key(" ", "Space", 32);
    await waitFor(() => document.getElementById("control-checkbox-mixed").getAttribute("aria-checked") === "true");
    await page(() => document.getElementById("control-checkbox-disabled").click());
    assert.equal(await page(() => document.getElementById("control-checkbox-disabled").getAttribute("aria-checked")), "true", "Disabled checkbox stays selected");
    await focus("control-switch");
    await key(" ", "Space", 32);
    await waitFor(() => document.getElementById("control-switch").getAttribute("aria-checked") === "false");
    await key(" ", "Space", 32);
    await waitFor(() => document.getElementById("control-switch").getAttribute("aria-checked") === "true");
    await settled();
    assert.notEqual(await page(() => getComputedStyle(document.querySelector("#control-switch [data-slot=switch-thumb]")).transform), "matrix(1, 0, 0, 1, 0, 0)", "Switch thumb moves when enabled");
    await focus("control-switch-small");
    await key(" ", "Space", 32);
    await waitFor(() => document.getElementById("control-switch-small").getAttribute("aria-checked") === "true");
    await settled();
    const switchBounds = await page(() => ["control-switch", "control-switch-small"].map(id => {
      const track = document.getElementById(id), thumb = track.querySelector("[data-slot=switch-thumb]");
      const bounds = track.getBoundingClientRect(), knob = thumb.getBoundingClientRect();
      return { id, translate: getComputedStyle(thumb).translate, inside: knob.left >= bounds.left + 1 && knob.right <= bounds.right - 1 && knob.top >= bounds.top + 1 && knob.bottom <= bounds.bottom - 1 };
    }));
    for (const track of switchBounds) {
      assert.equal(track.translate, "none", `${track.id} does not combine translate with transform`);
      assert.equal(track.inside, true, `${track.id} checked thumb stays within its track`);
    }
    await focus("control-tab-overview");
    await key("ArrowRight");
    await waitFor(() => document.getElementById("control-tab-assets").getAttribute("data-state") === "active");
    const nested = await page(() => ({
      orientation: document.getElementById("control-nested-tabs").getAttribute("aria-orientation"),
      direction: getComputedStyle(document.getElementById("control-nested-tabs")).flexDirection,
      alignment: getComputedStyle(document.getElementById("control-nested-parameters")).justifyContent,
      outer: document.getElementById("control-navigation").getAttribute("aria-orientation"),
    }));
    assert.equal(nested.outer, "vertical", "Outer navigation is vertical");
    assert.equal(nested.orientation, "horizontal", "Nested tabs announce horizontal navigation");
    assert.equal(nested.direction, "row", "Outer vertical styling does not stack inner horizontal tabs");
    assert.equal(nested.alignment, "center", "Outer vertical styling does not left-align inner horizontal triggers");
    await focus("control-nested-parameters");
    await key("ArrowRight");
    await waitFor(() => document.getElementById("control-nested-output").getAttribute("data-state") === "active");
    await focus("control-nav-creation");
    await key("ArrowDown");
    await waitFor(() => document.getElementById("control-nav-display").getAttribute("data-state") === "active");
    await key("ArrowUp");
    await waitFor(() => document.getElementById("control-nav-creation").getAttribute("data-state") === "active");
    await page(() => { const trigger = document.getElementById("control-long"); trigger.scrollIntoView({ block: "end" }); trigger.focus(); });
    await key(" ", "Space", 32);
    await waitFor(() => Boolean(document.querySelector('[role="listbox"]')));
    await settled();
    const menu = await page(() => {
      const box = document.querySelector('[role="listbox"]').getBoundingClientRect();
      return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: innerWidth, height: innerHeight,
        overflow: document.documentElement.scrollWidth > innerWidth };
    });
    assert.ok(menu.left >= 7 && menu.right <= menu.width - 7 && menu.top >= 7 && menu.bottom <= menu.height - 7,
      `Long Select menu stays within the viewport: ${JSON.stringify(menu)}`);
    assert.equal(menu.overflow, false, "Opening a long dropdown does not overflow the page");
    await key("End");
    await waitFor(() => document.activeElement?.getAttribute("data-value") === "63");
    await key("Enter");
    await waitFor(() => !document.querySelector('[role="listbox"]'));
    // Radix restores focus after unmounting the popup. Finish that lifecycle
    // before focusing another control, otherwise its delayed restore steals End.
    await waitFor(() => document.activeElement.id === "control-long");
    assert.equal(await page(() => document.getElementById("control-long").getAttribute("value")), "63", "Keyboard can reach and select the end of a long menu");
    await focus("control-brightness");
    await key("Home");
    assert.equal(await page(() => document.getElementById("control-brightness").value), "-100", "Range Home supports negative minima");
    await key("End");
    assert.equal(await page(() => document.getElementById("control-brightness").value), "100", "Range End reaches its maximum");
    await focus("control-actions");
    await key("Enter");
    await waitFor(() => Boolean(document.querySelector('[role="menu"]')));
    await key("Escape");
    await waitFor(() => !document.querySelector('[role="menu"]'));
    await waitFor(() => document.activeElement.id === "control-actions");
    assert.equal(await page(() => document.documentElement.scrollWidth > innerWidth), false, "Interactions retain page bounds");
    // Reset only synthetic review state so screenshots include checked and mixed examples.
    await cdp("Page.navigate", { url });
    await waitFor(() => Boolean(document.getElementById("control-checkbox-mixed")));
    await settled();
    const layout = await cdp("Page.getLayoutMetrics");
    const capture = await cdp("Page.captureScreenshot", { format: "png", captureBeyondViewport: true,
      clip: { x: 0, y: 0, width: viewport.width, height: layout.cssContentSize.height, scale: 1 } });
    const screenshot = `/tmp/agenvas-controls-review-${viewport.width}.png`;
    await writeFile(screenshot, Buffer.from(capture.data, "base64"), { mode: 0o600 });
    console.log(`Controls passed at ${viewport.width}x${viewport.height}; screenshot ${screenshot}`);
  }
  assert.deepEqual(browserErrors, [], "Controls have no browser runtime errors");
  assert.deepEqual(apiRequests, [], "Synthetic review never calls business APIs");
  console.log("Controls passed: shared density, editor readability, checked/mixed/disabled states, keyboard switches and tabs, nested orientations, dropdown bounds and focus, ranges; synthetic only");
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
