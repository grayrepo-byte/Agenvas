import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { ReferenceCatalogPage } from "./ReferenceCatalogPage";
it("places one verified image only after an explicit project and canvas action", async () => {
  setLocale("en");
  const requests: Record<string, unknown>[] = [];
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ role: "USER" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "token" })),
    http.get("/api/v1/projects", () =>
      HttpResponse.json({ items: [{ id: "project", name: "Storyboard", status: "ACTIVE" }], nextCursor: null }),
    ),
    http.get("/api/v1/reference-catalog/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/reference-catalog", () =>
      HttpResponse.json({
        items: [
          {
            id: "pose",
            title: "Synthetic pose",
            sourceId: "pack",
            snapshotId: "snapshot",
            category: "",
            tags: [],
            facets: {},
            importAllowed: true,
            variants: [{ id: "front", label: "Front", sha256: "abc", importAllowed: true, availability: "AVAILABLE" }],
          },
        ],
        total: 1,
        offset: 0,
        limit: 50,
      }),
    ),
    http.post("/api/v1/projects/project/reference-catalog/import", async ({ request }) => {
      requests.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json({ canvasItemId: "placed-item", versionId: "exact-version" });
    }),
  );
  render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter>
        <ReferenceCatalogPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "Preview Synthetic pose" }));
  expect(screen.getByRole("button", { name: "Place on canvas" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Close" }));
  await user.click(screen.getByRole("combobox", { name: "Target project" }));
  await user.click(screen.getByRole("option", { name: "Storyboard" }));
  expect(requests).toHaveLength(0);
  await user.click(screen.getByRole("button", { name: "Preview Synthetic pose" }));
  await user.click(screen.getByRole("button", { name: "Place on canvas" }));
  await waitFor(() => expect(requests).toHaveLength(1));
  expect(requests[0]).toMatchObject({ variantId: "front", placement: { x: 80, y: 80 } });
  expect(await screen.findByText("Image placed on canvas.")).toBeInTheDocument();
});
