import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

/** Isolated Compose smoke test: closing Chrome cannot stop a persisted Mock Run. */
const baseUrl = process.env.AGENVAS_E2E_URL;
const bootstrapSecret = process.env.AGENVAS_E2E_BOOTSTRAP_SECRET;
const composeProject = process.env.AGENVAS_E2E_COMPOSE_PROJECT;
const recoverAfterRestart = process.env.AGENVAS_E2E_RESTART_RECOVERY === "true";
const chromePath = process.env.AGENVAS_E2E_CHROME
  ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const repoRoot = fileURLToPath(new URL("../../", import.meta.url));
const browserUrl = baseUrl ? new URL(baseUrl) : null;
if (!browserUrl || !bootstrapSecret || !composeProject
    || !/^agenvas-t18-[a-z0-9-]+$/.test(composeProject)
    || !["127.0.0.1", "localhost"].includes(browserUrl.hostname)
    || !browserUrl.port || ["8080", "8088"].includes(browserUrl.port)) {
  throw new Error("Use a named T18 isolated Compose project on a non-default loopback port");
}

const profile = await mkdtemp(join(tmpdir(), "agenvas-t18-browser-"));
const port = 19100 + Math.floor(Math.random() * 800);
const chrome = spawn(chromePath, [
  "--headless=new", "--no-first-run", "--no-default-browser-check",
  "--disable-background-networking", `--remote-debugging-port=${port}`,
  `--user-data-dir=${profile}`, "about:blank",
], { stdio: "ignore" });
let socket;
let nextId = 0;
const pending = new Map();

/** A small CDP client only for actions that must originate in the real browser. */
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
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description
      ?? result.exceptionDetails.text);
  }
  return result.result.value;
}

/** All Compose operations are confined to the caller-selected isolated project. */
function compose(...args) {
  return execFileSync("docker", ["compose", "-p", composeProject,
    "-f", "deploy/compose.dev.yaml", ...args],
  { cwd: repoRoot, encoding: "utf8" }).trim();
}

/** Database inspection is independent of the terminated Chrome session. */
function db(sql) {
  return compose("exec", "-T", "postgres", "psql", "-U", "agenvas",
    "-d", "agenvas", "-tAc", sql);
}

