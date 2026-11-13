import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../../../test/server";
import { listSubtitleDrafts } from "./subtitlesApi";

describe("bounded subtitle reads", () => {
  it("reads batches sequentially without losing sources", async () => {
    let active = 0,
      peak = 0;
    const sources = Array.from({ length: 205 }, (_, i) => `source-${i}`);
    server.use(
      http.get("/api/v1/projects/budget/subtitles", async ({ request }) => {
        active++;
        peak = Math.max(peak, active);
        const ids = new URL(request.url).searchParams.getAll("sourceVersionId");
        await new Promise(resolve => setTimeout(resolve, 1));
        active--;
        return HttpResponse.json(ids.map(sourceVersionId => ({ sourceVersionId, cues: [] })));
      }),
    );
    const result = await listSubtitleDrafts("budget", sources);
    expect(result.map(draft => draft.sourceVersionId)).toEqual(sources);
    expect(peak).toBe(1);
  });
  it.each(["cues", "bytes"])("rejects cumulative %s excess without returning a partial list", async budget => {
    const sources = Array.from({ length: 101 }, (_, i) => `source-${i}`);
    const cues = Array.from({ length: budget === "cues" ? 5000 : 3000 }, () => ({
      text: budget === "cues" ? "x" : "字".repeat(500),
      startMs: 0,
      endMs: 1,
    }));
    server.use(
      http.get("/api/v1/projects/budget/subtitles", ({ request }) => {
        const ids = new URL(request.url).searchParams.getAll("sourceVersionId");
        return HttpResponse.json(
          ids.length === 100 ? [{ cues }, ...(budget === "cues" ? [{ cues }] : [])] : [{ cues }],
        );
      }),
    );
    await expect(listSubtitleDrafts("budget", sources)).rejects.toMatchObject({ code: "SUBTITLE_BATCH_TOO_LARGE" });
  });
});
