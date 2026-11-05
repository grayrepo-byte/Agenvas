import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, expect, it, vi } from "vitest";
import * as api from "../shared/api/client";
import type { StorageSettings } from "../shared/api/client";
import { changeControl } from "../test/controls";
import { server } from "../test/server";
import { App } from "./App";

const ROUTE_TIMEOUT = { timeout: 5_000 };
const oldStorage: StorageSettings = { llmRelayEnabled: true, imageRelayEnabled: true, version: 0,
  activeProfileId: null, relayProfileId: null, profiles: [{ id: "cloud-one", name: "Old archive", provider: "S3",
    endpoint: "https://storage.example.invalid", region: "synthetic", bucket: "synthetic", keyPrefix: "synthetic",
    pathStyle: true, accessKeyMask: "configured", createdAt: "2026-11-05T00:00:00Z", inUse: false }] };
const acceptedStorage: StorageSettings = { ...oldStorage, version: 1, activeProfileId: "cloud-one" };
const currentStorage: StorageSettings = { ...acceptedStorage, version: 2,
  profiles: [{ ...oldStorage.profiles[0]!, name: "Current archive" }] };
const activate = api.activateStorageProfile;

afterEach(() => window.history.replaceState({}, "", "/"));

function stubSession({ initiallyLoggedIn = true, postLoginMeStatus = 200 } = {}) {
  let loggedIn = initiallyLoggedIn;
  let loggedInAgain = false;
  let sessionReadStatus = postLoginMeStatus;
  let loginWrites = 0;
  let storageReads = 0;
  let activationStarted = false;
  let latest = oldStorage;
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  let acknowledge!: () => void;
  const activationFinished = new Promise<void>((resolve) => { acknowledge = resolve; });
  // Observe the real HTTP adapter's settlement after its caller resumes. This only
  // synchronizes the delayed response; requests, routing and cache behavior stay real.
  vi.spyOn(api, "activateStorageProfile").mockImplementation((input) => {
    const response = activate(input);
    const settled = () => queueMicrotask(() => { setTimeout(acknowledge, 0); });
    void response.then(settled, settled);
    return response;
  });
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.get("/api/v1/auth/me", () => {
      const status = !loggedIn ? 401 : loggedInAgain ? sessionReadStatus : 200;
      return status === 200 ? HttpResponse.json({ id: "same-admin", loginName: "admin", role: "ADMIN" })
        : HttpResponse.json({ title: "Synthetic session read failure", status,
          code: status === 401 ? "UNAUTHENTICATED" : "SYNTHETIC_FAILURE" },
        { status, headers: { "Content-Type": "application/problem+json" } });
    }),
    http.get("/api/v1/projects", () => HttpResponse.json({ items: [], nextCursor: null })),
    http.get("/api/v1/settings/storage", () => { storageReads++; return HttpResponse.json(latest); }),
    http.put("/api/v1/settings/storage/active", async ({ request }) => {
      expect(await request.json()).toEqual({ expectedVersion: 0, profileId: "cloud-one" });
      activationStarted = true; await pending; return HttpResponse.json(acceptedStorage);
    }),
    http.post("/api/v1/auth/logout", () => {
      loggedIn = false;
      // A second authenticated browser changed the server after the accepted write.
      latest = currentStorage;
      return new HttpResponse(null, { status: 204 });
    }),
    http.post("/api/v1/auth/login", () => {
      loggedIn = true; loggedInAgain = true; loginWrites++;
      return HttpResponse.json({ id: "same-admin", loginName: "admin", role: "ADMIN" });
    }),
  );
  return { release, activationFinished, activationStarted: () => activationStarted, storageReads: () => storageReads,
    loginWrites: () => loginWrites, restoreSessionRead: () => { sessionReadStatus = 200; },
    expire: () => { loggedIn = false; latest = currentStorage; } };
}

async function signIn(user: ReturnType<typeof userEvent.setup>) {
  const password = await screen.findByLabelText("密码", {}, ROUTE_TIMEOUT);
  await user.type(password, "synthetic-session-password");
  await user.click(screen.getByRole("button", { name: "登录" }));
}
async function loginAgain(user: ReturnType<typeof userEvent.setup>) {
  await signIn(user);
  await screen.findByRole("heading", { name: "项目" }, ROUTE_TIMEOUT);
  await user.click(screen.getByRole("link", { name: "资源存储" }));
}

