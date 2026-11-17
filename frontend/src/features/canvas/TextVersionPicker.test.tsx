import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { listCanvasItems, type Artifact, type ArtifactVersionList, type CanvasItem } from "../../shared/api/client";
import userEvent from "@testing-library/user-event";
import { useCanvasStore } from "./canvasStore";
import { clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { TextVersionPicker } from "./TextVersionPicker";
import type { VersionedArtifact } from "./versionedArtifact";

const base = "/api/v1/projects/project-1/artifacts/text-1";
const version = (id: string, versionNo: number, text: string): ArtifactVersionList["items"][number] => ({
  id,
  versionNo,
  schemaVersion: 1,
  content: { format: "PLAIN_TEXT", text },
  createdByKind: "USER",
  createdAt: "2026-10-24T00:00:00Z",
  inputReferences: [],
});
const current = version("v2", 2, "第二版正文");
const previous = version("v1", 1, "第一版正文");
const artifact: VersionedArtifact = {
  id: "text-1",
  projectId: "project-1",
  kind: "TEXT",
  title: "剧本",
  version: 7,
  resourceDefaultVersionId: current.id,
  resourceDefaultVersion: current,
  createdAt: current.createdAt,
  updatedAt: current.createdAt,
};

function setup({
  disabled = false,
  read = () => HttpResponse.json({ items: [current, previous] }),
}: { disabled?: boolean; read?: () => Response | Promise<Response> } = {}) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  server.use(
    http.get(`${base}/versions`, read),
    http.get("/api/v1/auth/csrf", () =>
      HttpResponse.json({
        headerName: "X-XSRF-TOKEN",
        token: "test",
      }),
    ),
  );
  render(
    <QueryClientProvider client={client}>
      <TextVersionPicker artifact={artifact} disabled={disabled} />
    </QueryClientProvider>,
  );
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
    expect(descriptionIds.every(id => selected.contains(document.getElementById(id)))).toBe(true);
    const cached = client.getQueryData<ArtifactVersionList>(["artifact-versions", "project-1", "text-1"]);
    expect(cached?.items[0]?.content).toEqual({ format: "PLAIN_TEXT", text: fullText });
  });

  it("preserves the editing guard without writing a selected historical version", async () => {
    let writes = 0;
    server.use(
      http.post(`${base}/set-default-version`, () => {
        writes++;
        return HttpResponse.json(artifact);
      }),
    );
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
    setup({
      read: () =>
        ++attempts === 1
          ? HttpResponse.json({ detail: "暂时无法读取" }, { status: 503 })
          : HttpResponse.json({ items: [current, previous] }),
    });
    await clickControl(screen.getByRole("button", { name: "版本 v2" }));
    expect(await screen.findByRole("alert")).toBeVisible();
    await clickControl(screen.getByRole("button", { name: "重试读取" }));
    expect(await screen.findByRole("menuitem", { name: "v1 选用此版本" })).toBeVisible();
    expect(attempts).toBe(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows switching state, disables writes until completion and retains artifact CAS", async () => {
    let complete: (value: Response) => void = () => {};
    const response = new Promise<Response>(resolve => {
      complete = resolve;
    });
    let request: unknown;
    server.use(
      http.post(`${base}/set-default-version`, async ({ request: incoming }) => {
        request = await incoming.json();
        return response;
      }),
    );
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
    server.use(
      http.post(`${base}/set-default-version`, () =>
        HttpResponse.json(
          {
            code: "VERSION_CONFLICT",
            detail: "内容已更新",
            title: "版本冲突",
          },
          { status: 409, headers: { "content-type": "application/problem+json" } },
        ),
      ),
    );
    setup();
    const trigger = screen.getByRole("button", { name: "版本 v2" });
    await clickControl(trigger);
    await clickControl(await screen.findByRole("menuitem", { name: "v1 选用此版本" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("内容有冲突，未切换版本；请刷新版本，再重新选择。");
    expect(screen.getByRole("menuitem", { name: "v2 当前选用" })).toHaveAttribute("aria-current", "true");
    fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    await waitFor(() => expect(trigger).toHaveFocus());
  });
});

{
  const CREATED_AT = "2026-10-11T00:00:00Z";
  const versions: NonNullable<Artifact["resourceDefaultVersion"]>[] = [1, 2].map(versionNo => ({
    id: `version-${versionNo}`,
    versionNo,
    schemaVersion: 1,
    content: { format: "PLAIN_TEXT", text: `正文 ${versionNo}` },
    createdByKind: "USER",
    inputReferences: [],
    createdAt: CREATED_AT,
  }));
  const initial: VersionedArtifact = {
    id: "artifact-1",
    projectId: "project-1",
    kind: "TEXT",
    title: "Notes",
    version: 3,
    resourceDefaultVersionId: "version-2",
    resourceDefaultVersion: versions[1]!,
    createdAt: CREATED_AT,
    updatedAt: CREATED_AT,
  };
  const draftKey = "project-1:node-1";

  function fixture() {
    const state = {
      artifact: initial,
      readFails: false,
      canvasFails: false,
      conflict: false,
      historyReads: 0,
      canvasReads: 0,
      writes: [] as { versionId: string; expectedVersion: number }[],
    };
    server.use(
      http.get("/api/v1/projects/:projectId/canvas/items", () => {
        state.canvasReads++;
        if (state.canvasFails)
          return HttpResponse.json(
            { detail: "合成画布读取失败" },
            { status: 503, headers: { "Content-Type": "application/problem+json" } },
          );
        return HttpResponse.json({ items: [{ id: "node-1", artifact: state.artifact }] });
      }),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () => {
        state.historyReads++;
        return state.readFails
          ? HttpResponse.json(
              { detail: "合成历史读取失败" },
              { status: 503, headers: { "Content-Type": "application/problem+json" } },
            )
          : HttpResponse.json({ items: versions, nextCursor: null });
      }),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/set-default-version", async ({ request }) => {
        const input = (await request.json()) as { versionId: string; expectedVersion: number };
        state.writes.push(input);
        if (state.conflict) {
          state.conflict = false;
          state.artifact = { ...state.artifact, version: 4 };
        }
        if (input.expectedVersion !== state.artifact.version)
          return HttpResponse.json({ code: "VERSION_CONFLICT" }, { status: 409 });
        state.artifact = {
          ...state.artifact,
          version: state.artifact.version + 1,
          resourceDefaultVersionId: input.versionId,
          resourceDefaultVersion: versions.find(v => v.id === input.versionId)!,
        };
        return HttpResponse.json(state.artifact);
      }),
    );
    return state;
  }
  function show() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    function Canvas() {
      const canvas = useQuery({ queryKey: ["canvas", "project-1"], queryFn: () => listCanvasItems("project-1") });
      const artifact = canvas.data?.items[0]?.artifact as VersionedArtifact | undefined;
      return artifact ? <TextVersionPicker artifact={artifact} /> : null;
    }
    render(
      <QueryClientProvider client={client}>
        <Canvas />
      </QueryClientProvider>,
    );
    return client;
  }
  async function open(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole("button", { name: "版本 v2" }));
    return screen.findByRole("menu", { name: "文字版本" });
  }

  describe("TextVersionPicker recovery", () => {
    beforeEach(() => {
      useCanvasStore.getState().clearTextGenerationDrafts();
      useCanvasStore.getState().setTextGenerationPrompt(draftKey, "未提交的创作提示词");
    });
    it("retries failed history reads in place without selecting a version", async () => {
      const state = fixture();
      state.readFails = true;
      show();
      const user = userEvent.setup();
      const menu = await open(user);
      expect(await within(menu).findByText("合成历史读取失败")).toBeVisible();
      state.readFails = false;
      await user.click(within(menu).getByRole("button", { name: "重试读取" }));
      expect(await within(menu).findByRole("menuitem", { name: /v1/ })).toBeVisible();
      expect(state.historyReads).toBe(2);
      expect(state.writes).toEqual([]);
      expect(screen.getByRole("button", { name: "版本 v2" })).toBeVisible();
      expect(useCanvasStore.getState().textGenerationDrafts[draftKey]?.prompt).toBe("未提交的创作提示词");
    });
    it("refreshes the artifact CAS after a conflict and waits for explicit reselection", async () => {
      const state = fixture();
      state.conflict = true;
      const client = show();
      const user = userEvent.setup();
      const menu = await open(user);
      await user.click(await within(menu).findByRole("menuitem", { name: /v1/ }));
      expect(await within(menu).findByText(/内容有冲突/)).toBeVisible();
      expect(state.writes).toEqual([{ versionId: "version-1", expectedVersion: 3 }]);
      await user.click(within(menu).getByRole("button", { name: "刷新版本" }));
      await waitFor(() => expect(within(menu).queryByText(/内容有冲突/)).not.toBeInTheDocument());
      expect(client.getQueryData<{ items: CanvasItem[] }>(["canvas", "project-1"])?.items[0]?.artifact?.version).toBe(
        4,
      );
      expect(state.writes).toHaveLength(1);
      expect(screen.getByRole("button", { name: "版本 v2" })).toBeVisible();
      expect(useCanvasStore.getState().textGenerationDrafts[draftKey]?.prompt).toBe("未提交的创作提示词");
      await user.click(within(menu).getByRole("menuitem", { name: /v1/ }));
      await waitFor(() =>
        expect(state.writes).toEqual([
          { versionId: "version-1", expectedVersion: 3 },
          { versionId: "version-1", expectedVersion: 4 },
        ]),
      );
      expect(await screen.findByRole("button", { name: "版本 v1" })).toBeVisible();
    });
    it("waits for every refresh read to settle and blocks selection after a failed refresh", async () => {
      const state = fixture();
      state.conflict = true;
      const client = show();
      const user = userEvent.setup();
      const menu = await open(user);
      await user.click(await within(menu).findByRole("menuitem", { name: /v1/ }));
      await within(menu).findByText(/内容有冲突/);
      let finishSnapshot: (() => void) | undefined;
      const snapshot = new Promise<void>(resolve => {
        finishSnapshot = resolve;
      });
      const invalidate = client.invalidateQueries.bind(client);
      const refreshRead = vi
        .spyOn(client, "invalidateQueries")
        .mockImplementation((filters, options) =>
          filters?.queryKey?.[0] === "snapshot" ? snapshot : invalidate(filters, options),
        );
      state.canvasFails = true;
      await user.click(within(menu).getByRole("button", { name: "刷新版本" }));
      await waitFor(() => expect(state.canvasReads).toBe(2));
      expect(within(menu).getByRole("button", { name: "刷新版本" })).toBeDisabled();
      const previous = within(menu).getByRole("menuitem", { name: /v1/ });
      expect(previous).toHaveAttribute("aria-disabled", "true");
      expect(within(menu).getByText(/内容有冲突/)).toBeVisible();
      finishSnapshot?.();
      expect(await within(menu).findByText("合成画布读取失败")).toBeVisible();
      expect(previous).toHaveAttribute("aria-disabled", "true");
      await user.click(previous);
      expect(state.writes).toHaveLength(1);
      expect(refreshRead).toHaveBeenCalledWith({ queryKey: ["snapshot", "project-1"] }, { throwOnError: true });
      state.canvasFails = false;
      await user.click(within(menu).getByRole("button", { name: "刷新版本" }));
      await waitFor(() => expect(within(menu).queryByText(/内容有冲突/)).not.toBeInTheDocument());
      expect(previous).not.toHaveAttribute("aria-disabled", "true");
      expect(state.writes).toHaveLength(1);
    });
    it("keeps the conflict and current result when canvas refresh fails, then recovers with read-only retry", async () => {
      const state = fixture();
      state.conflict = true;
      show();
      const user = userEvent.setup();
      const menu = await open(user);
      await user.click(await within(menu).findByRole("menuitem", { name: /v1/ }));
      await within(menu).findByText(/内容有冲突/);
      state.canvasFails = true;
      await user.click(within(menu).getByRole("button", { name: "刷新版本" }));
      expect(await within(menu).findByText("合成画布读取失败")).toBeVisible();
      expect(within(menu).getByText(/内容有冲突/)).toBeVisible();
      expect(state.writes).toHaveLength(1);
      state.canvasFails = false;
      await user.click(within(menu).getByRole("button", { name: "刷新版本" }));
      await waitFor(() => expect(within(menu).queryByText(/内容有冲突/)).not.toBeInTheDocument());
      expect(state.writes).toHaveLength(1);
      expect(useCanvasStore.getState().textGenerationDrafts[draftKey]?.prompt).toBe("未提交的创作提示词");
    });
  });
}
