import { t } from "../../shared/i18n";
import { applyCanvasCommands, createArtifact, getMediaDraft, listCanvasItems, saveMediaDraft,
  type Artifact, type SaveMediaDraftRequest } from "../../shared/api/client";
export const AUDIO_CARD_WIDTH = 560;
export const AUDIO_CARD_HEIGHT = 300;
/** Blank visual media share a landscape frame; actual pixels or an explicit image draft ratio take over later. */
export const VISUAL_MEDIA_CARD_WIDTH = 640;
export const VISUAL_MEDIA_CARD_HEIGHT = 360;
const NODE_GAP = 64;
export type PreparedMediaNode = { createKey: string; itemId: string; artifactId?: string; draftVersion?: number; placed?: boolean };

/** Carries stable identities across retries of the explicit create-and-fill UI action. */
export async function prepareMediaNode(projectId: string, sourceItemId: string,
  kind: Artifact["kind"], title: string, fields: Omit<SaveMediaDraftRequest, "expectedVersion">,
  progress: PreparedMediaNode) {
  if (!progress.artifactId) progress.artifactId = (await createArtifact(projectId, { kind, title, content: null }, progress.createKey)).id;
  if (!progress.placed) {
    const canvas = await listCanvasItems(projectId);
    const source = canvas.items.find((item) => item.id === sourceItemId);
    await applyCanvasCommands(projectId, [{ type: "PLACE_ARTIFACT", artifactId: progress.artifactId,
      itemId: progress.itemId, x: (source?.x ?? 0) + (source?.width ?? AUDIO_CARD_WIDTH) + NODE_GAP,
      y: source?.y ?? 0,
      width: kind === "AUDIO" ? AUDIO_CARD_WIDTH : VISUAL_MEDIA_CARD_WIDTH,
      height: kind === "AUDIO" ? AUDIO_CARD_HEIGHT : VISUAL_MEDIA_CARD_HEIGHT,
      zIndex: canvas.items.length, locked: false }]);
    progress.placed = true;
  }
  if (progress.draftVersion === undefined) {
    const current = await getMediaDraft(projectId, progress.itemId);
    // An action's blank target is created with the same ID even if the response was lost.
    if (current.version > 0) {
      const equal = current.prompt === fields.prompt && current.capabilityId === fields.capabilityId
        && (current.styleId ?? null) === (fields.styleId ?? null)
        && JSON.stringify(current.parameters) === JSON.stringify(fields.parameters)
        && current.durationSeconds === fields.durationSeconds && current.videoInputMode === fields.videoInputMode
        && JSON.stringify(current.mentions) === JSON.stringify(fields.mentions)
        && current.mediaInputs.map((input) => `${input.versionId}:${input.role}`).join()
          === fields.mediaInputs.map((input) => `${input.versionId}:${input.role}`).join();
      if (!equal) throw new Error(t("media.nodes.newNodeConflict"));
      progress.draftVersion = current.version;
    } else progress.draftVersion = (await saveMediaDraft(projectId, progress.itemId,
      { ...fields, expectedVersion: current.version })).version;
  }
  return { artifactId: progress.artifactId, canvasItemId: progress.itemId, expectedDraftVersion: progress.draftVersion };
}
