import { beforeEach, describe, expect, it, vi } from "vitest";
import { applyCanvasCommands, createArtifact, getMediaDraft, listCanvasItems, runMediaDraft, saveMediaDraft,
  type CanvasItem, type MediaDraft } from "../../shared/api/client";
import { createStoryboardNodePreparation, prepareStoryboardNode } from "./storyboardNodePreparation";
import { newStoryboardShot } from "./storyboardDraft";

vi.mock("../../shared/api/client", () => ({ applyCanvasCommands: vi.fn(), createArtifact: vi.fn(),
  getMediaDraft: vi.fn(), listCanvasItems: vi.fn(), runMediaDraft: vi.fn(), saveMediaDraft: vi.fn() }));

const now = "2026-10-23T00:00:00Z";
const source: CanvasItem = { id: "source", subjectType: "ARTIFACT", subjectId: "source-artifact", title: "Source",
  x: 120, y: 40, width: 640, height: 360, zIndex: 0, locked: false, groupId: null, version: 0,
  selectedVersionId: null, selectedVersion: null,
  artifact: { id: "source-artifact", projectId: "project", kind: "IMAGE", title: "Source",
    resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 0, createdAt: now, updatedAt: now } };

beforeEach(() => vi.resetAllMocks());

describe("storyboard node preparation", () => {
  it("resumes an interrupted preparation with the same node and frozen creative text, without generating media", async () => {
    const shot = { ...newStoryboardShot(0), description: "The traveler arrives.", dialogue: "Hello.", durationSeconds: 7.5 };
    const intent = createStoryboardNodePreparation(shot, "VIDEO", [source], "SHOT-01 · Video");
    const draft: MediaDraft = { projectId: "project", canvasItemId: intent.progress.itemId, prompt: "", parameters: {},
      durationSeconds: null, capabilityId: null, styleId: null, videoInputMode: "TEXT", mediaInputs: [], mentions: [],
      displayMode: "DRAFT", version: 0, createdAt: now, updatedAt: now };
    vi.mocked(createArtifact).mockResolvedValue({ ...source.artifact, id: "prepared-artifact", kind: "VIDEO" });
    vi.mocked(listCanvasItems).mockResolvedValue({ items: [source] });
    vi.mocked(applyCanvasCommands).mockRejectedValueOnce(new Error("Lost placement response"))
      .mockResolvedValue({ items: [source] });
    vi.mocked(getMediaDraft).mockResolvedValue(draft);
    vi.mocked(saveMediaDraft).mockResolvedValue({ ...draft, version: 1 });
    await expect(prepareStoryboardNode("project", intent)).rejects.toThrow("Lost placement response");
    shot.dialogue = "A later edit must stay in the board.";
    const result = await prepareStoryboardNode("project", intent);
    expect(result.canvasItemId).toBe(intent.progress.itemId);
    expect(createArtifact).toHaveBeenCalledTimes(1);
    expect(applyCanvasCommands).toHaveBeenCalledTimes(2);
    expect(vi.mocked(applyCanvasCommands).mock.calls[0]).toEqual(vi.mocked(applyCanvasCommands).mock.calls[1]);
    expect(saveMediaDraft).toHaveBeenCalledWith("project", intent.progress.itemId, expect.objectContaining({
      prompt: "The traveler arrives.\n\nHello.", expectedVersion: 0, mediaInputs: [], durationSeconds: null,
    }));
    expect(runMediaDraft).not.toHaveBeenCalled();
  });
});
