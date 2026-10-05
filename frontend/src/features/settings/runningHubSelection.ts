import type { RunningHubDefinition } from "../../shared/api/client";

/** Keep deselected candidates in the editor draft; only selected mappings are published. */
export function selectRunningHubNodes(definition: RunningHubDefinition, nodeIds?: readonly string[]): RunningHubDefinition {
  if (nodeIds === undefined) return definition;
  const selected = new Set(nodeIds);
  return { ...definition, fields: definition.fields.filter((field) => selected.has(field.nodeId)),
    ...(definition.fixedBindings ? { fixedBindings: definition.fixedBindings.filter((binding) => selected.has(binding.nodeId)) } : {}) };
}
