import { randomBytes } from "node:crypto";
import { spawnSync } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import { readdir } from "node:fs/promises";

/** Runs the browser performance probe against an isolated, disposable Mock stack. */
const project = "agenvas-api-perf-e2e";
const root = fileURLToPath(new URL("../../", import.meta.url));
const composeFile = fileURLToPath(new URL("../../deploy/compose.dev.yaml", import.meta.url));
const browserScript = fileURLToPath(new URL("./manual-storyboard-browser.mjs", import.meta.url));
const serverRuntimeDockerfile = fileURLToPath(new URL("./server-runtime.Dockerfile", import.meta.url));
const webRuntimeDockerfile = fileURLToPath(new URL("./web-runtime.Dockerfile", import.meta.url));
const localArtifacts = process.argv.includes("--local-artifacts");

function command(binary, args, env, options = {}) {
  const result = spawnSync(binary, args, { cwd: root, env, encoding: "utf8",
    stdio: options.capture ? ["ignore", "pipe", "pipe"] : "inherit",
    timeout: options.timeout ?? 600_000 });
  if (result.error || result.status !== 0) {
    throw new Error(`${binary} ${args[0]} failed: ${result.error?.message ?? result.stderr ?? result.status}`
      + (result.stdout ? `\n${result.stdout.slice(-3000)}` : ""));
  }
  return result.stdout ?? "";
}

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  const port = address.port;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

const existingContainers = command("docker", ["ps", "-aq", "--filter",
  `label=com.docker.compose.project=${project}`], process.env, { capture: true }).trim();
const existingVolumes = command("docker", ["volume", "ls", "-q", "--filter",
  `label=com.docker.compose.project=${project}`], process.env, { capture: true }).trim();
if (existingContainers || existingVolumes) {
  throw new Error(`Refusing to reuse existing Compose project ${project}`);
}
const localImageTags = [`${project}-server:latest`, `${project}-web:latest`];
if (localArtifacts) {
  for (const tag of localImageTags) {
    const existing = spawnSync("docker", ["image", "inspect", tag], { stdio: "ignore" });
    if (existing.status === 0) throw new Error(`Refusing to replace existing image ${tag}`);
  }
  if (!(await readdir(fileURLToPath(new URL("../../backend/target/", import.meta.url))))
    .some((name) => /^agenvas-server-.*\.jar$/.test(name))) {
    throw new Error("Build the current backend jar before --local-artifacts");
  }
}

const apiPort = await freePort();
let webPort = await freePort();
while (webPort === apiPort) webPort = await freePort();
const bootstrapSecret = randomBytes(32).toString("hex");
const env = { ...process.env,
  AGENVAS_DB_PASSWORD: randomBytes(32).toString("hex"),
  AGENVAS_BOOTSTRAP_SECRET: bootstrapSecret,
  AGENVAS_API_PORT: String(apiPort), AGENVAS_WEB_PORT: String(webPort),
  AGENVAS_LLM_MODE: "mock", AGENVAS_PROVIDER_MODE: "mock" };
const compose = ["compose", "-p", project, "-f", composeFile];
console.log(`Starting isolated ${project} on loopback ports ${apiPort}/${webPort}`);
try {
  if (localArtifacts) {
    command("docker", ["build", "-t", localImageTags[0], "-f", serverRuntimeDockerfile,
      fileURLToPath(new URL("../../backend/target/", import.meta.url))], env, { capture: true });
    command("docker", ["build", "-t", localImageTags[1], "-f", webRuntimeDockerfile,
      fileURLToPath(new URL("../dist/", import.meta.url))], env, { capture: true });
    command("docker", [...compose, "up", "--no-build", "-d"], env, { capture: true });
  } else {
    command("docker", [...compose, "up", "--build", "-d"], env, { capture: true });
  }
  console.log("Isolated Mock stack is ready for browser measurement");
  const health = `http://127.0.0.1:${apiPort}/actuator/health/readiness`;
  let ready = false;
  for (let attempt = 0; attempt < 90; attempt++) {
    try {
      ready = (await fetch(health, { signal: AbortSignal.timeout(2000) })).ok;
    } catch {
      // A not-yet-ready isolated container is expected during startup.
    }
    if (ready) break;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  if (!ready) throw new Error("Isolated server did not become ready");
  const result = command("node", [browserScript], { ...env,
    AGENVAS_E2E_URL: `http://127.0.0.1:${webPort}`,
    AGENVAS_E2E_BOOTSTRAP_SECRET: bootstrapSecret,
    AGENVAS_E2E_API_PERF: "1" }, { timeout: 600_000, capture: true });
  console.log(result.trim());
} finally {
  try {
    command("docker", [...compose, "down", "-v"], env, { capture: true });
  } finally {
    if (localArtifacts) {
      for (const tag of localImageTags) {
        if (spawnSync("docker", ["image", "inspect", tag], { stdio: "ignore" }).status === 0) {
          command("docker", ["image", "rm", tag], env, { capture: true });
        }
      }
    }
  }
}
