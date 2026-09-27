import type { Connection, Edge } from "@xyflow/react";
import type { Agent, CanvasItem } from "../../shared/api/client";
import { canvasItemVersion, canvasItemVersionId } from "./versionedArtifact";

/** Visual relationships are projections, never execution dependencies or generation commands. */
export function projectCanvasRelations(items: CanvasItem[]): Edge[] {
  const artifactCards = new Map<string, CanvasItem>();
  const artifactVersionCards = new Map<string, CanvasItem>();
  const versionCards = new Map<string, CanvasItem>();
  const outputGroups = new Map<string, CanvasItem[]>();
  const agentCards = items.filter((item) => item.agent !== null);
  for (const item of items) {
    if (!item.artifact) continue;
    if (!artifactCards.has(item.artifact.id)) artifactCards.set(item.artifact.id, item);
    const versionId = canvasItemVersionId(item);
    if (versionId) {
      if (!versionCards.has(versionId)) versionCards.set(versionId, item);
      artifactVersionCards.set(`${item.artifact.id}:${versionId}`, item);
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

  // A visible current version can name only visible exact-version inputs. Historical
  // references stay in the Artifact record; we must not draw them to a newer version.
  // 精确版本输入（视频所依据的输入图片）由生成时固定，没有可单独修改或删除的关系记录，
  // 因此只做展示，不给选中与删除手势。
  for (const output of items) {
    const outputVersion = canvasItemVersion(output);
    if (!outputVersion) continue;
    for (const reference of outputVersion.inputReferences) {
      const input = versionCards.get(reference.versionId);
      if (!input || input.id === output.id) continue;
      edges.push({
        id: `reference:${output.id}:${reference.role}:${reference.order}:${reference.versionId}`,
        source: input.id,
        sourceHandle: "artifact-output",
        target: output.id,
        targetHandle: "artifact-input",
        className: "relation-edge relation-edge--reference",
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
  if (!source || !target || !target.agent) return null;
  const bindings = inputBindingsAfterConnect(source, target);
  return bindings ? { agent: target.agent, bindings } : null;
}

/**
 * Drag feedback for React Flow. It reuses the same predicate as the commit path so a highlighted
 * drop target can never be one the server write would reject, and vice versa. Hand-drawn relations
 * only ever create Agent input bindings; two artifacts are never connected by a gesture.
 */
export function isCanvasConnectionValid(items: CanvasItem[], connection: Connection | Edge) {
  return inputConnectionUpdate(items, connection) !== null;
}

/**
 * 卡片用于接线的连接点；`null` 表示当前还接不了。Artifact 卡片的连接点只承载投影出来的
 * 精确版本输入线：手工拖动落在 Artifact 卡片上始终无效，只有 Agent 卡片能接手工连线
 * （见 [isCanvasConnectionValid]）。
 */
export function canvasTargetHandleId(item: CanvasItem): "agent-input" | "artifact-input" | null {
  if (item.agent) return "agent-input";
  return canvasItemVersionId(item) ? "artifact-input" : null;
}

/** What a user may remove behind a projected edge, or `null` when the edge is not an editable relation. */
export type CanvasRelationRemoval =
  | { kind: "inputBinding"; agent: Agent; bindingId: string };

/**
 * Resolves the relation a selected edge stands for, so deleting a line writes through the same
 * application services a card action would use. Only Agent input bindings are removable: output-group
 * membership and exact-version input references have no separately editable relation record, so both
 * return `null`.
 */
export function canvasRelationRemoval(items: CanvasItem[],
  edge: Edge): CanvasRelationRemoval | null {
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
