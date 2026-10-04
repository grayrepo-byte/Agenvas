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
    fields.push({ ...candidate, key });
  }
  return { ...imported, ...current, fields, importSource: imported.importSource,
    nodeOptions: imported.nodeOptions, sourceSha256: imported.sourceSha256 };
}
