import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import type { Project } from "../../shared/api/client";
import { server } from "../../test/server";
import { ProjectsPage } from "./ProjectsPage";

const project: Project = { id: "project-a", name: "森林漫游", aspectRatio: "LANDSCAPE_16_9", status: "ACTIVE", version: 2, createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-26T00:00:00Z" };
function showPage() {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/projects"]}><Routes><Route path="/projects" element={<ProjectsPage />} /><Route path="/login" element={<p>登录页</p>} /></Routes></MemoryRouter></QueryClientProvider>);
  return client;
}
function problem(detail: string, status = 409) { return HttpResponse.json({ code: "CONFLICT", title: "保存失败", detail, retryable: false }, { status, headers: { "Content-Type": "application/problem+json" } }); }

beforeEach(() => server.use(
  http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
  http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
  http.get("/api/v1/projects", () => HttpResponse.json({ items: [project], nextCursor: null })),
));

describe("ProjectsPage", () => {
  it("searches loaded projects and loads the next server cursor", async () => {
    const cursors: (string | null)[] = [];
    server.use(http.get("/api/v1/projects", ({ request }) => {
      const cursor = new URL(request.url).searchParams.get("cursor"); cursors.push(cursor);
      return HttpResponse.json(cursor ? { items: [{ ...project, id: "project-b", name: "城市光影" }], nextCursor: null } : { items: [project], nextCursor: "next-page" });
    }));
    showPage(); const user = userEvent.setup();
    expect(await screen.findByRole("article", { name: "森林漫游" })).toBeInTheDocument();
    await user.type(screen.getByRole("searchbox", { name: "搜索已加载项目" }), "城市");
    expect(screen.getByText("没有匹配的项目")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "加载更多项目" }));
    expect(await screen.findByRole("article", { name: "城市光影" })).toBeInTheDocument();
    expect(screen.queryByRole("article", { name: "森林漫游" })).not.toBeInTheDocument();
    expect(screen.getByText("2 个已加载")).toBeInTheDocument();
    expect(cursors).toEqual([null, "next-page"]);
  });

  it("preserves the create draft after failure and uses its chosen aspect on retry", async () => {
    const submitted: unknown[] = [];
    server.use(http.post("/api/v1/projects", async ({ request }) => {
      submitted.push(await request.json());
      return submitted.length === 1 ? problem("暂时无法创建", 503) : HttpResponse.json(project, { status: 201 });
    }));
    showPage(); const user = userEvent.setup();
    const input = await screen.findByRole("textbox", { name: "项目名称" });
    await user.type(input, "新世界");
    await user.selectOptions(screen.getByRole("combobox", { name: "画幅" }), "PORTRAIT_9_16");
    await user.click(screen.getByRole("button", { name: "创建项目" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("暂时无法创建");
    expect(input).toHaveValue("新世界");
    await user.click(screen.getByRole("button", { name: "创建项目" }));
    expect(await screen.findByText("项目已创建，可从列表打开画布。")).toBeInTheDocument();
    expect(input).toHaveValue("");
    expect(submitted).toEqual([{ name: "新世界", aspectRatio: "PORTRAIT_9_16" }, { name: "新世界", aspectRatio: "PORTRAIT_9_16" }]);
  });

  it("keeps loaded cards when the next page fails and retries the same cursor", async () => {
    let nextPageCalls = 0;
    server.use(http.get("/api/v1/projects", ({ request }) => {
      const cursor = new URL(request.url).searchParams.get("cursor");
      if (!cursor) return HttpResponse.json({ items: [project], nextCursor: "next-page" });
      expect(cursor).toBe("next-page"); nextPageCalls += 1;
      return nextPageCalls === 1 ? problem("下一页读取失败", 503) : HttpResponse.json({ items: [{ ...project, id: "project-b", name: "城市光影" }], nextCursor: null });
    }));
    showPage(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "加载更多项目" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("下一页读取失败");
    expect(screen.getByRole("article", { name: "森林漫游" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试读取" }));
    expect(await screen.findByRole("article", { name: "城市光影" })).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "森林漫游" })).toBeInTheDocument();
    expect(nextPageCalls).toBe(2);
  });

  it("keeps a failed rename open and closes it only after a successful CAS save", async () => {
    let saved = project; const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/projects", () => HttpResponse.json({ items: [saved] })),
      http.patch("/api/v1/projects/project-a", async ({ request }) => {
        submitted.push(await request.json());
        if (submitted.length === 1) return problem("项目版本冲突");
        saved = { ...project, name: "森林新章", version: 3 }; return HttpResponse.json(saved);
      }),
    );
    showPage(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "重命名 森林漫游" }));
    const input = screen.getByRole("textbox", { name: "项目新名称" });
    await user.clear(input); await user.type(input, "森林新章");
    await user.click(screen.getByRole("button", { name: "保存名称" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("项目版本冲突");
    expect(input).toHaveValue("森林新章");
    await user.click(screen.getByRole("button", { name: "保存名称" }));
    expect(await screen.findByRole("heading", { name: "森林新章" })).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "项目新名称" })).not.toBeInTheDocument();
    expect(submitted).toEqual([{ expectedVersion: 2, name: "森林新章" }, { expectedVersion: 2, name: "森林新章" }]);
  });

  it("preserves the rename draft and its version when a refreshed project has changed", async () => {
    let saved = project;
    server.use(http.get("/api/v1/projects", () => HttpResponse.json({ items: [saved] })));
    showPage(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "重命名 森林漫游" }));
    const input = screen.getByRole("textbox", { name: "项目新名称" });
    await user.clear(input); await user.type(input, "我的修改");
    saved = { ...project, name: "另一处修改", version: 3 };
    await user.click(screen.getByRole("button", { name: "刷新项目" }));
    expect(await screen.findByText("项目已更新。请取消后重新编辑，以载入最新名称和版本。")).toBeInTheDocument();
    expect(input).toHaveValue("我的修改"); expect(screen.getByRole("button", { name: "保存名称" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "取消" }));
    await user.click(screen.getByRole("button", { name: "重命名 另一处修改" }));
    expect(screen.getByRole("textbox", { name: "项目新名称" })).toHaveValue("另一处修改");
  });

  it("disables duplicate archive commands and keeps the card on failure", async () => {
    let finish: (() => void) | undefined; let calls = 0;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(http.post("/api/v1/projects/project-a/archive", async ({ request }) => {
      calls += 1; expect(await request.json()).toEqual({ expectedVersion: 2 });
      await pending; return problem("归档失败");
    }));
    showPage(); const user = userEvent.setup();
    const archive = await screen.findByRole("button", { name: "归档 森林漫游" });
    await user.click(archive);
    expect(archive).toBeDisabled(); expect(screen.getByRole("button", { name: "重命名 森林漫游" })).toBeDisabled();
    await user.click(archive); finish?.();
    expect(await screen.findByRole("alert")).toHaveTextContent("归档失败");
    expect(calls).toBe(1); expect(screen.getByRole("article", { name: "森林漫游" })).toBeInTheDocument();
  });

  it("retries list errors and shows archived cards without write actions", async () => {
    let calls = 0;
    server.use(http.get("/api/v1/projects", ({ request }) => {
      calls += 1; if (calls === 1) return problem("读取失败", 503);
      const archived = new URL(request.url).searchParams.get("includeArchived") === "true";
      return HttpResponse.json({ items: archived ? [{ ...project, status: "ARCHIVED" }] : [] });
    }));
    showPage(); const user = userEvent.setup();
    expect(await screen.findByRole("alert")).toHaveTextContent("读取失败");
    await user.click(screen.getByRole("button", { name: "重试读取" }));
    expect(await screen.findByText("你的第一个项目，从这里开始")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "显示已归档" }));
    const card = await screen.findByRole("article", { name: "森林漫游" });
    expect(within(card).getByText("已归档")).toBeInTheDocument();
    expect(within(card).queryByRole("button")).not.toBeInTheDocument();
  });

  it("does not redirect a temporary session failure to login", async () => {
    server.use(http.get("/api/v1/auth/me", () => problem("服务暂不可用", 503)));
    showPage();
    expect(await screen.findByRole("button", { name: "重试连接" })).toBeInTheDocument();
    expect(screen.queryByText("登录页")).not.toBeInTheDocument();
  });

  it("redirects an expired session to login", async () => {
    server.use(http.get("/api/v1/auth/me", () => problem("登录已失效", 401)));
    showPage(); await waitFor(() => expect(screen.getByText("登录页")).toBeInTheDocument());
  });
});