try {
  let version;
  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
      break;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }
  assert.ok(version, "Headless Chrome did not start");
  const target = await (await fetch(
    `http://127.0.0.1:${port}/json/new?${encodeURIComponent(baseUrl)}`,
    { method: "PUT" })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id == null) return;
    const waiting = pending.get(message.id);
    if (!waiting) return;
    pending.delete(message.id);
    if (message.error) waiting.reject(new Error(message.error.message));
    else waiting.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Runtime.enable");
  await cdp("Page.navigate", { url: baseUrl });
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await page(() => document.readyState === "complete")) break;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }

  const created = await page(async (secret) => {
    async function write(path, body, extraHeaders = {}) {
      const token = await (await fetch("/api/v1/auth/csrf")).json();
      const response = await fetch(path, {
        method: "POST", credentials: "same-origin",
        headers: { "Content-Type": "application/json", [token.headerName]: token.token,
          ...extraHeaders },
        body: JSON.stringify(body),
      });
      if (!response.ok) throw new Error(`${path} returned HTTP ${response.status}: `
        + await response.text());
      return response.json();
    }
    const setup = await (await fetch("/api/v1/auth/setup-status")).json();
    if (setup.setupRequired) {
      await write("/api/v1/auth/setup", {
        loginName: "t18-browser-admin", password: "t18-browser-password-123",
      }, { "X-Agenvas-Bootstrap-Secret": secret });
    }
    await write("/api/v1/auth/login", {
      loginName: "t18-browser-admin", password: "t18-browser-password-123",
    });
    const project = await write("/api/v1/projects", {
      name: "Browser-close background run", aspectRatio: "LANDSCAPE_16_9",
    });
    const agent = await write(`/api/v1/projects/${project.id}/agents`, {
      name: "Creator", instruction: "Create a three-shot storyboard", bindings: [],
    });
    const run = await write(`/api/v1/projects/${project.id}/runs`, {
      agentId: agent.id, instruction: "Make three shots",
    }, { "Idempotency-Key": crypto.randomUUID() });
    return { projectId: project.id, runId: run.id, initialStatus: run.status };
  }, bootstrapSecret);
  assert.match(created.runId, /^[0-9a-f-]{36}$/);
  assert.equal(created.initialStatus, "QUEUED");

  socket.close();
  chrome.kill();
  await new Promise((resolve) => chrome.once("exit", resolve));
  const deadline = Date.now() + 45000;
  let status = "";
  while (Date.now() < deadline) {
    status = db(`select status from agent_run where id = '${created.runId}'`);
    if (status === "WAITING_APPROVAL") break;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  assert.equal(status, "WAITING_APPROVAL", "Background Run did not reach approval");
  assert.equal(db(`select count(*) from execution_plan where run_id = '${created.runId}'`),
    "1", "The background model turn must persist exactly one plan");
  const completedTurns = Number(db(`select count(*) from task where run_id = '${created.runId}' `
    + "and kind = 'AGENT_TURN' and status = 'SUCCEEDED'"));
  assert.ok(completedTurns >= 1, "The background model turn must complete");
  assert.equal(db(`select count(*) from task where run_id = '${created.runId}' `
    + "and kind = 'AGENT_TURN' and status <> 'SUCCEEDED'"), "0");
  const result = { result: "passed", ...created, finalStatus: status,
    completedTurns, browserClosedBeforeApproval: true };

  if (recoverAfterRestart) {
    const runId = created.runId;
    const projectId = created.projectId;
    const finalTaskId = db(`select id from task where run_id = '${runId}' `
      + "and kind = 'AGENT_TURN' and input_json ->> 'stepIndex' = '2'");
    assert.match(finalTaskId, /^[0-9a-f-]{36}$/);
    const before = {
      plans: db(`select count(*) from execution_plan where run_id = '${runId}'`),
      tools: db(`select count(*) from tool_execution where run_id = '${runId}'`),
      versions: db(`select count(*) from artifact_version where project_id = '${projectId}'`),
      responseHash: db(`select md5(response_json::text) from llm_turn `
        + `where run_id = '${runId}' and step_index = 2`),
      leaseEpoch: Number(db(`select lease_epoch from task where id = '${finalTaskId}'`)),
    };
    assert.ok(Number(before.tools) > 0 && before.responseHash,
      "The last model response and tool ledger must be durable before restart");
    compose("stop", "server");
    assert.equal(db(`update task set status = 'RUNNING', output_json = null, `
      + "error_code = null, completed_at = null, lease_owner = 'crashed-worker', "
      + "lease_until = now() - interval '1 second', version = version + 1 "
      + `where id = '${finalTaskId}' and status = 'SUCCEEDED'`), "UPDATE 1");
    compose("start", "server");
    const recoveryDeadline = Date.now() + 45000;
    let recovered = "";
    while (Date.now() < recoveryDeadline) {
      recovered = db(`select status from task where id = '${finalTaskId}'`);
      if (recovered === "SUCCEEDED") break;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    assert.equal(recovered, "SUCCEEDED", "The new process did not finish the old turn");
    assert.equal(db(`select status from agent_run where id = '${runId}'`),
      "WAITING_APPROVAL");
    assert.equal(db(`select count(*) from execution_plan where run_id = '${runId}'`),
      before.plans);
    assert.equal(db(`select count(*) from tool_execution where run_id = '${runId}'`),
      before.tools);
    assert.equal(db(`select count(*) from artifact_version where project_id = '${projectId}'`),
      before.versions);
    assert.equal(db(`select md5(response_json::text) from llm_turn `
      + `where run_id = '${runId}' and step_index = 2`), before.responseHash);
    assert.ok(Number(db(`select lease_epoch from task where id = '${finalTaskId}'`))
      > before.leaseEpoch, "Recovery must take a new fenced lease");
    result.restartRecoveredFromLedger = true;
  }
  console.log(JSON.stringify(result));
} finally {
  socket?.close();
  if (chrome.exitCode == null) chrome.kill();
  await rm(profile, { recursive: true, force: true });
}
