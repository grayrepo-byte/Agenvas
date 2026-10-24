import { describe, expect, it } from "vitest";
import type { RunningHubDefinition } from "../../shared/api/client";
import { adoptRunningHubMetadata, compareRunningHubImports, runningHubMetadataAdoptionConflict } from "./runningHubImport";

const previous: RunningHubDefinition = { schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false,
  fields: [{ key: "oldKey", nodeId: "1", fieldName: "value", label: "source label", type: "INTEGER", required: false, advanced: false, defaultValue: 1, minimum: 0 }],
  outputs: [{ kind: "VIDEO", primary: true, maxCount: 1 }], nodeOptions: [{ nodeId: "9", label: "Video" }] };

describe("RunningHub source change comparison", () => {
  it("ignores labels and defaults while comparing remote types, options, constraints and node catalog", () => {
    const next: RunningHubDefinition = { ...previous, fields: [{ ...previous.fields[0]!, key: "input1", label: "new source title", defaultValue: 10, type: "NUMBER", maximum: 20 }],
      nodeOptions: [{ nodeId: "10", label: "SaveVideo" }] };
    expect(compareRunningHubImports(previous, next, true)).toMatchObject({ baselineAvailable: true,
      fields: [{ identity: "1:value", changes: ["TYPE", "CONSTRAINTS"] }], addedNodes: ["10"], removedNodes: ["9"] });
    expect(compareRunningHubImports(previous, { ...previous, fields: [{ ...previous.fields[0]!, label: "manual label", defaultValue: 7, maximum: null }] }, true).fields).toEqual([]);
  });
  it("marks missing and new candidates without deleting an administrator binding", () => {
    const next = { ...previous, fields: [{ ...previous.fields[0]!, nodeId: "2" }] };
    expect(compareRunningHubImports(previous, next, false)).toMatchObject({ baselineAvailable: false,
      fields: [{ identity: "2:value", changes: ["ADDED"] }, { identity: "1:value", changes: ["REMOVED"] }] });
    expect(previous.fields).toHaveLength(1);
  });
  it("adopts metadata explicitly while preserving stable identity, manual labels, sources, defaults and group", () => {
    const current = { ...previous.fields[0]!, label: "My duration", group: "Stage 1", source: "PARAMETER" as const, advanced: true, defaultValue: 4 };
    expect(adoptRunningHubMetadata(current, { ...previous.fields[0]!, type: "NUMBER", minimum: 1, maximum: 10, defaultValue: 8 })).toMatchObject({
      key: "oldKey", label: "My duration", source: "PARAMETER", advanced: true, defaultValue: 4, group: "Stage 1", type: "NUMBER", minimum: 1, maximum: 10 });
  });
  it("blocks adoption until an incompatible manual source or default is explicitly adjusted", () => {
    const prompt = { ...previous.fields[0]!, type: "STRING" as const, source: "PROMPT" as const, defaultValue: "scene" };
    const number = { ...previous.fields[0]!, type: "NUMBER" as const };
    expect(runningHubMetadataAdoptionConflict(prompt, number)).toBe("SOURCE");
    expect(adoptRunningHubMetadata(prompt, number)).toBe(prompt);
    const duration = { ...previous.fields[0]!, source: "DURATION_SECONDS" as const };
    expect(runningHubMetadataAdoptionConflict(duration, number)).toBe("SOURCE");
    const parameter = { ...prompt, source: "PARAMETER" as const };
    expect(runningHubMetadataAdoptionConflict(parameter, { ...number, type: "IMAGE", defaultValue: null })).toBe("DEFAULT");
    expect(runningHubMetadataAdoptionConflict({ ...parameter, defaultValue: null }, { ...number, type: "IMAGE", defaultValue: null })).toBeNull();
    expect(runningHubMetadataAdoptionConflict(previous.fields[0]!, { ...number, minimum: 5 })).toBe("DEFAULT");
    const selector = { ...previous.fields[0]!, type: "SELECT" as const, defaultValue: null, options: [{ label: "One", value: 1 }, { label: "Two", value: 2 }] };
    const child = { ...number, key: "child", enabledWhen: { field: selector.key, value: 2 } };
    expect(runningHubMetadataAdoptionConflict(selector, { ...selector, options: [{ label: "One", value: 1 }] }, [selector, child])).toBe("CONDITION");
  });
});
