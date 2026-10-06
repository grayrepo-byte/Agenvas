import type { RunningHubDefinition } from "../../shared/api/client";

/** Keep deselected candidates in the editor draft; only selected mappings are published. */
export function selectRunningHubNodes(definition: RunningHubDefinition, nodeIds?: readonly string[], fieldKeys?: readonly string[]): RunningHubDefinition {
  const selectedNodes = nodeIds === undefined ? undefined : new Set(nodeIds);
  const selectedFields = fieldKeys === undefined ? undefined : new Set(fieldKeys);
  return { ...definition, fields: definition.fields.filter((field) => (!selectedNodes || selectedNodes.has(field.nodeId)) && (!selectedFields || selectedFields.has(field.key))),
    ...(definition.fixedBindings ? { fixedBindings: definition.fixedBindings.filter((binding) => !selectedNodes || selectedNodes.has(binding.nodeId)) } : {}) };
}
