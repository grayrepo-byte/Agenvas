import type { Connection, Edge } from "@xyflow/react";
import type { Agent, CanvasItem, ReviseArtifactRequest } from "../../shared/api/client";

/** One entry of the immutable exact-version reference list a content version carries. */
export type ArtifactInputReference =
  NonNullable<NonNullable<CanvasItem["artifact"]>["currentVersion"]>["inputReferences"][number];

/** Visual relationships are projections, never execution dependencies or generation commands. */
export function projectCanvasRelations(items: CanvasItem[]): Edge[] {
  const artifactCards = new Map<string, CanvasItem>();
  const versionCards = new Map<string, CanvasItem>();
  const outputGroups = new Map<string, CanvasItem[]>();
  const agentCards = items.filter((item) => item.agent !== null);
  for (const item of items) {
    if (!item.artifact) continue;
    if (!artifactCards.has(item.artifact.id)) artifactCards.set(item.artifact.id, item);
    if (item.artifact.currentVersionId) {
      versionCards.set(item.artifact.currentVersionId, item);
    }
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
        className: "relation-edge relation-edge--input-binding",
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
        className: "relation-edge relation-edge--agent-output",
        // 输出组成员资格由 Agent 的输出组决定，没有可单独删除的关系记录，因此不给选中与删除手势。
        deletable: false,
        selectable: false,
      });
    }
  }

  // A visible current version can name only visible exact-version inputs. Historical
  // references stay in the Artifact record; we must not draw them to a newer version.
  for (const output of items) {
    if (!output.artifact?.currentVersion) continue;
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
        className: "relation-edge relation-edge--reference",
      });
    }
  }
  return edges;
}

/** One manual Artifact → Agent gesture updates only that Agent's explicit input binding. */
export function inputBindingsAfterConnect(source: CanvasItem, target: CanvasItem) {
  if (!source.artifact?.currentVersionId || !target.agent || source.id === target.id) return null;
  const bindings = new Map(target.agent.bindings.map((binding) => [binding.artifactId,
    { artifactId: binding.artifactId, selectedVersionId: binding.selectedVersionId }]));
  bindings.set(source.artifact.id, { artifactId: source.artifact.id,
    selectedVersionId: source.artifact.currentVersionId });
  return [...bindings.values()];
}

/** Rejects every gesture except the explicit input handle pair. */
export function inputConnectionUpdate(items: CanvasItem[], connection: Connection | Edge) {
  if (connection.sourceHandle !== "artifact-output" ||
      connection.targetHandle !== "agent-input") return null;
  const source = items.find((item) => item.id === connection.source);
  const target = items.find((item) => item.id === connection.target);
  if (!source || !target || !target.agent) return null;
  const bindings = inputBindingsAfterConnect(source, target);
  return bindings ? { agent: target.agent, bindings } : null;
}

/** A hand-drawn reference points from the referenced version to its consuming Artifact. */
export function semanticConnectionRevision(items: CanvasItem[], connection: Connection | Edge):
  { artifactId: string; revision: ReviseArtifactRequest | null } | null {
  if (connection.sourceHandle !== "artifact-output" ||
      connection.targetHandle !== "artifact-input") return null;
  const reference = items.find((item) => item.id === connection.source)?.artifact;
  const consumer = items.find((item) => item.id === connection.target)?.artifact;
  if (!reference?.currentVersionId || !consumer?.currentVersion ||
      reference.id === consumer.id) return null;
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

/**
 * Drag feedback for React Flow. It reuses the same two predicates as the commit path so a
 * highlighted drop target can never be one the server write would reject, and vice versa
 * ([inputConnectionUpdate] for Agent inputs, [semanticConnectionRevision] for references).
 */
export function isCanvasConnectionValid(items: CanvasItem[], connection: Connection | Edge) {
  if (connection.targetHandle === "artifact-input") {
    return semanticConnectionRevision(items, connection) !== null;
  }
  if (connection.targetHandle === "agent-input") {
    return inputConnectionUpdate(items, connection) !== null;
  }
  return false;
}

/** What a user may remove behind a projected edge, or `null` when the edge is not an editable relation. */
export type CanvasRelationRemoval =
  | { kind: "inputBinding"; agent: Agent; bindingId: string }
  | { kind: "reference"; item: CanvasItem; reference: ArtifactInputReference };

/**
 * Resolves the relation a selected edge stands for, so deleting a line writes through the same
 * application services a card action would use. Agent output-group membership has no relation
 * record, and a required scene reference can only be replaced, so both return `null`.
 */
export function canvasRelationRemoval(items: CanvasItem[],
  edge: Edge): CanvasRelationRemoval | null {
  if (edge.sourceHandle !== "artifact-output") return null;
  if (edge.targetHandle === "agent-input") {
    const source = items.find((item) => item.id === edge.source)?.artifact;
    const target = items.find((item) => item.id === edge.target);
    const binding = source
      ? target?.agent?.bindings.find((candidate) => candidate.artifactId === source.id)
      : undefined;
    return target?.agent && binding
      ? { kind: "inputBinding", agent: target.agent, bindingId: binding.id }
      : null;
  }
  if (edge.targetHandle !== "artifact-input") return null;
  // The projection draws one edge per visible reference and a consumer names a version at most once,
  // so the source's current version id identifies the clicked edge. semanticReferenceRemoval then
  // re-checks role, order and the required-scene rule before anything is written.
  const versionId = items.find((item) => item.id === edge.source)?.artifact?.currentVersionId;
  const consumer = items.find((item) => item.id === edge.target);
  if (!versionId || !consumer?.artifact?.currentVersion) return null;
  const reference = consumer.artifact.currentVersion.inputReferences
    .find((candidate) => candidate.versionId === versionId);
  return reference && semanticReferenceRemoval(consumer, reference)
    ? { kind: "reference", item: consumer, reference }
    : null;
}

/** Removes only optional schema-backed references; a SHOT scene must be replaced, not deleted. */
export function semanticReferenceRemoval(item: CanvasItem,
  reference: ArtifactInputReference): ReviseArtifactRequest | null {
  const artifact = item.artifact;
  if (!artifact?.currentVersion) return null;
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
