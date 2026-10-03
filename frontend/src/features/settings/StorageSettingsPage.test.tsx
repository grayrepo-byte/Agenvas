import { QueryClientProvider } from "@tanstack/react-query";
import { render,screen,waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { MemoryRouter,Route,Routes } from "react-router";
import { beforeEach,describe,expect,it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { StorageSettings } from "../../shared/api/client";
import { changeControl } from "../../test/controls";
import { server } from "../../test/server";
import { StorageSettingsPage } from "./StorageSettingsPage";

const profile = { id: "cloud-one", name: "云端一", provider: "S3" as const, endpoint: "https://s3.example.com",
  region: "us-east-1", bucket: "test-bucket", keyPrefix: "agenvas", pathStyle: true, accessKeyMask: "••••1234", createdAt: "2026-10-01T00:00:00Z" };
function setup(value: StorageSettings = { version: 0, relayProfileId: null, activeProfileId: null, profiles: [] }) {
  const client = createQueryClient();
  server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json(value)));
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/settings/storage"]}><Routes>
    <Route path="/settings/storage" element={<StorageSettingsPage />} /><Route path="/login" element={<h1>登录页</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}
async function fill() {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("连接名称"), "新连接");
  await changeControl(screen.getByLabelText("存储类型"), { target: { value: "S3" } });
  await user.type(screen.getByLabelText("Endpoint"), "https://s3.example.com");
  await user.type(screen.getByLabelText("Region"), "us-east-1");
  await user.type(screen.getByLabelText("Bucket"), "test-bucket");
  await user.type(screen.getByLabelText("AccessKey ID"), "my-access-id");
  await user.type(screen.getByLabelText("AccessKey Secret"), "my-secret-value");
  return user;
}
describe("StorageSettingsPage", () => {
  beforeEach(() => {
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })));
  });
  it("defaults to local and saves cloud settings without switching or retaining credentials", async () => {
    const writes: unknown[] = [];
    server.use(http.post("/api/v1/settings/storage/profiles", async ({ request }) => {
      writes.push(await request.json()); return HttpResponse.json({ version: 1, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    }));
    const client = setup();
    const user = await fill();
    await user.click(screen.getByRole("checkbox", { name: /使用 Path Style/ }));
    await user.click(screen.getByRole("button", { name: "保存连接" }));
    expect(await screen.findByRole("status")).toHaveTextContent("默认存储未切换");
    expect(screen.getByLabelText("AccessKey ID")).toHaveValue("");
    expect(screen.getByLabelText("AccessKey Secret")).toHaveValue("");
    expect(writes).toEqual([{ expectedVersion: 0, name: "新连接", provider: "S3", endpoint: "https://s3.example.com", region: "us-east-1",
      bucket: "test-bucket", keyPrefix: "agenvas", pathStyle: true, accessKeyId: "my-access-id", secretAccessKey: "my-secret-value" }]);
    expect(JSON.stringify(client.getQueryData(["settings", "storage"]))).not.toContain("my-secret-value");
    expect(screen.getByText("当前默认").closest(".storage-destination")).toHaveTextContent("本地存储");
  });
  it("explicitly switches to cloud and back to local using the current CAS version", async () => {
    const writes: unknown[] = [];
    server.use(http.put("/api/v1/settings/storage/active", async ({ request }) => {
      const input = await request.json() as { expectedVersion: number; profileId: string | null }; writes.push(input);
      return HttpResponse.json({ version: input.expectedVersion + 1, relayProfileId: null, activeProfileId: input.profileId, profiles: [profile] });
    }));
    setup({ version: 3, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "设为默认：云端一" }));
    await user.click(await screen.findByRole("button", { name: "切换到本地" }));
    await screen.findByText("后续资源使用本地存储。历史文件位置保持不变。");
    expect(writes).toEqual([{ expectedVersion: 3, profileId: "cloud-one" }, { expectedVersion: 4, profileId: null }]);
  });
  it("preserves non-secret draft and edit version after a conflict, then explicitly reloads", async () => {
    const writes: unknown[] = [];
    let version = 3;
    server.use(http.post("/api/v1/settings/storage/profiles", async ({ request }) => {
      writes.push(await request.json()); version = 4;
      return HttpResponse.json({ status: 409, detail: "配置已变化", code: "STORAGE_VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
    }));
    setup({ version: 3, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json({ version, relayProfileId: null, activeProfileId: null, profiles: [profile] })));
    const user = await fill(); await user.click(screen.getByRole("button", { name: "保存连接" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("配置已变化");
    expect(screen.getByLabelText("连接名称")).toHaveValue("新连接");
    expect(screen.getByLabelText("AccessKey Secret")).toHaveValue("");
    expect(writes).toHaveLength(1);
    expect(await screen.findByRole("button", { name: "载入最新配置" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "载入最新配置" }));
    expect(screen.getByLabelText("连接名称")).toHaveValue("");
  });
  it("rotates credentials without exposing them in cache or changing the destination", async () => {
    const writes: unknown[] = [];
    server.use(http.put("/api/v1/settings/storage/profiles/cloud-one/credentials", async ({ request }) => {
      writes.push(await request.json()); return HttpResponse.json({ version: 2, relayProfileId: null, activeProfileId: "cloud-one", profiles: [{ ...profile, accessKeyMask: "••••5678" }] });
    }));
    const client = setup({ version: 1, relayProfileId: null, activeProfileId: "cloud-one", profiles: [profile] });
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "更新凭证：云端一" }));
    await user.type(screen.getByLabelText("新的 AccessKey ID"), "new-id-5678");
    await user.type(screen.getByLabelText("新的 AccessKey Secret"), "new-secret-5678");
    await user.click(screen.getByRole("button", { name: "保存新凭证" }));
    expect(await screen.findByRole("status")).toHaveTextContent("凭证已更新");
    expect(writes).toEqual([{ expectedVersion: 1, accessKeyId: "new-id-5678", secretAccessKey: "new-secret-5678" }]);
    expect(JSON.stringify(client.getQueryData(["settings", "storage"]))).not.toContain("new-secret-5678");
    expect(screen.queryByLabelText("新的 AccessKey Secret")).not.toBeInTheDocument();
  });
  it("keeps reads recoverable and redirects expired sessions", async () => {
    setup();
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json({ status: 401, title: "登录过期" }, { status: 401 })));
    expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
  });
  it("selects a relay independently while leaving local archive storage active", async () => {
    const writes: unknown[] = [];
    server.use(http.put("/api/v1/settings/storage/relay", async ({ request }) => {
      const input = await request.json() as { expectedVersion: number; profileId: string | null };
      writes.push(input);
      return HttpResponse.json({ version: input.expectedVersion + 1, relayProfileId: input.profileId, activeProfileId: null, profiles: [profile] });
    }));
    setup({ version: 3, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    await changeControl(await screen.findByLabelText("中继连接"), { target: { value: profile.id } });
    expect(await screen.findByRole("status")).toHaveTextContent("中继配置已保存，默认存储未切换");
    expect(writes).toEqual([{ expectedVersion: 3, profileId: profile.id }]);
    expect(screen.getByText("当前默认").closest(".storage-destination")).toHaveTextContent("本地存储");
    await changeControl(screen.getByLabelText("中继连接"), { target: { value: "" } });
    await screen.findByRole("status");
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[1]).toEqual({ expectedVersion: 4, profileId: null });
  });

});
