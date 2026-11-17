import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { LibraryCanvasPicker } from "./LibraryCanvasPicker";

it("retains the fixed placement command after a 408 lost acknowledgement", async () => {
  const writes: Array<Record<string, unknown>> = [];
  let commits = 0,
    acceptedKey: unknown;
  server.use(
    http.get("/api/v1/library/entries", () =>
      HttpResponse.json({
        items: [
          {
            id: "entry",
            name: "合成旅馆",
            category: "SCENE",
            kind: "IMAGE",
            version: 2,
            source: {},
            favorite: false,
            createdAt: "2026-10-01T00:00:00Z",
            hasThumbnail: false,
          },
        ],
        total: 1,
        categoryCounts: { SCENE: 1 },
      }),
    ),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.post("/api/v1/projects/p/library-imports", async ({ request }) => {
      const input = (await request.json()) as Record<string, unknown>;
      writes.push(input);
      if (!acceptedKey || acceptedKey !== input.commandKey) {
        commits++;
        acceptedKey = input.commandKey;
      }
      return writes.length === 1
        ? HttpResponse.json(
            { code: "HTTP_ERROR", detail: "Synthetic placement acknowledgement lost" },
            { status: 408, headers: { "Content-Type": "application/problem+json" } },
          )
        : HttpResponse.json({ id: "cmd", status: "ACCEPTED" }, { status: 202 });
    }),
    http.get("/api/v1/library/commands/cmd", () =>
      HttpResponse.json({ id: "cmd", status: "SUCCEEDED", result: { canvasItemId: "placed-once" } }),
    ),
  );
  render(
    <QueryClientProvider client={createQueryClient()}>
      <LibraryCanvasPicker projectId="p" position={() => ({ x: 10, y: 20 })} />
    </QueryClientProvider>,
  );
  await userEvent.click(await screen.findByRole("button", { name: "放到画布：合成旅馆" }));
  await screen.findByText(/Synthetic placement acknowledgement lost/);
  expect(screen.getByRole("button", { name: "放到画布：合成旅馆" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "使用原命令重试提交" }));
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("已放到画布"));
  expect(writes).toHaveLength(2);
  expect(writes[1]).toEqual(writes[0]);
  expect(commits).toBe(1);
});
