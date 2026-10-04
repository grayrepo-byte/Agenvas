import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import type { RunningHubDefinition } from "../../shared/api/client";
import { changeControl, clickControl } from "../../test/controls";
import type { RunningHubValue } from "./RunningHubForm";
import { WorkflowMediaInputs, WorkflowParametersDialog, type WorkflowMediaChoice } from "./WorkflowDraftControls";

const definition: RunningHubDefinition = {
  schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "synthetic-workflow",
  usePersonalQueue: false, addMetadata: false,
  fields: [
    { key: "prompt", label: "正面提示词", type: "STRING", nodeId: "1", fieldName: "text", source: "PROMPT", required: true, advanced: false },
    { key: "first", label: "首帧", type: "IMAGE", nodeId: "2", fieldName: "image", required: true, advanced: false },
    { key: "last", label: "尾帧", type: "IMAGE", nodeId: "3", fieldName: "image", required: false, advanced: false },
    { key: "sound", label: "配音", type: "AUDIO", nodeId: "4", fieldName: "audio", required: false, advanced: false },
    { key: "mode", label: "模式", type: "SELECT", nodeId: "5", fieldName: "mode", required: true, advanced: false, defaultValue: 0,
      options: [{ label: "默认", value: 0 }, { label: "自定义", value: 2 }, { label: "停用", value: false }] },
    { key: "keepSound", label: "保留声音", type: "BOOLEAN", nodeId: "6", fieldName: "sound", required: true, advanced: false, defaultValue: false },
    { key: "seed", label: "种子", type: "INTEGER", nodeId: "7", fieldName: "seed", required: true, advanced: true, defaultValue: 0, minimum: 0, maximum: 100 },
    { key: "strength", label: "变化强度", type: "NUMBER", nodeId: "8", fieldName: "strength", required: true, advanced: true, minimum: 0, maximum: 1,
      enabledWhen: { field: "mode", value: 2 } },
    { key: "seconds", label: "时长", type: "INTEGER", nodeId: "9", fieldName: "duration", source: "DURATION_SECONDS", required: true, advanced: false },
    { key: "lora", label: "LoRA", description: "附加风格", type: "STRING", nodeId: "10", fieldName: "name", required: false, advanced: false, maxLength: 20 },
  ], outputs: [{ kind: "VIDEO", primary: true, maxCount: 1 }],
};
const choices: WorkflowMediaChoice[] = [
  { id: "image-v1", label: "合成图片 · v1", kind: "IMAGE", available: true, thumbnailUrl: "/synthetic-thumbnail.png" },
  { id: "image-missing", label: "已删除图片 · v2", kind: "IMAGE", available: false },
  { id: "audio-v1", label: "合成音频 · v1", kind: "AUDIO", available: true },
];
const canvasChoices: WorkflowMediaChoice[] = [
  { id: "image-canvas", label: "画布图片 · v3", title: "画布图片", kind: "IMAGE", available: true },
  { id: "image-canvas", label: "画布图片 · v3", title: "画布图片", kind: "IMAGE", available: true },
];

function ValuesHarness({ dialog = false, initialValues = {} }: { dialog?: boolean; initialValues?: Record<string, RunningHubValue> }) {
  const [values, setValues] = useState(initialValues);
  function change(key: string, value: RunningHubValue | undefined) {
    setValues((previous) => { const next = { ...previous }; if (value === undefined) delete next[key]; else next[key] = value; return next; });
  }
  return <>{dialog ? <WorkflowParametersDialog open onOpenChange={() => {}} definition={definition} values={values} prompt="画面提示词" durationSeconds={45} onChange={change} />
    : <WorkflowMediaInputs definition={definition} values={values} prompt="" durationSeconds={null} choices={choices} canvasChoices={canvasChoices} onChange={change} onChooseSource={() => {}} />}
    <output data-testid="values">{JSON.stringify(values)}</output></>;
}

