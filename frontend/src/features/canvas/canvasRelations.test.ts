import { describe, expect, it } from "vitest";
import type { CanvasItem } from "../../shared/api/client";
import type { VersionedArtifact } from "./versionedArtifact";
import { canvasRelationRemoval, canvasTargetHandleId, inputBindingsAfterConnect,
  inputConnectionUpdate, isCanvasConnectionValid, projectCanvasRelations,
  semanticConnectionRevision, semanticReferenceRemoval } from "./canvasRelations";

const createdAt = "2026-09-24T00:00:00Z";

/** Typed cards mirror the API projection; no relation state is created in React Flow. */
function artifactCard(id: string, versionId: string, groupId: string | null = null,
  inputReferences: VersionedArtifact["currentVersion"]["inputReferences"] = [],
): CanvasItem & { artifact: VersionedArtifact } {
  return {
    id: `card-${id}`, subjectType: "ARTIFACT", subjectId: id,
    x: 0, y: 0, width: 280, height: 180, zIndex: 0, groupId, locked: false, version: 0,
    agent: null,
    artifact: {
      id, projectId: "project-1", kind: "TEXT", title: id,
      currentVersionId: versionId, version: 0, createdAt, updatedAt: createdAt,
      currentVersion: {
        id: versionId, versionNo: 1, schemaVersion: 1,
        content: { format: "PLAIN_TEXT", text: "content" }, inputReferences,
        createdByKind: "USER", runId: null, createdAt,
      },
    },
  };
}

function agentCard(bindingVersion = "version-a"): CanvasItem {
  return {
    id: "card-agent", subjectType: "AGENT", subjectId: "agent-1",
    x: 400, y: 0, width: 320, height: 280, zIndex: 1, groupId: null,
    locked: false, version: 0, artifact: null,
    agent: {
      id: "agent-1", projectId: "project-1", profileKey: "creator",
      profileVersion: 1, name: "Creator", instruction: "Create",
      outputGroupId: "output-group-1", version: 3, createdAt,
      updatedAt: createdAt,
      bindings: [{ id: "binding-a", artifactId: "artifact-a",
        selectedVersionId: bindingVersion, bindingType: "INPUT" }],
    },
  };
}