it.each(["before login", "after login"] as const)("isolates an old accepted save which returns %s", async (timing) => {
  const session = stubSession();
  window.history.replaceState({}, "", "/settings/storage");
  render(<App />);
  const user = userEvent.setup();
  await screen.findByRole("button", { name: "编辑：Old archive" }, ROUTE_TIMEOUT);
  await changeControl(screen.getByLabelText("归档目的地"), { target: { value: "cloud-one" } });
  await waitFor(() => expect(session.activationStarted()).toBe(true));
  await user.click(screen.getByRole("link", { name: "项目" }));
  await screen.findByRole("heading", { name: "项目" }, ROUTE_TIMEOUT);
  await user.click(screen.getByRole("button", { name: "退出登录" }));
  await screen.findByRole("button", { name: "登录" }, ROUTE_TIMEOUT);
  try {
    if (timing === "before login") {
      await act(async () => { session.release(); await session.activationFinished; });
    }
    await loginAgain(user);
    if (timing === "after login") {
      await screen.findByRole("button", { name: "编辑：Current archive" }, ROUTE_TIMEOUT);
      await act(async () => { session.release(); await session.activationFinished; });
    }
    expect(await screen.findByRole("button", { name: "编辑：Current archive" }, ROUTE_TIMEOUT)).toBeVisible();
    expect(screen.queryByRole("button", { name: "编辑：Old archive" })).not.toBeInTheDocument();
    expect(session.storageReads()).toBe(2);
  } finally { session.release(); }
});

it("keeps workspace caches across ordinary private navigation", async () => {
  const session = stubSession();
  window.history.replaceState({}, "", "/settings/storage");
  render(<App />);
  const user = userEvent.setup();
  await screen.findByRole("button", { name: "编辑：Old archive" }, ROUTE_TIMEOUT);
  await user.click(screen.getByRole("link", { name: "项目" }));
  await screen.findByRole("heading", { name: "项目" }, ROUTE_TIMEOUT);
  await user.click(screen.getByRole("link", { name: "资源存储" }));
  expect(await screen.findByRole("button", { name: "编辑：Old archive" }, ROUTE_TIMEOUT)).toBeVisible();
  expect(session.storageReads()).toBe(1);
});

it.each(["/LOGIN/", "/l%6Fgin/"])("uses the router's authentication match for %s", async (path) => {
  const session = stubSession();
  window.history.replaceState({}, "", "/settings/storage");
  render(<App />);
  const user = userEvent.setup();
  await screen.findByRole("button", { name: "编辑：Old archive" }, ROUTE_TIMEOUT);
  await changeControl(screen.getByLabelText("归档目的地"), { target: { value: "cloud-one" } });
  await waitFor(() => expect(session.activationStarted()).toBe(true));
  session.expire();
  await act(async () => {
    window.history.pushState({}, "", path);
    window.dispatchEvent(new PopStateEvent("popstate"));
  });
  await screen.findByRole("button", { name: "登录" }, ROUTE_TIMEOUT);
  try {
    await act(async () => { session.release(); await session.activationFinished; });
    await loginAgain(user);
    expect(await screen.findByRole("button", { name: "编辑：Current archive" }, ROUTE_TIMEOUT)).toBeVisible();
    expect(session.storageReads()).toBe(2);
  } finally { session.release(); }
});

it("recovers a failed new-session check without repeating the successful login", async () => {
  const session = stubSession({ initiallyLoggedIn: false, postLoginMeStatus: 503 });
  window.history.replaceState({}, "", "/login");
  render(<App />);
  const user = userEvent.setup();
  await signIn(user);
  expect(await screen.findByRole("alert", {}, ROUTE_TIMEOUT)).toHaveTextContent("暂时无法读取会话");
  expect(window.location.pathname).toBe("/projects");
  session.restoreSessionRead();
  await user.click(screen.getByRole("button", { name: "重试连接" }));
  expect(await screen.findByRole("heading", { name: "项目" }, ROUTE_TIMEOUT)).toBeVisible();
  expect(session.loginWrites()).toBe(1);
});

it("returns to login when the new workspace session is unauthorized", async () => {
  const session = stubSession({ initiallyLoggedIn: false, postLoginMeStatus: 401 });
  window.history.replaceState({}, "", "/login");
  render(<App />);
  const user = userEvent.setup();
  await signIn(user);
  await waitFor(() => expect(window.location.pathname).toBe("/login"));
  expect(await screen.findByRole("button", { name: "登录" }, ROUTE_TIMEOUT)).toBeVisible();
  await waitFor(() => expect(screen.getByLabelText("密码")).toHaveValue(""));
  expect(screen.queryByRole("heading", { name: "项目" })).not.toBeInTheDocument();
  expect(session.loginWrites()).toBe(1);
});