describe("WorkflowMediaInputs", () => {
  it("opens the shared source menu for each named slot and delegates selection with its exact key", async () => {
    const choose = vi.fn(); const clear = vi.fn();
    render(<WorkflowMediaInputs definition={definition} values={{ first: "image-v1", last: "image-v1" }} prompt="" durationSeconds={null}
      choices={choices} canvasChoices={canvasChoices} onChange={clear} onChooseSource={choose} onUpload={vi.fn()} />);
    expect(screen.getAllByRole("button", { name: /^选择/ })).toHaveLength(3);
    expect(screen.getByText("首帧 *")).toHaveClass("sr-only");
    await clickControl(screen.getByRole("button", { name: "选择首帧" }));
    const menu = screen.getByRole("menu", { name: "图片来源" });
    expect(menu).toHaveClass("media-draft-reference-sources");
    await clickControl(within(menu).getByRole("menuitem", { name: "从资源库选择" }));
    expect(choose).toHaveBeenLastCalledWith(expect.objectContaining({ key: "first" }), "resources", screen.getByRole("button", { name: "选择首帧" }));
    await clickControl(screen.getByRole("button", { name: "选择尾帧" }));
    await clickControl(screen.getByRole("menuitem", { name: "从画布选择" }));
    expect(choose).toHaveBeenLastCalledWith(expect.objectContaining({ key: "last" }), "canvas", screen.getByRole("button", { name: "选择尾帧" }));
    await clickControl(screen.getByRole("button", { name: "选择配音" }));
    await clickControl(screen.getByRole("menuitem", { name: "从我的资产选择" }));
    expect(choose).toHaveBeenLastCalledWith(expect.objectContaining({ key: "sound" }), "library", screen.getByRole("button", { name: "选择配音" }));
    await clickControl(screen.getByRole("button", { name: "清空首帧" }));
    expect(clear).toHaveBeenCalledWith("first", undefined);
  });

  it("uses defaults to hide inactive slots and keeps unavailable saved versions visible", () => {
    const conditional = { ...definition, fields: definition.fields.map((field) => field.key === "last" ? { ...field, enabledWhen: { field: "mode", value: 2 } } : field) };
    const props = { definition: conditional, values: { first: "missing-version" }, prompt: "", durationSeconds: null, choices, onChooseSource: vi.fn(), onChange: vi.fn() };
    const { rerender } = render(<WorkflowMediaInputs {...props} />);
    expect(screen.queryByRole("button", { name: "选择尾帧" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择首帧" })).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("首帧 · 版本不可用")).toBeInTheDocument();
    rerender(<WorkflowMediaInputs {...props} values={{ first: "image-v1", mode: 2 }} />);
    expect(screen.getByRole("button", { name: "选择尾帧" })).toBeInTheDocument();
    expect(screen.queryByText("首帧 · 版本不可用")).not.toBeInTheDocument();
  });

  it("reports upload busy, preserves failures for retry and disables portal choices while locked", async () => {
    const uploaded = new File(["synthetic"], "reference.png", { type: "image/png" });
    let rejectUpload: ((reason: Error) => void) | undefined;
    const upload = vi.fn().mockImplementationOnce(() => new Promise<void>((_resolve, reject) => { rejectUpload = reject; })).mockResolvedValue(undefined);
    const onBusy = vi.fn();
    const props = { definition, values: {}, prompt: "", durationSeconds: null, choices, onChooseSource: vi.fn(), onChange: vi.fn(), onUpload: upload, onBusy };
    const { rerender } = render(<WorkflowMediaInputs {...props} />);
    await clickControl(screen.getByRole("button", { name: "选择首帧" }));
    fireEvent.change(screen.getByLabelText("上传首帧"), { target: { files: [uploaded] } });
    expect(onBusy).toHaveBeenLastCalledWith(true);
    expect(screen.getByRole("button", { name: "选择首帧" })).toBeDisabled();
    rejectUpload?.(new Error("合成上传失败"));
    await waitFor(() => expect(onBusy).toHaveBeenLastCalledWith(false));
    expect(screen.getAllByRole("alert").some((alert) => alert.textContent?.includes("合成上传失败"))).toBe(true);
    await clickControl(screen.getByRole("button", { name: "重试上传" }));
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
    expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ key: "first" }), uploaded);
    await waitFor(() => expect(screen.queryByText("合成上传失败")).not.toBeInTheDocument());
    upload.mockRejectedValueOnce(new Error("新的合成上传失败"));
    fireEvent.change(screen.getByLabelText("上传首帧"), { target: { files: [uploaded] } });
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("新的合成上传失败"));
    rerender(<WorkflowMediaInputs {...props} values={{ first: "image-v1" }} />);
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    await clickControl(screen.getByRole("button", { name: "选择首帧" }));
    rerender(<WorkflowMediaInputs {...props} disabled />);
    expect(screen.getByRole("button", { name: "选择首帧" })).toBeDisabled();
    expect(screen.queryByRole("menu", { name: "图片来源" })).not.toBeInTheDocument();
  });
});

