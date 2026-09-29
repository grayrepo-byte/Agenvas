import { describe, expect, it } from "vitest";
import type { CanvasConnection, CanvasItem } from "../../shared/api/client";
import type { VersionedArtifact } from "./versionedArtifact";
import { agentImageConnection, canvasRelationRemoval, canvasTargetHandleId, inputBindingsAfterConnect,
  inputConnectionUpdate, isCanvasConnectionValid, projectCanvasRelations } from "./canvasRelations";

const createdAt = "2026-09-24T00:00:00Z";

function mediaConnection(source: CanvasItem, target: CanvasItem,
  sourceVersionId: string): CanvasConnection {
  return { id: "connection-1", projectId: "project-1", sourceCanvasItemId: source.id,
    targetCanvasItemId: target.id, relationType: "MEDIA_INPUT",
    sourceArtifactVersionId: sourceVersionId, version: 0, createdAt, updatedAt: createdAt };
}

function agentImageEdge(source: CanvasItem, target: CanvasItem,
  sourceVersionId: string): CanvasConnection {
  return { ...mediaConnection(source, target, sourceVersionId), id: "agent-image-connection",
    relationType: "AGENT_IMAGE_INPUT" };
}

function mediaDerivation(source: CanvasItem, target: CanvasItem,
  sourceVersionId: string): CanvasConnection {
  return { ...mediaConnection(source, target, sourceVersionId), id: "media-derivation",
    relationType: "MEDIA_DERIVATION" };
}

/** Typed cards mirror the API projection; no relation state is created in React Flow. */
function artifactCard(id: string, versionId: string, groupId: string | null = null,
  inputReferences: VersionedArtifact["resourceDefaultVersion"]["inputReferences"] = [],
): CanvasItem & { artifact: VersionedArtifact } {
  return {
    id: `card-${id}`, subjectType: "ARTIFACT", subjectId: id, title: id,
    x: 0, y: 0, width: 280, height: 180, zIndex: 0, groupId, locked: false, selectedVersionId: null, selectedVersion: null, version: 0,
    agent: null,
    artifact: {
      id, projectId: "project-1", kind: "TEXT", title: id,
      resourceDefaultVersionId: versionId, version: 0, createdAt, updatedAt: createdAt,
      resourceDefaultVersion: {
        id: versionId, versionNo: 1, schemaVersion: 1,
        content: { format: "PLAIN_TEXT", text: "content" }, inputReferences,
        createdByKind: "USER", runId: null, createdAt,
      },
    },
  };
}

function mediaCard(id: string, resourceDefaultVersionId: string, selectedVersionId: string,
  inputReferences: VersionedArtifact["resourceDefaultVersion"]["inputReferences"] = [],
): CanvasItem {
  const card = artifactCard(id, resourceDefaultVersionId);
  card.artifact.kind = "IMAGE";
  card.selectedVersionId = selectedVersionId;
  card.selectedVersion = {
    ...card.artifact.resourceDefaultVersion,
    id: selectedVersionId,
    inputReferences,
  };
  return card;
}

