import { describe, expect, it } from "vitest";
import type { CanvasItem } from "../../shared/api/client";
import { characterConnectionForGesture, projectCharacterRelations } from "./characterCanvas";
import { canvasItemVersionId } from "./versionedArtifact";

const role: CanvasItem = {
  id: "role",
  subjectType: "CHARACTER",
  subjectId: "profile",
  title: "Hero",
  x: 0,
  y: 0,
  width: 320,
  height: 240,
  zIndex: 0,
  locked: false,
  groupId: null,
  version: 1,
  selectedVersionId: null,
  selectedVersion: null,
  artifact: null,
  characterNode: {
    canvasItemId: "role",
    version: 1,
    newerVersionAvailable: true,
    selection: {
      characterId: "profile",
      characterVersion: 3,
      lookId: "look",
      viewVersionIds: ["front"],
      useAppearance: true,
      useVoice: false,
    },
    snapshot: {
      characterId: "profile",
      characterVersion: 3,
      name: "Hero",
      description: "Pilot",
      look: {
        id: "look",
        name: "Flight suit",
        clothing: "",
        expression: "",
        description: "",
        views: [{ versionId: "front", purpose: "Front", description: "" }],
      },
      voice: null,
      useAppearance: true,
      useVoice: false,
    },
    connections: [{ id: "link", sourceCanvasItemId: "role", targetCanvasItemId: "image", version: 1 }],
  },
};
const target: CanvasItem = {
  ...role,
  id: "image",
  subjectType: "ARTIFACT",
  subjectId: "media",
  characterNode: null,
  artifact: {
    id: "media",
    projectId: "project",
    kind: "IMAGE",
    title: "Shot",
    version: 1,
    resourceDefaultVersionId: null,
    resourceDefaultVersion: null,
    createdAt: "2026-11-06T00:00:00Z",
    updatedAt: "2026-11-06T00:00:00Z",
  },
};
const gesture = { source: "role", target: "image", sourceHandle: "artifact-output", targetHandle: "artifact-input" };

describe("character canvas projection", () => {
  it("keeps a real character node independent of media versions and projects only its persisted relationship", () => {
    expect(canvasItemVersionId(role)).toBeNull();
    expect(projectCharacterRelations([role, target])).toEqual([
      expect.objectContaining({ id: "character-connection:link", source: "role", target: "image" }),
    ]);
    expect(projectCharacterRelations([role])).toEqual([]);
    expect(role.characterNode?.selection.characterVersion).toBe(3);
  });
  it("routes character gestures to image/video preview, while rejecting audio, locked and reversed gestures", () => {
    expect(characterConnectionForGesture([role, target], gesture)).toEqual({ source: role, target });
    expect(characterConnectionForGesture([role, { ...target, locked: true }], gesture)).toBeNull();
    expect(characterConnectionForGesture([{ ...role, locked: true }, target], gesture)).toBeNull();
    expect(characterConnectionForGesture([role, target], { ...gesture, sourceHandle: "artifact-input" })).toBeNull();
    if (!target.artifact) throw new Error("Expected fixture artifact");
    expect(
      characterConnectionForGesture([role, { ...target, artifact: { ...target.artifact, kind: "AUDIO" } }], gesture),
    ).toBeNull();
  });
});
