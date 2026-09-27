import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Artifact, CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { MediaCardUpload } from "./MediaCardUpload";

const PROJECT_ID = "project-1";
const ARTIFACT_ID = "empty-image-1";
const CANVAS_ITEM_ID = "canvas-image-1";
const ASSET_ID = "uploaded-asset-1";
const UPLOADED_VERSION_ID = "uploaded-version-1";
const ORIGINAL_VERSION = 7;
const REVISED_VERSION = 8;
const NOW = "2026-09-26T00:00:00Z";
const UPLOAD_URL = `/api/v1/projects/${PROJECT_ID}/assets`;
const ARTIFACT_URL = `/api/v1/projects/${PROJECT_ID}/artifacts/${ARTIFACT_ID}`;
const REVISION_URL = `/api/v1/projects/${PROJECT_ID}/artifacts/${ARTIFACT_ID}/revisions`;
const SELECT_URL = `/api/v1/projects/${PROJECT_ID}/canvas-items/${CANVAS_ITEM_ID}/select-version`;
const EMPTY_IMAGE: Artifact = {
  id: ARTIFACT_ID, projectId: PROJECT_ID, kind: "IMAGE", title: "空图片卡片",
  resourceDefaultVersionId: null, resourceDefaultVersion: null, version: ORIGINAL_VERSION,
  createdAt: NOW, updatedAt: NOW,
};
const REVISED_IMAGE: Artifact = {
  ...EMPTY_IMAGE, resourceDefaultVersionId: UPLOADED_VERSION_ID, version: REVISED_VERSION,
  resourceDefaultVersion: {
    id: UPLOADED_VERSION_ID, versionNo: 1, schemaVersion: 1,
    content: { sourceType: "UPLOAD", assetId: ASSET_ID }, inputReferences: [],
    createdByKind: "USER", runId: null, createdAt: NOW,
  },
};
const ITEM: CanvasItem = {
  id: CANVAS_ITEM_ID, subjectType: "ARTIFACT", subjectId: ARTIFACT_ID, title: "空图片卡片",
  x: 0, y: 0, width: 320, height: 320, zIndex: 0, groupId: null, locked: false,
  selectedVersionId: null, selectedVersion: null, version: 0, artifact: EMPTY_IMAGE, agent: null,
};

function mockUpload(onUpload: () => void) {
  server.use(http.get("/api/v1/auth/csrf", () =>
    HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })));
  // Match the existing image-upload tests: inspect browser FormData before Node's
  // fetch implementation tries to serialize jsdom's File objects.
  const interceptedFetch = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (input === UPLOAD_URL) {
      expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("test-token");
      expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
      expect(init?.body).toBeInstanceOf(FormData);
      expect((init?.body as FormData).get("file")).toHaveProperty("name", "reference.webp");
      onUpload();
      return HttpResponse.json({ id: ASSET_ID, mediaKind: "IMAGE" }, { status: 201 });
    }
    return interceptedFetch(input, init);
  });
}

function mountUpload() {
  const onDone = vi.fn();
  const client = createQueryClient();
  render(<QueryClientProvider client={client}>
    <MediaCardUpload artifact={EMPTY_IMAGE} item={ITEM} onDone={onDone} />
  </QueryClientProvider>);
  return onDone;
}

function imageFile() {
  return new File(["image bytes validated by the backend"], "reference.webp", { type: "image/webp" });
}

function submitUpload() {
  const button = screen.getByRole("button", { name: "上传到此卡片" });
  expect(button).toBeEnabled();
  // jsdom does not connect userEvent's FileList to native required validation.
  // Dispatch the form event as in the workspace's existing upload tests.
  const form = button.closest("form");
  if (!form) throw new Error("Upload form is missing");
  fireEvent.submit(form);
}

