import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render,screen,waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { assetThumbnailUrl,type CanvasCommand,type ResourceResult } from "../../shared/api/client";
import { server } from "../../test/server";
import { ProjectResourceBrowser } from "./ProjectResourceBrowser";

vi.mock("./MediaPreviewDialog", () => ({ MediaPreviewDialog: ({ sourceUrl,title,onClose }: {
  sourceUrl: string; title: string; onClose: () => void;
}) => <div role="dialog" aria-label={title} data-source={sourceUrl}><button onClick={onClose}>Close preview</button></div> }));

const result: ResourceResult = { projectId: "project-1", projectName: "Project", artifactId: "shared-image",
  versionId: "result-old", title: "Sea", kind: "IMAGE", versionNo: 2,
  content: { sourceType: "UPLOAD", assetId: "old-asset" }, createdAt: "2026-10-01T00:00:00Z" };
const newer: ResourceResult = { ...result, versionId: "result-new", versionNo: 5,
  content: { sourceType: "UPLOAD", assetId: "new-asset" } };

function renderBrowser() {
  const onPlaced = vi.fn();
  const onPlacing = vi.fn(); const onPlaceError = vi.fn();
  const position = vi.fn(() => ({ x: 120, y: 80 }));
  render(<QueryClientProvider client={createQueryClient()}><ProjectResourceBrowser
    projectId="project-1" position={position} zIndex={4} onPlaced={onPlaced} onPlacing={onPlacing} onPlaceError={onPlaceError} /></QueryClientProvider>);
  return { onPlaced, onPlacing, onPlaceError, position, user: userEvent.setup() };
}

