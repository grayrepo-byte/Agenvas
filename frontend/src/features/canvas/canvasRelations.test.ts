import { describe, expect, it } from "vitest";
import type { CanvasItem } from "../../shared/api/client";
import { inputBindingsAfterConnect, inputConnectionUpdate,
  projectCanvasRelations, semanticConnectionRevision,
  semanticReferenceRemoval } from "./canvasRelations";

const createdAt = "2026-09-24T00:00:00Z";

/** Typed cards mirror the API projection; no relation state is created in React Flow. */
function artifactCard(id: string, versionId: string, groupId: string | null = null,
  inputReferences: NonNullable<CanvasItem["artifact"]>["currentVersion"]["inputReferences"] = [],
): CanvasItem {
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
    expect(edges.map((edge) => [edge.source, edge.target, edge.label])).toEqual([
      ["card-artifact-a", "card-agent", "输入"],
      ["card-agent", "card-artifact-b", "Agent 输出组"],
      ["card-artifact-a", "card-artifact-b", "素材引用 · source"],
    ]);
    expect(new Set(edges.map((edge) => edge.id)).size).toBe(3);
  });

  it("labels historical bindings and never misdraws a historical reference to current content", () => {
    const current = artifactCard("artifact-a", "version-new");
    const output = artifactCard("artifact-b", "version-b", null, [
      { versionId: "version-old", role: "source", order: 0, kind: "TEXT" },
    ]);
    const edges = projectCanvasRelations([current, agentCard("version-old"), output]);
    expect(edges).toHaveLength(1);
    expect(edges[0]?.label).toBe("输入 · 历史版本");
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
