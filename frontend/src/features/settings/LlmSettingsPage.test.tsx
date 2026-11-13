import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { type LlmConnection, type LlmConnections, type SaveLlmConnectionRequest } from "../../shared/api/client";
import { setLocale, t } from "../../shared/i18n";
import { server } from "../../test/server";
import { LlmSettingsPage } from "./LlmSettingsPage";

const FIRST = "00000000-0000-4000-8000-000000000001";
const SECOND = "00000000-0000-4000-8000-000000000002";
const primary: LlmConnection = {
  id: FIRST,
  name: "日常创作",
  version: 4,
  endpoint: "https://primary.example/v1",
  modelId: "model-a",
  keyMask: "••••one1",
  toolCallingVerified: true,
  updatedAt: "2026-11-07T00:00:00Z",
  modelPolicy: null,
};
const secondary: LlmConnection = {
  ...primary,
  id: SECOND,
  name: "备用服务",
  version: 7,
  endpoint: "https://secondary.example/v1",
  modelId: "model-b",
  keyMask: "••••two2",
  toolCallingVerified: false,
};
const initial: LlmConnections = { items: [primary, secondary], activeConnectionId: FIRST, activeVersion: 4 };
const key = ["settings", "llm", "connections"];
beforeEach(() => setLocale("zh"));

function mount(value: LlmConnections = initial) {
  const store = { value: structuredClone(value) };
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/settings/llm/connections", () => HttpResponse.json(store.value)),
    http.get("/api/v1/settings/diagnostics", () =>
      HttpResponse.json({ llmMode: "CONFIGURED", mediaMode: "MOCK", recentErrors: [] }),
    ),
  );
  const client = createQueryClient();
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <LlmSettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { store, client };
}
async function edit(name = primary.name) {
  const row = await screen.findByRole("row", { name: new RegExp(name) });
  await userEvent.click(within(row).getByRole("button", { name: "编辑" }));
  return screen.findByRole("dialog", { name: "编辑连接" });
}
function edited(values: Partial<SaveLlmConnectionRequest>, base = secondary): LlmConnection {
  return {
    ...base,
    name: values.name ?? base.name,
    endpoint: values.endpoint ?? base.endpoint,
    modelId: values.modelId ?? base.modelId,
    version: base.version + 1,
    toolCallingVerified: false,
  };
}

