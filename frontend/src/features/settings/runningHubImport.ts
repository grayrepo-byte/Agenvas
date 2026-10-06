import type { RunningHubDefinition } from "../../shared/api/client";

/** Re-parse the same target to recover candidates without discarding administrator mappings.
 * Fixed bindings stay fixed; existing keys, labels, constraints, outputs and execution options win. */
export function restoreRunningHubCandidates(current: RunningHubDefinition, imported: RunningHubDefinition): RunningHubDefinition {
  const target = (nodeId: string, fieldName: string) => `${nodeId}:${fieldName}`;
  const configured = new Set([...current.fields, ...(current.fixedBindings ?? [])].map((item) => target(item.nodeId, item.fieldName)));
  const keys = new Set(current.fields.map((item) => item.key));
  const fields = [...current.fields];
  for (const candidate of imported.fields) {
    if (configured.has(target(candidate.nodeId, candidate.fieldName))) continue;
    let key = candidate.key;
    let ordinal = 1;
    while (keys.has(key)) key = `input${ordinal++}`;
    keys.add(key);
    configured.add(target(candidate.nodeId, candidate.fieldName));
    // An administrator's existing prompt binding wins over a newly inferred one.
    fields.push({ ...candidate, key, ...(candidate.source === "PROMPT" && current.fields.some((field) => field.source === "PROMPT") ? { source: "PARAMETER" as const } : {}) });
  }
  return { ...imported, ...current, fields, importSource: imported.importSource,
    nodeOptions: imported.nodeOptions, sourceSha256: imported.sourceSha256 };
}

/** Preserve manual choices for known bindings, and apply suggestions only to new candidates.
 * Match node/field identities because merging may rename a colliding candidate key. */
export function importedRunningHubSelection(current: RunningHubDefinition | undefined, imported: RunningHubDefinition, next: RunningHubDefinition,
  selectedNodeIds: readonly string[], selectedFieldKeys: readonly string[], recommendedFieldKeys: readonly string[]) {
  const target = (nodeId: string, fieldName: string) => `${nodeId}:${fieldName}`;
  const configured = new Set(current?.fields.map((field) => target(field.nodeId, field.fieldName)) ?? []);
  const recommended = new Set(recommendedFieldKeys);
  const suggestedTargets = new Set(imported.fields.filter((field) => recommended.has(field.key)).map((field) => target(field.nodeId, field.fieldName)));
  const nodeIds = new Set(current ? selectedNodeIds : []);
  const fieldKeys = new Set(current ? selectedFieldKeys : []);
  for (const field of next.fields) {
    const identity = target(field.nodeId, field.fieldName);
    if (configured.has(identity) || !suggestedTargets.has(identity)) continue;
    // A preserved prompt elsewhere must not silently turn a second text candidate into an exposed parameter.
    if (!field.source || field.source === "PARAMETER") {
      if (!["IMAGE", "AUDIO", "VIDEO"].includes(field.type)) continue;
    }
    fieldKeys.add(field.key);
    nodeIds.add(field.nodeId);
  }
  return { nodeIds: [...nodeIds], fieldKeys: [...fieldKeys] };
}
