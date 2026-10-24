import type { CanvasItem, SaveMediaDraftRequest } from "../../shared/api/client";
import { prepareMediaNode, type PreparedMediaNode } from "./mediaNodeActions";
import type { ProductionInput, ShotInput } from "./storyboardDraft";

const ROLE_KIND = { KEYFRAME: "IMAGE", VIDEO: "VIDEO", AUDIO: "AUDIO" } as const;

/** A user's prepare action freezes its identities and fields before the first write. */
export type StoryboardNodePreparation = {
  role: ProductionInput["role"];
  sourceItemId: string;
  title: string;
  fields: Omit<SaveMediaDraftRequest, "expectedVersion">;
  progress: PreparedMediaNode;
};

/**
 * Create editable inputs only. Board reference roles and estimated timing are creative
 * annotations; the user still chooses compatible model inputs and duration in the node.
 */
export function createStoryboardNodePreparation(shot: ShotInput, role: ProductionInput["role"],
  items: readonly CanvasItem[], title: string): StoryboardNodePreparation {
  const rightmost = items.reduce<CanvasItem | undefined>((current, item) =>
    !current || item.x + item.width > current.x + current.width ? item : current, undefined);
  const prompt = role === "AUDIO" ? shot.dialogue : role === "KEYFRAME" ? shot.description
    : [shot.description, shot.dialogue].filter((value) => value.trim()).join("\n\n");
  return { role, sourceItemId: rightmost?.id ?? "", title,
    fields: { prompt, parameters: {}, durationSeconds: null, capabilityId: null,
      videoInputMode: role === "VIDEO" ? "TEXT" : null, mediaInputs: [], mentions: [] },
    progress: { createKey: crypto.randomUUID(), itemId: crypto.randomUUID() } };
}

/** Retries resume confirmed local stages with the original payload; no generation is submitted. */
export function prepareStoryboardNode(projectId: string, intent: StoryboardNodePreparation) {
  return prepareMediaNode(projectId, intent.sourceItemId, ROLE_KIND[intent.role], intent.title,
    intent.fields, intent.progress);
}