describe("LLM connection management", () => {
  it("shows multiple saved services and current selection without a permanent form or discovery controls", async () => {
    mount();
    expect(await screen.findByRole("row", { name: /备用服务/ })).toHaveTextContent("model-b");
    expect(screen.getByRole("row", { name: /日常创作/ })).toHaveTextContent("当前使用");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "查找本机 Ollama" })).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByText(t("settings.llm.costConfirmationHint"))).not.toBeInTheDocument();
  });

  it("keeps optional model settings collapsed without losing edited capacity", async () => {
    mount();
    const dialog = await edit();
    expect(within(dialog).queryByLabelText("确认的上下文窗口")).not.toBeInTheDocument();
    const user = userEvent.setup();
    const toggle = within(dialog).getByRole("button", { name: "模型发现与高级设置" });
    await user.click(toggle);
    await user.click(within(dialog).getByRole("button", { name: "高级设置" }));
    const context = within(dialog).getByLabelText("确认的上下文窗口");
    fireEvent.change(context, { target: { value: "65536" } });
    await user.click(toggle);
    expect(context).not.toBeVisible();
    await user.click(toggle);
    expect(context).toHaveValue(65536);
    expect(within(dialog).getByLabelText("API Key")).toHaveValue("");
  });

  it("probes local Ollama only on request and fills the address without changing other fields", async () => {
    mount();
    let probes = 0;
    server.use(
      http.post("/api/v1/settings/local-services/discover", () => {
        probes++;
        return HttpResponse.json({
          service: "OLLAMA",
          runtime: "CONTAINER",
          status: "FOUND",
          endpoint: "http://host.docker.internal:11434/v1",
          policyAllowed: true,
        });
      }),
    );
    const dialog = await edit();
    const user = userEvent.setup();
    fireEvent.change(within(dialog).getByLabelText("模型 ID"), { target: { value: "draft-model" } });
    await user.click(within(dialog).getByRole("button", { name: "连接帮助" }));
    expect(probes).toBe(0);
    await user.click(screen.getByRole("button", { name: "查找本机 Ollama" }));
    expect(within(dialog).getByLabelText("端点地址")).toHaveValue(primary.endpoint);
    await user.click(await screen.findByRole("button", { name: "填入地址" }));
    expect(within(dialog).getByLabelText("端点地址")).toHaveValue("http://host.docker.internal:11434/v1");
    expect(within(dialog).getByLabelText("模型 ID")).toHaveValue("draft-model");
    expect(within(dialog).getByLabelText("API Key")).toHaveValue("");
    expect(probes).toBe(1);
  });

  it("edits an inactive connection with a blank key without changing the active service", async () => {
    const { store } = mount();
    const writes: unknown[] = [];
    server.use(
      http.put(`/api/v1/settings/llm/connections/${SECOND}`, async ({ request }) => {
        const input = (await request.json()) as SaveLlmConnectionRequest;
        writes.push(input);
        const saved = edited(input);
        store.value.items = [primary, saved];
        return HttpResponse.json(saved);
      }),
    );
    const dialog = await edit(secondary.name);
    const apiKey = within(dialog).getByLabelText("API Key");
    expect(apiKey).not.toBeRequired();
    expect(apiKey).toHaveValue("");
    expect(apiKey).toHaveAttribute("placeholder", expect.stringContaining(secondary.keyMask));
    fireEvent.change(within(dialog).getByLabelText("连接名称"), { target: { value: "备用已改名" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "保存配置" }));
    await waitFor(() =>
      expect(writes).toEqual([
        { expectedVersion: 7, name: "备用已改名", endpoint: secondary.endpoint, modelId: secondary.modelId },
      ]),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(await screen.findByRole("row", { name: /日常创作/ })).toHaveTextContent("当前使用");
    expect(screen.getByRole("row", { name: /备用已改名/ })).not.toHaveTextContent("当前使用");
  });

  it.each(["ollama", "space key", "非ascii-key", "x".repeat(4097)])(
    "rejects a non-empty invalid replacement key while preserving the draft",
    async invalid => {
      mount();
      let writes = 0;
      server.use(
        http.put("/api/v1/settings/llm/connections/:id", () => {
          writes++;
          return new HttpResponse(null, { status: 500 });
        }),
      );
      const dialog = await edit();
      const input = within(dialog).getByLabelText("API Key");
      fireEvent.change(input, { target: { value: invalid } });
      // Exercise the application guard as well as native form validation.
      if (invalid.length > 4096) input.closest("form")!.noValidate = true;
      await userEvent.click(within(dialog).getByRole("button", { name: "保存配置" }));
      expect(input).toHaveValue(invalid);
      expect(input).toHaveAttribute("aria-invalid", "true");
      expect(writes).toBe(0);
    },
  );

  it("requires a key for a new connection and retains a stable identity across failed saves", async () => {
    mount({ items: [], activeConnectionId: null, activeVersion: 0 });
    await screen.findByText("添加第一个 LLM 服务");
    await userEvent.click(screen.getAllByRole("button", { name: "新增连接" })[0]!);
    const dialog = await screen.findByRole("dialog", { name: "新增连接" });
    fireEvent.change(within(dialog).getByLabelText("连接名称"), { target: { value: "新连接" } });
    fireEvent.change(within(dialog).getByLabelText("端点地址"), { target: { value: "https://new.example/v1" } });
    fireEvent.change(within(dialog).getByLabelText("模型 ID"), { target: { value: "model-new" } });
    const keyInput = within(dialog).getByLabelText("API Key");
    expect(keyInput).toBeRequired();
    const ids: string[] = [];
    const writes: SaveLlmConnectionRequest[] = [];
    server.use(
      http.put("/api/v1/settings/llm/connections/:id", async ({ request, params }) => {
        ids.push(String(params.id));
        writes.push((await request.json()) as SaveLlmConnectionRequest);
        return HttpResponse.json(
          { title: "Not saved", detail: "Synthetic network failure", code: "TEST", retryable: true },
          { status: 503 },
        );
      }),
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "保存配置" }));
    expect(writes).toEqual([]);
    for (let i = 0; i < 2; i++) {
      fireEvent.change(keyInput, { target: { value: "synthetic-key" } });
      await userEvent.click(within(dialog).getByRole("button", { name: "保存配置" }));
      await waitFor(() => expect(writes).toHaveLength(i + 1));
      await waitFor(() => expect(keyInput).toHaveValue(""));
    }
    expect(ids[0]).toBe(ids[1]);
    expect(writes.every(input => input.expectedVersion === 0)).toBe(true);
    expect(within(dialog).getByLabelText("连接名称")).toHaveValue("新连接");
  });

  it("switches using both target and active CAS versions without a model call", async () => {
    const { store } = mount();
    const intents: unknown[] = [];
    server.use(
      http.post(`/api/v1/settings/llm/connections/${SECOND}/activate`, async ({ request }) => {
        intents.push(await request.json());
        store.value = { items: [primary, { ...secondary, version: 8 }], activeConnectionId: SECOND, activeVersion: 8 };
        return HttpResponse.json(store.value);
      }),
    );
    const row = await screen.findByRole("row", { name: /备用服务/ });
    await userEvent.click(within(row).getByRole("button", { name: "设为当前" }));
    await waitFor(() => expect(intents).toEqual([{ expectedVersion: 7, expectedActiveVersion: 4 }]));
    await waitFor(() => expect(screen.getByRole("row", { name: /备用服务/ })).toHaveTextContent("当前使用"));
    expect(screen.getByRole("row", { name: /日常创作/ })).not.toHaveTextContent("当前使用");
  });

  it("creates an additional connection without replacing either saved service or activating it", async () => {
    const { store } = mount();
    const writes: SaveLlmConnectionRequest[] = [];
    let activations = 0;
    server.use(
      http.put("/api/v1/settings/llm/connections/:id", async ({ request, params }) => {
        const input = (await request.json()) as SaveLlmConnectionRequest;
        writes.push(input);
        const added: LlmConnection = {
          ...secondary,
          id: String(params.id),
          name: input.name,
          endpoint: input.endpoint,
          modelId: input.modelId,
          version: 8,
          keyMask: "••••new1",
        };
        store.value.items.push(added);
        return HttpResponse.json(added);
      }),
      http.post("/api/v1/settings/llm/connections/:id/activate", () => {
        activations++;
        return HttpResponse.json(store.value);
      }),
    );
    await screen.findByRole("row", { name: /备用服务/ });
    await userEvent.click(screen.getByRole("button", { name: "新增连接" }));
    const dialog = await screen.findByRole("dialog", { name: "新增连接" });
    fireEvent.change(within(dialog).getByLabelText("连接名称"), { target: { value: "第三个服务" } });
    fireEvent.change(within(dialog).getByLabelText("端点地址"), { target: { value: "https://third.example/v1" } });
    fireEvent.change(within(dialog).getByLabelText("模型 ID"), { target: { value: "model-c" } });
    fireEvent.change(within(dialog).getByLabelText("API Key"), { target: { value: "synthetic-new-key" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("row", { name: /第三个服务/ })).not.toHaveTextContent("当前使用");
    expect(screen.getByRole("row", { name: /日常创作/ })).toHaveTextContent("当前使用");
    expect(screen.getByRole("row", { name: /备用服务/ })).toBeInTheDocument();
    expect(activations).toBe(0);
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({
      expectedVersion: 0,
      apiKey: "synthetic-new-key",
      modelSelection: { mode: "MANUAL", contextWindowTokens: 1_000_000 },
    });
  });

  it("replaces a credential only when a new non-empty key is submitted", async () => {
    const { store } = mount();
    const writes: SaveLlmConnectionRequest[] = [];
    server.use(
      http.put(`/api/v1/settings/llm/connections/${SECOND}`, async ({ request }) => {
        const input = (await request.json()) as SaveLlmConnectionRequest;
        writes.push(input);
        const saved = { ...edited(input), keyMask: "••••next" };
        store.value.items = [primary, saved];
        return HttpResponse.json(saved);
      }),
    );
    const dialog = await edit(secondary.name);
    fireEvent.change(within(dialog).getByLabelText("API Key"), { target: { value: "synthetic-next" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(writes[0]).toMatchObject({ expectedVersion: 7, apiKey: "synthetic-next" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const reopened = await edit(secondary.name);
    expect(within(reopened).getByLabelText("API Key")).toHaveValue("");
    expect(within(reopened).getByLabelText("API Key")).toHaveAttribute(
      "placeholder",
      expect.stringContaining("••••next"),
    );
  });

  it("retains the current service after a switch failure and retries the same intent", async () => {
    mount();
    const intents: unknown[] = [];
    server.use(
      http.post(`/api/v1/settings/llm/connections/${SECOND}/activate`, async ({ request }) => {
        intents.push(await request.json());
        return HttpResponse.json(
          { title: "Unavailable", detail: "Try again", code: "TEST", retryable: true },
          { status: 503 },
        );
      }),
    );
    await userEvent.click(
      within(await screen.findByRole("row", { name: /备用服务/ })).getByRole("button", { name: "设为当前" }),
    );
    await screen.findByRole("alert");
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(intents).toHaveLength(2));
    expect(intents[1]).toEqual(intents[0]);
    expect(screen.getByRole("row", { name: /日常创作/ })).toHaveTextContent("当前使用");
  });

  it("requires separate diagnostic consent and targets the selected inactive connection", async () => {
    const { store } = mount();
    const requests: unknown[] = [];
    server.use(
      http.post(`/api/v1/settings/llm/connections/${SECOND}/diagnose`, async ({ request }) => {
        requests.push(await request.json());
        const verified = { ...secondary, toolCallingVerified: true };
        store.value.items = [primary, verified];
        return HttpResponse.json(verified);
      }),
    );
    const row = await screen.findByRole("row", { name: /备用服务/ });
    const user = userEvent.setup();
    await user.click(within(row).getByRole("button", { name: "诊断" }));
    let dialog = await screen.findByRole("dialog", { name: "工具协议诊断" });
    expect(requests).toEqual([]);
    expect(within(dialog).getByRole("button", { name: "执行可能计费的诊断" })).toBeDisabled();
    await user.click(within(dialog).getByRole("checkbox"));
    await user.click(within(dialog).getByRole("button", { name: "取消" }));
    await user.click(within(row).getByRole("button", { name: "诊断" }));
    dialog = await screen.findByRole("dialog", { name: "工具协议诊断" });
    expect(within(dialog).getByRole("checkbox")).not.toBeChecked();
    await user.click(within(dialog).getByRole("checkbox"));
    await user.click(within(dialog).getByRole("button", { name: "执行可能计费的诊断" }));
    await waitFor(() => expect(requests).toEqual([{ expectedVersion: 7, acknowledgeCost: true }]));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.getByRole("row", { name: /日常创作/ })).toHaveTextContent("当前使用");
  });

  it("invalidates diagnostic consent when another editor changes that connection", async () => {
    const { client } = mount();
    const row = await screen.findByRole("row", { name: /备用服务/ });
    await userEvent.click(within(row).getByRole("button", { name: "诊断" }));
    const dialog = await screen.findByRole("dialog", { name: "工具协议诊断" });
    await userEvent.click(within(dialog).getByRole("checkbox"));
    act(() => client.setQueryData(key, { ...initial, items: [primary, { ...secondary, version: 9 }] }));
    await waitFor(() => expect(within(dialog).getByRole("checkbox")).not.toBeChecked());
    expect(within(dialog).getByRole("button", { name: "执行可能计费的诊断" })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole("button", { name: "载入最新配置" }));
    expect(within(dialog).getByRole("checkbox")).not.toBeChecked();
  });

  it("recovers a diagnostic server conflict even when the local catalog has not refreshed", async () => {
    const { store } = mount();
    const attempts: unknown[] = [];
    server.use(
      http.post(`/api/v1/settings/llm/connections/${SECOND}/diagnose`, async ({ request }) => {
        attempts.push(await request.json());
        const latest = { ...secondary, version: 9 };
        store.value.items = [primary, latest];
        return attempts.length === 1
          ? HttpResponse.json(
              {
                title: "Conflict",
                detail: "Read the latest connection",
                code: "PROVIDER_CONFIG_VERSION_CONFLICT",
                retryable: false,
              },
              { status: 409 },
            )
          : HttpResponse.json({ ...latest, toolCallingVerified: true });
      }),
    );
    const row = await screen.findByRole("row", { name: /备用服务/ });
    await userEvent.click(within(row).getByRole("button", { name: "诊断" }));
    const dialog = await screen.findByRole("dialog", { name: "工具协议诊断" });
    await userEvent.click(within(dialog).getByRole("checkbox"));
    await userEvent.click(within(dialog).getByRole("button", { name: "执行可能计费的诊断" }));
    const refresh = await within(dialog).findByRole("button", { name: "载入最新配置" });
    await userEvent.click(refresh);
    await waitFor(() => expect(dialog).toHaveAccessibleDescription(/v9/));
    expect(within(dialog).getByRole("checkbox")).not.toBeChecked();
    expect(within(dialog).getByRole("button", { name: "执行可能计费的诊断" })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole("checkbox"));
    await userEvent.click(within(dialog).getByRole("button", { name: "执行可能计费的诊断" }));
    await waitFor(() =>
      expect(attempts).toEqual([
        { expectedVersion: 7, acknowledgeCost: true },
        { expectedVersion: 9, acknowledgeCost: true },
      ]),
    );
  });

  it("preserves dirty fields and the pinned version across background reads until an explicit conflict refresh", async () => {
    const { store, client } = mount();
    const dialog = await edit(secondary.name);
    const writes: unknown[] = [];
    fireEvent.change(within(dialog).getByLabelText("模型 ID"), { target: { value: "unsaved-model" } });
    store.value.items = [primary, { ...secondary, version: 9, modelId: "remote-model" }];
    act(() => client.setQueryData(key, store.value));
    expect(within(dialog).getByLabelText("模型 ID")).toHaveValue("unsaved-model");
    server.use(
      http.put(`/api/v1/settings/llm/connections/${SECOND}`, async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(
          {
            title: "Conflict",
            detail: "Reload configuration",
            code: "PROVIDER_CONFIG_VERSION_CONFLICT",
            retryable: false,
          },
          { status: 409 },
        );
      }),
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toMatchObject({ expectedVersion: 7, modelId: "unsaved-model" });
    expect(within(dialog).getByLabelText("模型 ID")).toHaveValue("unsaved-model");
    await userEvent.click(await within(dialog).findByRole("button", { name: "载入最新配置" }));
    await waitFor(() => expect(within(dialog).getByLabelText("模型 ID")).toHaveValue("remote-model"));
  });

  it("asks before discarding a dirty editor and preserves the input when editing continues", async () => {
    mount();
    const dialog = await edit();
    fireEvent.change(within(dialog).getByLabelText("连接名称"), { target: { value: "未保存" } });
    await userEvent.click(within(dialog).getByRole("button", { name: "取消" }));
    const confirmation = await screen.findByRole("dialog", { name: "放弃未保存的修改？" });
    await userEvent.click(within(confirmation).getByRole("button", { name: "继续编辑" }));
    expect(within(dialog).getByLabelText("连接名称")).toHaveValue("未保存");
  });
});
