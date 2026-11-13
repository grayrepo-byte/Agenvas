import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import type { SaveMediaDraftRequest } from "../../shared/api/client";
import { setLocale, t } from "../../shared/i18n";
import { server } from "../../test/server";
import { prepareMediaNode, type PreparedMediaNode } from "./mediaNodeActions";

it("recovers an accepted MV draft after a lost response and interface language change", async () => {
  setLocale("en");
  const progress: PreparedMediaNode = { createKey: "synthetic-mv", itemId: "mv-item" };
  let stored: SaveMediaDraftRequest | undefined;
  let creates = 0,
    saves = 0;
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" })),
    http.post("/api/v1/projects/project-1/artifacts", () => {
      creates++;
      return HttpResponse.json({ id: "mv-artifact" });
    }),
    http.get("/api/v1/projects/project-1/canvas/items", () => HttpResponse.json({ items: [] })),
    http.post("/api/v1/projects/project-1/canvas/commands", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/projects/project-1/canvas-items/mv-item/media-draft", () =>
      HttpResponse.json(stored ? { ...stored, version: 1 } : { version: 0 }),
    ),
    http.put("/api/v1/projects/project-1/canvas-items/mv-item/media-draft", async ({ request }) => {
      stored = (await request.json()) as SaveMediaDraftRequest;
      saves++;
      return HttpResponse.error(); // Server accepted the original draft; its acknowledgement was lost.
    }),
  );
  const fields = (): Omit<SaveMediaDraftRequest, "expectedVersion"> => ({
    prompt: t("canvas.workspace.musicVideoPrompt"),
    parameters: { aspectRatio: "AUTO" },
    durationSeconds: 5,
    capabilityId: null,
    videoInputMode: "GENERAL_REFERENCE",
    mediaInputs: [{ versionId: "audio-version", role: "AUDIO_REFERENCE", color: "#67C7F3" }],
    mentions: [],
  });
  await expect(
    prepareMediaNode("project-1", "audio-item", "VIDEO", "Audio · MV", fields(), progress),
  ).rejects.toThrow();
  setLocale("ja");
  await expect(prepareMediaNode("project-1", "audio-item", "VIDEO", "Audio · MV", fields(), progress)).resolves.toEqual(
    {
      artifactId: "mv-artifact",
      canvasItemId: "mv-item",
      expectedDraftVersion: 1,
    },
  );
  expect(creates).toBe(1);
  expect(saves).toBe(1);
});
