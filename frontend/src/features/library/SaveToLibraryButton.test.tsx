import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { SaveToLibraryButton } from "./SaveToLibraryButton";

it("saves the fixed displayed result with the selected category, waiting for durable success", async () => {
  const submitted: unknown[] = [];
  server.use(
    http.get("/api/v1/projects/p/canvas-items/i/library-saves", () => HttpResponse.json({
      title: "海边旅馆", kind: "IMAGE", versionId: "v2", expectedSelectionEpoch: 3,
      expectedArtifactVersion: 8, textContent: null, assetId: "image", versionNo: 2,
    })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/projects/p/canvas-items/i/library-saves", async ({ request }) => {
      submitted.push(await request.json()); return HttpResponse.json({ id: "cmd", status: "ACCEPTED", result: null }, { status: 202 });
    }),
    http.get("/api/v1/library/commands/cmd", () => HttpResponse.json({ id: "cmd", status: "SUCCEEDED", result: { entryId: "entry", alreadySaved: false, trashed: false } })),
  );
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><SaveToLibraryButton projectId="p" itemId="i" /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "保存为资产" }));
  expect(await screen.findByText("海边旅馆 · 节点 v2")).toBeInTheDocument();
  await user.selectOptions(screen.getByRole("combobox", { name: "资产分类" }), "SCENE");
  await user.click(screen.getByRole("button", { name: "保存资产" }));
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("已保存到我的资产 · 场景"));
  await waitFor(() => expect(submitted).toEqual([expect.objectContaining({ versionId: "v2", expectedSelectionEpoch: 3, category: "SCENE", name: "海边旅馆" })]));
});
