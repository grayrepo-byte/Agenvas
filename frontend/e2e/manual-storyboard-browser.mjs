import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

/** Dependency-free Chrome DevTools smoke test for the Mock-mode manual storyboard. */
const baseUrl = process.env.AGENVAS_E2E_URL;
const bootstrapSecret = process.env.AGENVAS_E2E_BOOTSTRAP_SECRET;
const chromePath = process.env.AGENVAS_E2E_CHROME
  ?? "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const projectName = `M1 browser storyboard ${Date.now()}`;
if (!baseUrl || !bootstrapSecret) {
  throw new Error("Set AGENVAS_E2E_URL and AGENVAS_E2E_BOOTSTRAP_SECRET for an isolated instance");
}

const profile = await mkdtemp(join(tmpdir(), "agenvas-m1-browser-"));
const port = 19000 + Math.floor(Math.random() * 1000);
const chrome = spawn(chromePath, [
  "--headless=new", "--no-first-run", "--no-default-browser-check",
  "--disable-background-networking", "--window-size=1440,900",
  `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "about:blank",
], { stdio: "ignore" });

let socket;
let nextId = 0;
const pending = new Map();

/** Sends one CDP command, keeping protocol errors distinct from page errors. */
function cdp(method, params = {}) {
  const id = ++nextId;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}

/** Executes a serializable DOM action or assertion in the actual browser page. */
async function page(fn, ...args) {
  const expression = `(${fn.toString()})(...${JSON.stringify(args)})`;
  const result = await cdp("Runtime.evaluate", {
    expression, awaitPromise: true, returnByValue: true,
  });
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.text
      + ": " + (result.exceptionDetails.exception?.description ?? ""));
  }
  return result.result.value;
}

/** Polls an observable page condition without assuming fixed network timing. */
async function waitFor(fn, description, timeoutMs = 15000, ...args) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await page(fn, ...args)) return;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  throw new Error(`Timed out waiting for ${description}`);
}

/** Changes a labeled controlled React field through its native value setter. */
async function fill(rootSelector, labelText, value) {
  await page((rootSelectorInPage, label, nextValue) => {
    const root = document.querySelector(rootSelectorInPage);
    const field = [...root.querySelectorAll("label")]
      .find((candidate) => candidate.textContent.trim().startsWith(label))
      ?.querySelector("input,textarea,select");
    if (!field) throw new Error(`Missing ${label} field`);
    const prototype = field instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype
      : field instanceof HTMLSelectElement
        ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(prototype, "value").set.call(field, nextValue);
    field.dispatchEvent(new Event(field instanceof HTMLSelectElement ? "change" : "input",
      { bubbles: true }));
  }, rootSelector, labelText, String(value));
}

/** Clicks an exact-text button inside a limited section. */
async function click(rootSelector, text) {
  await page((rootSelectorInPage, buttonText) => {
    const root = document.querySelector(rootSelectorInPage);
    const button = [...root.querySelectorAll("button")]
      .find((candidate) => candidate.textContent.trim() === buttonText);
    if (!button || button.disabled) throw new Error(`Missing enabled button ${buttonText}`);
    button.click();
  }, rootSelector, text);
}

/** Uses a real browser pointer activation for media controls that reject synthetic clicks. */
async function pointerClick(rootSelector, text) {
  const point = await page((selector, buttonText) => {
    const root = document.querySelector(selector);
    const button = [...root.querySelectorAll("button")]
      .find((candidate) => candidate.textContent.trim() === buttonText);
    if (!button || button.disabled) throw new Error(`Missing enabled button ${buttonText}`);
    button.scrollIntoView({ block: "center" });
    const rect = button.getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
  }, rootSelector, text);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...point,
    button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...point,
    button: "left", clickCount: 1 });
}

/** Reads the visible React Flow card matching a title. */
async function visibleCard(title) {
  return page((expected) => [...document.querySelectorAll(".react-flow__node")]
    .some((node) => node.textContent.includes(expected)
      && getComputedStyle(node).visibility === "visible"), title);
}

/** Reads the authenticated database-backed projection through the browser session. */
async function snapshot() {
  return page(async () => {
    const projectId = location.pathname.split("/").at(-1);
    const response = await fetch(`/api/v1/projects/${projectId}/snapshot`);
    if (!response.ok) throw new Error(`Snapshot returned HTTP ${response.status}`);
    return response.json();
  });
}

/** Reads the task counts the human sees before confirming a frozen media plan. */
async function displayedApprovalCounts() {
  return page(() => {
    const panel = document.querySelector("section[aria-label='待审批执行计划']");
    if (!panel) throw new Error("Approval panel is missing");
    const match = panel.textContent.match(/本次将创建图片任务 (\d+) 个、视频任务 (\d+) 个/);
    if (!match) throw new Error("Approval panel has no explicit media-task counts");
    return { image: Number(match[1]), video: Number(match[2]),
      steps: panel.querySelectorAll("ol > li").length };
  });
}

/** Selects the source card, then drags from its visible output handle onto the target card. */
async function connect(sourceTitle, sourceHandle, targetTitle, targetHandle) {
  await page(() => document.querySelector(".react-flow__controls-fitview")?.click());
  await new Promise((resolve) => setTimeout(resolve, 500));
  // Only a selected card renders an output handle, so the gesture starts with a real click on the card.
  const cardPoint = await page((sourceName) => {
    const node = [...document.querySelectorAll(".react-flow__node")]
      .find((candidate) => candidate.querySelector("h3")?.textContent === sourceName);
    if (!node) throw new Error(`Missing ${sourceName} card`);
    const rect = node.querySelector("h3").getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
  }, sourceTitle);
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...cardPoint,
    button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...cardPoint,
    button: "left", clickCount: 1 });
  await new Promise((resolve) => setTimeout(resolve, 200));
  const points = await page((sourceName, sourceId, targetName, targetId) => {
    const nodes = [...document.querySelectorAll(".react-flow__node")];
    function nodeOf(title) {
      const node = nodes.find((candidate) => candidate.querySelector("h3")?.textContent === title);
      if (!node) throw new Error(`Missing ${title} card`);
      return node;
    }
    function center(title, handleId) {
      const handle = nodeOf(title).querySelector(`[data-handleid='${handleId}']`);
      if (!handle) throw new Error(`Missing ${title} ${handleId} handle`);
      const rect = handle.getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    }
    const source = center(sourceName, sourceId);
    const target = center(targetName, targetId);
    return { source, target,
      sourceHit: document.elementFromPoint(source.x, source.y)?.getAttribute("data-handleid"),
      targetOpacity: getComputedStyle(nodeOf(targetName)
        .querySelector(`[data-handleid='${targetId}']`)).opacity };
  }, sourceTitle, sourceHandle, targetTitle, targetHandle);
  for (const point of [points.source, points.target]) {
    assert.ok(point.x > 0 && point.x < 1440 && point.y > 0 && point.y < 900,
      "Connection endpoint must be in the browser viewport");
  }
  assert.equal(points.sourceHit, sourceHandle,
    "A selected card's output handle must receive the pointer");
  assert.equal(points.targetOpacity, "0",
    "Target handles stay out of the way until a gesture reaches them");
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...points.source,
    button: "left", clickCount: 1 });
  await new Promise((resolve) => setTimeout(resolve, 100));
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved",
    x: (points.source.x + points.target.x) / 2,
    y: (points.source.y + points.target.y) / 2, button: "left", buttons: 1 });
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.equal(await page(() => Boolean(document.querySelector(".react-flow__connectionline"))),
    true, "React Flow must show a connection line while dragging");
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", ...points.target,
    button: "left", buttons: 1 });
  await new Promise((resolve) => setTimeout(resolve, 100));
  assert.deepEqual(await page((targetName, targetId) => {
    const handle = [...document.querySelectorAll(".react-flow__node")]
      .find((node) => node.querySelector("h3")?.textContent === targetName)
      .querySelector(`[data-handleid='${targetId}']`);
    return { opacity: getComputedStyle(handle).opacity, valid: handle.classList.contains("valid") };
  }, targetTitle, targetHandle), { opacity: "1", valid: true },
  "The reached target handle must appear and report a valid drop");
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased", ...points.target,
    button: "left", clickCount: 1 });
}

try {
  let version;
  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`);
      version = await response.json();
      break;
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }
  assert.ok(version, "Headless Chrome did not start");
  const target = await (await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent(baseUrl)}`, {
    method: "PUT",
  })).json();
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.id == null) return;
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id);
    if (message.error) entry.reject(new Error(message.error.message));
    else entry.resolve(message.result);
  });
  await cdp("Page.enable");
  await cdp("Runtime.enable");
  await cdp("Network.enable");
  await cdp("Page.navigate", { url: new URL("/setup", baseUrl).href });
  await waitFor(() => document.body.textContent.includes("初始化密钥")
    || document.body.textContent.includes("系统已初始化"), "setup status");
  if (await page(() => document.body.textContent.includes("初始化密钥"))) {
    await fill("main form", "初始化密钥", bootstrapSecret);
    await fill("main form", "管理员登录名", "m1-smoke-admin");
    await fill("main form", "管理员密码", "m1-smoke-password-123");
    await click("main form", "创建管理员");
    await waitFor(() => location.pathname === "/login", "login navigation");
  } else {
    await cdp("Page.navigate", { url: new URL("/login", baseUrl).href });
    await waitFor(() => Boolean(document.querySelector("main form")), "login form");
  }
  await fill("main form", "登录名", "m1-smoke-admin");
  await fill("main form", "密码", "m1-smoke-password-123");
  await click("main form", "登录");
  await waitFor(() => location.pathname === "/projects", "project list");
  await fill("main form", "项目名称", projectName);
  await click("main form", "创建项目");
  await waitFor((name) => [...document.querySelectorAll("article h3")]
    .some((heading) => heading.textContent === name), "new project", 15000, projectName);
  await page((name) => {
    const article = [...document.querySelectorAll("article")]
      .find((item) => item.querySelector("h3")?.textContent === name);
    article.querySelector("a[href^='/projects/']").click();
  }, projectName);
  await waitFor(() => Boolean(document.querySelector("section[aria-label='手工分镜']")),
    "manual storyboard panel");

  const form = "section[aria-label='手工分镜'] form";
  await fill(form, "标题", "M1 scene");
  await fill(form, "地点", "Studio");
  await fill(form, "时间", "Night");
  await fill(form, "光线", "Soft blue light");
  await fill(form, "风格", "Minimal cinematic");
  await click(form, "添加场景到画布");
  await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
    .some((node) => node.textContent.includes("M1 scene")), "scene card");
  assert.equal(await visibleCard("M1 scene"), true);

  await fill(form, "类型", "CHARACTER");
  await fill(form, "标题", "M1 hero");
  await fill(form, "描述", "The protagonist");
  await fill(form, "外观", "Blue coat");
  await click(form, "添加角色到画布");
  await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
    .some((node) => node.textContent.includes("M1 hero")), "character card");
  assert.equal(await visibleCard("M1 hero"), true);

  await fill(form, "类型", "SHOT");
  await page((rootSelector) => {
    const label = [...document.querySelector(rootSelector).querySelectorAll("label")]
      .find((candidate) => candidate.textContent.includes("M1 hero"));
    const checkbox = label?.querySelector("input[type='checkbox']");
    if (!checkbox) throw new Error("Character version checkbox is missing");
    checkbox.click();
  }, form);
  for (let order = 1; order <= 3; order++) {
    await fill(form, "标题", `M1 shot ${order}`);
    await fill(form, "描述", `Shot ${order} description`);
    await fill(form, "顺序", order);
    await fill(form, "时长", 3000);
    await fill(form, "镜头语言", "Medium shot");
    await fill(form, "动作", `Action ${order}`);
    await page((rootSelector) => {
      const select = [...document.querySelector(rootSelector).querySelectorAll("select")]
        .find((candidate) => candidate.closest("label")?.textContent.includes("场景精确版本"));
      const option = [...select.options].find((candidate) => candidate.textContent.includes("M1 scene"));
      select.value = option.value;
      select.dispatchEvent(new Event("change", { bubbles: true }));
    }, form);
    await click(form, "添加镜头到画布");
    await waitFor((title) => [...document.querySelectorAll(".react-flow__node")]
      .some((node) => node.textContent.includes(title)), `shot ${order} card`, 15000,
    `M1 shot ${order}`);
    assert.equal(await visibleCard(`M1 shot ${order}`), true);
  }
  const beforeEdit = await snapshot();
  const sceneBeforeEdit = beforeEdit.canvas.items.find((item) => item.artifact?.title === "M1 scene");
  const hero = beforeEdit.canvas.items.find((item) => item.artifact?.title === "M1 hero");
  const shotsBeforeEdit = beforeEdit.canvas.items
    .filter((item) => item.artifact?.kind === "SHOT")
    .sort((left, right) => left.artifact.currentVersion.content.order
      - right.artifact.currentVersion.content.order);
  assert.deepEqual(shotsBeforeEdit.map((item) => item.artifact.currentVersion.content.order),
    [1, 2, 3]);
  await waitFor(() => document.querySelectorAll(".react-flow__edge").length === 6,
    "six exact-version relationship edges");
  assert.ok(sceneBeforeEdit?.artifact?.currentVersionId);
  assert.ok(hero?.artifact?.currentVersionId);
  for (const shot of shotsBeforeEdit) {
    assert.equal(shot.artifact.currentVersion.content.sceneVersionId,
      sceneBeforeEdit.artifact.currentVersionId);
    assert.deepEqual(shot.artifact.currentVersion.content.characterVersionIds,
      [hero.artifact.currentVersionId]);
  }

  await fill(form, "类型", "CHARACTER");
  await fill(form, "标题", "M1 ally");
  await fill(form, "描述", "Supporting character");
  await fill(form, "外观", "Red coat");
  await click(form, "添加角色到画布");
  await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
    .some((node) => node.querySelector("h3")?.textContent === "M1 ally"), "ally card");
  await fill("main", "名称", "M1 creator");
  await fill("main", "指令", "Use only explicit inputs");
  await click("main", "添加 Agent 到画布");
  await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
    .some((node) => node.querySelector("h3")?.textContent === "M1 creator"), "Agent card");
  const beforeConnection = await snapshot();
  const ally = beforeConnection.canvas.items.find((item) => item.artifact?.title === "M1 ally");
  const agentBefore = beforeConnection.canvas.items.find((item) => item.agent?.name === "M1 creator");
  assert.ok(ally?.artifact?.currentVersionId);
  assert.deepEqual(agentBefore.agent.bindings, []);
  await connect("M1 ally", "artifact-output", "M1 shot 1", "artifact-input");
  await waitFor(async (id, versionId) => {
    const projectId = location.pathname.split("/").at(-1);
    const projection = await (await fetch(`/api/v1/projects/${projectId}/snapshot`)).json();
    const shot = projection.canvas.items.find((item) => item.artifact?.id === id);
    return shot?.artifact?.currentVersion.content.characterVersionIds.includes(versionId);
  }, "semantic connection revision", 15000, shotsBeforeEdit[0].artifact.id,
  ally.artifact.currentVersionId);
  await waitFor(() => document.querySelectorAll(".react-flow__edge").length === 7,
    "new semantic edge");
  await connect("M1 hero", "artifact-output", "M1 creator", "agent-input");
  await waitFor(async (id, artifactId, versionId) => {
    const projectId = location.pathname.split("/").at(-1);
    const projection = await (await fetch(`/api/v1/projects/${projectId}/snapshot`)).json();
    const agent = projection.canvas.items.find((item) => item.agent?.id === id)?.agent;
    return agent?.bindings.some((binding) => binding.artifactId === artifactId
      && binding.selectedVersionId === versionId);
  }, "Agent input connection", 15000, agentBefore.agent.id, hero.artifact.id,
  hero.artifact.currentVersionId);
  await waitFor(() => document.querySelectorAll(".react-flow__edge").length === 8,
    "new Agent input edge");

  const dragPoint = await page(() => {
    const node = [...document.querySelectorAll(".react-flow__node")]
      .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 scene");
    const rect = node.querySelector("h3").getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
  });
  await cdp("Input.dispatchMouseEvent", { type: "mousePressed", ...dragPoint,
    button: "left", clickCount: 1 });
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved",
    x: dragPoint.x + 8, y: dragPoint.y + 8, button: "left" });
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved",
    x: dragPoint.x + 96, y: dragPoint.y + 48, button: "left" });
  await cdp("Input.dispatchMouseEvent", { type: "mouseReleased",
    x: dragPoint.x + 96, y: dragPoint.y + 48, button: "left", clickCount: 1 });
  await waitFor(async (id, oldX, oldY) => {
    const projectId = location.pathname.split("/").at(-1);
    const projection = await (await fetch(`/api/v1/projects/${projectId}/snapshot`)).json();
    const item = projection.canvas.items.find((candidate) => candidate.id === id);
    return item && (item.x !== oldX || item.y !== oldY);
  }, "persisted drag layout", 15000, sceneBeforeEdit.id, sceneBeforeEdit.x,
  sceneBeforeEdit.y);
  const draggedScene = (await snapshot()).canvas.items.find(
    (item) => item.id === sceneBeforeEdit.id);

  await page(() => {
    const node = [...document.querySelectorAll(".react-flow__node")]
      .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 scene");
    const summary = [...node.querySelectorAll("summary")]
      .find((candidate) => candidate.textContent === "修改场景说明");
    summary.click();
    const field = [...node.querySelectorAll("label")]
      .find((candidate) => candidate.textContent.trim().startsWith("地点"))
      ?.querySelector("input");
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")
      .set.call(field, "Revised studio");
    field.dispatchEvent(new Event("input", { bubbles: true }));
    [...node.querySelectorAll("button")]
      .find((candidate) => candidate.textContent.trim() === "保存新版本").click();
  });
  await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
    .some((node) => node.querySelector("h3")?.textContent === "M1 scene"
      && node.textContent.includes("Revised studio")
      && node.textContent.includes("v2")), "scene revision");

  await cdp("Page.reload", { ignoreCache: true });
  await waitFor(() => [1, 2, 3].every((order) => [...document.querySelectorAll(".react-flow__node")]
    .some((node) => node.textContent.includes(`M1 shot ${order}`))),
  "all three cards after reload");
  for (const title of ["M1 scene", "M1 hero", "M1 shot 1", "M1 shot 2", "M1 shot 3"]) {
    assert.equal(await visibleCard(title), true, `${title} is visible after reload`);
  }
  await waitFor(() => document.querySelectorAll(".react-flow__edge").length === 5,
    "only current-version relationships after scene revision");
  assert.equal(await page(() => [...document.querySelectorAll(".react-flow__node")]
    .some((node) => node.querySelector("h3")?.textContent === "M1 scene"
      && node.textContent.includes("Revised studio")
      && node.textContent.includes("v2"))), true, "scene revision persists after reload");
  const afterReload = await snapshot();
  const sceneAfterReload = afterReload.canvas.items.find((item) => item.artifact?.title === "M1 scene");
  assert.equal(sceneAfterReload.x, draggedScene.x);
  assert.equal(sceneAfterReload.y, draggedScene.y);
  assert.notEqual(sceneAfterReload.artifact.currentVersionId,
    sceneBeforeEdit.artifact.currentVersionId);
  for (const shot of afterReload.canvas.items.filter((item) => item.artifact?.kind === "SHOT")) {
    assert.equal(shot.artifact.currentVersion.content.sceneVersionId,
      sceneBeforeEdit.artifact.currentVersionId,
      "editing a shared scene must not change a shot's pinned input");
  }
  const shotOneAfterReload = afterReload.canvas.items.find(
    (item) => item.artifact?.title === "M1 shot 1");
  assert.deepEqual(shotOneAfterReload.artifact.currentVersion.content.characterVersionIds,
    [hero.artifact.currentVersionId, ally.artifact.currentVersionId]);
  const agentAfterReload = afterReload.canvas.items.find(
    (item) => item.agent?.name === "M1 creator");
  assert.equal(agentAfterReload.agent.bindings.some((binding) =>
    binding.artifactId === hero.artifact.id
      && binding.selectedVersionId === hero.artifact.currentVersionId), true);
  assert.equal(afterReload.activeRun, null);
  assert.deepEqual(afterReload.activeTasks, []);
  console.log("M1 browser smoke passed: three shots, pointer connections, drag, revision and reload");

  if (process.env.AGENVAS_E2E_API_PERF === "1") {
    const samples = await page(async (projectId, agentId, sceneId) => {
      function stats(values) {
        const ordered = [...values].sort((left, right) => left - right);
        return { count: ordered.length,
          p50Ms: ordered[Math.ceil(ordered.length * 0.50) - 1],
          p95Ms: ordered[Math.ceil(ordered.length * 0.95) - 1],
          maxMs: ordered.at(-1) };
      }
      async function read(path, count) {
        const values = [];
        for (let index = 0; index < count + 10; index++) {
          const started = performance.now();
          const response = await fetch(path, { cache: "no-store" });
          if (!response.ok) throw new Error(`${path} returned ${response.status}`);
          await response.json();
          if (index >= 10) values.push(performance.now() - started);
        }
        return stats(values);
      }
      const projects = await read("/api/v1/projects", 100);
      const snapshot = await read(`/api/v1/projects/${projectId}/snapshot`, 100);
      const csrf = await (await fetch("/api/v1/auth/csrf")).json();
      const preflight = await (await fetch(
        `/api/v1/projects/${projectId}/runs/preflight?agentId=${agentId}`)).json();
      if (preflight.agentId !== agentId || !preflight.policySnapshot.systemPromptVersion) {
        throw new Error("Run preflight lacks a pinned prompt version");
      }
      const runTimes = [];
      for (let index = 0; index < 30; index++) {
        const started = performance.now();
        const accepted = await fetch(`/api/v1/projects/${projectId}/runs`, {
          method: "POST", credentials: "same-origin",
          headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token,
            "Idempotency-Key": crypto.randomUUID() },
          body: JSON.stringify({ agentId, instruction: `API performance run ${index}`,
            expectedAgentVersion: preflight.agentVersion,
            expectedModelConfigSource: preflight.policySnapshot.modelConfigSource,
            expectedModelConfigVersion: preflight.policySnapshot.modelConfigVersion,
            expectedSystemPromptVersion: preflight.policySnapshot.systemPromptVersion }),
        });
        if (accepted.status !== 202) {
          throw new Error(`Run acceptance returned ${accepted.status}: ${await accepted.text()}`);
        }
        const run = await accepted.json();
        runTimes.push(performance.now() - started);
        const canceled = await fetch(`/api/v1/projects/${projectId}/runs/${run.id}/cancel`, {
          method: "POST", credentials: "same-origin", headers: { [csrf.headerName]: csrf.token },
        });
        if (!canceled.ok) throw new Error(`Run cancellation returned ${canceled.status}`);
        await canceled.json();
      }
      const eventTimes = [];
      for (let index = 0; index < 30; index++) {
        const current = await (await fetch(
          `/api/v1/projects/${projectId}/artifacts/${sceneId}`)).json();
        const marker = `API performance scene ${index}`;
        const started = performance.now();
        const revised = await fetch(
          `/api/v1/projects/${projectId}/artifacts/${sceneId}/revisions`, {
            method: "POST", credentials: "same-origin",
            headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token },
            body: JSON.stringify({ expectedVersion: current.version,
              content: { ...current.currentVersion.content, location: marker } }),
          });
        if (revised.status !== 201) {
          throw new Error(`Scene revision returned ${revised.status}: ${await revised.text()}`);
        }
        await revised.json();
        await new Promise((resolve, reject) => {
          const deadline = performance.now() + 5000;
          function poll() {
            const card = [...document.querySelectorAll(".react-flow__node")]
              .find((node) => node.querySelector("h3")?.textContent === "M1 scene");
            if (card?.textContent.includes(marker)) resolve();
            else if (performance.now() >= deadline) reject(new Error("SSE visibility timeout"));
            else requestAnimationFrame(poll);
          }
          requestAnimationFrame(poll);
        });
        eventTimes.push(performance.now() - started);
      }
      return { projects, snapshot, runAcceptance: stats(runTimes),
        actionToVisible: stats(eventTimes) };
    }, afterReload.project.id, agentAfterReload.agent.id, sceneAfterReload.artifact.id);
    console.log(`T28 API performance: ${JSON.stringify(samples)}`);
    assert.ok(samples.projects.p95Ms <= 300, "Project list p95 exceeds the read target");
    assert.ok(samples.snapshot.p95Ms <= 300, "Project snapshot p95 exceeds the read target");
    assert.ok(samples.runAcceptance.p95Ms <= 500, "Run acceptance p95 exceeds the target");
    assert.ok(samples.actionToVisible.p95Ms <= 1000,
      "Action-to-visible p95 exceeds the event target");
  }

  if (process.env.AGENVAS_E2E_THREE_SHOT_COUNT === "1") {
    await page(() => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 creator");
      const instruction = [...node.querySelectorAll("textarea")]
        .find((candidate) => candidate.placeholder.includes("规划三个镜头"));
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")
        .set.call(instruction, "Create a three-shot silent storyboard with approved media");
      instruction.dispatchEvent(new Event("input", { bubbles: true }));
      const start = [...node.querySelectorAll("button")]
        .find((candidate) => candidate.textContent.trim() === "检查运行范围");
      if (!start || start.disabled) throw new Error("Full-project preflight is unavailable");
      start.click();
    });
    await waitFor(() => document.body.textContent.includes("运行前确认")
      && [...document.querySelectorAll(".react-flow__node button")]
        .some((button) => button.textContent.trim() === "确认开始规划" && !button.disabled),
    "full-project preflight");
    await page(() => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 creator");
      node.querySelectorAll("button").forEach((button) => {
        if (button.textContent.trim() === "确认开始规划" && !button.disabled) button.click();
      });
    });
    await waitFor(() => document.querySelector("section[aria-label='待审批执行计划']")
      ?.textContent.includes("关键帧图片计划"), "three-shot image approval", 30000);
    const runId = (await snapshot()).activeRun?.id;
    assert.ok(runId, "Three-shot Run must be durable before image approval");
    const imageCounts = await displayedApprovalCounts();
    assert.deepEqual(imageCounts, { image: 3, video: 0, steps: 3 });
    const beforeMedia = await page(async (id) => {
      const projectId = location.pathname.split("/").at(-1);
      return (await (await fetch(`/api/v1/projects/${projectId}/runs/${id}/tasks`)).json())
        .filter((task) => task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION");
    }, runId);
    assert.deepEqual(beforeMedia, [], "Image tasks must not be created before approval");
    await click("section[aria-label='待审批执行计划']", "确认执行此计划");
    await waitFor(() => document.querySelectorAll(
      "section[aria-label='选择镜头关键帧'] li").length === 3,
    "three completed image choices", 90000);
    for (let selected = 1; selected <= 3; selected++) {
      await waitFor(() => [...document.querySelectorAll(
        "section[aria-label='选择镜头关键帧'] li button")]
        .some((button) => button.textContent.trim() === "选为此镜头关键帧" && !button.disabled),
      `keyframe choice ${selected}`, 15000);
      await page(() => {
        const button = [...document.querySelectorAll(
          "section[aria-label='选择镜头关键帧'] li button")]
          .find((candidate) => candidate.textContent.trim() === "选为此镜头关键帧"
            && !candidate.disabled);
        button.click();
      });
      await waitFor((expected) => [...document.querySelectorAll(
        "section[aria-label='选择镜头关键帧'] li button")]
        .filter((button) => button.textContent.trim() === "已选为关键帧").length === expected,
      `persisted keyframe selection ${selected}`, 15000, selected);
    }
    await waitFor(() => document.querySelector("section[aria-label='待审批执行计划']")
      ?.textContent.includes("视频计划"), "three-shot video approval", 30000);
    const videoCounts = await displayedApprovalCounts();
    assert.deepEqual(videoCounts, { image: 0, video: 3, steps: 3 });
    await click("section[aria-label='待审批执行计划']", "确认执行此计划");
    await waitFor(async () => {
      const projectId = location.pathname.split("/").at(-1);
      return (await (await fetch(`/api/v1/projects/${projectId}/snapshot`)).json()).activeRun === null;
    }, "three-shot Run completion", 120000);
    const allTasks = await page(async (id) => {
      const projectId = location.pathname.split("/").at(-1);
      const response = await fetch(`/api/v1/projects/${projectId}/runs/${id}/tasks`);
      if (!response.ok) throw new Error(`Three-shot tasks returned HTTP ${response.status}`);
      return response.json();
    }, runId);
    const imageTasks = allTasks.filter((task) => task.kind === "IMAGE_GENERATION");
    const videoTasks = allTasks.filter((task) => task.kind === "VIDEO_GENERATION");
    assert.equal(imageTasks.length, imageCounts.image);
    assert.equal(videoTasks.length, videoCounts.video);
    assert.ok([...imageTasks, ...videoTasks].every((task) => task.status === "SUCCEEDED"));
    assert.equal(new Set(imageTasks.map((task) => task.input.shotArtifactId)).size, 3);
    assert.deepEqual(new Set(videoTasks.map((task) => task.input.shotArtifactId)),
      new Set(imageTasks.map((task) => task.input.shotArtifactId)));
    console.log("T27 browser smoke passed: three-shot approval shows 3+3 tasks and persists exactly six media Tasks");
  }

  if (process.env.AGENVAS_E2E_CANVAS_CAPACITY === "1") {
    const projectId = afterReload.project.id;
    await cdp("Page.navigate", { url: new URL("/projects", baseUrl).href });
    await waitFor(() => location.pathname === "/projects", "project list before capacity seed");
    const seeded = await page(async (id) => {
      const csrf = await (await fetch("/api/v1/auth/csrf")).json();
      async function post(path, body, headers = {}) {
        const response = await fetch(path, { method: "POST", credentials: "same-origin",
          headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token, ...headers },
          body: JSON.stringify(body) });
        if (!response.ok) throw new Error(`${path} returned ${response.status}: ${await response.text()}`);
        return response.json();
      }
      async function artifact(kind, title, content) {
        return post(`/api/v1/projects/${id}/artifacts`, { kind, title, content },
          { "Idempotency-Key": crypto.randomUUID() });
      }
      const scene = await artifact("SCENE", "Perf scene", { name: "Perf scene",
        location: "Studio", timeOfDay: "Day", lighting: "Soft light", style: "Cinematic",
        referenceVersionIds: [] });
      const lead = await artifact("CHARACTER", "Perf lead", { name: "Perf lead",
        description: "Lead actor", appearance: "Blue jacket", referenceVersionIds: [] });
      const extra = await artifact("CHARACTER", "Perf extra", { name: "Perf extra",
        description: "Supporting actor", appearance: "Red jacket", referenceVersionIds: [] });
      const created = [scene, lead, extra];
      for (let index = 0; index < 40; index++) {
        const image = document.createElement("canvas");
        image.width = 480;
        image.height = 270;
        const context = image.getContext("2d");
        const gradient = context.createLinearGradient(0, 0, 480, 270);
        gradient.addColorStop(0, `hsl(${index * 9} 70% 50%)`);
        gradient.addColorStop(1, `hsl(${index * 9 + 90} 70% 35%)`);
        context.fillStyle = gradient;
        context.fillRect(0, 0, 480, 270);
        for (let mark = 0; mark < 300; mark++) {
          context.fillStyle = `hsla(${(index * 17 + mark * 31) % 360} 80% 80% / 0.5)`;
          context.fillRect((mark * 73) % 480, (mark * 47) % 270, 8, 8);
        }
        const blob = await new Promise((resolve) => image.toBlob(resolve, "image/png"));
        if (!blob) throw new Error("Could not encode fixture PNG");
        const form = new FormData();
        form.append("file", blob, `perf-${index}.png`);
        const upload = await fetch(`/api/v1/projects/${id}/assets`, {
          method: "POST", credentials: "same-origin", headers: { [csrf.headerName]: csrf.token },
          body: form,
        });
        if (upload.status !== 201) throw new Error(`PNG upload returned ${upload.status}`);
        const asset = await upload.json();
        created.push(await artifact("IMAGE", `Perf image ${index}`, {
          sourceType: "UPLOAD", assetId: asset.id,
        }));
      }
      for (let index = 0; index < 250; index++) {
        created.push(await artifact("SHOT", `Perf shot ${index}`, {
          order: index % 6 + 1, durationMs: 3000,
          description: `Performance shot ${index}`, camera: "Medium shot",
          action: "Move through the scene",
          sceneVersionId: scene.currentVersionId,
          characterVersionIds: index < 95
            ? [lead.currentVersionId, extra.currentVersionId] : [lead.currentVersionId],
        }));
      }
      const commands = created.map((entry, index) => ({
        type: "PLACE_ARTIFACT", itemId: crypto.randomUUID(), artifactId: entry.id,
        x: 80 + index % 10 * 360, y: 900 + Math.floor(index / 10) * 300,
        width: entry.kind === "SHOT" ? 320 : 280,
        height: entry.kind === "SHOT" ? 260 : 220,
        zIndex: index + 7, locked: false,
      }));
      for (let index = 0; index < commands.length; index += 100) {
        await post(`/api/v1/projects/${id}/canvas/commands`, {
          commands: commands.slice(index, index + 100),
        });
      }
      return { addedCards: created.length, uploadedImages: 40 };
    }, projectId);
    assert.equal(seeded.addedCards, 293);
    const projection = await page(async (id) => {
      const start = performance.now();
      const response = await fetch(`/api/v1/projects/${id}/snapshot`);
      if (!response.ok) throw new Error(`Capacity snapshot returned ${response.status}`);
      const result = await response.json();
      return { durationMs: performance.now() - start, snapshot: result };
    }, projectId);
    assert.equal(projection.snapshot.canvas.items.length, 300);
    const relationCount = await page((items) => {
      const versionIds = new Set(items.filter((item) => item.artifact)
        .map((item) => item.artifact.currentVersionId));
      let count = 0;
      for (const item of items) {
        for (const reference of item.artifact?.currentVersion.inputReferences ?? []) {
          if (versionIds.has(reference.versionId)) count++;
        }
        if (item.agent) count += item.agent.bindings.length;
      }
      return count;
    }, projection.snapshot.canvas.items);
    assert.equal(relationCount, 600);
    const refreshMs = [];
    let refreshStart = Date.now();
    await cdp("Page.navigate", { url: new URL(`/projects/${projectId}`, baseUrl).href });
    await waitFor(() => document.body.textContent.includes("Perf shot 249"),
      "300-card workspace rendering", 60000);
    refreshMs.push(Date.now() - refreshStart);
    for (let attempt = 0; attempt < 5; attempt++) {
      refreshStart = Date.now();
      await cdp("Page.reload", { ignoreCache: true });
      await waitFor(() => document.body.textContent.includes("Perf shot 249"),
        "300-card workspace refresh", 60000);
      refreshMs.push(Date.now() - refreshStart);
    }
    const drag = await page(() => {
      const bounds = document.querySelector(".workspace-canvas").getBoundingClientRect();
      const node = [...document.querySelectorAll(".react-flow__node")].find((candidate) => {
        const rect = candidate.querySelector("h3")?.getBoundingClientRect();
        return rect && rect.width >= 20 && rect.left >= bounds.left
          && rect.right <= bounds.right && rect.top >= bounds.top
          && rect.bottom <= bounds.bottom;
      });
      if (!node) throw new Error("No card is visible for the 300-card drag measurement");
      const rect = node.querySelector("h3").getBoundingClientRect();
      window.__agenvasFrames = [];
      const started = performance.now();
      function recordFrame(timestamp) {
        window.__agenvasFrames.push(timestamp);
        if (timestamp - started < 3000) requestAnimationFrame(recordFrame);
      }
      requestAnimationFrame(recordFrame);
      return { id: node.getAttribute("data-id"),
        x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    });
    const beforeDrag = projection.snapshot.canvas.items.find((item) => item.id === drag.id);
    assert.ok(beforeDrag, "The measured drag must start on a persisted card");
    await cdp("Input.dispatchMouseEvent", { type: "mousePressed", x: drag.x, y: drag.y,
      button: "left", clickCount: 1 });
    for (let step = 1; step <= 90; step++) {
      await cdp("Input.dispatchMouseEvent", { type: "mouseMoved",
        x: drag.x + step * 1.2, y: drag.y + step * 0.4, button: "left", buttons: 1 });
      await new Promise((resolve) => setTimeout(resolve, 16));
    }
    await cdp("Input.dispatchMouseEvent", { type: "mouseReleased",
      x: drag.x + 108, y: drag.y + 36, button: "left", clickCount: 1 });
    const frameStats = await page(() => {
      const values = window.__agenvasFrames;
      const deltas = values.slice(1).map((value, index) => value - values[index]);
      const sorted = [...deltas].sort((left, right) => left - right);
      return { frames: values.length,
        durationMs: values.at(-1) - values[0],
        fps: deltas.length * 1000 / (values.at(-1) - values[0]),
        p95FrameMs: sorted[Math.ceil(sorted.length * 0.95) - 1],
        framesOver33Ms: deltas.filter((value) => value > 33.3).length,
        renderedNodes: document.querySelectorAll(".react-flow__node").length,
        renderedEdges: document.querySelectorAll(".react-flow__edge").length,
      };
    });
    assert.ok(frameStats.frames > 60, "A live browser frame sample is required");
    await waitFor(async (id, oldX, oldY) => {
      const projectId = location.pathname.split("/").at(-1);
      const response = await fetch(`/api/v1/projects/${projectId}/snapshot`);
      if (!response.ok) return false;
      const current = (await response.json()).canvas.items.find((item) => item.id === id);
      return current && (current.x !== oldX || current.y !== oldY);
    }, "300-card drag persisted", 15000, drag.id, beforeDrag.x, beforeDrag.y);
    const loadedThumbnails = await page(() => [...document.querySelectorAll(".react-flow__node img")]
      .filter((image) => image.complete && image.naturalWidth > 0).length);
    console.log(`T28 canvas capacity: 300 cards, 600 references, ${seeded.uploadedImages} real PNG thumbnails (${loadedThumbnails} loaded in viewport); snapshot ${projection.durationMs.toFixed(0)} ms; refresh ${refreshMs.join("/")} ms; drag ${frameStats.fps.toFixed(1)} FPS, p95 frame ${frameStats.p95FrameMs.toFixed(1)} ms, >33 ms ${frameStats.framesOver33Ms}/${frameStats.frames - 1}; DOM ${frameStats.renderedNodes} nodes/${frameStats.renderedEdges} edges`);
  }

  if (process.env.AGENVAS_E2E_SSE_EXPIRY === "1") {
    const composeProject = process.env.AGENVAS_E2E_COMPOSE_PROJECT;
    if (!/^agenvas-[a-z0-9-]+-e2e$/.test(composeProject ?? "")) {
      throw new Error("SSE expiry needs an explicitly named isolated *-e2e Compose project");
    }
    const projectId = afterReload.project.id;
    assert.match(projectId, /^[0-9a-f-]{36}$/);
    const firstSeq = afterReload.snapshotSeq;
    const sceneId = sceneAfterReload.artifact.id;
    const composeFile = fileURLToPath(new URL("../../deploy/compose.dev.yaml", import.meta.url));
    await cdp("Network.setBlockedURLs", { urls: [
      `*api/v1/projects/${projectId}/events*`,
      `*api/v1/projects/${projectId}/snapshot*`,
    ] });
    await cdp("Network.emulateNetworkConditions", { offline: true, latency: 0,
      downloadThroughput: 0, uploadThroughput: 0 });
    await cdp("Network.emulateNetworkConditions", { offline: false, latency: 0,
      downloadThroughput: -1, uploadThroughput: -1 });
    async function reviseFromOutsideReact(locationText) {
      return page(async (id, artifactId, nextLocation) => {
        const current = await (await fetch(`/api/v1/projects/${id}/artifacts/${artifactId}`)).json();
        const csrf = await (await fetch("/api/v1/auth/csrf")).json();
        const response = await fetch(`/api/v1/projects/${id}/artifacts/${artifactId}/revisions`, {
          method: "POST", credentials: "same-origin",
          headers: { "Content-Type": "application/json", [csrf.headerName]: csrf.token },
          body: JSON.stringify({ expectedVersion: current.version,
            content: { ...current.currentVersion.content, location: nextLocation } }),
        });
        if (response.status !== 201) throw new Error(`Revision returned ${response.status}`);
        return response.json();
      }, projectId, sceneId, locationText);
    }
    await reviseFromOutsideReact("Offline revision three");
    const prune = `delete from project_event where project_id = '${projectId}' and seq <= ${firstSeq + 1}`;
    const deleted = execFileSync("docker", ["compose", "-f", composeFile,
      "-p", composeProject, "exec", "-T", "postgres", "psql", "-U", "agenvas", "-d", "agenvas",
      "-v", "ON_ERROR_STOP=1", "-c", prune], { encoding: "utf8" });
    assert.match(deleted, /DELETE [1-9]\d*/);
    await reviseFromOutsideReact("Offline revision four");
    assert.equal(await page(() => [...document.querySelectorAll(".react-flow__node")]
      .some((node) => node.querySelector("h3")?.textContent === "M1 scene"
        && node.textContent.includes("Offline revision four"))), false,
    "The browser must not learn the offline revision before reconnecting");
    await cdp("Network.setBlockedURLs", { urls: [
      `*api/v1/projects/${projectId}/snapshot*`,
    ] });
    const staleResponse = await page(async (id, seq) => {
      const response = await fetch(`/api/v1/projects/${id}/events?after=${seq}`, {
        headers: { Accept: "text/event-stream" },
      });
      return { status: response.status, body: await response.json() };
    }, projectId, firstSeq);
    assert.equal(staleResponse.status, 409);
    assert.equal(staleResponse.body.code, "EVENT_CURSOR_EXPIRED");
    await cdp("Network.setBlockedURLs", { urls: [] });
    await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
      .some((node) => node.querySelector("h3")?.textContent === "M1 scene"
        && node.textContent.includes("Offline revision four")
        && node.textContent.includes("v4")), "fresh snapshot after expired cursor", 20000);
    await waitFor(() => document.querySelector(".workspace-header")?.dataset.syncState === "live",
      "live SSE after snapshot recovery", 20000);
    await reviseFromOutsideReact("Post-recovery revision five");
    await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
      .some((node) => node.querySelector("h3")?.textContent === "M1 scene"
        && node.textContent.includes("Post-recovery revision five")
        && node.textContent.includes("v5")), "new SSE event after recovery", 20000);
    console.log("T12 browser smoke passed: stale cursor 409, v4 snapshot and v5 live event");
  }

  if (process.env.AGENVAS_E2E_REDO === "1") {
    const beforeRedo = afterReload;
    const shotTwoBeforeRedo = beforeRedo.canvas.items.find(
      (item) => item.artifact?.title === "M1 shot 2");
    const siblingVersions = beforeRedo.canvas.items
      .filter((item) => ["M1 shot 1", "M1 shot 3"].includes(item.artifact?.title))
      .map((item) => [item.artifact.id, item.artifact.currentVersionId]);
    await page(() => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 shot 2");
      const summary = [...node.querySelectorAll("summary")]
        .find((candidate) => candidate.textContent === "修改此镜头");
      if (!summary) throw new Error("Missing shot redo editor");
      summary.click();
    });
    await page(() => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 shot 2");
      const description = [...node.querySelectorAll("label")]
        .find((candidate) => candidate.textContent.trim().startsWith("描述"))
        ?.querySelector("textarea");
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")
        .set.call(description, "Revised second-shot action");
      description.dispatchEvent(new Event("input", { bubbles: true }));
      [...node.querySelectorAll("button")]
        .find((candidate) => candidate.textContent.trim() === "保存局部修改").click();
    });
    try {
      await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
        .some((node) => node.querySelector("h3")?.textContent === "M1 shot 2"
          && node.textContent.includes("v2")
          && node.textContent.includes("Revised second-shot action")), "second-shot revision");
    } catch (error) {
      const diagnostic = await page(() => {
        const node = [...document.querySelectorAll(".react-flow__node")]
          .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 shot 2");
        return { text: node?.textContent, fields: [...(node?.querySelectorAll("form input,form textarea") ?? [])]
          .map((field) => ({ name: field.closest("label")?.textContent, value: field.value })) };
      });
      throw new Error(`${String(error)}: ${JSON.stringify(diagnostic)}`, { cause: error });
    }
    const afterRedo = await snapshot();
    const shotTwoAfterRedo = afterRedo.canvas.items.find(
      (item) => item.artifact?.id === shotTwoBeforeRedo.artifact.id);
    assert.notEqual(shotTwoAfterRedo.artifact.currentVersionId,
      shotTwoBeforeRedo.artifact.currentVersionId);
    assert.equal(shotTwoAfterRedo.artifact.currentVersion.content.description,
      "Revised second-shot action");
    assert.equal(shotTwoAfterRedo.artifact.currentVersion.content.sceneVersionId,
      shotTwoBeforeRedo.artifact.currentVersion.content.sceneVersionId);
    for (const [id, versionId] of siblingVersions) {
      assert.equal(afterRedo.canvas.items.find((item) => item.artifact?.id === id)
        .artifact.currentVersionId, versionId);
    }

    // Set up the exact new binding through the authenticated API; the browser path under
    // test starts at the Agent card's local Run controls, not React Flow's overlapping handles.
    await page(async (shotId, versionId) => {
      const projectId = location.pathname.split("/").at(-1);
      const projection = await (await fetch(`/api/v1/projects/${projectId}/snapshot`)).json();
      const agent = projection.canvas.items.find((item) => item.agent?.name === "M1 creator")?.agent;
      const token = (await (await fetch("/api/v1/auth/csrf")).json()).token;
      const response = await fetch(`/api/v1/projects/${projectId}/agents/${agent.id}`, {
        method: "PATCH", headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": token },
        body: JSON.stringify({ expectedVersion: agent.version, name: agent.name,
          instruction: agent.instruction,
          bindings: [...agent.bindings.map((binding) => ({ artifactId: binding.artifactId,
            selectedVersionId: binding.selectedVersionId })),
          { artifactId: shotId, selectedVersionId: versionId }] }),
      });
      if (!response.ok) throw new Error(`Agent binding returned HTTP ${response.status}`);
    }, shotTwoAfterRedo.artifact.id,
    shotTwoAfterRedo.artifact.currentVersionId);
    await cdp("Page.reload", { ignoreCache: true });
    await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
      .some((node) => node.querySelector("h3")?.textContent === "M1 creator"), "Agent after binding reload");

    await page(() => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 creator");
      const select = [...node.querySelectorAll("select")]
        .find((candidate) => candidate.closest("label")?.textContent.includes("运行范围"));
      const option = [...select.options].find((candidate) =>
        candidate.textContent.includes("仅重做「M1 shot 2」"));
      if (!option) throw new Error("Missing second-shot redo option");
      select.value = option.value;
      select.dispatchEvent(new Event("change", { bubbles: true }));
      const instruction = [...node.querySelectorAll("textarea")]
        .find((candidate) => candidate.placeholder.includes("规划三个镜头"));
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")
        .set.call(instruction, "Regenerate only the revised second shot");
      instruction.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await page(() => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 creator");
      const button = [...node.querySelectorAll("button")]
        .find((candidate) => candidate.textContent.trim() === "检查运行范围");
      if (!button || button.disabled) throw new Error("Redo preflight button is unavailable");
      button.click();
    });
    await waitFor(() => document.body.textContent.includes("局部重做目标：M1 shot 2"),
      "redo preflight");
    await page(() => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === "M1 creator");
      const button = [...node.querySelectorAll("button")]
        .find((candidate) => candidate.textContent.trim() === "确认开始规划");
      if (!button || button.disabled) throw new Error("Redo confirmation is unavailable");
      button.click();
    });
    await waitFor(() => document.querySelector("section[aria-label='待审批执行计划']")
      ?.textContent.includes("关键帧图片计划"), "redo image approval", 30000);
    const redoRunId = (await snapshot()).activeRun?.id;
    assert.ok(redoRunId, "Redo Run must be durable before image approval");
    const imageApprovalCounts = await displayedApprovalCounts();
    assert.deepEqual(imageApprovalCounts, { image: 1, video: 0, steps: 1 });
    await click("section[aria-label='待审批执行计划']", "确认执行此计划");
    await waitFor(() => Boolean(document.querySelector("section[aria-label='选择镜头关键帧']")
      ?.querySelector("button:not([disabled])")), "redo keyframe choice", 60000);
    await click("section[aria-label='选择镜头关键帧']", "选为此镜头关键帧");
    await waitFor(() => document.querySelector("section[aria-label='待审批执行计划']")
      ?.textContent.includes("视频计划"), "redo video approval", 30000);
    const videoApprovalCounts = await displayedApprovalCounts();
    assert.deepEqual(videoApprovalCounts, { image: 0, video: 1, steps: 1 });
    await click("section[aria-label='待审批执行计划']", "确认执行此计划");
    await waitFor(async () => {
      const projectId = location.pathname.split("/").at(-1);
      const projection = await (await fetch(`/api/v1/projects/${projectId}/snapshot`)).json();
      return projection.activeRun === null;
    }, "redo Run completion", 60000);
    const completed = await snapshot();
    const redoTasks = await page(async (runId) => {
      const projectId = location.pathname.split("/").at(-1);
      const response = await fetch(`/api/v1/projects/${projectId}/runs/${runId}/tasks`);
      if (!response.ok) throw new Error(`Redo tasks returned HTTP ${response.status}`);
      return response.json();
    }, redoRunId);
    const mediaTasks = redoTasks.filter((task) =>
      task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION");
    assert.equal(mediaTasks.filter((task) => task.kind === "IMAGE_GENERATION").length,
      imageApprovalCounts.image);
    assert.equal(mediaTasks.filter((task) => task.kind === "VIDEO_GENERATION").length,
      videoApprovalCounts.video);
    assert.deepEqual(mediaTasks.map((task) => task.kind).sort(),
      ["IMAGE_GENERATION", "VIDEO_GENERATION"]);
    assert.ok(mediaTasks.every((task) => task.status === "SUCCEEDED"));
    assert.ok(mediaTasks.every((task) => task.input.shotArtifactId === shotTwoAfterRedo.artifact.id));
    const completedShot = completed.canvas.items.find(
      (item) => item.artifact?.id === shotTwoAfterRedo.artifact.id)?.artifact;
    assert.equal(completedShot.currentVersion.content.description, "Revised second-shot action");
    const imageTask = mediaTasks.find((task) => task.kind === "IMAGE_GENERATION");
    const videoTask = mediaTasks.find((task) => task.kind === "VIDEO_GENERATION");
    assert.equal(completedShot.currentVersion.content.selectedImageVersionId,
      imageTask.output.artifactVersionId);
    assert.equal(completedShot.currentVersion.content.selectedVideoVersionId,
      videoTask.output.artifactVersionId);
    assert.equal(videoTask.output.selectedShotVersionId, completedShot.currentVersionId);
    for (const task of mediaTasks) {
      assert.equal(task.output.selected, true);
      assert.ok(completed.canvas.items.some((item) => item.artifact?.id === task.output.artifactId
        && item.artifact.currentVersionId === task.output.artifactVersionId),
      `${task.kind} output must be visible as its exact artifact version`);
    }
    const videoTitle = completed.canvas.items.find(
      (item) => item.artifact?.id === videoTask.output.artifactId)?.artifact?.title;
    assert.ok(videoTitle, "Generated video card must have a title");
    assert.equal(await page((title) => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === title);
      return Boolean(node) && !node.querySelector("video") && Boolean(node.querySelector("img"));
    }, videoTitle), true, "Video must use a poster before explicit playback");
    await page((title) => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === title);
      const button = [...node.querySelectorAll("button")]
        .find((candidate) => candidate.textContent.trim() === "播放视频");
      if (!button) throw new Error("Missing manual video playback button");
      button.click();
    }, videoTitle);
    await waitFor((title) => {
      const node = [...document.querySelectorAll(".react-flow__node")]
        .find((candidate) => candidate.querySelector("h3")?.textContent === title);
      const video = node?.querySelector("video");
      return Boolean(video) && video.error === null && video.readyState >= 1
        && video.videoWidth > 0 && Number.isFinite(video.duration) && video.duration > 0;
    }, "manual generated-video metadata", 15000, videoTitle);
    for (const [id, versionId] of siblingVersions) {
      assert.equal(completed.canvas.items.find((item) => item.artifact?.id === id)
        .artifact.currentVersionId, versionId);
    }
    await cdp("Page.reload", { ignoreCache: true });
    await waitFor(() => [...document.querySelectorAll(".react-flow__node")]
      .some((node) => node.querySelector("h3")?.textContent === "M1 shot 2"),
    "completed shot after reload");
    const restored = await snapshot();
    const restoredShot = restored.canvas.items.find(
      (item) => item.artifact?.id === completedShot.id)?.artifact;
    assert.equal(restoredShot.currentVersionId, completedShot.currentVersionId);
    assert.equal(restoredShot.currentVersion.content.selectedVideoVersionId,
      videoTask.output.artifactVersionId);
    console.log("T24 browser smoke passed: second-shot redo, two approvals, selected media and sibling isolation");
    console.log("T27 browser smoke passed: displayed image/video counts and step counts match two persisted media Tasks");

    if (process.env.AGENVAS_E2E_EXPORT === "1") {
      const exportPanel = "section[aria-label='顺序导出']";
      await waitFor((id) => [...document.querySelectorAll(
        "section[aria-label='顺序导出'] select[aria-label='选择视频'] option")]
        .some((option) => option.value === id), "generated video export choice", 15000,
      videoTask.output.artifactId);
      await page((id) => {
        const select = document.querySelector(
          "section[aria-label='顺序导出'] select[aria-label='选择视频']");
        select.value = id;
        select.dispatchEvent(new Event("change", { bubbles: true }));
      }, videoTask.output.artifactId);
      await waitFor(() => {
        const section = document.querySelector("section[aria-label='顺序导出']");
        return Boolean(section?.querySelector("button:not([disabled])")
          && [...section.querySelectorAll("button")]
            .some((button) => button.textContent.trim() === "添加" && !button.disabled));
      }, "verified source-video duration", 15000);
      await click(exportPanel, "添加");
      await page(() => {
        const section = document.querySelector("section[aria-label='顺序导出']");
        const field = [...section.querySelectorAll("label")]
          .find((candidate) => candidate.textContent.trim().startsWith("终点（秒）"))
          ?.querySelector("input");
        if (!field) throw new Error("Missing export segment endpoint");
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")
          .set.call(field, "0.5");
        field.dispatchEvent(new Event("input", { bubbles: true }));
      });
      await click(exportPanel, "开始导出");
      await waitFor(async (versionId) => {
        const projectId = location.pathname.split("/").at(-1);
        const response = await fetch(`/api/v1/projects/${projectId}/exports`);
        if (!response.ok) throw new Error(`Export list returned HTTP ${response.status}`);
        const tasks = await response.json();
        const match = tasks.find((task) => task.kind === "MEDIA_EXPORT"
          && task.input?.segments?.[0]?.videoVersionId === versionId);
        return match?.status === "SUCCEEDED";
      }, "one-segment export completion", 60000, videoTask.output.artifactVersionId);
      const finishedExport = await page(async (versionId) => {
        const projectId = location.pathname.split("/").at(-1);
        const tasks = await (await fetch(`/api/v1/projects/${projectId}/exports`)).json();
        return tasks.find((task) => task.kind === "MEDIA_EXPORT"
          && task.input?.segments?.[0]?.videoVersionId === versionId);
      }, videoTask.output.artifactVersionId);
      assert.equal(finishedExport.input.segments.length, 1);
      assert.equal(finishedExport.input.segments[0].endMs, 500);
      assert.ok(finishedExport.output.assetId);
      await waitFor(() => Boolean(document.querySelector(
        "section[aria-label='顺序导出'] a[download='agenvas-export.mp4']")),
      "private MP4 download link", 15000);
      assert.equal(await page(() => Boolean(document.querySelector(
        "section[aria-label='顺序导出'] video"))), false,
      "Export media must not load before user playback");
      await pointerClick(exportPanel, "播放导出");
      await waitFor(() => {
        const video = document.querySelector("section[aria-label='顺序导出'] video");
        return Boolean(video) && video.error === null && video.readyState >= 1
          && video.videoWidth === 1280 && video.videoHeight === 720
          && Number.isFinite(video.duration) && video.duration > 0;
      }, "export MP4 browser metadata", 15000);
      await page(async () => {
        const video = document.querySelector("section[aria-label='顺序导出'] video");
        await video.play();
      });
      await waitFor(() => {
        const video = document.querySelector("section[aria-label='顺序导出'] video");
        return Boolean(video) && video.error === null && video.readyState >= 2
          && video.currentTime > 0.05;
      }, "export MP4 playback time progression", 15000);
      const headers = await page(async (assetId) => {
        const projectId = location.pathname.split("/").at(-1);
        const response = await fetch(`/api/v1/projects/${projectId}/assets/${assetId}/content`,
          { method: "HEAD" });
        return { status: response.status, type: response.headers.get("content-type"),
          length: Number(response.headers.get("content-length")) };
      }, finishedExport.output.assetId);
      assert.equal(headers.status, 200);
      assert.equal(headers.type, "video/mp4");
      assert.ok(headers.length > 1000);
      console.log("T25 browser smoke passed: manual export, private download and MP4 playback");
    }
  }
} finally {
  socket?.close();
  chrome.kill();
  await rm(profile, { recursive: true, force: true });
}
