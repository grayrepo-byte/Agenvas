import { QueryClientProvider } from "@tanstack/react-query";
import { render,screen,waitFor,within } from "@testing-library/react";
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
  region: "us-east-1", bucket: "test-bucket", keyPrefix: "agenvas", pathStyle: true, accessKeyMask: "••••1234", createdAt: "2026-10-01T00:00:00Z", inUse: false };
function setup(value: StorageSettings = { llmRelayEnabled: true, imageRelayEnabled: true, version: 0, relayProfileId: null, activeProfileId: null, profiles: [] }) {
  const client = createQueryClient();
  server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json(value)));
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/settings/storage"]}><Routes>
    <Route path="/settings/storage" element={<StorageSettingsPage />} /><Route path="/login" element={<h1>登录页</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}
async function fill() {
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "添加存储连接" }));
  await user.type(screen.getByLabelText("连接名称"), "新连接");
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
  it.each(["SMB", "WEBDAV"] as const)("saves %s with username/password and hides object fields", async (provider) => {
    let body: unknown;
    const remote = { ...profile, provider, name: "NAS", endpoint: provider === "SMB" ? "smb://nas.example.com/media" : "https://nas.example.com/dav/media", region: "", bucket: "", pathStyle: false };
    server.use(http.post("/api/v1/settings/storage/profiles", async ({ request }) => {
      body = await request.json(); return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 1, activeProfileId: null, relayProfileId: null, profiles: [remote] });
    }));
    const client = setup(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "添加存储连接" }));
    await user.type(screen.getByLabelText("连接名称"), "NAS");
    await changeControl(screen.getByLabelText("存储类型"), { target: { value: provider } });
    expect(screen.queryByLabelText("Region")).not.toBeInTheDocument(); expect(screen.queryByLabelText("Bucket")).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("Endpoint"), remote.endpoint);
    await user.type(screen.getByLabelText("用户名"), "synthetic-user"); await user.type(screen.getByLabelText("密码"), "synthetic-password");
    await user.click(screen.getByRole("button", { name: "保存连接" }));
    await screen.findByRole("button", { name: "编辑：NAS" });
    expect(body).toMatchObject({ provider, endpoint: remote.endpoint, region: "", bucket: "", pathStyle: false, accessKeyId: "synthetic-user", secretAccessKey: "synthetic-password" });
    expect(JSON.stringify(client.getQueryData(["settings", "storage"]))).not.toContain("synthetic-password");
    const relay = screen.getByLabelText("中继连接"); expect(within(relay).queryByRole("option", { name: "NAS" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "编辑：NAS" }));
    expect(screen.getByLabelText("用户名")).toHaveValue(""); expect(screen.getByLabelText("密码")).toHaveValue("");
  });
  it("defaults to local and saves cloud settings without switching or retaining credentials", async () => {
    const writes: unknown[] = [];
    server.use(http.post("/api/v1/settings/storage/profiles", async ({ request }) => {
      writes.push(await request.json()); return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 1, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    }));
    const client = setup();
    const user = await fill();
    await user.click(screen.getByRole("checkbox", { name: /使用 Path Style/ }));
    await user.click(screen.getByRole("button", { name: "保存连接" }));
    expect(await screen.findByRole("status")).toHaveTextContent("默认存储未切换");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("AccessKey Secret")).not.toBeInTheDocument();
    expect(writes).toEqual([{ expectedVersion: 0, name: "新连接", provider: "S3", endpoint: "https://s3.example.com", region: "us-east-1",
      bucket: "test-bucket", keyPrefix: "agenvas", pathStyle: true, accessKeyId: "my-access-id", secretAccessKey: "my-secret-value" }]);
    expect(JSON.stringify(client.getQueryData(["settings", "storage"]))).not.toContain("my-secret-value");
    expect(screen.getByText("当前默认").closest(".storage-destination")).toHaveTextContent("本地存储");
  });
  it("explicitly switches to cloud and back to local using the current CAS version", async () => {
    const writes: unknown[] = [];
    server.use(http.put("/api/v1/settings/storage/active", async ({ request }) => {
      const input = await request.json() as { expectedVersion: number; profileId: string | null }; writes.push(input);
      return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: input.expectedVersion + 1, relayProfileId: null, activeProfileId: input.profileId, profiles: [profile] });
    }));
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 3, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    await changeControl(await screen.findByLabelText("归档目的地"), { target: { value: profile.id } });
    await screen.findByText("后续资源使用所选存储连接。历史文件位置保持不变。");
    await changeControl(screen.getByLabelText("归档目的地"), { target: { value: "" } });
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
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 3, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version, relayProfileId: null, activeProfileId: null, profiles: [profile] })));
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
      writes.push(await request.json()); return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 2, relayProfileId: null, activeProfileId: "cloud-one", profiles: [{ ...profile, accessKeyMask: "••••5678" }] });
    }));
    const client = setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 1, relayProfileId: null, activeProfileId: "cloud-one", profiles: [profile] });
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
      return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: input.expectedVersion + 1, relayProfileId: input.profileId, activeProfileId: null, profiles: [profile] });
    }));
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 3, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    await changeControl(await screen.findByLabelText("中继连接"), { target: { value: profile.id } });
    expect(await screen.findByRole("status")).toHaveTextContent("中继配置已保存，默认存储未切换");
    expect(writes).toEqual([{ expectedVersion: 3, profileId: profile.id, llmRelayEnabled: true, imageRelayEnabled: true }]);
    expect(screen.getByText("当前默认").closest(".storage-destination")).toHaveTextContent("本地存储");
    await changeControl(screen.getByLabelText("中继连接"), { target: { value: "" } });
    await screen.findByRole("status");
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[1]).toEqual({ expectedVersion: 4, profileId: null, llmRelayEnabled: true, imageRelayEnabled: true });
  });

  it("independently switches LLM and image relay functions with CAS", async () => {
    const writes: unknown[] = [];
    server.use(http.put("/api/v1/settings/storage/relay", async ({ request }) => {
      const input = await request.json() as { expectedVersion: number; profileId: string | null; llmRelayEnabled: boolean; imageRelayEnabled: boolean };
      writes.push(input);
      return HttpResponse.json({ ...input, version: input.expectedVersion + 1, relayProfileId: input.profileId, activeProfileId: null, profiles: [profile] });
    }));
    setup({ version: 3, relayProfileId: profile.id, activeProfileId: null, llmRelayEnabled: true, imageRelayEnabled: true, profiles: [profile] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("switch", { name: "图片生成参考图与蒙版" }));
    await waitFor(() => expect(screen.getByRole("switch", { name: "图片生成参考图与蒙版" })).not.toBeChecked());
    expect(screen.getByRole("switch", { name: "LLM 图片输入" })).toBeChecked();
    await user.click(screen.getByRole("switch", { name: "LLM 图片输入" }));
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes).toEqual([
      { expectedVersion: 3, profileId: profile.id, llmRelayEnabled: true, imageRelayEnabled: false },
      { expectedVersion: 4, profileId: profile.id, llmRelayEnabled: false, imageRelayEnabled: false },
    ]);
  });

  it("preserves failed relay changes for an explicit retry", async () => {
    setup({ version: 3, relayProfileId: profile.id, activeProfileId: null, llmRelayEnabled: true, imageRelayEnabled: false, profiles: [profile] });
    server.use(http.put("/api/v1/settings/storage/relay", () => HttpResponse.json({ detail: "保存失败" }, { status: 500 })));
    await userEvent.setup().click(await screen.findByRole("switch", { name: "LLM 图片输入" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("switch", { name: "LLM 图片输入" })).not.toBeChecked();
    expect(screen.getByRole("switch", { name: "图片生成参考图与蒙版" })).not.toBeChecked();
    expect(screen.getByRole("button", { name: "重试保存中继" })).toBeEnabled();
  });

  it("preserves relay choices on a CAS conflict until the latest settings are loaded", async () => {
    let latest = { version: 3, relayProfileId: profile.id, activeProfileId: null, llmRelayEnabled: true, imageRelayEnabled: true, profiles: [profile] };
    setup(latest);
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json(latest)),
      http.put("/api/v1/settings/storage/relay", () => {
        latest = { ...latest, version: 4, llmRelayEnabled: false };
        return HttpResponse.json({ detail: "配置已变化", code: "STORAGE_VERSION_CONFLICT" }, { status: 409 });
      }));
    await userEvent.setup().click(await screen.findByRole("switch", { name: "图片生成参考图与蒙版" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "重试保存中继" })).toBeDisabled());
    expect(screen.getByRole("switch", { name: "图片生成参考图与蒙版" })).not.toBeChecked();
    expect(screen.getByRole("switch", { name: "LLM 图片输入" })).toBeChecked();
    await userEvent.setup().click(screen.getByRole("button", { name: "载入最新配置" }));
    await waitFor(() => expect(screen.getByRole("switch", { name: "图片生成参考图与蒙版" })).toBeChecked());
    expect(screen.getByRole("switch", { name: "LLM 图片输入" })).not.toBeChecked();
  });

  it("edits a saved OSS region while retaining credentials and default selections", async () => {
    const oss = { ...profile, provider: "ALIYUN_OSS" as const, region: "oss-cn-chengdu", pathStyle: false,
      endpoint: "https://oss-cn-chengdu.aliyuncs.com" };
    const writes: unknown[] = [];
    server.use(http.put("/api/v1/settings/storage/profiles/cloud-one", async ({ request }) => {
      writes.push(await request.json());
      return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 4, activeProfileId: oss.id, relayProfileId: oss.id, profiles: [{ ...oss, region: "cn-chengdu" }] });
    }));
    const client = setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 3, activeProfileId: oss.id, relayProfileId: oss.id, profiles: [oss] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑：云端一" }));
    expect(screen.getByLabelText("连接名称")).toHaveValue(oss.name);
    expect(screen.getByLabelText("AccessKey ID")).toHaveValue("");
    expect(screen.getByLabelText("AccessKey Secret")).toHaveValue("");
    await user.clear(screen.getByLabelText("Region")); await user.type(screen.getByLabelText("Region"), "cn-chengdu");
    await user.click(screen.getByRole("button", { name: "保存修改" }));
    expect(await screen.findByRole("status")).toHaveTextContent("连接已更新");
    expect(writes).toEqual([{ expectedVersion: 3, name: oss.name, provider: oss.provider, endpoint: oss.endpoint,
      region: "cn-chengdu", bucket: oss.bucket, keyPrefix: oss.keyPrefix, pathStyle: false }]);
    expect(client.getQueryData<StorageSettings>(["settings", "storage"])?.activeProfileId).toBe(oss.id);
    expect(client.getQueryData<StorageSettings>(["settings", "storage"])?.relayProfileId).toBe(oss.id);
    expect(screen.queryByRole("button", { name: "保存修改" })).not.toBeInTheDocument();
  });
  it("keeps failed edit fields while clearing replacement keys and requiring an explicit conflict reload", async () => {
    let version = 2;
    const writes: unknown[] = [];
    server.use(http.put("/api/v1/settings/storage/profiles/cloud-one", async ({ request }) => {
      writes.push(await request.json()); version = 3;
      return HttpResponse.json({ status: 409, detail: "配置已变化", code: "STORAGE_VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
    }));
    const client = setup({ llmRelayEnabled: true, imageRelayEnabled: true, version, activeProfileId: null, relayProfileId: null, profiles: [profile] });
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version, activeProfileId: null, relayProfileId: null, profiles: [profile] })));
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "编辑：云端一" }));
    await user.clear(screen.getByLabelText("连接名称")); await user.type(screen.getByLabelText("连接名称"), "保留草稿");
    await user.type(screen.getByLabelText("AccessKey ID"), "replacement-id");
    await user.type(screen.getByLabelText("AccessKey Secret"), "replacement-secret");
    await user.click(screen.getByRole("button", { name: "保存修改" }));
    await screen.findByRole("button", { name: "载入最新配置" });
    expect(screen.getByLabelText("连接名称")).toHaveValue("保留草稿");
    expect(screen.getByLabelText("AccessKey Secret")).toHaveValue("");
    expect(screen.getByRole("button", { name: "保存修改" })).toBeDisabled();
    expect(JSON.stringify(client.getQueryData(["settings", "storage"]))).not.toContain("replacement-secret");
    expect(writes).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "载入最新配置" }));
    expect(screen.getByLabelText("连接名称")).toHaveValue(profile.name);
    expect(screen.getByRole("button", { name: "保存修改" })).toBeEnabled();
  });
  it("requires confirmation to delete and uses the version pinned when the dialog opened", async () => {
    const writes: string[] = [];
    server.use(http.delete("/api/v1/settings/storage/profiles/cloud-one", ({ request }) => {
      writes.push(new URL(request.url).searchParams.get("expectedVersion") ?? "");
      return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 6, activeProfileId: null, relayProfileId: null, profiles: [] });
    }));
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 5, activeProfileId: profile.id, relayProfileId: profile.id, profiles: [profile] });
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "删除：云端一" }));
    expect(writes).toHaveLength(0);
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "取消" }));
    expect(writes).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "删除：云端一" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "确认删除" }));
    expect(await screen.findByRole("status")).toHaveTextContent("连接已删除");
    expect(writes).toEqual(["5"]);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByText("当前默认").closest(".storage-destination")).toHaveTextContent("本地存储");
    expect(screen.queryByRole("button", { name: "编辑：云端一" })).not.toBeInTheDocument();
  });
  it("preserves a failed delete dialog and reloads a stale version only on explicit action", async () => {
    let version = 5; const writes: string[] = [];
    server.use(http.delete("/api/v1/settings/storage/profiles/cloud-one", ({ request }) => {
      writes.push(new URL(request.url).searchParams.get("expectedVersion") ?? "");
      if (writes.length === 1) {
        version = 6;
        return HttpResponse.json({ status: 409, title: "配置已变化", code: "STORAGE_VERSION_CONFLICT", detail: "配置已变化" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
      }
      return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 7, activeProfileId: null, relayProfileId: null, profiles: [] });
    }));
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version, activeProfileId: null, relayProfileId: null, profiles: [profile] });
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version, activeProfileId: null, relayProfileId: null, profiles: [profile] })));
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "删除：云端一" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "确认删除" }));
    await within(screen.getByRole("dialog")).findByRole("button", { name: "载入最新配置" });
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "确认删除" })).toBeDisabled();
    expect(within(screen.getByRole("dialog")).getByRole("alert")).toHaveTextContent("配置已变化");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "载入最新配置" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "确认删除" }));
    await screen.findByRole("status"); expect(writes).toEqual(["5", "6"]);
  });
  it("locks referenced locations while keeping the name editable and explaining why deletion is unavailable", async () => {
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 5, activeProfileId: profile.id, relayProfileId: null, profiles: [{ ...profile, inUse: true }] });
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "编辑：云端一" }));
    expect(screen.getByLabelText("连接名称")).toBeEnabled();
    expect(screen.getByLabelText("Endpoint")).toBeDisabled(); expect(screen.getByLabelText("Region")).toBeDisabled();
    expect(screen.getByLabelText("Bucket")).toBeDisabled(); expect(screen.getByLabelText("AccessKey ID")).toBeEnabled();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "关闭" }));
    await user.click(screen.getByRole("button", { name: "删除：云端一" }));
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "确认删除" })).toBeDisabled();
    expect(screen.getByRole("dialog")).toHaveTextContent("无法删除");
  });
  it("reloads the fixed location when a new archive reference arrives during editing without changing the settings version", async () => {
    let inUse = false;
    server.use(http.put("/api/v1/settings/storage/profiles/cloud-one", () => {
      inUse = true;
      return HttpResponse.json({ code: "STORAGE_PROFILE_IN_USE", detail: "连接正在使用" },
        { status: 409, headers: { "Content-Type": "application/problem+json" } });
    }));
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 5, activeProfileId: profile.id, relayProfileId: null, profiles: [profile] });
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 5, activeProfileId: profile.id,
      relayProfileId: null, profiles: [{ ...profile, inUse }] })));
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "编辑：云端一" }));
    await user.clear(screen.getByLabelText("Region")); await user.type(screen.getByLabelText("Region"), "us-west-1");
    await user.click(screen.getByRole("button", { name: "保存修改" }));
    await screen.findByRole("button", { name: "载入最新配置" });
    expect(screen.getByLabelText("Region")).toHaveValue("us-west-1");
    expect(screen.getByRole("button", { name: "保存修改" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "载入最新配置" }));
    expect(screen.getByLabelText("Region")).toHaveValue(profile.region);
    expect(screen.getByLabelText("Region")).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存修改" })).toBeEnabled();
  });
  it("blocks a retry when deletion discovers a new reference without a settings version change", async () => {
    let inUse = false;
    server.use(http.delete("/api/v1/settings/storage/profiles/cloud-one", () => {
      inUse = true;
      return HttpResponse.json({ code: "STORAGE_PROFILE_IN_USE", detail: "连接正在使用" },
        { status: 409, headers: { "Content-Type": "application/problem+json" } });
    }));
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 5, activeProfileId: profile.id, relayProfileId: null, profiles: [profile] });
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 5, activeProfileId: profile.id,
      relayProfileId: null, profiles: [{ ...profile, inUse }] })));
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "删除：云端一" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "确认删除" }));
    await within(screen.getByRole("dialog")).findByText(/此连接已被素材/);
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "确认删除" })).toBeDisabled();
  });

  it("keeps separate changed drafts across dialogs while clearing credentials on close", async () => {
    const second = { ...profile, id: "cloud-two", name: "云端二" };
    setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 3, relayProfileId: null, activeProfileId: null, profiles: [profile, second] });
    const user = userEvent.setup();
    expect(screen.queryByLabelText("连接名称")).not.toBeInTheDocument();
    await user.click(await screen.findByRole("button", { name: "编辑：云端一" }));
    await user.clear(screen.getByLabelText("连接名称")); await user.type(screen.getByLabelText("连接名称"), "第一个草稿");
    await user.type(screen.getByLabelText("AccessKey ID"), "temporary-id");
    await user.type(screen.getByLabelText("AccessKey Secret"), "temporary-secret");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "关闭" }));
    expect(screen.getByRole("region", { name: "云端一" })).toHaveTextContent("有未保存的修改");
    await user.click(screen.getByRole("button", { name: "编辑：云端二" }));
    expect(screen.getByLabelText("连接名称")).toHaveValue("云端二");
    await user.clear(screen.getByLabelText("连接名称")); await user.type(screen.getByLabelText("连接名称"), "第二个草稿");
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "编辑：云端一" }));
    expect(screen.getByLabelText("连接名称")).toHaveValue("第一个草稿");
    expect(screen.getByLabelText("AccessKey ID")).toHaveValue("");
    expect(screen.getByLabelText("AccessKey Secret")).toHaveValue("");
    await user.click(screen.getByRole("button", { name: "放弃修改" }));
    expect(screen.getByRole("region", { name: "云端一" })).not.toHaveTextContent("有未保存的修改");
    await user.click(screen.getByRole("button", { name: "编辑：云端二" }));
    expect(screen.getByLabelText("连接名称")).toHaveValue("第二个草稿");
  });

  it("does not retain an unchanged edit or pin a new empty draft to an old version", async () => {
    const client = setup({ llmRelayEnabled: true, imageRelayEnabled: true, version: 3, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑：云端一" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "关闭" }));
    expect(screen.getByRole("region", { name: "云端一" })).not.toHaveTextContent("有未保存的修改");
    await user.click(screen.getByRole("button", { name: "添加存储连接" }));
    await user.keyboard("{Escape}");
    client.setQueryData<StorageSettings>(["settings", "storage"], (old) => old && { ...old, version: 4, profiles: [{ ...profile, name: "最新名称" }] });
    await user.click(screen.getByRole("button", { name: "添加存储连接" }));
    expect(screen.queryByText("配置版本已更新")).not.toBeInTheDocument();
    expect(screen.getByLabelText("连接名称")).toHaveValue("");
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "编辑：最新名称" }));
    expect(screen.getByLabelText("连接名称")).toHaveValue("最新名称");
  });

  it("preserves a failed credential dialog, clears secrets and requires explicit conflict reload", async () => {
    let version = 3;
    let writes = 0;
    const client = setup({ llmRelayEnabled: true, imageRelayEnabled: true, version, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    server.use(http.get("/api/v1/settings/storage", () => HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version, relayProfileId: null, activeProfileId: null, profiles: [profile] })),
      http.put("/api/v1/settings/storage/profiles/cloud-one/credentials", () => {
        version = 4; writes++;
        return HttpResponse.json({ detail: "凭证版本已变化", code: "STORAGE_VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
      }));
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "更新凭证：云端一" }));
    await user.type(screen.getByLabelText("新的 AccessKey ID"), "new-id");
    await user.type(screen.getByLabelText("新的 AccessKey Secret"), "new-secret");
    await user.click(screen.getByRole("button", { name: "保存新凭证" }));
    await screen.findByRole("button", { name: "载入最新配置" });
    expect(screen.getByRole("dialog")).toHaveTextContent("凭证版本已变化");
    expect(screen.getByLabelText("新的 AccessKey Secret")).toHaveValue("");
    expect(screen.getByLabelText("新的 AccessKey ID")).toHaveValue("");
    expect(screen.getByRole("button", { name: "保存新凭证" })).toBeDisabled();
    expect(JSON.stringify(client.getQueryData(["settings", "storage"]))).not.toContain("new-secret");
    await user.click(screen.getByRole("button", { name: "载入最新配置" }));
    expect(screen.getByRole("button", { name: "保存新凭证" })).toBeEnabled();
    expect(writes).toBe(1);
  });

  it("keeps a submitted dialog open and blocks changes, close and duplicate submissions while waiting", async () => {
    let finish: (() => void) | undefined;
    let writes = 0;
    server.use(http.post("/api/v1/settings/storage/profiles", async () => {
      writes++;
      await new Promise<void>((resolve) => { finish = resolve; });
      return HttpResponse.json({ llmRelayEnabled: true, imageRelayEnabled: true, version: 1, relayProfileId: null, activeProfileId: null, profiles: [profile] });
    }));
    setup(); const user = await fill();
    await user.click(screen.getByRole("button", { name: "保存连接" }));
    await waitFor(() => expect(writes).toBe(1));
    const dialog = screen.getByRole("dialog");
    expect(screen.getByLabelText("连接名称")).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "关闭" })).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "正在保存…" })).toBeDisabled();
    await user.keyboard("{Escape}"); expect(screen.getByRole("dialog")).toBe(dialog);
    finish?.();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(writes).toBe(1);
  });
});
