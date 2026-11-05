import { QueryClient, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it } from "vitest";
import { listCanvasItems, type Artifact, type CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { TextVersionPicker } from "./TextVersionPicker";
import type { VersionedArtifact } from "./versionedArtifact";

const CREATED_AT = "2026-10-11T00:00:00Z";
const versions: NonNullable<Artifact["resourceDefaultVersion"]>[] = [1, 2].map((versionNo) => ({
  id: `version-${versionNo}`, versionNo, schemaVersion: 1,
  content: { format: "PLAIN_TEXT", text: `正文 ${versionNo}` }, createdByKind: "USER",
  inputReferences: [], createdAt: CREATED_AT,
}));
const initial: VersionedArtifact = { id: "artifact-1", projectId: "project-1", kind: "TEXT", title: "Notes",
  version: 3, resourceDefaultVersionId: "version-2", resourceDefaultVersion: versions[1]!,
  createdAt: CREATED_AT, updatedAt: CREATED_AT };
const draftKey = "project-1:node-1";

function fixture() {
  const state = { artifact: initial, readFails: false, canvasFails: false, conflict: false,
    historyReads: 0, canvasReads: 0, writes: [] as { versionId: string; expectedVersion: number }[] };
  server.use(
    http.get("/api/v1/projects/:projectId/canvas/items", () => {
      state.canvasReads++;
      if (state.canvasFails) return HttpResponse.json({ detail: "合成画布读取失败" }, { status: 503, headers: { "Content-Type": "application/problem+json" } });
      return HttpResponse.json({ items: [{ id: "node-1", artifact: state.artifact }] });
    }),
    http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () => {
      state.historyReads++;
      return state.readFails ? HttpResponse.json({ detail: "合成历史读取失败" }, { status: 503, headers: { "Content-Type": "application/problem+json" } })
        : HttpResponse.json({ items: versions, nextCursor: null });
    }),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
    http.post("/api/v1/projects/:projectId/artifacts/:artifactId/set-default-version", async ({ request }) => {
      const input = await request.json() as { versionId: string; expectedVersion: number };
      state.writes.push(input);
      if (state.conflict) { state.conflict = false; state.artifact = { ...state.artifact, version: 4 }; }
      if (input.expectedVersion !== state.artifact.version) return HttpResponse.json({ code: "VERSION_CONFLICT" }, { status: 409 });
      state.artifact = { ...state.artifact, version: state.artifact.version + 1,
        resourceDefaultVersionId: input.versionId, resourceDefaultVersion: versions.find((v) => v.id === input.versionId)! };
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
  render(<QueryClientProvider client={client}><Canvas /></QueryClientProvider>);
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
    const state = fixture(); state.readFails = true; show(); const user = userEvent.setup();
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
    const state = fixture(); state.conflict = true; const client = show(); const user = userEvent.setup();
    const menu = await open(user);
    await user.click(await within(menu).findByRole("menuitem", { name: /v1/ }));
    expect(await within(menu).findByText(/内容有冲突/)).toBeVisible();
    expect(state.writes).toEqual([{ versionId: "version-1", expectedVersion: 3 }]);
    await user.click(within(menu).getByRole("button", { name: "刷新版本" }));
    await waitFor(() => expect(within(menu).queryByText(/内容有冲突/)).not.toBeInTheDocument());
    expect(client.getQueryData<{ items: CanvasItem[] }>(["canvas", "project-1"])?.items[0]?.artifact?.version).toBe(4);
    expect(state.writes).toHaveLength(1);
    expect(screen.getByRole("button", { name: "版本 v2" })).toBeVisible();
    expect(useCanvasStore.getState().textGenerationDrafts[draftKey]?.prompt).toBe("未提交的创作提示词");
    await user.click(within(menu).getByRole("menuitem", { name: /v1/ }));
    await waitFor(() => expect(state.writes).toEqual([
      { versionId: "version-1", expectedVersion: 3 }, { versionId: "version-1", expectedVersion: 4 },
    ]));
    expect(await screen.findByRole("button", { name: "版本 v1" })).toBeVisible();
  });
  it("keeps the conflict and current result when canvas refresh fails, then recovers with read-only retry", async () => {
    const state = fixture(); state.conflict = true; show(); const user = userEvent.setup();
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