describe("MediaCardUpload", () => {
  it("fills the existing empty IMAGE and selects the exact revision with its returned CAS version", async () => {
    const operations: string[] = [];
    mockUpload(() => operations.push("upload"));
    server.use(
      http.post(REVISION_URL, async ({ request }) => {
        operations.push("revise");
        expect(await request.json()).toEqual({ expectedVersion: ORIGINAL_VERSION,
          content: { sourceType: "UPLOAD", assetId: ASSET_ID } });
        return HttpResponse.json(REVISED_IMAGE, { status: 201 });
      }),
      http.post(SELECT_URL, async ({ request }) => {
        operations.push("select");
        expect(await request.json()).toEqual({ versionId: UPLOADED_VERSION_ID,
          expectedVersion: ITEM.version });
        return HttpResponse.json(REVISED_IMAGE);
      }),
    );
    const onDone = mountUpload();
    const user = userEvent.setup();

    await user.upload(screen.getByLabelText("选择图片"), imageFile());
    submitUpload();

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(operations).toEqual(["upload", "revise", "select"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("retries selection without uploading bytes or creating the completed revision again", async () => {
    const operations: string[] = [];
    let selections = 0;
    mockUpload(() => operations.push("upload"));
    server.use(
      http.post(REVISION_URL, () => {
        operations.push("revise");
        return HttpResponse.json(REVISED_IMAGE, { status: 201 });
      }),
      http.post(SELECT_URL, async ({ request }) => {
        operations.push("select");
        selections++;
        expect(await request.json()).toEqual({ versionId: UPLOADED_VERSION_ID,
          expectedVersion: ITEM.version });
        if (selections === 1) return HttpResponse.json({ title: "暂时不可用", detail: "选用请求暂时失败。",
          code: "SERVICE_UNAVAILABLE", retryable: true },
        { status: 503, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json(REVISED_IMAGE);
      }),
    );
    const onDone = mountUpload();
    const user = userEvent.setup();
    const file = imageFile();
    const input = screen.getByLabelText<HTMLInputElement>("选择图片");
    await user.upload(input, file);
    submitUpload();

    expect(await screen.findByRole("alert")).toHaveTextContent("选用请求暂时失败。");
    expect(input.files?.[0]).toBe(file);
    expect(onDone).not.toHaveBeenCalled();
    submitUpload();

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(operations).toEqual(["upload", "revise", "select", "select"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("recovers a committed revision after its response fails without submitting the upload or revision again", async () => {
    const operations: string[] = [];
    mockUpload(() => operations.push("upload"));
    server.use(
      http.post(REVISION_URL, async ({ request }) => {
        operations.push("revise");
        expect(await request.json()).toEqual({ expectedVersion: ORIGINAL_VERSION,
          content: { sourceType: "UPLOAD", assetId: ASSET_ID } });
        return HttpResponse.json({ title: "响应失败", detail: "版本已经提交，响应暂时不可用。",
          code: "SERVICE_UNAVAILABLE", retryable: true },
        { status: 503, headers: { "Content-Type": "application/problem+json" } });
      }),
      http.get(ARTIFACT_URL, () => {
        operations.push("read-current");
        return HttpResponse.json(REVISED_IMAGE);
      }),
      http.post(SELECT_URL, async ({ request }) => {
        operations.push("select");
        expect(await request.json()).toEqual({ versionId: UPLOADED_VERSION_ID,
          expectedVersion: ITEM.version });
        return HttpResponse.json(REVISED_IMAGE);
      }),
    );
    const onDone = mountUpload();
    const user = userEvent.setup();
    await user.upload(screen.getByLabelText("选择图片"), imageFile());
    submitUpload();

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(operations).toEqual(["upload", "revise", "read-current", "select"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it.each(["revision", "selection"] as const)("retains the selected file after a %s conflict", async (conflictStage) => {
    const operations: string[] = [];
    const conflict = () => HttpResponse.json({ title: "版本冲突", detail: "卡片已被其他编辑更新。",
      code: "ARTIFACT_VERSION_CONFLICT", retryable: false },
    { status: 409, headers: { "Content-Type": "application/problem+json" } });
    mockUpload(() => operations.push("upload"));
    server.use(
      http.get(ARTIFACT_URL, () => HttpResponse.json(EMPTY_IMAGE)),
      http.post(REVISION_URL, () => {
        operations.push("revise");
        return conflictStage === "revision" ? conflict() : HttpResponse.json(REVISED_IMAGE, { status: 201 });
      }),
      http.post(SELECT_URL, () => {
        operations.push("select");
        return conflict();
      }),
    );
    const onDone = mountUpload();
    const user = userEvent.setup();
    const file = imageFile();
    const input = screen.getByLabelText<HTMLInputElement>("选择图片");
    await user.upload(input, file);
    submitUpload();

    expect(await screen.findByRole("alert")).toHaveTextContent("卡片已有更新，文件已保留");
    expect(input.files).toHaveLength(1);
    expect(input.files?.[0]).toBe(file);
    expect(input).toBeEnabled();
    expect(screen.getByRole("button", { name: "上传到此卡片" })).toBeEnabled();
    expect(onDone).not.toHaveBeenCalled();
    expect(operations).toEqual(conflictStage === "revision" ? ["upload", "revise"] : ["upload", "revise", "select"]);
  });
});
