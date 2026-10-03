import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasItem, MediaDraft } from "../../shared/api/client";
import { server } from "../../test/server";
import { VideoImageReferences, videoImageReferences } from "./VideoImageReferences";

const oldReference = { artifactId: "image", versionId: "old-version", role: "START_FRAME" as const, order: 0 };
const draftReference = { ...oldReference, versionId: "new-version", color: "blue", sources: [] };
function item(frozenInput: Record<string, unknown>): CanvasItem {
  return { id: "video-card", subjectType: "ARTIFACT", subjectId: "video", title: "Shot", x: 0, y: 0,
    width: 280, height: 180, zIndex: 0, groupId: null, locked: false, version: 0, artifact: null, agent: null,
    selectedVersionId: "video-version", selectedVersion: { id: "video-version", versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "video-asset" }, inputReferences: [], frozenInput, createdByKind: "AGENT", runId: "run", createdAt: "2026-10-03T00:00:00Z" } };
}
const draft: MediaDraft = { projectId: "project", canvasItemId: "video-card", prompt: "", parameters: {},
  videoInputMode: "START_END", mediaInputs: [draftReference], mentions: [], durationSeconds: 5, capabilityId: null, styleId: null,
  displayMode: "DRAFT", version: 2, createdAt: "2026-10-03T00:00:00Z", updatedAt: "2026-10-03T00:00:00Z" };
function show() { return render(<QueryClientProvider client={createQueryClient()}>
  <VideoImageReferences projectId="project" references={[oldReference]} />
</QueryClientProvider>); }
describe("VideoImageReferences", () => {
  it("keeps a result's exact frozen input when the draft or resource default changes", () => {
    expect(videoImageReferences(item({ images: [oldReference] }), draft, false)).toEqual([oldReference]);
    expect(videoImageReferences(item({ images: [oldReference] }), draft, true)).toEqual([draftReference]);
    expect(videoImageReferences(item({}), draft, false)).toEqual([]);
    expect(videoImageReferences(item({ images: [{ ...oldReference, role: "AUDIO_REFERENCE" }] }), draft, false)).toEqual([]);
  });
  it("opens the archived referenced image rather than the newer version", async () => {
    server.use(http.get("/api/v1/projects/project/artifacts/image/versions", () => HttpResponse.json({ items: [
      { id: "new-version", versionNo: 2, content: { assetId: "new-asset" } },
      { id: "old-version", versionNo: 1, content: { assetId: "old-asset" } },
    ] })));
    show();
    const button = await screen.findByRole("button", { name: "首帧 · v1" });
    expect(within(button).getByRole("img")).toHaveAttribute("src", "/api/v1/projects/project/assets/old-asset/thumbnail");
    await userEvent.setup().click(button);
    expect(within(screen.getByRole("dialog")).getByRole("img")).toHaveAttribute("src", "/api/v1/projects/project/assets/old-asset/content");
  });
  it("exposes a failed history read and retries without replacing the reference", async () => {
    let reads = 0;
    server.use(http.get("/api/v1/projects/project/artifacts/image/versions", () => {
      reads++;
      return reads === 1 ? HttpResponse.json({ status: 503 }, { status: 503 }) : HttpResponse.json({ items: [] });
    }));
    show(); await userEvent.setup().click(await screen.findByRole("button", { name: "重试" }));
    await screen.findByTitle("引用图片暂无可用预览");
    expect(screen.getByRole("button", { name: "首帧" })).toBeDisabled(); expect(reads).toBe(2);
  });
});
