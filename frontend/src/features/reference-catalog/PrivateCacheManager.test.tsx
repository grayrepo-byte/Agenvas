import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { PrivateCacheManager } from "./PrivateCacheManager";
const base = "/api/v1/reference-catalog/private-images";
const oldImage = {
  id: "old-private",
  sourceId: "source",
  snapshotId: "old-snapshot",
  entryId: "old-pose",
  variantId: "front",
  cached: true,
  byteSize: 150,
  previewUrl: `${base}/old-private/preview`,
  sha256: "old-sha",
  width: 100,
  height: 150,
  contentType: "image/png",
};
function setup() {
  setLocale("en");
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })));
  const client = createQueryClient();
  const view = (ownerId: string) => (
    <QueryClientProvider client={client}>
      <PrivateCacheManager key={ownerId} ownerId={ownerId} onClose={() => {}} />
    </QueryClientProvider>
  );
  return { user: userEvent.setup(), view };
}
it("lists all own snapshots and clears an old private copy after confirmation", async () => {
  const { user, view } = setup();
  let deletes = 0;
  server.use(
    http.get(base, ({ request }) => {
      expect(new URL(request.url).search).toBe("");
      return HttpResponse.json({ items: [oldImage] });
    }),
    http.delete(`${base}/old-private`, () => {
      deletes++;
      return HttpResponse.json({ ...oldImage, cached: false });
    }),
  );
  render(view("owner"));
  expect(await screen.findByText("old-snapshot")).toBeInTheDocument();
  expect(screen.getByText("old-pose")).toBeInTheDocument();
  expect(screen.getByText("Your cached images: 1 · total image size: 150 bytes")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Clear private copy old-pose · front" }));
  expect(deletes).toBe(0);
  expect(
    screen.getByText(
      "Clear your private cache of this image? Imported project copies and other users’ caches are preserved.",
    ),
  ).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Confirm clear private copy" }));
  await waitFor(() => expect(deletes).toBe(1));
  expect(await screen.findByText("No private images are currently cached for your account.")).toBeInTheDocument();
});
it("keeps another account's private records and clear targets out of the manager", async () => {
  const { user, view } = setup();
  let owner = "first";
  server.use(
    http.get(base, () =>
      HttpResponse.json({
        items: owner === "first" ? [oldImage] : [{ ...oldImage, id: "other-private", entryId: "other-owner-pose" }],
      }),
    ),
  );
  const rendered = render(view("first"));
  await user.click(await screen.findByRole("button", { name: "Clear private copy old-pose · front" }));
  owner = "second";
  rendered.rerender(view("second"));
  expect(await screen.findByText("other-owner-pose")).toBeInTheDocument();
  expect(screen.queryByText("old-pose")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Confirm clear private copy" })).not.toBeInTheDocument();
});
it("retains the selected private copy and permits retry when owner deletion fails", async () => {
  const { user, view } = setup();
  let failed = true;
  server.use(
    http.get(base, () => HttpResponse.json({ items: [oldImage] })),
    http.delete(`${base}/old-private`, () =>
      failed
        ? HttpResponse.json(
            { title: "Cache unavailable", code: "CACHE_BUSY" },
            { status: 503, headers: { "Content-Type": "application/problem+json" } },
          )
        : HttpResponse.json({ ...oldImage, cached: false }),
    ),
  );
  render(view("owner"));
  await user.click(await screen.findByRole("button", { name: "Clear private copy old-pose · front" }));
  await user.click(screen.getByRole("button", { name: "Confirm clear private copy" }));
  await screen.findByRole("alert");
  expect(screen.getByText("old-pose")).toBeInTheDocument();
  failed = false;
  await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm clear private copy" }));
  expect(await screen.findByText("No private images are currently cached for your account.")).toBeInTheDocument();
});