function agentCard(bindingVersion = "version-a"): CanvasItem {
  return {
    id: "card-agent", subjectType: "AGENT", subjectId: "agent-1", title: "Agent",
    x: 400, y: 0, width: 320, height: 280, zIndex: 1, groupId: null,
    locked: false, selectedVersionId: null, selectedVersion: null, version: 0, artifact: null,
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
  it("keeps Agent bindings, output ownership, and persisted media inputs distinct", () => {
    const input = mediaCard("artifact-a", "version-a", "version-a");
    const output = mediaCard("artifact-b", "version-b", "version-b");
    output.groupId = "output-group-1";
    const edges = projectCanvasRelations([input, agentCard(), output], [
      mediaConnection(input, output, "version-a"),
    ]);

    expect(edges).toHaveLength(3);
    expect(edges.map((edge) => [edge.source, edge.target])).toEqual([
      ["card-artifact-a", "card-artifact-b"],
      ["card-artifact-a", "card-agent"],
      ["card-agent", "card-artifact-b"],
    ]);
    expect(edges.every((edge) => edge.label === undefined)).toBe(true);
    expect(new Set(edges.map((edge) => edge.id)).size).toBe(3);
    expect(edges.map((edge) => edge.className)).toEqual([
      "relation-edge relation-edge--reference",
      "relation-edge relation-edge--input-binding",
      "relation-edge relation-edge--agent-output",
    ]);
    // Output-group membership is derived from the Agent, so that line offers no selection or deletion.
    expect(edges[2]).toMatchObject({ deletable: false, selectable: false });
    expect(edges[0]?.deletable).toBeUndefined();
    expect(edges[1]?.deletable).toBeUndefined();
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

  it("projects an Agent image line once and resolves its exact card-local version", () => {
    const image = mediaCard("artifact-a", "version-library", "version-card");
    const agent = agentCard("version-card");
    const connection = agentImageEdge(image, agent, "version-card");
    const edges = projectCanvasRelations([image, agent], [connection]);
    expect(edges).toHaveLength(1);
    expect(edges[0]).toMatchObject({ source: image.id, target: agent.id,
      id: `canvas-connection:${connection.id}` });
    expect(agentImageConnection([image, agent], {
      source: image.id, sourceHandle: "artifact-output",
      target: agent.id, targetHandle: "agent-input",
    })).toMatchObject({ sourceVersionId: "version-card", agent: agent.agent });
    expect(inputConnectionUpdate([image, agent], {
      source: image.id, sourceHandle: "artifact-output",
      target: agent.id, targetHandle: "agent-input",
    })).toBeNull();
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

  it("projects a media card's selected version instead of its resource default", () => {
    const input = mediaCard("artifact-a", "version-library", "version-card");
    const output = mediaCard("artifact-b", "version-output-library", "version-output-card");
    const edges = projectCanvasRelations([input, agentCard("version-card"), output], [
      mediaConnection(input, output, "version-card"),
    ]);

    expect(edges).toHaveLength(2);
    expect(edges[0]).toMatchObject({ source: input.id, target: output.id,
      className: "relation-edge relation-edge--reference" });
    expect(edges[1]?.className).toBe("relation-edge relation-edge--input-binding");
    expect(inputBindingsAfterConnect(input, agentCard("version-old"))).toEqual([
      { artifactId: "artifact-a", selectedVersionId: "version-card" },
    ]);
  });

  it("projects media derivation as removable lineage rather than a draft input", () => {
    const source = mediaCard("artifact-a", "version-a", "version-a");
    const result = mediaCard("artifact-a", "version-a", "version-result");
    result.id = "card-result";
    const connection = mediaDerivation(source, result, "version-a");
    const edge = projectCanvasRelations([source, result], [connection])[0];

    expect(edge).toMatchObject({
      source: source.id,
      target: result.id,
      className: "relation-edge relation-edge--derivation",
    });
    expect(canvasRelationRemoval([source, result], [connection], edge!)).toEqual({
      kind: "mediaConnection", connection,
    });
    expect(result.selectedVersionId).toBe("version-result");
    expect(source.selectedVersionId).toBe("version-a");
  });

  it("draws a shared Artifact binding from the card displaying the pinned version", () => {
    const firstBranch = mediaCard("artifact-a", "version-library", "version-first");
    const secondBranch = mediaCard("artifact-a", "version-library", "version-second");
    secondBranch.id = "card-artifact-a-second";

    const edges = projectCanvasRelations([
      firstBranch, secondBranch, agentCard("version-second"),
    ]);

    expect(edges).toHaveLength(1);
    expect(edges[0]).toMatchObject({ source: secondBranch.id, target: "card-agent" });
    expect(edges[0]?.className).toBe("relation-edge relation-edge--input-binding");
  });

  it("hand-drawn relations only ever bind an Artifact into an Agent input", () => {
    const input = artifactCard("artifact-a", "version-new");
    const agent = agentCard("version-old");
    const other = artifactCard("artifact-b", "version-b");
    expect(isCanvasConnectionValid([input, agent], {
      source: input.id, sourceHandle: "artifact-output",
      target: agent.id, targetHandle: "agent-input",
    })).toBe(true);
    // 一张 Artifact 与另一张 Artifact 之间不再有可提交的连线手势。
    expect(isCanvasConnectionValid([input, other], {
      source: input.id, sourceHandle: "artifact-output",
      target: other.id, targetHandle: "artifact-input",
    })).toBe(false);
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

  it("reads a projected Edge as well as a live Connection", () => {
    const input = artifactCard("artifact-a", "version-a");
    const agent = agentCard();
    const projected = projectCanvasRelations([input, agent])[0]!;
    expect(projected.source).toBe(input.id);
    expect(isCanvasConnectionValid([input, agent], projected)).toBe(true);
  });
});

describe("canvas target handle", () => {
  it("names the single handle a card receives manual relations on", () => {
    expect(canvasTargetHandleId(agentCard())).toBe("agent-input");
    expect(canvasTargetHandleId(artifactCard("artifact-a", "version-a"))).toBe("artifact-input");
    // 首次生成前允许媒体版本为空，但草稿仍能接收图片输入连线。
    const unversioned = artifactCard("artifact-c", "version-c");
    unversioned.artifact!.kind = "IMAGE";
    unversioned.artifact!.resourceDefaultVersionId = null as never;
    unversioned.artifact!.resourceDefaultVersion = null as never;
    expect(canvasTargetHandleId(unversioned)).toBe("artifact-input");
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

  it("refuses the derived output line, unknown handle pairs and a line whose card is gone", () => {
    const input = artifactCard("artifact-a", "version-a");
    const output = artifactCard("artifact-b", "version-b", "output-group-1");
    const items = [input, output, agentCard()];
    const edges = new Map(projectCanvasRelations(items).map((edge) => [edge.id, edge]));
    expect(canvasRelationRemoval(items, edges.get(`output:card-agent:${output.id}`)!)).toBeNull();
    expect(canvasRelationRemoval(items, {
      id: "unknown", source: input.id, target: "card-agent",
      sourceHandle: "artifact-output", targetHandle: "missing-handle",
    })).toBeNull();
    // A line whose exact version is no longer on the canvas has nothing left to remove.
    expect(canvasRelationRemoval([agentCard()],
      edges.get(`input:binding-a:${input.id}:card-agent`)!)).toBeNull();
  });
});
