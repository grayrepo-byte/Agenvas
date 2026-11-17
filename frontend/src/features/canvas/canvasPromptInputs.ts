import type { CanvasConnection, CanvasItem } from "../../shared/api/client";
import type { components } from "../../shared/api/schema";
import { readContentText } from "./artifactContent";

export type CanvasPromptInput = components["schemas"]["FrozenPromptInput"];

function creationNanos(value: string): bigint {
  const fraction = value.match(/\.(\d+)/)?.[1] ?? "";
  return BigInt(Date.parse(value)) * 1_000_000n + BigInt(fraction.padEnd(9, "0").slice(3, 9));
}

/** Match PostgreSQL's createdAt/UUID ordering; never use spatial position or response array order. */
export function projectPromptInputs(
  items: CanvasItem[],
  connections: CanvasConnection[],
  targetId: string,
): CanvasPromptInput[] | null {
  const edges = connections
    .filter(edge => edge.relationType === "PROMPT_INPUT" && edge.targetCanvasItemId === targetId)
    .sort((a, b) => {
      const first = creationNanos(a.createdAt);
      const second = creationNanos(b.createdAt);
      return first < second ? -1 : first > second ? 1 : a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    });
  const sources: CanvasPromptInput[] = [];
  for (const edge of edges) {
    const source = items.find(item => item.id === edge.sourceCanvasItemId);
    const version = source?.artifact?.resourceDefaultVersion;
    if (!source?.artifact || source.artifact.kind !== "TEXT" || !version) return null;
    sources.push({
      connectionId: edge.id,
      canvasItemId: source.id,
      artifactId: source.artifact.id,
      versionId: version.id,
      title: source.title,
      text: readContentText(version.content, "text"),
    });
  }
  return sources;
}

/** Keep local edits untouched; this value is a preview/validation projection only. */
export function composePromptInputs(inputs: readonly CanvasPromptInput[], local: string): string {
  const texts = inputs.map(input => input.text.trim()).filter(Boolean);
  return texts.length ? [...texts, local.trim()].filter(Boolean).join("\n\n") : local;
}
