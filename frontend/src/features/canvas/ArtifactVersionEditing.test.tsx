import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { VersionedArtifact } from "./versionedArtifact";
import { server } from "../../test/server";
import { ArtifactVersionHistory } from "./ArtifactVersionHistory";
import { TextCanvasEditor } from "./TextCanvasEditor";

const now = "2026-09-24T00:00:00Z";
const REVISIONS_URL = "/api/v1/projects/:projectId/artifacts/:artifactId/revisions";

/** The text card is the surviving in-node editing path; editing it must never rewrite version 2. */
function artifact(): VersionedArtifact {
  return { id: "artifact-1", projectId: "project-1", kind: "TEXT", title: "Draft",
    resourceDefaultVersionId: "version-2", version: 4, createdAt: now, updatedAt: now,
    resourceDefaultVersion: { id: "version-2", versionNo: 2, schemaVersion: 2,
      content: { format: "PLAIN_TEXT", text: "Draft" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: now },
  };
}

function updatedArtifact(base: VersionedArtifact, text: string,
  format: "PLAIN_TEXT" | "MARKDOWN" = "PLAIN_TEXT"): VersionedArtifact {
  return { ...base, version: base.version + 1, resourceDefaultVersionId: "version-3", resourceDefaultVersion: {
    ...base.resourceDefaultVersion, id: "version-3", versionNo: 3, content: { format, text },
  } };
}

function renderEditor(element: ReactNode) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  const rendered = render(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
  return { client, rerender: (next: ReactNode) =>
    rendered.rerender(<QueryClientProvider client={client}>{next}</QueryClientProvider>) };
}

function textEditor(value: VersionedArtifact, onDone: () => void = vi.fn()) {
  return <TextCanvasEditor artifact={value} locked={false} onDone={onDone} />;
}

function csrf() {
  return http.get("/api/v1/auth/csrf", () => HttpResponse.json({
    headerName: "X-XSRF-TOKEN", token: "test-token" }));
}

function conflict(message = "旧版本已变化。") {
  return HttpResponse.json({ title: "冲突", detail: message,
    code: "ARTIFACT_VERSION_CONFLICT", retryable: false },
  { status: 409, headers: { "Content-Type": "application/problem+json" } });
}

describe("Artifact version editing", () => {
  it("opens the text fields directly and exposes a focusable canvas editing target", () => {
    renderEditor(textEditor(artifact()));
    const field = screen.getByLabelText("内容");
    expect(field).toBeVisible();
    expect(field).toHaveAttribute("data-content-editor-focus", "true");
    field.focus();
    expect(field).toHaveFocus();
    expect(screen.getByRole("button", { name: "保存新版本" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /v2/ })).toBeInTheDocument();
  });

  it("revises manual text content without overwriting its previous version", async () => {
    let revisions = 0;
    server.use(
      csrf(),
      http.post(REVISIONS_URL, async ({ request }) => {
        revisions++;
        expect(await request.json()).toEqual({ expectedVersion: 4, title: "Draft",
          content: { format: "MARKDOWN", text: "Revised" } });
        return HttpResponse.json(updatedArtifact(artifact(), "Revised", "MARKDOWN"),
          { status: 201 });
      }),
    );
    renderEditor(textEditor(artifact()));
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText("文字格式"), "MARKDOWN");
    await user.clear(screen.getByLabelText("内容"));
    await user.type(screen.getByLabelText("内容"), "Revised");
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    await waitFor(() => expect(revisions).toBe(1));
    // 保存产生新版本：编辑器切到 v3，旧内容没有被原地覆盖。
    expect(await screen.findByText("新版本已保存")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /v3/ })).toBeInTheDocument();
  });

  it("retains text edits after a 409 conflict instead of claiming they were saved", async () => {
    server.use(csrf(), http.post(REVISIONS_URL, () => conflict()));
    renderEditor(textEditor(artifact()));
    const user = userEvent.setup();
    await user.clear(screen.getByLabelText("内容"));
    await user.type(screen.getByLabelText("内容"), "Local edit");
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    expect(await screen.findByText("内容有冲突，修改未保存；请核对当前版本后重试。"))
      .toBeInTheDocument();
    expect(screen.getByLabelText("内容")).toHaveValue("Local edit");
    expect(screen.queryByText("新版本已保存")).not.toBeInTheDocument();
  });

  it("keeps a dirty text draft and its original CAS when a newer artifact arrives, until an explicit reload", async () => {
    const initial = artifact();
    const latest = updatedArtifact(initial, "Remote revision");
    const requests: unknown[] = [];
    server.use(
      csrf(),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId", () => HttpResponse.json(latest)),
      http.post(REVISIONS_URL, async ({ request }) => {
        requests.push(await request.json());
        return conflict("版本已变化");
      }),
    );
    const editor = renderEditor(textEditor(initial));
    const user = userEvent.setup();
    await user.clear(screen.getByLabelText("内容"));
    await user.type(screen.getByLabelText("内容"), "Local draft");
    editor.rerender(textEditor(latest));
    expect(screen.getByLabelText("内容")).toHaveValue("Local draft");
    expect(screen.getByRole("button", { name: /v2/ })).toBeInTheDocument();
    expect(screen.getByText(/当前版本已更新，本地修改仍保留/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    await screen.findByRole("alert");
    expect(requests).toEqual([{ expectedVersion: 4, title: "Draft",
      content: { format: "PLAIN_TEXT", text: "Local draft" } }]);
    expect(screen.getByLabelText("内容")).toHaveValue("Local draft");
    await user.click(screen.getByRole("button", { name: "载入最新版本" }));
    await waitFor(() => expect(screen.getByLabelText("内容")).toHaveValue("Remote revision"));
    expect(screen.getByRole("button", { name: /v3/ })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "保存新版本" })).toBeDisabled();
  });

  it("blocks duplicate submission while saving and retains text after a failed response", async () => {
    let attempts = 0;
    let finish: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => { finish = resolve; });
    server.use(
      csrf(),
      http.post(REVISIONS_URL, async () => {
        attempts++; await gate;
        return HttpResponse.json({ title: "暂时失败", detail: "保存暂不可用" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    renderEditor(textEditor(artifact()));
    const user = userEvent.setup();
    const input = screen.getByLabelText("内容");
    await user.clear(input); await user.type(input, "Keep my text");
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    await waitFor(() => expect(attempts).toBe(1));
    expect(input).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存新版本" })).toBeDisabled();
    if (!(input instanceof HTMLTextAreaElement) || !input.form) throw new Error("Missing editor form");
    fireEvent.submit(input.form);
    expect(attempts).toBe(1);
    if (!finish) throw new Error("Missing save gate");
    finish();
    expect(await screen.findByRole("alert")).toHaveTextContent("保存暂不可用 输入已保留。");
    expect(input).toHaveValue("Keep my text");
    expect(input).toBeEnabled();
    expect(screen.getByRole("button", { name: "保存新版本" })).toBeEnabled();
  });

  it("accepts a newer server version automatically when there is no local edit", async () => {
    const initial = artifact();
    const editor = renderEditor(textEditor(initial));
    editor.rerender(textEditor(updatedArtifact(initial, "New server text", "MARKDOWN")));
    await waitFor(() => expect(screen.getByLabelText("内容")).toHaveValue("New server text"));
    expect(screen.getByLabelText("文字格式")).toHaveValue("MARKDOWN");
    expect(screen.queryByRole("button", { name: "载入最新版本" })).not.toBeInTheDocument();
  });

  it("loads history on demand and selects a past exact version with artifact CAS", async () => {
    let reads = 0;
    let selections = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () => {
        reads++;
        return HttpResponse.json({ items: [artifact().resourceDefaultVersion,
          { ...artifact().resourceDefaultVersion, id: "version-1", versionNo: 1 }] });
      }),
      csrf(),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/set-default-version",
        async ({ request }) => {
          selections++;
          expect(await request.json()).toEqual({ versionId: "version-1", expectedVersion: 4 });
          return HttpResponse.json(artifact());
        }),
    );
    renderEditor(<ArtifactVersionHistory artifact={artifact()} />);
    expect(reads).toBe(0);
    const user = userEvent.setup();
    await user.click(screen.getByText("版本历史与选用"));
    expect(await screen.findByText(/v1 · USER/)).toBeInTheDocument();
    expect(reads).toBe(1);
    await user.click(screen.getByRole("button", { name: "选用此版本" }));
    await waitFor(() => expect(selections).toBe(1));
  });
});
