import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

/** Isolated local Chromium session shared by browser evidence scripts. */
export async function createBrowserSession({
  chromePath = process.env.AGENVAS_E2E_CHROME ??
    (process.platform === "darwin"
      ? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
      : "/usr/bin/chromium"),
  timeoutMs = 20_000,
} = {}) {
  const profile = await mkdtemp(join(tmpdir(), "agenvas-browser-"));
  const chrome = spawn(
    chromePath,
    [
      "--headless=new",
      "--no-sandbox",
      "--no-first-run",
      "--no-default-browser-check",
      "--disable-background-networking",
      "--remote-debugging-port=0",
      `--user-data-dir=${profile}`,
      "about:blank",
    ],
    { stdio: ["ignore", "ignore", "pipe"] },
  );
  let socket;
  const pending = new Map();
  let nextId = 0;
  const exceptions = [];
  try {
    const endpoint = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Chromium startup timed out")), timeoutMs);
      chrome.once("error", error => {
        clearTimeout(timer);
        reject(error);
      });
      chrome.once("exit", code => {
        clearTimeout(timer);
        reject(new Error(`Chromium exited during startup (${code})`));
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
        exceptions.push(message.params.exceptionDetails.exception?.description ?? message.params.exceptionDetails.text);
      const waiting = pending.get(message.id);
      if (!waiting) return;
      pending.delete(message.id);
      clearTimeout(waiting.timer);
      if (message.error) waiting.reject(new Error(message.error.message));
      else waiting.resolve(message.result);
    });
    function cdp(method, params = {}) {
      const id = ++nextId;
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
          pending.delete(id);
          reject(new Error(`CDP timeout: ${method}`));
        }, timeoutMs);
        pending.set(id, { resolve, reject, timer });
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
        await new Promise(resolve => setTimeout(resolve, 70));
      }
      throw new Error(`Browser condition timed out: ${await page(() => document.body.innerText)}`);
    }
    async function close() {
      for (const item of pending.values()) clearTimeout(item.timer);
      pending.clear();
      socket?.close();
      if (chrome.exitCode == null) {
        const exited = new Promise(resolve => chrome.once("exit", resolve));
        chrome.kill();
        await exited;
      }
      await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    }
    await cdp("Page.enable");
    await cdp("Runtime.enable");
    return { cdp, page, waitFor, close, exceptions };
  } catch (error) {
    socket?.close();
    if (chrome.exitCode == null) chrome.kill();
    await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    throw error;
  }
}
