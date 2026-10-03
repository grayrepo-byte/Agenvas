import type { Connection, Edge } from "@xyflow/react";
import type { Agent, CanvasConnection, CanvasItem } from "../../shared/api/client";
import { canvasItemVersionId } from "./versionedArtifact";

/** Visual relationships are projections, never execution dependencies or generation commands. */
export function projectCanvasRelations(items: CanvasItem[], connections: CanvasConnection[] = []): Edge[] {
  const artifactCards = new Map<string, CanvasItem>();
  const artifactVersionCards = new Map<string, CanvasItem>();
  const outputGroups = new Map<string, CanvasItem[]>();
  const agentCards = items.filter((item) => item.agent !== null);
  for (const item of items) {
    if (!item.artifact) continue;
    if (!artifactCards.has(item.artifact.id)) artifactCards.set(item.artifact.id, item);
    const versionId = canvasItemVersionId(item);
    if (versionId) {
      artifactVersionCards.set(`${item.artifact.id}:${versionId}`, item);
    }
    if (item.groupId) {
      const grouped = outputGroups.get(item.groupId) ?? [];
      grouped.push(item);
      outputGroups.set(item.groupId, grouped);
    }
  }

  const edges: Edge[] = [];
  for (const connection of connections) {
    const source = items.find((item) => item.id === connection.sourceCanvasItemId);
    const historical = !source || canvasItemVersionId(source) !== connection.sourceArtifactVersionId;
    const derivation = connection.relationType === "MEDIA_DERIVATION";
    edges.push({
      id: `canvas-connection:${connection.id}`,
      source: connection.sourceCanvasItemId,
      sourceHandle: "artifact-output",
      target: connection.targetCanvasItemId,
      targetHandle: connection.relationType === "AGENT_IMAGE_INPUT" ? "agent-input" : "artifact-input",
      className: `relation-edge ${derivation ? "relation-edge--derivation" : "relation-edge--reference"}${
        historical ? " relation-edge--input-binding-historical" : ""}`,
    });
  }
  for (const agentCard of agentCards) {
    const agent = agentCard.agent;
    if (!agent) continue;
    for (const binding of agent.bindings) {
      if (connections.some((connection) => connection.relationType === "AGENT_IMAGE_INPUT"
          && connection.targetCanvasItemId === agentCard.id
          && connection.sourceArtifactVersionId === binding.selectedVersionId)) continue;
      const input = artifactVersionCards.get(
        `${binding.artifactId}:${binding.selectedVersionId}`,
      ) ?? artifactCards.get(binding.artifactId);
      if (!input) continue;
      const historical = canvasItemVersionId(input) !== binding.selectedVersionId;
      edges.push({
        id: `input:${binding.id}:${input.id}:${agentCard.id}`,
        source: input.id,
        sourceHandle: "artifact-output",
        target: agentCard.id,
        targetHandle: "agent-input",
        // 关系线不带文字说明；指向历史版本的绑定用虚线区分（见 styles.css 的同名规则）。
        className: `relation-edge relation-edge--input-binding${
          historical ? " relation-edge--input-binding-historical" : ""}`,
      });
    }
    for (const output of outputGroups.get(agent.outputGroupId) ?? []) {
      edges.push({
        id: `output:${agentCard.id}:${output.id}`,
        source: agentCard.id,
        sourceHandle: "agent-output",
        target: output.id,
        targetHandle: "artifact-input",
        className: "relation-edge relation-edge--agent-output",
        // 输出组成员资格由 Agent 的输出组决定，没有可单独删除的关系记录，因此不给选中与删除手势。
        deletable: false,
        selectable: false,
      });
    }
  }

  return edges;
}

/** One manual Artifact → Agent gesture updates only that Agent's explicit input binding. */
export function inputBindingsAfterConnect(source: CanvasItem, target: CanvasItem) {
  const selectedVersionId = canvasItemVersionId(source);
  if (!source.artifact || !selectedVersionId || !target.agent || source.id === target.id) return null;
  const bindings = new Map(target.agent.bindings.map((binding) => [binding.artifactId,
    { artifactId: binding.artifactId, selectedVersionId: binding.selectedVersionId }]));
  bindings.set(source.artifact.id, { artifactId: source.artifact.id,
    selectedVersionId });
  return [...bindings.values()];
}