describe("canvas relation projection", () => {
  it("keeps input, output ownership, and exact-version semantic references distinct", () => {
    const input = artifactCard("artifact-a", "version-a");
    const output = artifactCard("artifact-b", "version-b", "output-group-1", [
      { versionId: "version-a", role: "source", order: 0, kind: "TEXT" },
    ]);
    const edges = projectCanvasRelations([input, agentCard(), output]);

    expect(edges).toHaveLength(3);
    expect(edges.map((edge) => [edge.source, edge.target])).toEqual([
      ["card-artifact-a", "card-agent"],
      ["card-agent", "card-artifact-b"],
      ["card-artifact-a", "card-artifact-b"],
    ]);
    expect(edges.every((edge) => edge.label === undefined)).toBe(true);
    expect(new Set(edges.map((edge) => edge.id)).size).toBe(3);
    expect(edges.map((edge) => edge.className)).toEqual([
      "relation-edge relation-edge--input-binding",
      "relation-edge relation-edge--agent-output",
      "relation-edge relation-edge--reference",
    ]);
    // Output-group membership is derived from the Agent, so that line offers no selection or deletion.
    expect(edges[1]).toMatchObject({ deletable: false, selectable: false });
    expect(edges[0]?.deletable).toBeUndefined();
    expect(edges[2]?.selectable).toBeUndefined();
  });

  it("marks historical bindings with a dashed line and never misdraws them to newer content", () => {
    const current = artifactCard("artifact-a", "version-new");
    const output = artifactCard("artifact-b", "version-b", null, [
      { versionId: "version-old", role: "source", order: 0, kind: "TEXT" },
    ]);
    const edges = projectCanvasRelations([current, agentCard("version-old"), output]);
    expect(edges).toHaveLength(1);
    expect(edges[0]?.className).toBe(
      "relation-edge relation-edge--input-binding relation-edge--input-binding-historical");
  });

  it("manual Artifact to Agent binding pins the selected current version without duplicates", () => {
    const input = artifactCard("artifact-a", "version-new");
    const agent = agentCard("version-old");
    const updated = inputBindingsAfterConnect(input, agent);
    expect(updated).toEqual([{ artifactId: "artifact-a",
      selectedVersionId: "version-new" }]);
    expect(agent.agent?.bindings[0]?.selectedVersionId).toBe("version-old");
    expect(inputBindingsAfterConnect(agent, input)).toBeNull();
    expect(inputConnectionUpdate([input, agent], {
      source: input.id, sourceHandle: "artifact-output",
      target: agent.id, targetHandle: "agent-input",
    })).toEqual({ agent: agent.agent, bindings: updated });
    expect(inputConnectionUpdate([input, agent], {
      source: agent.id, sourceHandle: "agent-output",
      target: input.id, targetHandle: "artifact-input",
    })).toBeNull();
  });

  it("hand-drawn semantic edges revise only the consuming content with exact version and CAS", () => {
    const image = artifactCard("image", "image-v2");
    image.artifact!.kind = "IMAGE";
    const character = artifactCard("character", "character-v1");
    character.artifact!.kind = "CHARACTER";
    character.artifact!.version = 4;
    character.artifact!.currentVersion.content = {
      name: "Hero", description: "Lead", appearance: "Blue coat", referenceVersionIds: [],
    };
    const connection = { source: image.id, sourceHandle: "artifact-output",
      target: character.id, targetHandle: "artifact-input" };
    expect(semanticConnectionRevision([image, character], connection)).toEqual({
      artifactId: "character",
      revision: { expectedVersion: 4, content: {
        name: "Hero", description: "Lead", appearance: "Blue coat",
        referenceVersionIds: ["image-v2"],
      } },
    });
    expect(character.artifact!.currentVersion.content).toEqual({
      name: "Hero", description: "Lead", appearance: "Blue coat", referenceVersionIds: [],
    });
    expect(semanticConnectionRevision([character, image], {
      ...connection, source: character.id, target: image.id,
    })).toBeNull();
  });

  it("allows shot character append and scene replacement but rejects duplicate or unsupported links", () => {
    const character = artifactCard("character", "character-v2");
    character.artifact!.kind = "CHARACTER";
    const scene = artifactCard("scene", "scene-v2");
    scene.artifact!.kind = "SCENE";
    const shot = artifactCard("shot", "shot-v1");
    shot.artifact!.kind = "SHOT";
    shot.artifact!.currentVersion.content = {
      order: 1, durationSeconds: 3, description: "Shot", camera: "wide", action: "walk",
      characterVersionIds: ["character-v1"], sceneVersionId: "scene-v1",
    };
    const connect = (source: CanvasItem) => semanticConnectionRevision([source, shot], {
      source: source.id, sourceHandle: "artifact-output",
      target: shot.id, targetHandle: "artifact-input",
    });
    expect(connect(character)?.revision?.content).toMatchObject({
      characterVersionIds: ["character-v1", "character-v2"], sceneVersionId: "scene-v1",
    });
    expect(connect(scene)?.revision?.content).toMatchObject({ sceneVersionId: "scene-v2" });
    character.artifact!.currentVersionId = "character-v1";
    expect(connect(character)).toEqual({ artifactId: "shot", revision: null });
    expect(connect(shot)).toBeNull();
  });

  it("removes optional references by exact role, index and version without touching required scene", () => {
    const character = artifactCard("character", "character-v3", null, [
      { versionId: "image-a", role: "referenceImage", order: 0, kind: "IMAGE" },
      { versionId: "image-b", role: "referenceImage", order: 1, kind: "IMAGE" },
    ]);
    character.artifact!.kind = "CHARACTER";
    character.artifact!.version = 2;
    character.artifact!.currentVersion.content = {
      name: "Hero", description: "Lead", appearance: "Blue coat",
      referenceVersionIds: ["image-a", "image-b"],
    };
    const imageRef = character.artifact!.currentVersion.inputReferences[0]!;
    expect(semanticReferenceRemoval(character, imageRef)).toEqual({
      expectedVersion: 2,
      content: { name: "Hero", description: "Lead", appearance: "Blue coat",
        referenceVersionIds: ["image-b"] },
    });
    expect(character.artifact!.currentVersion.content).toMatchObject({
      referenceVersionIds: ["image-a", "image-b"],
    });
    expect(semanticReferenceRemoval(character, { ...imageRef, order: 1 })).toBeNull();

    const shot = artifactCard("shot", "shot-v2", null, [
      { versionId: "character-v1", role: "character", order: 0, kind: "CHARACTER" },
      { versionId: "scene-v1", role: "scene", order: 0, kind: "SCENE" },
    ]);
    shot.artifact!.kind = "SHOT";
    shot.artifact!.currentVersion.content = {
      order: 1, durationSeconds: 3, description: "Shot", camera: "wide", action: "walk",
      characterVersionIds: ["character-v1"], sceneVersionId: "scene-v1",
    };
    expect(semanticReferenceRemoval(shot,
      shot.artifact!.currentVersion.inputReferences[0]!)?.content).toMatchObject({
      characterVersionIds: [], sceneVersionId: "scene-v1",
    });
    expect(semanticReferenceRemoval(shot,
      shot.artifact!.currentVersion.inputReferences[1]!)).toBeNull();
  });

  it("does not silently revise a selected historical millisecond shot", () => {
    const character = artifactCard("character", "character-v2");
    character.artifact!.kind = "CHARACTER";
    const shot = artifactCard("shot", "shot-v1", null, [
      { versionId: "character-v1", role: "character", order: 0, kind: "CHARACTER" },
    ]);
    shot.artifact!.kind = "SHOT";
    shot.artifact!.currentVersion.content = {
      order: 1, durationMs: 4500, description: "Shot", camera: "wide", action: "walk",
      characterVersionIds: ["character-v1"], sceneVersionId: "scene-v1",
    };
    expect(semanticConnectionRevision([character, shot], {
      source: character.id, sourceHandle: "artifact-output",
      target: shot.id, targetHandle: "artifact-input",
    })).toBeNull();
    expect(semanticReferenceRemoval(shot,
      shot.artifact!.currentVersion.inputReferences[0]!)).toBeNull();
  });
});

