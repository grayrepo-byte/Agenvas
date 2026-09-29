import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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
const NOW = "2026-09-26T00:00:00Z";
const UPLOAD_URL = `/api/v1/projects/${PROJECT_ID}/assets`;
const CARD_UPLOAD_URL = `/api/v1/projects/${PROJECT_ID}/canvas-items/${CANVAS_ITEM_ID}/upload-version`;
const EMPTY_IMAGE: Artifact = {
  id: ARTIFACT_ID, projectId: PROJECT_ID, kind: "IMAGE", title: "空图片卡片",
  resourceDefaultVersionId: null, resourceDefaultVersion: null, version: ORIGINAL_VERSION,
  createdAt: NOW, updatedAt: NOW,
};
const REVISED_IMAGE: Artifact = {
  ...EMPTY_IMAGE, resourceDefaultVersionId: UPLOADED_VERSION_ID,
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

function mountUpload(initialFile = imageFile()) {
  const onDone = vi.fn();
  const client = createQueryClient();
  render(<QueryClientProvider client={client}>
    <MediaCardUpload artifact={EMPTY_IMAGE} item={ITEM} initialFile={initialFile} onDone={onDone} />
  </QueryClientProvider>);
  return onDone;
}

function imageFile() {
  return new File(["image bytes validated by the backend"], "reference.webp", { type: "image/webp" });
}

function submitUpload() {
  const button = screen.getByRole("button", { name: "重试上传" });
  expect(button).toBeEnabled();
  // jsdom does not connect userEvent's FileList to native required validation.
  // Dispatch the form event as in the workspace's existing upload tests.
  const form = button.closest("form");
  if (!form) throw new Error("Upload form is missing");
  fireEvent.submit(form);
}

describe("MediaCardUpload", () => {
  it("uploads the chosen file immediately and uses a client-stable target id", async () => {
    const operations: string[] = [];
    mockUpload(() => operations.push("upload"));
    server.use(
      http.post(CARD_UPLOAD_URL, async ({ request }) => {
        operations.push("card-upload");
        expect(await request.json()).toEqual({ targetItemId: expect.any(String),
          expectedVersion: ITEM.version, content: { sourceType: "UPLOAD", assetId: ASSET_ID } });
        return HttpResponse.json({ ...ITEM, selectedVersionId: UPLOADED_VERSION_ID,
          selectedVersion: REVISED_IMAGE.resourceDefaultVersion, version: 1 }, { status: 201 });
      }),
    );
    const onDone = mountUpload();

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(operations).toEqual(["upload", "card-upload"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("retries the idempotent card operation once after a transient response failure", async () => {
    const operations: string[] = [];
    let requests = 0;
    mockUpload(() => operations.push("upload"));
    server.use(
      http.post(CARD_UPLOAD_URL, async ({ request }) => {
        operations.push("card-upload");
        requests++;
        expect(await request.json()).toEqual({ targetItemId: expect.any(String),
          expectedVersion: ITEM.version, content: { sourceType: "UPLOAD", assetId: ASSET_ID } });
        if (requests === 1) return HttpResponse.json({ title: "暂时不可用", detail: "响应暂时失败。",
          code: "SERVICE_UNAVAILABLE", retryable: true },
        { status: 503, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json({ ...ITEM, selectedVersionId: UPLOADED_VERSION_ID,
          selectedVersion: REVISED_IMAGE.resourceDefaultVersion, version: 1 }, { status: 201 });
      }),
    );
    const onDone = mountUpload();

    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(operations).toEqual(["upload", "card-upload", "card-upload"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("retains uploaded bytes for a manual retry after repeated transient failures", async () => {
    const operations: string[] = [];
    let failing = true;
    mockUpload(() => operations.push("upload"));
    server.use(
      http.post(CARD_UPLOAD_URL, async ({ request }) => {
        operations.push("card-upload");
        expect(await request.json()).toEqual({ targetItemId: expect.any(String),
          expectedVersion: ITEM.version, content: { sourceType: "UPLOAD", assetId: ASSET_ID } });
        if (failing) return HttpResponse.json({ title: "响应失败", detail: "响应暂时不可用。",
          code: "SERVICE_UNAVAILABLE", retryable: true },
        { status: 503, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json({ ...ITEM, selectedVersionId: UPLOADED_VERSION_ID,
          selectedVersion: REVISED_IMAGE.resourceDefaultVersion, version: 1 }, { status: 201 });
      }),
    );
    const onDone = mountUpload();

    expect(await screen.findByRole("alert")).toHaveTextContent("响应暂时不可用");
    expect(screen.getByText("已选择：reference.webp")).toBeInTheDocument();
    failing = false;
    submitUpload();
    await waitFor(() => expect(onDone).toHaveBeenCalledOnce());
    expect(operations).toEqual(["upload", "card-upload", "card-upload", "card-upload"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("retains the selected file after a card conflict", async () => {
    const operations: string[] = [];
    const conflict = () => HttpResponse.json({ title: "版本冲突", detail: "卡片已被其他编辑更新。",
      code: "ARTIFACT_VERSION_CONFLICT", retryable: false },
    { status: 409, headers: { "Content-Type": "application/problem+json" } });
    mockUpload(() => operations.push("upload"));
    server.use(
      http.post(CARD_UPLOAD_URL, () => {
        operations.push("card-upload");
        return conflict();
      }),
    );
    const onDone = mountUpload();

    expect(await screen.findByRole("alert")).toHaveTextContent("卡片已有更新，文件已保留");
    expect(screen.getByText("已选择：reference.webp")).toBeInTheDocument();
    expect(screen.getByLabelText("更换图片")).toBeEnabled();
    expect(screen.getByRole("button", { name: "重试上传" })).toBeEnabled();
    expect(onDone).not.toHaveBeenCalled();
    expect(operations).toEqual(["upload", "card-upload"]);
  });
});
