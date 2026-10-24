import type { RunningHubDefinition, RunningHubField } from "../../shared/api/client";
import { validRunningHubEditorValue } from "./runningHubEditorValidation";

export const runningHubFieldIdentity = (field: Pick<RunningHubField, "nodeId" | "fieldName">) => `${field.nodeId}:${field.fieldName}`;
export type RunningHubRemoteChange = {
  identity: string;
  before?: RunningHubField;
  after?: RunningHubField;
  changes: ("ADDED" | "REMOVED" | "TYPE" | "OPTIONS" | "CONSTRAINTS" | "RESOURCE_FORMAT")[];
};
export type RunningHubImportDiff = {
  baselineAvailable: boolean;
  fields: RunningHubRemoteChange[];
  addedNodes: string[];
  removedNodes: string[];
};

/** Compare source-derived candidates, never administrator labels or defaults.
 * Without an older source, only saved bindings can be compared; omissions are not proof of a remote deletion. */
export function compareRunningHubImports(previous: RunningHubDefinition, next: RunningHubDefinition, baselineAvailable: boolean): RunningHubImportDiff {
  const before = new Map(previous.fields.map((field) => [runningHubFieldIdentity(field), field]));
  const after = new Map(next.fields.map((field) => [runningHubFieldIdentity(field), field]));
  const fields: RunningHubRemoteChange[] = [];
  for (const [identity, field] of after) {
    const old = before.get(identity);
    if (!old) { fields.push({ identity, after: field, changes: ["ADDED"] }); continue; }
    const changes: RunningHubRemoteChange["changes"] = [];
    if (old.type !== field.type) changes.push("TYPE");
    if (JSON.stringify(old.options ?? []) !== JSON.stringify(field.options ?? [])) changes.push("OPTIONS");
    if (["minimum", "maximum", "maxLength", "required"].some((key) => (old[key as keyof RunningHubField] ?? null) !== (field[key as keyof RunningHubField] ?? null))) changes.push("CONSTRAINTS");
    if ((old.resourceFormat ?? null) !== (field.resourceFormat ?? null)) changes.push("RESOURCE_FORMAT");
    if (changes.length) fields.push({ identity, before: old, after: field, changes });
  }
  for (const [identity, field] of before) if (!after.has(identity)) fields.push({ identity, before: field, changes: ["REMOVED"] });
  const oldNodes = new Set(previous.nodeOptions?.map((node) => node.nodeId) ?? []);
  const newNodes = new Set(next.nodeOptions?.map((node) => node.nodeId) ?? []);
  return { baselineAvailable, fields,
    addedNodes: [...newNodes].filter((id) => !oldNodes.has(id)),
    removedNodes: [...oldNodes].filter((id) => !newNodes.has(id)) };
}

/** Adoption is explicit and limited to remote input semantics. Stable keys and creator-facing settings stay intact. */
export function adoptRunningHubMetadata(current: RunningHubField, remote: RunningHubField, fields: readonly RunningHubField[] = []): RunningHubField {
  if (runningHubMetadataAdoptionConflict(current, remote, fields)) return current;
  return { ...current, type: remote.type, options: remote.options ?? [], minimum: remote.minimum ?? null,
    maximum: remote.maximum ?? null, maxLength: remote.maxLength ?? null, required: remote.required,
    resourceFormat: remote.resourceFormat ?? null };
}

/** A remote type or constraint must never silently invalidate a manually configured source/default. */
export function runningHubMetadataAdoptionConflict(current: RunningHubField, remote: RunningHubField, fields: readonly RunningHubField[] = []): "SOURCE" | "DEFAULT" | "CONDITION" | null {
  if (current.source === "PROMPT" && remote.type !== "STRING" || current.source === "DURATION_SECONDS" && remote.type !== "INTEGER") return "SOURCE";
  if (current.defaultValue != null && !validRunningHubEditorValue({ ...remote, source: current.source }, current.defaultValue)) return "DEFAULT";
  if (fields.some((field) => field.enabledWhen?.field === current.key && !validRunningHubEditorValue(remote, field.enabledWhen.value))) return "CONDITION";
  return null;
}

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
  const suggestedFields = new Map(imported.fields.filter((field) => recommended.has(field.key))
    .map((field) => [target(field.nodeId, field.fieldName), field]));
  const nodeIds = new Set(current ? selectedNodeIds : []);
  const fieldKeys = new Set(current ? selectedFieldKeys : []);
  for (const field of next.fields) {
    const identity = target(field.nodeId, field.fieldName);
    const suggestion = suggestedFields.get(identity);
    if (configured.has(identity) || !suggestion) continue;
    // A preserved prompt elsewhere must not silently turn a second text candidate into an exposed parameter.
    if (suggestion.source === "PROMPT" && field.source !== "PROMPT") continue;
    fieldKeys.add(field.key);
    nodeIds.add(field.nodeId);
  }
  return { nodeIds: [...nodeIds], fieldKeys: [...fieldKeys] };
}
