import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import type { ArtifactList, ArtifactVersionList } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import { CharacterReferenceImage } from "./CharacterReferencePicker";

it("replaces a failed thumbnail with an accessible fallback without exposing resource IDs", () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  client.setQueryData<ArtifactList>(["artifacts", "project"], {
    items: [
      {
        id: "resource-id",
        projectId: "project",
        kind: "IMAGE",
        title: "Internal resource title",
        version: 1,
        resourceDefaultVersion: null,
        resourceDefaultVersionId: null,
        createdAt: "2026-11-06T00:00:00Z",
        updatedAt: "2026-11-06T00:00:00Z",
      },
    ],
  });
  client.setQueryData<ArtifactVersionList>(["artifact-versions", "project", "resource-id"], {
    items: [
      {
        id: "exact-version-id",
        versionNo: 1,
        schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: "asset-id" },
        inputReferences: [],
        createdByKind: "USER",
        createdAt: "2026-11-06T00:00:00Z",
      },
    ],
  });
  render(
    <QueryClientProvider client={client}>
      <CharacterReferenceImage projectId="project" versionId="exact-version-id" alt="Mira portrait" />
    </QueryClientProvider>,
  );
  fireEvent.error(screen.getByRole("img", { name: "Mira portrait" }));
  expect(screen.getByRole("img", { name: t("characterNode.previewUnavailable") })).toBeVisible();
  expect(screen.queryByText("Internal resource title")).not.toBeInTheDocument();
  expect(screen.queryByText("exact-version-id")).not.toBeInTheDocument();
});