describe("ProjectResourceBrowser", () => {
  beforeEach(() => {
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.get("/api/v1/projects/:projectId/resources", () => HttpResponse.json({ items: [newer, result], nextCursor: null })));
  });

  it("shows exact result thumbnails and previews a historical result independently of any resource default", async () => {
    const { user } = renderBrowser();
    const old = await screen.findByRole("button", { name: "查看 Sea · v2" });
    expect(old.querySelector("img")).toHaveAttribute("src", assetThumbnailUrl("project-1", "old-asset"));
    expect(screen.getByRole("button", { name: "查看 Sea · v5" }).querySelector("img"))
      .toHaveAttribute("src", assetThumbnailUrl("project-1", "new-asset"));
    expect(screen.queryByText(/未设置默认结果/)).not.toBeInTheDocument();
    await user.click(old);
    expect(screen.getByRole("dialog", { name: "Sea" })).toHaveAttribute("data-source", "/api/v1/projects/project-1/assets/old-asset/content");
  });

  it("places the chosen historical version and reuses the same command after a lost response", async () => {
    const writes: CanvasCommand[][] = [];
    server.use(http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
      const body = await request.json() as { commands: CanvasCommand[] }; writes.push(body.commands);
      return writes.length === 1 ? HttpResponse.json({ detail: "Temporary failure" }, { status: 503 }) : HttpResponse.json({ items: [] });
    }));
    const { user,onPlaced,onPlacing,onPlaceError,position } = renderBrowser();
    await user.click(await screen.findByRole("button", { name: "放到画布：Sea · v2" }));
    await screen.findByRole("alert");
    position.mockReturnValue({ x: 700, y: 300 });
    await user.click(screen.getByRole("button", { name: "放到画布：Sea · v2" }));
    await waitFor(() => expect(onPlaced).toHaveBeenCalledOnce());
    expect(writes[0]?.[0]).toMatchObject({ type: "PLACE_ARTIFACT", artifactId: "shared-image", versionId: "result-old",
      x: 120, y: 80, width: 640, height: 360, zIndex: 4 });
    expect(writes[1]).toEqual(writes[0]);
    expect(position).toHaveBeenCalledOnce();
    expect(onPlacing).toHaveBeenCalledTimes(2); expect(onPlaceError).toHaveBeenCalledOnce();
  });

  it("copies the chosen text revision once and preserves it across a failed placement", async () => {
    const text: ResourceResult = { ...result, kind: "TEXT", artifactId: "text", versionId: "old-text", versionNo: 1,
      title: "Draft", content: { format: "MARKDOWN", text: "A historical paragraph" } };
    const creates = vi.fn(); const writes: CanvasCommand[][] = [];
    server.use(http.get("/api/v1/projects/:projectId/resources", () => HttpResponse.json({ items: [text], nextCursor: null })),
      http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
        creates(await request.json(), request.headers.get("Idempotency-Key"));
        return HttpResponse.json({ id: "independent-text" });
      }), http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        writes.push((await request.json() as { commands: CanvasCommand[] }).commands);
        return writes.length === 1 ? new HttpResponse(null, { status: 503 }) : HttpResponse.json({ items: [] });
      }));
    const { user,onPlaced } = renderBrowser();
    await user.click(await screen.findByRole("button", { name: "查看 Draft · v1" }));
    expect(screen.getByRole("dialog", { name: "Draft" })).toHaveTextContent("A historical paragraph");
    await user.click(screen.getAllByRole("button", { name: "放到画布：Draft · v1" }).at(-1)!);
    await waitFor(() => expect(writes).toHaveLength(1));
    await user.click(await screen.findByRole("button", { name: "放到画布：Draft · v1" }));
    await waitFor(() => expect(onPlaced).toHaveBeenCalledOnce());
    expect(creates).toHaveBeenCalledExactlyOnceWith({ kind: "TEXT", title: "Draft", content: text.content }, expect.any(String));
    expect(writes[0]?.[0]).toMatchObject({ type: "PLACE_ARTIFACT", artifactId: "independent-text", width: 420, height: 270 });
    expect(writes[0]?.[0]).not.toHaveProperty("versionId");
    expect(writes[1]).toEqual(writes[0]);
  });

  it("keeps failed thumbnails usable and uses a manual audio player without requesting an audio thumbnail", async () => {
    const audio: ResourceResult = { ...result, title: "Voice", versionId: "audio", kind: "AUDIO" };
    server.use(http.get("/api/v1/projects/:projectId/resources", () => HttpResponse.json({ items: [result,audio], nextCursor: null })));
    const { user } = renderBrowser();
    const old = await screen.findByRole("button", { name: "查看 Sea · v2" });
    fireEvent.error(old.querySelector("img")!);
    expect(old.querySelector("img")).toBeNull();
    const audioPreview = screen.getByRole("button", { name: "查看 Voice · v2" });
    expect(audioPreview.querySelector("img")).toBeNull();
    await user.click(audioPreview);
    const player = screen.getByRole("dialog", { name: "Voice" }).querySelector("audio");
    expect(player).toHaveAttribute("preload", "none"); expect(player).not.toHaveAttribute("autoplay");
  });

  it("pages and searches project results without fetching other projects or every artifact's history", async () => {
    const reads: string[] = [];
    server.use(http.get("/api/v1/projects/:projectId/resources", ({ request }) => {
      const url = new URL(request.url); reads.push(url.search);
      return HttpResponse.json(url.searchParams.get("query") ? { items: [], nextCursor: null }
        : url.searchParams.has("cursor") ? { items: [result], nextCursor: null } : { items: [newer], nextCursor: "older" });
    }));
    const { user } = renderBrowser();
    await user.click(await screen.findByRole("button", { name: "加载更多资源" }));
    await screen.findByRole("button", { name: "查看 Sea · v2" });
    await user.type(screen.getByRole("searchbox", { name: "搜索资源" }), "%");
    await screen.findByText("没有匹配的资源");
    expect(reads.some((query) => new URLSearchParams(query).get("cursor") === "older")).toBe(true);
    expect(new URLSearchParams(reads.at(-1)).get("query")).toBe("%");
  });

  it("keeps read failures distinct from empty content and supports an explicit retry", async () => {
    let attempts = 0;
    server.use(http.get("/api/v1/projects/:projectId/resources", () => ++attempts === 1
      ? new HttpResponse(null, { status: 403 }) : HttpResponse.json({ items: [], nextCursor: null })));
    const { user } = renderBrowser();
    await screen.findByRole("alert"); expect(screen.queryByText("还没有资源")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试读取资源" }));
    await screen.findByText("还没有资源");
    expect(screen.getByText(/空节点不会出现在这里/)).toBeInTheDocument();
  });
});
