import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { setLocale } from "../../shared/i18n";
import { server } from "../../test/server";
import { SaveCostumeReferenceButton } from "./SaveCostumeReferenceButton";

const props = {
  projectId: "project",
  canvasItemId: "item",
  sourceVersionId: "selected-version",
  expectedCanvasItemVersion: 7,
  sourceTitle: "Linen outfit",
};
function setup(admin = true) {
  setLocale("en");
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ role: admin ? "ADMIN" : "USER" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
  );
  const client = createQueryClient();
  return {
    user: userEvent.setup(),
    ...render(
      <QueryClientProvider client={client}>
        <SaveCostumeReferenceButton {...props} />
      </QueryClientProvider>,
    ),
    client,
  };
}
it("offers saving only to the administrator and sends no save until explicitly confirmed", async () => {
  const { user } = setup();
  let count = 0;
  server.use(
    http.post("/api/v1/projects/project/reference-catalog/costumes", () => {
      count++;
      return HttpResponse.json({ id: "saved" });
    }),
  );
  await user.click(await screen.findByRole("button", { name: "Save as costume reference" }));
  expect(count).toBe(0);
  expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Linen outfit");
  await user.click(screen.getByRole("button", { name: "Save costume reference" }));
  expect(await screen.findByRole("status")).toHaveTextContent("Costume reference saved.");
  expect(count).toBe(1);
});
it("keeps one uncertain save intention and frozen source through retry and reopening", async () => {
  const { user, rerender, client } = setup();
  const requests: Record<string, unknown>[] = [];
  server.use(
    http.post("/api/v1/projects/project/reference-catalog/costumes", async ({ request }) => {
      requests.push((await request.json()) as Record<string, unknown>);
      return requests.length === 1 ? HttpResponse.error() : HttpResponse.json({ id: "saved" });
    }),
  );
  await user.click(await screen.findByRole("button", { name: "Save as costume reference" }));
  await user.type(screen.getByRole("textbox", { name: "Tags" }), "linen, summer");
  rerender(
    <QueryClientProvider client={client}>
      <SaveCostumeReferenceButton {...props} sourceVersionId="new-version" expectedCanvasItemVersion={8} />
    </QueryClientProvider>,
  );
  await user.click(screen.getByRole("button", { name: "Save costume reference" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Your input has been kept");
  expect(screen.getByRole("textbox", { name: "Tags" })).toHaveValue("linen, summer");
  expect(screen.getByRole("textbox", { name: "Name" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Cancel" }));
  await user.click(screen.getByRole("button", { name: "Save as costume reference" }));
  await user.click(screen.getByRole("button", { name: "Retry" }));
  await screen.findByRole("status");
  expect(requests).toHaveLength(2);
  expect(requests[0]).toEqual(requests[1]);
  expect(requests[0]).toMatchObject({
    sourceVersionId: "selected-version",
    expectedCanvasItemVersion: 7,
    name: "Linen outfit",
    tags: ["linen", "summer"],
  });
});
it("retains editable metadata after a definitive CAS failure", async () => {
  const { user } = setup();
  server.use(
    http.post("/api/v1/projects/project/reference-catalog/costumes", () =>
      HttpResponse.json(
        { title: "Source changed", code: "VERSION_CONFLICT" },
        { status: 409, headers: { "Content-Type": "application/problem+json" } },
      ),
    ),
  );
  await user.click(await screen.findByRole("button", { name: "Save as costume reference" }));
  await user.click(screen.getByRole("button", { name: "Save costume reference" }));
  await screen.findByRole("alert");
  expect(screen.getByRole("textbox", { name: "Name" })).toBeEnabled();
  expect(screen.getByRole("textbox", { name: "Name" })).toHaveValue("Linen outfit");
});
it("does not expose saving to a non-administrator", async () => {
  const { client } = setup(false);
  await waitFor(() => expect(client.getQueryData(["auth", "me"])).toMatchObject({ role: "USER" }));
  expect(screen.queryByRole("button", { name: "Save as costume reference" })).not.toBeInTheDocument();
});
it("rejects oversized tags without truncating the user's input", async () => {
  const { user } = setup();
  await user.click(await screen.findByRole("button", { name: "Save as costume reference" }));
  await user.type(screen.getByRole("textbox", { name: "Tags" }), "a".repeat(65));
  expect(screen.getByRole("button", { name: "Save costume reference" })).toBeDisabled();
  expect(screen.getByRole("textbox", { name: "Tags" })).toHaveValue("a".repeat(65));
});
it("locks the save form while the command is pending", async () => {
  const { user } = setup();
  let release: (() => void) | undefined;
  let attempts = 0;
  server.use(
    http.post("/api/v1/projects/project/reference-catalog/costumes", async () => {
      attempts++;
      await new Promise<void>(resolve => {
        release = resolve;
      });
      return HttpResponse.json({ id: "saved" });
    }),
  );
  await user.click(await screen.findByRole("button", { name: "Save as costume reference" }));
  await user.click(screen.getByRole("button", { name: "Save costume reference" }));
  await waitFor(() => expect(release).toBeDefined());
  expect(screen.getByRole("textbox", { name: "Name" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Saving…" })).toBeDisabled();
  expect(attempts).toBe(1);
  release!();
  await screen.findByRole("status");
});
