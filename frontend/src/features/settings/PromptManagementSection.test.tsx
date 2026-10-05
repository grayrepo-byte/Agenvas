import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { PromptManagementSection } from "./PromptManagementSection";

const defaults = { id: "director", key: "agent.director", kind: "AGENT", name: "导演 Agent", description: "Synthetic purpose", content: "Synthetic workflow", builtIn: true, version: 1, createdAt: "2026-10-03T00:00:00Z", updatedAt: "2026-10-03T00:00:00Z" };
function show(enabled = true) {
  const view = render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><Routes>
    <Route path="/" element={<PromptManagementSection enabled={enabled} />} />
    <Route path="/login" element={<p>Login destination</p>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return view;
}
describe("PromptManagementSection", () => {
  it("does not load while its category is inactive", () => {
    const reads = vi.fn();
    server.use(http.get("/api/v1/settings/prompts", () => { reads(); return HttpResponse.json({ items: [defaults] }); }));
    show(false); expect(reads).not.toHaveBeenCalled();
  });
  it("saves complete content with the pinned version", async () => {
    const writes: unknown[] = [];
    server.use(http.get("/api/v1/settings/prompts", () => HttpResponse.json({ items: [defaults] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" })),
      http.put("/api/v1/settings/prompts/director", async ({ request }) => {
        const input = await request.json(); writes.push(input);
        return HttpResponse.json({ ...defaults, content: "Full creative workflow", version: 2 });
      }));
    show(); await screen.findByDisplayValue(defaults.content);
    fireEvent.change(screen.getByLabelText("提示词正文"), { target: { value: "Full creative workflow" } });
    await userEvent.setup().click(screen.getByRole("button", { name: "保存配置" }));
    await screen.findByText("提示词已保存。");
    expect(writes).toEqual([{ description: defaults.description, name: defaults.name, content: "Full creative workflow", expectedVersion: 1 }]);
  });
  it("retains edited content after a conflict and requires explicitly adopting the refreshed version", async () => {
    let version = 1; const versions: number[] = [];
    server.use(http.get("/api/v1/settings/prompts", () => HttpResponse.json({ items: [{ ...defaults, content: version === 1 ? defaults.content : "Other administrator", version }] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" })),
      http.put("/api/v1/settings/prompts/director", async ({ request }) => {
        const input = await request.json() as { expectedVersion: number }; versions.push(input.expectedVersion);
        if (version === 1) { version = 2; return HttpResponse.json({ code: "PROMPT_CONFLICT", status: 409 }, { status: 409 }); }
        return HttpResponse.json({ ...defaults, content: "My draft", version: 3 });
      }));
    show(); await screen.findByDisplayValue(defaults.content);
    fireEvent.change(screen.getByLabelText("提示词正文"), { target: { value: "My draft" } });
    const user = userEvent.setup(); await user.click(screen.getByRole("button", { name: "保存配置" }));
    await screen.findByText("提示词已被其他请求修改，请重新读取后处理草稿。");
    await user.click(screen.getByRole("button", { name: "重新读取设置" }));
    await user.click(await screen.findByRole("button", { name: "保留草稿并使用最新版本" }));
    expect(screen.getByLabelText("提示词正文")).toHaveValue("My draft");
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(versions).toEqual([1, 2]));
  });
  it("keeps independent drafts while switching between Agent and feature prompts and supports filtering", async () => {
    const feature = { ...defaults, id: "text", key: "text.generate", kind: "FUNCTION", name: "文字卡片生成", content: "Feature prompt" };
    server.use(http.get("/api/v1/settings/prompts", () => HttpResponse.json({ items: [defaults, feature] })));
    show(); await screen.findByDisplayValue(defaults.content);
    fireEvent.change(screen.getByLabelText("提示词正文"), { target: { value: "Director draft" } });
    const user = userEvent.setup(); await user.click(screen.getByRole("button", { name: /文字卡片生成/ }));
    fireEvent.change(screen.getByLabelText("提示词正文"), { target: { value: "Feature draft" } });
    await user.click(screen.getByRole("button", { name: /导演 Agent/ }));
    expect(screen.getByLabelText("提示词正文")).toHaveValue("Director draft");
    await selectValue(screen.getByRole("combobox", { name: "筛选用途" }), "FUNCTION");
    expect(screen.queryByRole("button", { name: /导演 Agent/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /文字卡片生成/ }));
    expect(screen.getByLabelText("提示词正文")).toHaveValue("Feature draft");
    expect(screen.queryByRole("button", { name: "删除提示词" })).not.toBeInTheDocument();
  });
  it("creates and explicitly deletes a custom function using its version", async () => {
    const writes: unknown[] = []; const deletions: string[] = [];
    const feature = { ...defaults, id: "summary", key: "feature.summary", kind: "FUNCTION", name: "Summary", description: "", content: "Summarize", builtIn: false };
    server.use(http.get("/api/v1/settings/prompts", () => HttpResponse.json({ items: [defaults] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" })),
      http.post("/api/v1/settings/prompts", async ({ request }) => { writes.push(await request.json()); return HttpResponse.json(feature, { status: 201 }); }),
      http.delete("/api/v1/settings/prompts/summary", ({ request }) => { deletions.push(new URL(request.url).searchParams.get("expectedVersion")!); return new HttpResponse(null, { status: 204 }); }));
    show(); await screen.findByDisplayValue(defaults.content);
    const user = userEvent.setup(); await user.click(screen.getByRole("button", { name: "新建提示词" }));
    fireEvent.change(screen.getByLabelText("用途标识"), { target: { value: feature.key } });
    await selectValue(screen.getByRole("combobox", { name: "用途" }), "FUNCTION");
    fireEvent.change(screen.getByLabelText("名称"), { target: { value: feature.name } });
    fireEvent.change(screen.getByLabelText("提示词正文"), { target: { value: feature.content } });
    await user.click(screen.getByRole("button", { name: "保存配置" })); await screen.findByText("提示词已保存。");
    expect(writes).toEqual([{ key: feature.key, kind: "FUNCTION", name: feature.name, content: feature.content, description: "" }]);
    expect(screen.getByLabelText("用途标识")).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "删除提示词" })); expect(deletions).toEqual([]);
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: /Summary/ })).not.toBeInTheDocument());
    expect(deletions).toEqual(["1"]);
  });
  it("finds and edits the four built-in view prompts independently", async () => {
    const viewPrompts = [
      { id: "character", key: "image.three-view.character", name: "角色三视图" },
      { id: "face", key: "image.three-view.face", name: "脸部三视图" },
      { id: "prop", key: "image.three-view.prop", name: "道具三视图" },
      { id: "scene", key: "image.three-view.scene-grid", name: "场景宫格图" },
    ].map((entry) => ({ ...defaults, ...entry, kind: "FUNCTION", content: `Synthetic ${entry.id} views` }));
    const writes: unknown[] = [];
    const face = viewPrompts[1]!;
    server.use(http.get("/api/v1/settings/prompts", () => HttpResponse.json({ items: [defaults, ...viewPrompts] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" })),
      http.put("/api/v1/settings/prompts/face", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json({ ...face, content: "Synthetic customized facial views", version: 2 });
      }));
    show(); await screen.findByDisplayValue(defaults.content);
    const user = userEvent.setup();
    await selectValue(screen.getByRole("combobox", { name: "筛选用途" }), "FUNCTION");
    for (const entry of viewPrompts) expect(screen.getByRole("button", { name: new RegExp(entry.name) })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /角色三视图/ }));
    fireEvent.change(screen.getByLabelText("提示词正文"), { target: { value: "Synthetic character draft" } });
    fireEvent.change(screen.getByRole("textbox", { name: "搜索名称、标识或说明" }), { target: { value: "image.three-view.face" } });
    expect(screen.queryByRole("button", { name: /角色三视图/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /脸部三视图/ }));
    expect(screen.getByLabelText("用途标识")).toHaveValue(face.key);
    expect(screen.getByLabelText("用途标识")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "删除提示词" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("提示词正文"), { target: { value: "Synthetic customized facial views" } });
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    await screen.findByText("提示词已保存。");
    expect(writes).toEqual([{ name: face.name, description: face.description,
      content: "Synthetic customized facial views", expectedVersion: face.version }]);
    fireEvent.change(screen.getByRole("textbox", { name: "搜索名称、标识或说明" }), { target: { value: "" } });
    await user.click(screen.getByRole("button", { name: /角色三视图/ }));
    expect(screen.getByLabelText("提示词正文")).toHaveValue("Synthetic character draft");
  });
  it("keeps a new prompt draft after a duplicate key and allows correcting that key", async () => {
    let creates = 0;
    server.use(http.get("/api/v1/settings/prompts", () => HttpResponse.json({ items: [defaults] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" })),
      http.post("/api/v1/settings/prompts", () => { creates++; return creates === 1
        ? HttpResponse.json({ code: "PROMPT_KEY_CONFLICT", status: 409 }, { status: 409, headers: { "Content-Type": "application/problem+json" } })
        : HttpResponse.json({ ...defaults, id: "writer", key: "agent.writer", name: "Writer", content: "Write", builtIn: false }); }));
    show(); await screen.findByDisplayValue(defaults.content); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "新建提示词" }));
    fireEvent.change(screen.getByLabelText("用途标识"), { target: { value: "agent.director" } });
    fireEvent.change(screen.getByLabelText("名称"), { target: { value: "Writer" } });
    fireEvent.change(screen.getByLabelText("提示词正文"), { target: { value: "Write" } });
    await user.click(screen.getByRole("button", { name: "保存配置" })); await screen.findByRole("alert");
    fireEvent.change(screen.getByLabelText("用途标识"), { target: { value: "agent.writer" } });
    expect(screen.getByLabelText("提示词正文")).toHaveValue("Write");
    await user.click(screen.getByRole("button", { name: "保存配置" })); await screen.findByText("提示词已保存。"); expect(creates).toBe(2);
  });
  it.each([401, 403, 503])("handles an HTTP %s read without an editable empty prompt", async (status) => {
    server.use(http.get("/api/v1/settings/prompts", () => HttpResponse.json({ status }, { status })));
    show();
    if (status === 401) await screen.findByText("Login destination");
    else { await screen.findByRole("alert"); expect(screen.getByRole("button", { name: "保存配置" })).toBeDisabled(); }
  });
});
