import { useState } from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { RunningHubDefinition } from "../../shared/api/client";
import { RunningHubForm, runningHubErrors, runningHubUsedVersions, type RunningHubValue } from "./RunningHubForm";

const definition: RunningHubDefinition = { schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false, addMetadata: false,
  fields: [
    { key: "mode", label: "模式", type: "SELECT", nodeId: "1", fieldName: "mode", required: true, advanced: false, defaultValue: 1, options: [{ label: "保留原画", value: 1 }, { label: "重绘", value: 2 }] },
    { key: "sound", label: "保留声音", type: "BOOLEAN", nodeId: "2", fieldName: "sound", required: true, advanced: false, defaultValue: false },
    { key: "strength", label: "变化强度", type: "NUMBER", nodeId: "3", fieldName: "strength", required: true, advanced: true, minimum: 0, maximum: 1, enabledWhen: { field: "mode", value: 2 } },
    { key: "clip", label: "参考视频", type: "VIDEO", nodeId: "4", fieldName: "video", required: true, advanced: false },
  ], outputs: [{ kind: "VIDEO", primary: true, maxCount: 1 }] };
const choices = [{ id: "video-v1", label: "参考片段 · v1", kind: "VIDEO", available: true }];
function Form() {
  const [values, setValues] = useState<Record<string, RunningHubValue>>({});
  return <><RunningHubForm definition={definition} values={values} prompt="" durationSeconds={null} choices={choices}
    onChange={(key, value) => setValues((before) => { const next = { ...before }; if (value === undefined) delete next[key]; else next[key] = value; return next; })} />
    <output>{JSON.stringify(values)}</output></>;
}
describe("RunningHubForm", () => {
  it("renders typed enums and false defaults, conditionally reveals advanced fields and chooses an exact video version", () => {
    render(<Form />);
    expect(screen.getByRole("combobox", { name: "模式 *" })).toHaveValue("0");
    expect(screen.getByRole("combobox", { name: "保留声音 *" })).toHaveValue("false");
    expect(screen.queryByRole("spinbutton", { name: "变化强度 *" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "模式 *" }), { target: { value: "1" } });
    fireEvent.click(screen.getByText("高级参数"));
    expect(screen.getByRole("spinbutton", { name: "变化强度 *" })).toHaveAttribute("max", "1");
    fireEvent.change(screen.getByRole("combobox", { name: "参考视频 *" }), { target: { value: "video-v1" } });
    expect(screen.getByRole("status")).toHaveTextContent('{"mode":2,"clip":"video-v1"}');
  });
  it("does not require universal prompt or duration and validates only active fields", () => {
    expect(runningHubErrors(definition, { clip: "video-v1" }, "", null, choices)).toEqual([]);
    expect(runningHubErrors(definition, { clip: "video-v1", mode: 2, strength: 1.5 }, "", null, choices)).toContain("“变化强度”的值或素材版本不可用");
    expect(runningHubErrors(definition, { clip: "other-project-version" }, "", null, choices)).toContain("“参考视频”的值或素材版本不可用");
  });
  it("inactive media slots cannot cause unassigned references to be mistaken for active inputs", () => {
    const conditional = { ...definition, fields: definition.fields.map((field) => field.key === "clip" ? { ...field, enabledWhen: { field: "mode", value: 2 } } : field) };
    expect(runningHubUsedVersions(conditional, { clip: "video-v1" }, "", null).size).toBe(0);
    expect(runningHubUsedVersions(conditional, { clip: "video-v1", mode: 2 }, "", null).has("video-v1")).toBe(true);
  });
  it("applies the duration source limit even when imported fields have no numeric bounds", () => {
    const duration: RunningHubDefinition = { ...definition, fields: [{ key: "seconds", label: "时长", type: "INTEGER", nodeId: "5", fieldName: "duration", required: true, advanced: false, source: "DURATION_SECONDS" }] };
    render(<RunningHubForm definition={duration} values={{}} prompt="" durationSeconds={45} choices={[]} onChange={() => {}} />);
    expect(screen.getByRole("spinbutton", { name: "时长 *" })).toHaveAttribute("min", "1");
    expect(screen.getByRole("spinbutton", { name: "时长 *" })).toHaveAttribute("max", "60");
    expect(runningHubErrors(duration, {}, "", 45, [])).toEqual([]);
    expect(runningHubErrors(duration, {}, "", 61, [])).toContain("“时长”的值或素材版本不可用");
  });
});
