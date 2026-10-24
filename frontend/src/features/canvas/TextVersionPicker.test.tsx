import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import { http,HttpResponse } from "msw";
import { describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ArtifactVersionList } from "../../shared/api/client";
import { clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { TextVersionPicker } from "./TextVersionPicker";
import type { VersionedArtifact } from "./versionedArtifact";

const base = "/api/v1/projects/project-1/artifacts/text-1";
const version = (id: string, versionNo: number, text: string): ArtifactVersionList["items"][number] => ({
  id, versionNo, schemaVersion: 1, content: { format: "PLAIN_TEXT", text },
  createdByKind: "USER", createdAt: "2026-10-24T00:00:00Z", inputReferences: [],
});
const current = version("v2", 2, "第二版正文");
const previous = version("v1", 1, "第一版正文");
const artifact: VersionedArtifact = {
  id: "text-1", projectId: "project-1", kind: "TEXT", title: "剧本", version: 7,
  resourceDefaultVersionId: current.id, resourceDefaultVersion: current,
  createdAt: current.createdAt, updatedAt: current.createdAt,
};

function setup({ disabled = false, read = () => HttpResponse.json({ items: [current, previous] }) }:
  { disabled?: boolean; read?: () => Response | Promise<Response> } = {}) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  server.use(http.get(`${base}/versions`, read), http.get("/api/v1/auth/csrf", () => HttpResponse.json({
    headerName: "X-XSRF-TOKEN", token: "test",
  })));
  render(<QueryClientProvider client={client}><TextVersionPicker artifact={artifact} disabled={disabled} /></QueryClientProvider>);
  return client;
}

describe("TextVersionPicker", () => {
  it("reads on demand and shows text, source, timestamp and a disabled current version", async () => {
    const read = vi.fn(() => HttpResponse.json({ items: [current, previous] }));
    setup({ read });
    expect(read).not.toHaveBeenCalled();
    await clickControl(screen.getByRole("button", { name: "版本 v2" }));
    const menu = await screen.findByRole("menu", { name: "文字版本" });
    expect(await within(menu).findByText("2 个版本")).toBeVisible();
    expect(within(menu).getByText("版本历史与选用")).toBeVisible();
    const selected = within(menu).getByRole("menuitem", { name: "v2 当前选用" });
    expect(selected).toHaveAttribute("aria-current", "true");
    expect(selected).toHaveAttribute("aria-disabled", "true");
    expect(selected).toHaveTextContent("第二版正文");
    expect(selected).toHaveTextContent("用户编辑");
    expect(selected.querySelector("time")).toHaveAttribute("datetime", current.createdAt);
    expect(within(menu).getByText("选用旧版本会改变资源库默认内容，不修改历史版本。")).toBeVisible();
  });

  it("bounds menu summaries and accessible descriptions while retaining complete immutable text", async () => {
    const fullText = `  长正文\n\n${"正文段落 ".repeat(4_000)}结束标记`;
    const longVersion = version("v2", 2, fullText);
    const client = setup({ read: () => HttpResponse.json({ items: [longVersion, previous] }) });
    await clickControl(screen.getByRole("button", { name: "版本 v2" }));
    const selected = await screen.findByRole("menuitem", { name: "v2 当前选用" });
    const summary = selected.querySelector(".canvas-version-summary");
    expect(summary?.textContent?.length).toBeLessThanOrEqual(240);
    expect(summary).toHaveTextContent(/^长正文 正文段落/);
    expect(summary).not.toHaveTextContent("结束标记");
    expect(summary).toHaveAttribute("title", summary?.textContent);
    expect(selected).toHaveAccessibleDescription(/长正文 正文段落.*用户编辑/);
    const descriptionIds = selected.getAttribute("aria-describedby")?.split(" ") ?? [];
    expect(descriptionIds).toHaveLength(2);
    expect(descriptionIds.every((id) => selected.contains(document.getElementById(id)))).toBe(true);
    const cached = client.getQueryData<ArtifactVersionList>(["artifact-versions", "project-1", "text-1"]);
    expect(cached?.items[0]?.content).toEqual({ format: "PLAIN_TEXT", text: fullText });
  });

  it("preserves the editing guard without writing a selected historical version", async () => {
    let writes = 0;
    server.use(http.post(`${base}/set-default-version`, () => { writes++; return HttpResponse.json(artifact); }));
    setup({ disabled: true });
    await clickControl(screen.getByRole("button", { name: "版本 v2" }));
    const old = await screen.findByRole("menuitem", { name: "v1 选用此版本" });
    expect(screen.getByRole("note")).toHaveTextContent("请先保存或退出编辑，再切换版本。");
    expect(old).toHaveAttribute("aria-disabled", "true");
    await clickControl(old);
    expect(writes).toBe(0);
    expect(screen.getByRole("menu")).toBeVisible();
  });

  it("keeps the menu open after a failed history read and retries the read", async () => {
    let attempts = 0;
    setup({ read: () => ++attempts === 1 ? HttpResponse.json({ detail: "暂时无法读取" }, { status: 503 })
      : HttpResponse.json({ items: [current, previous] }) });
    await clickControl(screen.getByRole("button", { name: "版本 v2" }));
    expect(await screen.findByRole("alert")).toBeVisible();
    await clickControl(screen.getByRole("button", { name: "重试读取" }));
    expect(await screen.findByRole("menuitem", { name: "v1 选用此版本" })).toBeVisible();
    expect(attempts).toBe(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows switching state, disables writes until completion and retains artifact CAS", async () => {
    let complete: (value: Response) => void = () => {};
    const response = new Promise<Response>((resolve) => { complete = resolve; });
    let request: unknown;
    server.use(http.post(`${base}/set-default-version`, async ({ request: incoming }) => {
      request = await incoming.json();
      return response;
    }));
    const client = setup();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    await clickControl(screen.getByRole("button", { name: "版本 v2" }));
    await clickControl(await screen.findByRole("menuitem", { name: "v1 选用此版本" }));
    expect(await screen.findByRole("status", { name: "选用中…" })).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "v1 选用此版本" })).toHaveAttribute("aria-disabled", "true");
    await waitFor(() => expect(request).toEqual({ versionId: "v1", expectedVersion: 7 }));
    complete(HttpResponse.json(artifact));
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["canvas", "project-1"] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["artifact-versions", "project-1", "text-1"] });
  });

  it("retains the current result and menu on conflict, and restores focus on Escape", async () => {
    server.use(http.post(`${base}/set-default-version`, () => HttpResponse.json({
      code: "VERSION_CONFLICT", detail: "内容已更新", title: "版本冲突",
    }, { status: 409, headers: { "content-type": "application/problem+json" } })));
    setup();
    const trigger = screen.getByRole("button", { name: "版本 v2" });
    await clickControl(trigger);
    await clickControl(await screen.findByRole("menuitem", { name: "v1 选用此版本" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("内容有冲突，未切换版本；请重新打开版本列表后重试。");
    expect(screen.getByRole("menuitem", { name: "v2 当前选用" })).toHaveAttribute("aria-current", "true");
    fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});
