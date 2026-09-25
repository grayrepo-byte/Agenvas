import type { Connection, Edge } from "@xyflow/react";
import type { CanvasItem, ReviseArtifactRequest } from "../../shared/api/client";

/** Visual relationships are projections, never execution dependencies or generation commands. */
export function projectCanvasRelations(items: CanvasItem[]): Edge[] {
  const artifactCards = new Map<string, CanvasItem>();
  const versionCards = new Map<string, CanvasItem>();
  const outputGroups = new Map<string, CanvasItem[]>();
  const agentCards = items.filter((item) => item.agent !== null);
  for (const item of items) {
    if (!item.artifact) continue;
    if (!artifactCards.has(item.artifact.id)) artifactCards.set(item.artifact.id, item);
    versionCards.set(item.artifact.currentVersionId, item);
    if (item.groupId) {
      const grouped = outputGroups.get(item.groupId) ?? [];
      grouped.push(item);
      outputGroups.set(item.groupId, grouped);
    }
  }

  const edges: Edge[] = [];
  for (const agentCard of agentCards) {
    const agent = agentCard.agent;
    if (!agent) continue;
    for (const binding of agent.bindings) {
      const input = artifactCards.get(binding.artifactId);
      if (!input) continue;
      const historical = input.artifact?.currentVersionId !== binding.selectedVersionId;
      edges.push({
        id: `input:${binding.id}:${input.id}:${agentCard.id}`,
        source: input.id,
        sourceHandle: "artifact-output",
        target: agentCard.id,
        targetHandle: "agent-input",
        label: historical ? "输入 · 历史版本" : "输入",
        type: "smoothstep",
        style: { stroke: "#2563eb", strokeWidth: 2 },
      });
    }
    for (const output of outputGroups.get(agent.outputGroupId) ?? []) {
      edges.push({
        id: `output:${agentCard.id}:${output.id}`,
        source: agentCard.id,
        sourceHandle: "agent-output",
        target: output.id,
        targetHandle: "artifact-input",
        label: "Agent 输出组",
        type: "smoothstep",
        style: { stroke: "#059669", strokeWidth: 1.5 },
      });
    }
  }

  // A visible current version can name only visible exact-version inputs. Historical
  // references stay in the Artifact record; we must not draw them to a newer version.
  for (const output of items) {
    if (!output.artifact) continue;
    for (const reference of output.artifact.currentVersion.inputReferences) {
      const input = versionCards.get(reference.versionId);
      if (!input || input.id === output.id) continue;
      edges.push({
        id: `reference:${output.id}:${reference.role}:${reference.order}:${reference.versionId}`,
        source: input.id,
        sourceHandle: "artifact-output",
        target: output.id,
        targetHandle: "artifact-input",
        label: `素材引用 · ${reference.role}`,
        type: "smoothstep",
        style: { stroke: "#64748b", strokeWidth: 1.5, strokeDasharray: "4 4" },
      });
    }
  }
  return edges;
}

/** One manual Artifact → Agent gesture updates only that Agent's explicit input binding. */
export function inputBindingsAfterConnect(source: CanvasItem, target: CanvasItem) {
  if (!source.artifact || !target.agent || source.id === target.id) return null;
  const bindings = new Map(target.agent.bindings.map((binding) => [binding.artifactId,
    { artifactId: binding.artifactId, selectedVersionId: binding.selectedVersionId }]));
  bindings.set(source.artifact.id, { artifactId: source.artifact.id,
    selectedVersionId: source.artifact.currentVersionId });
  return [...bindings.values()];
}

/** Rejects every gesture except the explicit input handle pair. */
export function inputConnectionUpdate(items: CanvasItem[], connection: Connection) {
  if (connection.sourceHandle !== "artifact-output" ||
      connection.targetHandle !== "agent-input") return null;
  const source = items.find((item) => item.id === connection.source);
  const target = items.find((item) => item.id === connection.target);
  if (!source || !target || !target.agent) return null;
  const bindings = inputBindingsAfterConnect(source, target);
  return bindings ? { agent: target.agent, bindings } : null;
}

/** A hand-drawn reference points from the referenced version to its consuming Artifact. */
export function semanticConnectionRevision(items: CanvasItem[], connection: Connection):
  { artifactId: string; revision: ReviseArtifactRequest | null } | null {
  if (connection.sourceHandle !== "artifact-output" ||
      connection.targetHandle !== "artifact-input") return null;
  const reference = items.find((item) => item.id === connection.source)?.artifact;
  const consumer = items.find((item) => item.id === connection.target)?.artifact;
  if (!reference || !consumer || reference.id === consumer.id) return null;
  const content = consumer.currentVersion.content;
  // A selected v1 shot needs an explicit duration edit before a new v2 revision.
  if ("durationMs" in content) return null;
  const versionId = reference.currentVersionId;
  let next: ReviseArtifactRequest["content"];
  if ((consumer.kind === "CHARACTER" || consumer.kind === "SCENE") &&
      reference.kind === "IMAGE" && "referenceVersionIds" in content &&
      Array.isArray(content.referenceVersionIds)) {
    if (content.referenceVersionIds.includes(versionId)) {
      return { artifactId: consumer.id, revision: null };
    }
    next = { ...content, referenceVersionIds: [...content.referenceVersionIds, versionId] };
  } else if (consumer.kind === "SHOT" && reference.kind === "CHARACTER" &&
      "characterVersionIds" in content && Array.isArray(content.characterVersionIds)) {
    if (content.characterVersionIds.includes(versionId)) {
      return { artifactId: consumer.id, revision: null };
    }
    next = { ...content, characterVersionIds: [...content.characterVersionIds, versionId] };
  } else if (consumer.kind === "SHOT" && reference.kind === "SCENE" &&
      "sceneVersionId" in content && typeof content.sceneVersionId === "string") {
    if (content.sceneVersionId === versionId) {
      return { artifactId: consumer.id, revision: null };
    }
    next = { ...content, sceneVersionId: versionId };
  } else {
    return null;
  }
  return { artifactId: consumer.id,
    revision: { expectedVersion: consumer.version, content: next } };
}

/** Removes only optional schema-backed references; a SHOT scene must be replaced, not deleted. */
export function semanticReferenceRemoval(item: CanvasItem,
  reference: NonNullable<CanvasItem["artifact"]>["currentVersion"]["inputReferences"][number]):
  ReviseArtifactRequest | null {
  const artifact = item.artifact;
  if (!artifact) return null;
  const content = artifact.currentVersion.content;
  if ("durationMs" in content) return null;
  let next: ReviseArtifactRequest["content"];
  if ((artifact.kind === "CHARACTER" || artifact.kind === "SCENE") &&
      reference.role === "referenceImage" && reference.kind === "IMAGE" &&
      "referenceVersionIds" in content && Array.isArray(content.referenceVersionIds) &&
      content.referenceVersionIds[reference.order] === reference.versionId) {
    next = { ...content, referenceVersionIds: content.referenceVersionIds.filter(
      (versionId) => versionId !== reference.versionId) };
  } else if (artifact.kind === "SHOT" && reference.role === "character" &&
      reference.kind === "CHARACTER" && "characterVersionIds" in content &&
      Array.isArray(content.characterVersionIds) &&
      content.characterVersionIds[reference.order] === reference.versionId) {
    next = { ...content, characterVersionIds: content.characterVersionIds.filter(
      (versionId) => versionId !== reference.versionId) };
  } else {
    return null;
  }
  return { expectedVersion: artifact.version, content: next };
}