/** Rejects every gesture except the explicit input handle pair. */
export function inputConnectionUpdate(items: CanvasItem[], connection: Connection | Edge) {
  if (connection.sourceHandle !== "artifact-output" ||
      connection.targetHandle !== "agent-input") return null;
  const source = items.find((item) => item.id === connection.source);
  const target = items.find((item) => item.id === connection.target);
  if (!source || !target || !target.agent || source.artifact?.kind === "IMAGE") return null;
  const bindings = inputBindingsAfterConnect(source, target);
  return bindings ? { agent: target.agent, bindings } : null;
}

/** Resolves IMAGE → Agent separately from ordinary artifact bindings. */
export function agentImageConnection(items: CanvasItem[], connection: Connection | Edge) {
  if (connection.sourceHandle !== "artifact-output" || connection.targetHandle !== "agent-input") return null;
  const source = items.find((item) => item.id === connection.source);
  const target = items.find((item) => item.id === connection.target);
  const sourceVersionId = source ? canvasItemVersionId(source) : null;
  if (source?.artifact?.kind !== "IMAGE" || !sourceVersionId || !target?.agent
      || source.id === target.id) return null;
  return { sourceCanvasItemId: source.id, targetCanvasItemId: target.id,
    sourceVersionId, agent: target.agent };
}

/** Resolves an image-card gesture into the exact version required by the media connection API. */
export function mediaInputConnection(items: CanvasItem[], connection: Connection | Edge) {
  if (connection.sourceHandle !== "artifact-output" || connection.targetHandle !== "artifact-input") return null;
  const source = items.find((item) => item.id === connection.source);
  const target = items.find((item) => item.id === connection.target);
  const sourceVersionId = source ? canvasItemVersionId(source) : null;
  if (!source?.artifact || !["IMAGE", "AUDIO", "VIDEO"].includes(source.artifact.kind) || !sourceVersionId
      || !target?.artifact || target.artifact.kind === "TEXT"
      || source.artifact.kind === "AUDIO" && target.artifact.kind === "IMAGE"
      || source.artifact.kind === "VIDEO" && target.artifact.kind !== "VIDEO" || source.id === target.id) return null;
  return { sourceCanvasItemId: source.id, targetCanvasItemId: target.id, sourceVersionId };
}

/**
 * Drag feedback for React Flow. It reuses the same predicate as the commit path so a highlighted
 * drop target can never be one the server write would reject, and vice versa. Hand-drawn relations
 * create either an Agent input binding or a persisted image-to-media input connection.
 */
export function isCanvasConnectionValid(items: CanvasItem[], connection: Connection | Edge) {
  return inputConnectionUpdate(items, connection) !== null
    || agentImageConnection(items, connection) !== null
    || mediaInputConnection(items, connection) !== null;
}

/**
 * 卡片用于接线的连接点；`null` 表示当前还接不了。Agent 接收显式输入绑定，
 * Artifact 卡片使用统一输入点；目标类型与来源版本由 [isCanvasConnectionValid] 统一校验。
 * 媒体空产物没有当前版本，但仍可在草稿中接收图片输入连线。
 */
export function canvasTargetHandleId(item: CanvasItem): "agent-input" | "artifact-input" | null {
  if (item.agent) return "agent-input";
  return item.artifact ? "artifact-input" : null;
}

/** What a user may remove behind a projected edge, or `null` when the edge is not an editable relation. */
export type CanvasRelationRemoval =
  | { kind: "inputBinding"; agent: Agent; bindingId: string }
  | { kind: "mediaConnection"; connection: CanvasConnection };

/**
 * Resolves the relation a selected edge stands for, so deleting a line writes through the same
 * application services a card action would use. Agent bindings, references, and derivation lines are
 * removable; only output-group membership is a projection and returns `null`.
 */
export function canvasRelationRemoval(items: CanvasItem[], connectionsOrEdge: CanvasConnection[] | Edge,
  maybeEdge?: Edge): CanvasRelationRemoval | null {
  const connections = Array.isArray(connectionsOrEdge) ? connectionsOrEdge : [];
  const edge = Array.isArray(connectionsOrEdge) ? maybeEdge : connectionsOrEdge;
  if (!edge) return null;
  const persisted = connections.find((connection) => edge.id === `canvas-connection:${connection.id}`);
  if (persisted) return { kind: "mediaConnection", connection: persisted };
  if (edge.sourceHandle !== "artifact-output" || edge.targetHandle !== "agent-input") return null;
  const source = items.find((item) => item.id === edge.source)?.artifact;
  const target = items.find((item) => item.id === edge.target);
  const binding = source
    ? target?.agent?.bindings.find((candidate) => candidate.artifactId === source.id)
    : undefined;
  return target?.agent && binding
    ? { kind: "inputBinding", agent: target.agent, bindingId: binding.id }
    : null;
}