describe("canvas connection validity", () => {
  it("accepts Artifact to Agent input and rejects every other handle pair", () => {
    const input = artifactCard("artifact-a", "version-a");
    const agent = agentCard();
    expect(isCanvasConnectionValid([input, agent], {
      source: input.id, sourceHandle: "artifact-output",
      target: agent.id, targetHandle: "agent-input",
    })).toBe(true);
    expect(isCanvasConnectionValid([input, agent], {
      source: agent.id, sourceHandle: "agent-output",
      target: input.id, targetHandle: "artifact-input",
    })).toBe(false);
    expect(isCanvasConnectionValid([input, agent], {
      source: input.id, sourceHandle: "artifact-output",
      target: agent.id, targetHandle: null,
    })).toBe(false);
  });

  it("follows the semantic revision path, including the no-op repeat drop", () => {
    const image = artifactCard("image", "image-v2");
    image.artifact!.kind = "IMAGE";
    const character = artifactCard("character", "character-v2");
    character.artifact!.kind = "CHARACTER";
    character.artifact!.currentVersion.content = {
      name: "Hero", description: "Lead", appearance: "Blue coat",
      referenceVersionIds: ["image-v2"],
    };
    const gesture = { source: image.id, sourceHandle: "artifact-output",
      target: character.id, targetHandle: "artifact-input" };
    // Re-dropping a version that is already referenced commits as a no-op success, so it must not read as invalid.
    expect(semanticConnectionRevision([image, character], gesture))
      .toEqual({ artifactId: "character", revision: null });
    expect(isCanvasConnectionValid([image, character], gesture)).toBe(true);
    expect(isCanvasConnectionValid([image, character], { ...gesture, target: image.id }))
      .toBe(false);
    expect(isCanvasConnectionValid([image, artifactCard("text", "text-v1")], {
      ...gesture, target: "card-text",
    })).toBe(false);
  });

  it("reads a projected Edge as well as a live Connection", () => {
    const image = artifactCard("image", "image-v2");
    image.artifact!.kind = "IMAGE";
    const character = artifactCard("character", "character-v1");
    character.artifact!.kind = "CHARACTER";
    character.artifact!.currentVersion.content = {
      name: "Hero", description: "Lead", appearance: "Blue coat", referenceVersionIds: [],
    };
    expect(isCanvasConnectionValid([image, character], {
      id: "reference:card-character:referenceImage:0:image-v2",
      source: image.id, sourceHandle: "artifact-output",
      target: character.id, targetHandle: "artifact-input",
    })).toBe(true);
  });
});

