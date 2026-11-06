import { QueryClientProvider } from "@tanstack/react-query";
import { render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { MemoryRouter,Route,Routes,useParams } from "react-router";
import { beforeEach,describe,expect,it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Project } from "../../shared/api/client";
import { selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { ProjectsPage } from "./ProjectsPage";

const project: Project = { id: "project-a", name: "森林漫游", aspectRatio: "LANDSCAPE_16_9", status: "ACTIVE", version: 2, createdAt: "2026-09-24T00:00:00Z", updatedAt: "2026-09-26T00:00:00Z" };
function showPage() {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/projects"]}><Routes><Route path="/projects" element={<ProjectsPage />} /><Route path="/projects/:projectId" element={<WorkspaceStub />} /><Route path="/login" element={<p>登录页</p>} /></Routes></MemoryRouter></QueryClientProvider>);
  return client;
}
function WorkspaceStub() { return <p>画布页：{useParams().projectId}</p>; }
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
    expect(screen.getByText("已加载项目中没有匹配项")).toBeInTheDocument();
    expect(screen.getByRole("searchbox")).toHaveAccessibleDescription("搜索仅涵盖已加载的 1 个项目，仍有项目未加载。");
    await user.click(screen.getByRole("button", { name: "加载更多项目" }));
    expect(await screen.findByRole("article", { name: "城市光影" })).toBeInTheDocument();
    expect(screen.queryByRole("article", { name: "森林漫游" })).not.toBeInTheDocument();
    expect(screen.getByText("2 个已加载")).toBeInTheDocument();
    expect(screen.getByRole("searchbox")).toHaveAccessibleDescription("当前列表的 2 个项目已全部加载。");
    expect(screen.queryByText("搜索仅涵盖已加载的 1 个项目，仍有项目未加载。")).not.toBeInTheDocument();
    expect(cursors).toEqual([null, "next-page"]);
  });

  it("preserves the create draft after failure and uses its chosen aspect on retry", async () => {
    const submitted: unknown[] = [];
    server.use(http.post("/api/v1/projects", async ({ request }) => {
      submitted.push(await request.json());
      return submitted.length === 1 ? problem("暂时无法创建", 503) : HttpResponse.json(project, { status: 201 });
    }));
    const client = showPage(); const user = userEvent.setup();
    const input = await screen.findByRole("textbox", { name: "项目名称" });
    await user.type(input, "新世界");
    await selectValue(screen.getByRole("combobox", { name: "画幅" }), "PORTRAIT_9_16");
    await user.click(screen.getByRole("button", { name: "创建项目" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("暂时无法创建");
    expect(input).toHaveValue("新世界");
    await user.click(screen.getByRole("button", { name: "创建项目" }));
    expect(await screen.findByText("画布页：project-a")).toBeInTheDocument();
    expect(client.getQueryData(["projects", project.id])).toEqual(project);
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
        if (submitted.length === 1) return problem("保存暂不可用", 503);
        saved = { ...project, name: "森林新章", version: 3 }; return HttpResponse.json(saved);
      }),
    );
    showPage(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "重命名 森林漫游" }));
    const input = screen.getByRole("textbox", { name: "项目新名称" });
    await user.clear(input); await user.type(input, "森林新章");
    await user.click(screen.getByRole("button", { name: "保存名称" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("保存暂不可用");
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
    expect(await screen.findByText("最新名称：另一处修改")).toBeInTheDocument();
    expect(input).toHaveValue("我的修改"); expect(screen.getByRole("button", { name: "保存名称" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "取消" }));
    await user.click(screen.getByRole("button", { name: "重命名 另一处修改" }));
    expect(screen.getByRole("textbox", { name: "项目新名称" })).toHaveValue("另一处修改");
  });

  it("refreshes a rename conflict without losing input and only saves the new baseline explicitly", async () => {
    let saved = project; const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/projects", () => HttpResponse.json({ items: [saved], nextCursor: null })),
      http.patch("/api/v1/projects/project-a", async ({ request }) => {
        submitted.push(await request.json());
        if (submitted.length === 1) { saved = { ...project, name: "另一处修改", version: 3 }; return problem("项目版本冲突"); }
        saved = { ...saved, name: "我的修改", version: 4 }; return HttpResponse.json(saved);
      }),
    );
    showPage(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "重命名 森林漫游" }));
    const input = screen.getByRole("textbox", { name: "项目新名称" });
    await user.clear(input); await user.type(input, "我的修改");
    await user.click(screen.getByRole("button", { name: "保存名称" }));
    expect(await screen.findByText("最新名称：另一处修改")).toBeVisible();
    expect(input).toHaveValue("我的修改");
    expect(screen.getByRole("button", { name: "保存名称" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "保留输入，继续编辑" }));
    expect(input).toHaveFocus(); expect(input).toHaveValue("我的修改");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(submitted).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "保存名称" }));
    expect(await screen.findByRole("heading", { name: "我的修改" })).toBeVisible();
    expect(submitted).toEqual([{ expectedVersion: 2, name: "我的修改" }, { expectedVersion: 3, name: "我的修改" }]);
  });

  it("opens the created project despite filters and a failed list refresh, without duplicate creation", async () => {
    let finish: (() => void) | undefined; const submitted: unknown[] = [];
    let created = false;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(
      http.get("/api/v1/projects", () => created ? problem("刷新列表失败", 503) : HttpResponse.json({ items: [project], nextCursor: null })),
      http.post("/api/v1/projects", async ({ request }) => {
        submitted.push(await request.json()); await pending; created = true;
        return HttpResponse.json({ ...project, id: "created-id", name: "新创作" }, { status: 201 });
      }),
    );
    showPage(); const user = userEvent.setup();
    await screen.findByRole("article", { name: "森林漫游" });
    await user.type(screen.getByRole("searchbox"), "不匹配");
    await user.click(screen.getByRole("checkbox", { name: "显示已归档" }));
    await user.type(screen.getByRole("textbox", { name: "项目名称" }), "新创作");
    const create = screen.getByRole("button", { name: "创建项目" });
    await user.dblClick(create);
    expect(create).toBeDisabled();
    await waitFor(() => expect(submitted).toHaveLength(1)); finish?.();
    expect(await screen.findByText("画布页：created-id")).toBeVisible();
    expect(submitted).toEqual([{ name: "新创作", aspectRatio: "LANDSCAPE_16_9" }]);
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

  it("retries list errors and offers deletion only on archived cards", async () => {
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
    expect(within(card).getByRole("button", { name: "删除 森林漫游" })).toBeEnabled();
    expect(within(card).queryByRole("button", { name: "重命名 森林漫游" })).not.toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "归档 森林漫游" })).not.toBeInTheDocument();
  });

  it("confirms deletion, supports cancellation and clears project data after success", async () => {
    let deleted = false; let calls = 0;
    server.use(
      http.get("/api/v1/projects", () => HttpResponse.json({ items: deleted ? [] : [{ ...project, status: "ARCHIVED" }] })),
      http.delete("/api/v1/projects/project-a", ({ request }) => {
        expect(new URL(request.url).searchParams.get("expectedVersion")).toBe("2");
        calls += 1; deleted = true; return new HttpResponse(null, { status: 204 });
      }),
    );
    const client = showPage(); const user = userEvent.setup();
    client.setQueryData(["project", project.id], project);
    client.setQueryData(["resources", {}], { items: [] });
    const trigger = await screen.findByRole("button", { name: "删除 森林漫游" });
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "删除项目「森林漫游」？" });
    expect(within(dialog).getByText(/已收藏的个人资产不受影响/)).toBeInTheDocument();
    expect(calls).toBe(0);
    await user.click(within(dialog).getByRole("button", { name: "取消" }));
    expect(trigger).toHaveFocus();
    await user.click(trigger);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(calls).toBe(0);
    await user.click(trigger);
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(screen.queryByRole("article", { name: "森林漫游" })).not.toBeInTheDocument());
    expect(calls).toBe(1);
    expect(client.getQueryData(["project", project.id])).toBeUndefined();
    expect(client.getQueryState(["resources", {}])?.isInvalidated).toBe(true);
  });

  it("keeps a failed deletion open, prevents duplicates while pending and retries the confirmed version", async () => {
    let finish: (() => void) | undefined; let calls = 0; let deleted = false;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(
      http.get("/api/v1/projects", () => HttpResponse.json({ items: deleted ? [] : [{ ...project, status: "ARCHIVED" }] })),
      http.delete("/api/v1/projects/project-a", async ({ request }) => {
        expect(new URL(request.url).searchParams.get("expectedVersion")).toBe("2"); calls += 1;
        if (calls === 1) { await pending; return problem("删除失败，请重试", 503); }
        deleted = true; return new HttpResponse(null, { status: 204 });
      }),
    );
    showPage(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "删除 森林漫游" }));
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    expect(screen.getByRole("button", { name: "正在删除…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "取消" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "正在删除…" }));
    finish?.();
    expect(await screen.findByRole("alert")).toHaveTextContent("删除失败，请重试");
    expect(calls).toBe(1);
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(calls).toBe(2);
  });

  it("does not silently delete a newer project version when the list refreshes during confirmation", async () => {
    let saved: Project = { ...project, status: "ARCHIVED" };
    server.use(http.get("/api/v1/projects", () => HttpResponse.json({ items: [saved] })));
    const client = showPage(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "删除 森林漫游" }));
    saved = { ...saved, version: 3 };
    await client.invalidateQueries({ queryKey: ["projects"] });
    expect(await screen.findByText("项目已更新，请取消后重新打开确认窗口。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "确认删除" })).toBeDisabled();
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