describe("WorkflowParametersDialog", () => {
  it("places non-media, non-prompt fields in a table and preserves zero and false defaults with typed choices", async () => {
    render(<ValuesHarness dialog />);
    const dialog = screen.getByRole("dialog", { name: "扩展参数" });
    expect(within(dialog).queryByText("正面提示词")).not.toBeInTheDocument();
    expect(within(dialog).queryByText("首帧 *")).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "模式 *" })).toHaveValue("0");
    expect(screen.getByRole("combobox", { name: "保留声音 *" })).toHaveValue("false");
    expect(screen.getByRole("spinbutton", { name: "种子 *" })).toHaveValue(0);
    expect(screen.queryByRole("spinbutton", { name: "变化强度 *" })).not.toBeInTheDocument();
    await changeControl(screen.getByRole("combobox", { name: "模式 *" }), { target: { value: "1" } });
    const strength = screen.getByRole("spinbutton", { name: "变化强度 *" });
    expect(strength).toHaveAttribute("min", "0");
    expect(strength).toHaveAttribute("max", "1");
    expect(strength).toHaveAttribute("step", "any");
    await changeControl(strength, { target: { value: "0.5" } });
    await changeControl(screen.getByRole("combobox", { name: "保留声音 *" }), { target: { value: "true" } });
    expect(screen.getByTestId("values")).toHaveTextContent('{"mode":2,"strength":0.5,"keepSound":true}');
    await changeControl(screen.getByRole("combobox", { name: "模式 *" }), { target: { value: "2" } });
    expect(screen.getByTestId("values")).toHaveTextContent('"mode":false');
    expect(screen.queryByRole("spinbutton", { name: "变化强度 *" })).not.toBeInTheDocument();
  });

  it("keeps string limits, integer bounds, source duration bounds and parameter mappings", () => {
    render(<ValuesHarness dialog />);
    expect(screen.getByRole("textbox", { name: "LoRA" })).toHaveAttribute("maxlength", "20");
    const seed = screen.getByRole("spinbutton", { name: "种子 *" });
    expect(seed).toHaveAttribute("min", "0");
    expect(seed).toHaveAttribute("max", "100");
    expect(seed).toHaveAttribute("step", "1");
    const seconds = screen.getByRole("spinbutton", { name: "时长 *" });
    expect(seconds).toHaveValue(45);
    expect(seconds).toHaveAttribute("min", "1");
    expect(seconds).toHaveAttribute("max", "60");
    expect(screen.getByText("10.name")).toBeInTheDocument();
    expect(screen.getByText("附加风格")).toBeInTheDocument();
  });

  it("allows inspecting running-task values but disables every portal control and keeps close usable", async () => {
    const close = vi.fn();
    render(<WorkflowParametersDialog open onOpenChange={close} definition={definition} values={{ mode: 2, strength: 0.75 }} prompt="" durationSeconds={20}
      disabled onChange={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "扩展参数" });
    for (const control of within(dialog).getAllByRole("combobox")) expect(control).toBeDisabled();
    for (const control of within(dialog).getAllByRole("spinbutton")) expect(control).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "LoRA" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "变化强度 *" })).toHaveValue(0.75);
    await clickControl(within(dialog).getByRole("button", { name: "关闭" }));
    expect(close).toHaveBeenCalledWith(false);
  });
});