describe("canvas target handle", () => {
  it("names the single handle a card receives manual relations on", () => {
    expect(canvasTargetHandleId(agentCard())).toBe("agent-input");
    expect(canvasTargetHandleId(artifactCard("artifact-a", "version-a"))).toBe("artifact-input");
    // 首次生成前允许当前媒体版本为空，这种卡片还不能接收引用。
    const unversioned = artifactCard("artifact-c", "version-c");
    unversioned.artifact!.currentVersionId = null as never;
    unversioned.artifact!.currentVersion = null as never;
    expect(canvasTargetHandleId(unversioned)).toBeNull();
  });
});

describe("canvas relation removal", () => {
  it("resolves a selected input line to the Agent binding it stands for", () => {
    const input = artifactCard("artifact-a", "version-a");
    const agent = agentCard();
    const edges = projectCanvasRelations([input, agent]);
    expect(edges).toHaveLength(1);
    expect(canvasRelationRemoval([input, agent], edges[0]!)).toEqual({
      kind: "inputBinding", agent: agent.agent, bindingId: "binding-a",
    });
  });

  it("resolves a reference line to the exact reference the consumer may drop", () => {
    const image = artifactCard("image", "image-v2");
    image.artifact!.kind = "IMAGE";
    const character = artifactCard("character", "character-v2", null, [
      { versionId: "image-v2", role: "referenceImage", order: 0, kind: "IMAGE" },
    ]);
    character.artifact!.kind = "CHARACTER";
    character.artifact!.currentVersion.content = {
      name: "Hero", description: "Lead", appearance: "Blue coat",
      referenceVersionIds: ["image-v2"],
    };
    const edges = projectCanvasRelations([image, character]);
    expect(edges).toHaveLength(1);
    expect(canvasRelationRemoval([image, character], edges[0]!)).toEqual({
      kind: "reference", item: character,
      reference: { versionId: "image-v2", role: "referenceImage", order: 0, kind: "IMAGE" },
    });
  });

  it("refuses the derived output line, a required scene reference and unknown handle pairs", () => {
    const scene = artifactCard("scene", "scene-v1");
    scene.artifact!.kind = "SCENE";
    const shot = artifactCard("shot", "shot-v1", null, [
      { versionId: "scene-v1", role: "scene", order: 0, kind: "SCENE" },
    ]);
    shot.artifact!.kind = "SHOT";
    shot.artifact!.currentVersion.content = {
      order: 1, durationSeconds: 3, description: "Shot", camera: "wide", action: "walk",
      characterVersionIds: [], sceneVersionId: "scene-v1",
    };
    const output = artifactCard("artifact-b", "version-b", "output-group-1");
    const items = [scene, shot, output, agentCard()];
    const edges = new Map(projectCanvasRelations(items).map((edge) => [edge.id, edge]));
    expect(canvasRelationRemoval(items, edges.get(`output:card-agent:${output.id}`)!)).toBeNull();
    expect(canvasRelationRemoval(items,
      edges.get(`reference:${shot.id}:scene:0:scene-v1`)!)).toBeNull();
    expect(canvasRelationRemoval(items, {
      id: "unknown", source: scene.id, target: shot.id,
      sourceHandle: "artifact-output", targetHandle: "missing-handle",
    })).toBeNull();
    // A line whose exact version is no longer on the canvas has nothing left to remove.
    expect(canvasRelationRemoval([scene],
      edges.get(`reference:${shot.id}:scene:0:scene-v1`)!)).toBeNull();
  });
});
