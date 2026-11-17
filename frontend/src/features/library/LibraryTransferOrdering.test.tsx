import { onlineManager, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { LibraryCommand } from "../../shared/api/client";
import { server } from "../../test/server";
import { t } from "../../shared/i18n";
import { LibraryCanvasPicker } from "./LibraryCanvasPicker";

const failed = {
  id: "cmd-a",
  status: "FAILED",
  result: null,
  errorCode: "STORAGE_ERROR",
  errorDetail: "Synthetic copy failed",
} as const;
const accepted = { ...failed, status: "ACCEPTED", errorCode: null, errorDetail: null } as const;
const succeeded = { ...accepted, status: "SUCCEEDED", result: { canvasItemId: "placed-once" } } as const;
function gate() {
  let release = () => {};
  const promise = new Promise<void>(resolve => {
    release = resolve;
  });
  return { promise, release };
}
function show() {
  const client = createQueryClient();
  let submissions = 0;
  server.use(
    http.get("/api/v1/library/entries", () =>
      HttpResponse.json({
        items: [
          {
            id: "entry",
            name: "Synthetic lodge",
            category: "SCENE",
            kind: "IMAGE",
            version: 2,
            source: {},
            favorite: false,
            createdAt: "2026-11-04T00:00:00Z",
            hasThumbnail: false,
          },
        ],
        total: 1,
        categoryCounts: { SCENE: 1 },
      }),
    ),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.post("/api/v1/projects/p/library-imports", () =>
      HttpResponse.json({ ...accepted, id: ++submissions === 1 ? "cmd-a" : "cmd-b" }, { status: 202 }),
    ),
  );
  render(
    <QueryClientProvider client={client}>
      <LibraryCanvasPicker projectId="p" position={() => ({ x: 10, y: 20 })} />
    </QueryClientProvider>,
  );
  return client;
}
async function beginRetry() {
  await userEvent.click(await screen.findByRole("button", { name: "放到画布：Synthetic lodge" }));
  await userEvent.click(await screen.findByRole("button", { name: t("library.transfer.retryTransfer") }));
}

it("rejects a pre-retry FAILED read that arrives after the retry acknowledgement", async () => {
  const retry = gate(),
    read = gate();
  let reads = 0,
    retryWrites = 0;
  let current: LibraryCommand = failed;
  server.use(
    http.get("/api/v1/library/commands/cmd-a", async () => {
      const snapshot = current;
      if (++reads === 2) await read.promise;
      return HttpResponse.json(snapshot);
    }),
    http.post("/api/v1/library/commands/cmd-a/retry", async () => {
      retryWrites++;
      await retry.promise;
      current = accepted;
      return HttpResponse.json(accepted, { status: 202 });
    }),
  );
  const client = show();
  await beginRetry();
  await waitFor(() => expect(retryWrites).toBe(1));
  let oldRead = Promise.resolve();
  act(() => {
    oldRead = client.refetchQueries({ queryKey: ["library-command", "cmd-a"], exact: true });
  });
  await waitFor(() => expect(reads).toBe(2));
  await act(async () => retry.release());
  await waitFor(() => expect(client.isMutating()).toBe(0));
  await act(async () => {
    read.release();
    await oldRead;
  });
  expect(client.getQueryData<LibraryCommand>(["library-command", "cmd-a"])?.status).toBe("ACCEPTED");
  expect(screen.getByText(t("library.transfer.transferring"))).toBeVisible();
  expect(retryWrites).toBe(1);
});

it("keeps a success read on reconnect when a captured ACCEPTED retry reply arrives later", async () => {
  const retry = gate();
  let writes = 0;
  let current: LibraryCommand = failed;
  server.use(
    http.get("/api/v1/library/commands/cmd-a", () => HttpResponse.json(current)),
    http.post("/api/v1/library/commands/cmd-a/retry", async () => {
      writes++;
      current = succeeded;
      await retry.promise;
      return HttpResponse.json(accepted, { status: 202 });
    }),
  );
  const client = show();
  await beginRetry();
  await waitFor(() => expect(writes).toBe(1));
  try {
    act(() => {
      onlineManager.setOnline(false);
      onlineManager.setOnline(true);
    });
    await screen.findByText(t("library.canvasPicker.placed"));
  } finally {
    onlineManager.setOnline(true);
  }
  await act(async () => retry.release());
  await waitFor(() => expect(client.isMutating()).toBe(0));
  expect(client.getQueryData<LibraryCommand>(["library-command", "cmd-a"])?.status).toBe("SUCCEEDED");
  expect(screen.getByRole("button", { name: t("library.canvasPicker.addAnother") })).toBeEnabled();
});

it("starts another placement after known success without letting the previous retry reply contaminate it", async () => {
  const retry = gate();
  let writes = 0;
  let current: LibraryCommand = failed;
  server.use(
    http.get("/api/v1/library/commands/:id", ({ params }) =>
      HttpResponse.json(params.id === "cmd-b" ? { ...succeeded, id: "cmd-b" } : current),
    ),
    http.post("/api/v1/library/commands/cmd-a/retry", async () => {
      writes++;
      current = succeeded;
      await retry.promise;
      return HttpResponse.json(accepted, { status: 202 });
    }),
  );
  const client = show();
  await beginRetry();
  await waitFor(() => expect(writes).toBe(1));
  await act(async () => {
    await client.refetchQueries({ queryKey: ["library-command", "cmd-a"], exact: true });
  });
  await userEvent.click(await screen.findByRole("button", { name: t("library.canvasPicker.addAnother") }));
  try {
    expect(screen.getByRole("button", { name: "放到画布：Synthetic lodge" })).toBeEnabled();
    await userEvent.click(screen.getByRole("button", { name: "放到画布：Synthetic lodge" }));
    await screen.findByText(t("library.canvasPicker.placed"));
  } finally {
    await act(async () => retry.release());
  }
  await waitFor(() => expect(client.isMutating()).toBe(0));
  expect(client.getQueryData<LibraryCommand>(["library-command", "cmd-b"])?.status).toBe("SUCCEEDED");
  expect(client.getQueryData(["library-command", undefined])).toBeUndefined();
});
