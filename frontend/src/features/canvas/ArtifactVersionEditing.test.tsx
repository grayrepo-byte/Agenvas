import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Artifact } from "../../shared/api/client";
import { server } from "../../test/server";
import { ArtifactVersionHistory } from "./ArtifactVersionHistory";
import { StructuredArtifactEditor } from "./StructuredArtifactEditor";

const now = "2026-09-24T00:00:00Z";
const imageVersionId = "11111111-1111-4111-8111-111111111111";

function artifact(kind: "CHARACTER" | "SCENE" | "TEXT"): Artifact {
  const content = kind === "CHARACTER"
    ? { name: "Hero", description: "Lead", appearance: "Blue coat",
      referenceVersionIds: [imageVersionId] }
    : kind === "SCENE" ? { name: "Studio", location: "Room", timeOfDay: "Day",
      lighting: "Soft", style: "Realistic", referenceVersionIds: [imageVersionId] }
      : { format: "PLAIN_TEXT" as const, text: "Draft" };
  return { id: "artifact-1", projectId: "project-1", kind,
    title: kind === "TEXT" ? "Draft" : kind === "SCENE" ? "Studio" : "Hero",
    currentVersionId: "version-2", version: 4, createdAt: now, updatedAt: now,
    currentVersion: { id: "version-2", versionNo: 2, schemaVersion: 1,
      content, inputReferences: kind === "TEXT" ? [] : [
        { versionId: imageVersionId, role: "referenceImage", order: 0, kind: "IMAGE" }],
      createdByKind: "USER", runId: null, createdAt: now },
  };
}

function renderComponent(element: React.ReactNode) {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
  return client;
}

describe("Artifact version editing", () => {
  it("revises manual text content without overwriting its previous version", async () => {
    let revisions = 0;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions",
        async ({ request }) => {
          revisions++;
          expect(await request.json()).toEqual({ expectedVersion: 4, title: "Draft",
            content: { format: "MARKDOWN", text: "Revised" } });
          return HttpResponse.json(artifact("TEXT"), { status: 201 });
        }),
    );
    renderComponent(<StructuredArtifactEditor artifact={artifact("TEXT")} />);
    const user = userEvent.setup();
    await user.click(screen.getByText("修改文字内容"));
    await user.selectOptions(screen.getByLabelText("格式"), "MARKDOWN");
    await user.clear(screen.getByLabelText("内容"));
    await user.type(screen.getByLabelText("内容"), "Revised");
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    await waitFor(() => expect(revisions).toBe(1));
  });

  it("saves a character description as a new CAS version while preserving image references", async () => {
    let revisions = 0;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions",
        async ({ request }) => {
          revisions++;
          expect(await request.json()).toEqual({ expectedVersion: 4, title: "Hero",
            content: { name: "Hero", description: "Lead revised", appearance: "Blue coat",
              referenceVersionIds: [imageVersionId] } });
          return HttpResponse.json(artifact("CHARACTER"), { status: 201 });
        }),
    );
    renderComponent(<StructuredArtifactEditor artifact={artifact("CHARACTER")} />);
    const user = userEvent.setup();
    await user.click(screen.getByText("修改角色说明"));
    await user.clear(screen.getByLabelText("描述"));
    await user.type(screen.getByLabelText("描述"), "Lead revised");
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    await waitFor(() => expect(revisions).toBe(1));
    expect(await screen.findByText("新版本已保存。")).toBeInTheDocument();
  });

  it("retains scene edits after a 409 conflict instead of claiming they were saved", async () => {
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions", () =>
        HttpResponse.json({ title: "冲突", detail: "旧版本已变化。",
          code: "ARTIFACT_VERSION_CONFLICT", retryable: false },
        { status: 409, headers: { "Content-Type": "application/problem+json" } })),
    );
    renderComponent(<StructuredArtifactEditor artifact={artifact("SCENE")} />);
    const user = userEvent.setup();
    await user.click(screen.getByText("修改场景说明"));
    await user.clear(screen.getByLabelText("时间"));
    await user.type(screen.getByLabelText("时间"), "Evening");
    await user.click(screen.getByRole("button", { name: "保存新版本" }));
    expect(await screen.findByText("内容有冲突，修改未保存；请核对当前版本后重试。"))
      .toBeInTheDocument();
    expect(screen.getByLabelText("时间")).toHaveValue("Evening");
    expect(screen.queryByText("新版本已保存。")).not.toBeInTheDocument();
  });

  it("loads history on demand and selects a past exact version with artifact CAS", async () => {
    let reads = 0;
    let selections = 0;
    server.use(
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/versions", () => {
        reads++;
        return HttpResponse.json({ items: [artifact("TEXT").currentVersion,
          { ...artifact("TEXT").currentVersion, id: "version-1", versionNo: 1 }] });
      }),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/select-version",
        async ({ request }) => {
          selections++;
          expect(await request.json()).toEqual({ versionId: "version-1", expectedVersion: 4 });
          return HttpResponse.json(artifact("TEXT"));
        }),
    );
    renderComponent(<ArtifactVersionHistory artifact={artifact("TEXT")} />);
    expect(reads).toBe(0);
    const user = userEvent.setup();
    await user.click(screen.getByText("版本历史与选用"));
    expect(await screen.findByText(/v1 · USER/)).toBeInTheDocument();
    expect(reads).toBe(1);
    await user.click(screen.getByRole("button", { name: "选用此版本" }));
    await waitFor(() => expect(selections).toBe(1));
  });
});
