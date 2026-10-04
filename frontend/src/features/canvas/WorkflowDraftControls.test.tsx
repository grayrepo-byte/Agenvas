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
  it("renders one shared add entry for ten empty workflow media slots", () => {
    const tenSlots = { ...definition, fields: Array.from({ length: 10 }, (_, index) => ({
      key: `reference_${index}`, label: `参考图 ${index + 1}`, type: "IMAGE" as const,
      nodeId: String(index + 1), fieldName: "image", required: true, advanced: false,
    })) };
    render(<WorkflowMediaInputs definition={tenSlots} values={{}} prompt="" durationSeconds={null}
      choices={choices} onChange={vi.fn()} onChooseSource={vi.fn()} />);
    expect(screen.getAllByRole("button")).toHaveLength(1);
    expect(screen.getByRole("button", { name: "添加参考素材" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: /^选择参考图/ })).not.toBeInTheDocument();
  });

  it("opens the shared add menu and keeps exact named slots for replacing selected inputs", async () => {
    const choose = vi.fn(); const clear = vi.fn();
    render(<WorkflowMediaInputs definition={definition} values={{ first: "image-v1", last: "image-v1" }} prompt="" durationSeconds={null}
      choices={choices} canvasChoices={canvasChoices} onChange={clear} onChooseSource={choose} onUpload={vi.fn()} />);
    expect(screen.getAllByRole("button", { name: /^选择/ })).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "添加参考素材" })).toHaveLength(1);
    expect(screen.getAllByRole("presentation")).toHaveLength(2);
    expect(screen.getByText("首帧 *")).toHaveClass("sr-only");
    await clickControl(screen.getByRole("button", { name: "选择首帧" }));
    const menu = screen.getByRole("menu", { name: "图片来源" });
    expect(menu).toHaveClass("media-draft-reference-sources");
    await clickControl(within(menu).getByRole("menuitem", { name: "从资源库选择" }));
    expect(choose).toHaveBeenLastCalledWith(expect.objectContaining({ key: "first" }), "resources", screen.getByRole("button", { name: "选择首帧" }));
    await clickControl(screen.getByRole("button", { name: "选择尾帧" }));
    await clickControl(screen.getByRole("menuitem", { name: "从画布选择" }));
    expect(choose).toHaveBeenLastCalledWith(expect.objectContaining({ key: "last" }), "canvas", screen.getByRole("button", { name: "选择尾帧" }));
    await clickControl(screen.getByRole("button", { name: "添加参考素材" }));
    await clickControl(screen.getByRole("menuitem", { name: "从我的资产选择" }));
    expect(choose).toHaveBeenLastCalledWith(null, "library", screen.getByRole("button", { name: "添加参考素材" }));
    await clickControl(screen.getByRole("button", { name: "添加参考素材" }));
    await clickControl(screen.getByRole("menuitem", { name: "从资源库选择" }));
    expect(choose).toHaveBeenLastCalledWith(null, "resources", screen.getByRole("button", { name: "添加参考素材" }));
    await clickControl(screen.getByRole("button", { name: "添加参考素材" }));
    await clickControl(screen.getByRole("menuitem", { name: "从画布选择" }));
    expect(choose).toHaveBeenLastCalledWith(null, "canvas", screen.getByRole("button", { name: "添加参考素材" }));
    await clickControl(screen.getByRole("button", { name: "清空首帧" }));
    expect(clear).toHaveBeenCalledWith("first", undefined);
  });

  it("uses defaults to hide inactive slots and keeps unavailable saved versions visible", () => {
    const conditional = { ...definition, fields: definition.fields.map((field) => field.key === "last" ? { ...field, enabledWhen: { field: "mode", value: 2 } } : field) };
    const props = { definition: conditional, values: { first: "missing-version", last: "image-v1" }, prompt: "", durationSeconds: null, choices, onChooseSource: vi.fn(), onChange: vi.fn() };
    const { rerender } = render(<WorkflowMediaInputs {...props} />);
    expect(screen.queryByRole("button", { name: "选择尾帧" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择首帧" })).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("首帧 · 版本不可用")).toBeInTheDocument();
    rerender(<WorkflowMediaInputs {...props} values={{ first: "image-v1", last: "image-v1", mode: 2 }} />);
    expect(screen.getByRole("button", { name: "选择尾帧" })).toBeInTheDocument();
    expect(screen.queryByText("首帧 · 版本不可用")).not.toBeInTheDocument();
    rerender(<WorkflowMediaInputs {...props} values={{ first: "image-v1", last: "image-v1", sound: "audio-v1", mode: 0 }} />);
    expect(screen.queryByRole("button", { name: "选择尾帧" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择配音" }).closest(".workflow-media-slot")).toHaveTextContent("3");
    expect(screen.getByRole("button", { name: "添加参考素材" })).toBeDisabled();
  });

  it("reports upload busy, preserves failures for retry and disables portal choices while locked", async () => {
    const uploaded = new File(["synthetic"], "reference.png", { type: "image/png" });
    let rejectUpload: ((reason: Error) => void) | undefined;
    const upload = vi.fn().mockImplementationOnce(() => new Promise<void>((_resolve, reject) => { rejectUpload = reject; })).mockResolvedValue(undefined);
    const onBusy = vi.fn();
    const props = { definition, values: {}, prompt: "", durationSeconds: null, choices, onChooseSource: vi.fn(), onChange: vi.fn(), onUpload: upload, onBusy };
    const { rerender } = render(<WorkflowMediaInputs {...props} />);
    await clickControl(screen.getByRole("button", { name: "添加参考素材" }));
    fireEvent.change(screen.getByLabelText("选择本地图片、视频或音频"), { target: { files: [uploaded] } });
    expect(onBusy).toHaveBeenLastCalledWith(true);
    expect(screen.getByRole("button", { name: "添加参考素材" })).toBeDisabled();
    rejectUpload?.(new Error("合成上传失败"));
    await waitFor(() => expect(onBusy).toHaveBeenLastCalledWith(false));
    expect(screen.getAllByRole("alert").some((alert) => alert.textContent?.includes("合成上传失败"))).toBe(true);
    await clickControl(screen.getByRole("button", { name: "重试上传" }));
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
    expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ key: "first" }), uploaded, { requireEmptySlot: true });
    await waitFor(() => expect(screen.queryByText("合成上传失败")).not.toBeInTheDocument());
    upload.mockRejectedValueOnce(new Error("新的合成上传失败"));
    fireEvent.change(screen.getByLabelText("选择本地图片、视频或音频"), { target: { files: [uploaded] } });
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("新的合成上传失败"));
    rerender(<WorkflowMediaInputs {...props} values={{ first: "image-v1" }} />);
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    await clickControl(screen.getByRole("button", { name: "选择首帧" }));
    rerender(<WorkflowMediaInputs {...props} values={{ first: "image-v1" }} disabled />);
    expect(screen.getByRole("button", { name: "选择首帧" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "添加参考素材" })).toBeDisabled();
    expect(screen.queryByRole("menu", { name: "图片来源" })).not.toBeInTheDocument();
  });

  it("routes mixed uploads to the first compatible empty slot and narrows accepted types as slots fill", async () => {
    const videoField = { key: "clip", label: "参考视频", type: "VIDEO" as const, nodeId: "11", fieldName: "video", required: false, advanced: false };
    const mixedDefinition = { ...definition, fields: [...definition.fields, videoField] };
    const upload = vi.fn().mockResolvedValue(undefined);
    const props = { definition: mixedDefinition, values: {}, prompt: "", durationSeconds: null, choices, onChooseSource: vi.fn(), onChange: vi.fn(), onUpload: upload };
    const { rerender } = render(<WorkflowMediaInputs {...props} />);
    const input = screen.getByLabelText("选择本地图片、视频或音频");
    expect(input).toHaveAttribute("accept", "image/png,image/jpeg,image/webp,audio/mpeg,audio/wav,audio/ogg,video/mp4");
    const audio = new File(["synthetic"], "sound.ogg", { type: "audio/ogg" });
    fireEvent.change(input, { target: { files: [audio] } });
    await waitFor(() => expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ key: "sound" }), audio, { requireEmptySlot: true }));
    await waitFor(() => expect(screen.getByRole("button", { name: "添加参考素材" })).toBeEnabled());
    rerender(<WorkflowMediaInputs {...props} values={{ sound: "audio-v1", first: "image-v1" }} />);
    expect(input).toHaveAttribute("accept", "image/png,image/jpeg,image/webp,video/mp4");
    const video = new File(["synthetic"], "clip.mp4", { type: "video/mp4" });
    fireEvent.change(input, { target: { files: [video] } });
    await waitFor(() => expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ key: "clip" }), video, { requireEmptySlot: true }));
    await waitFor(() => expect(screen.getByRole("button", { name: "添加参考素材" })).toBeEnabled());
    const image = new File(["synthetic"], "last.png", { type: "image/png" });
    fireEvent.change(input, { target: { files: [image] } });
    await waitFor(() => expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ key: "last" }), image, { requireEmptySlot: true }));
  });

  it("keeps the shared entry disabled at capacity and restores it after a named assignment is cleared", async () => {
    render(<ValuesHarness initialValues={{ first: "image-v1", last: "image-v1", sound: "audio-v1" }} />);
    expect(screen.getByRole("button", { name: "添加参考素材" })).toBeDisabled();
    expect(screen.getAllByRole("button", { name: /^选择/ })).toHaveLength(3);
    await clickControl(screen.getByRole("button", { name: "清空尾帧" }));
    expect(screen.getByRole("button", { name: "添加参考素材" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "选择尾帧" })).not.toBeInTheDocument();
    expect(screen.getByTestId("values")).toHaveTextContent('{"first":"image-v1","sound":"audio-v1"}');
  });

  it("uploads replacements to the selected slot and preserves its exact file for retry", async () => {
    const file = new File(["synthetic"], "replacement.png", { type: "image/png" });
    const upload = vi.fn().mockRejectedValueOnce(new Error("合成替换失败")).mockResolvedValue(undefined);
    render(<WorkflowMediaInputs definition={definition} values={{ last: "image-v1" }} prompt="" durationSeconds={null}
      choices={choices} onChange={vi.fn()} onChooseSource={vi.fn()} onUpload={upload} />);
    fireEvent.change(screen.getByLabelText("上传尾帧"), { target: { files: [file] } });
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("合成替换失败"));
    await clickControl(screen.getByRole("button", { name: "重试上传" }));
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
    expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ key: "last" }), file);
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  it("reports a local file without a compatible empty slot and does not offer a retry", () => {
    const upload = vi.fn();
    render(<WorkflowMediaInputs definition={definition} values={{ sound: "audio-v1" }} prompt="" durationSeconds={null}
      choices={choices} onChange={vi.fn()} onChooseSource={vi.fn()} onUpload={upload} />);
    const audio = new File(["synthetic"], "extra.ogg", { type: "audio/ogg" });
    fireEvent.change(screen.getByLabelText("选择本地图片、视频或音频"), { target: { files: [audio] } });
    expect(upload).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("所选素材超出图片/音频数量限制，或包含不能混用的参考素材。");
    expect(screen.queryByRole("button", { name: "重试上传" })).not.toBeInTheDocument();
  });

  it("retains a failed shared upload when its empty slot is claimed during upload and retries after a slot is freed", async () => {
    const file = new File(["synthetic"], "new-reference.png", { type: "image/png" });
    let rejectUpload: ((failure: Error) => void) | undefined;
    const upload = vi.fn().mockImplementationOnce(() => new Promise<void>((_resolve, reject) => { rejectUpload = reject; })).mockResolvedValue(undefined);
    const props = { definition, values: {}, prompt: "", durationSeconds: null, choices, onChooseSource: vi.fn(), onChange: vi.fn(), onUpload: upload };
    const { rerender } = render(<WorkflowMediaInputs {...props} />);
    fireEvent.change(screen.getByLabelText("选择本地图片、视频或音频"), { target: { files: [file] } });
    rerender(<WorkflowMediaInputs {...props} values={{ first: "image-v1", last: "image-v1", sound: "audio-v1" }} />);
    expect(screen.getByRole("status")).toHaveTextContent("首帧");
    rejectUpload?.(new Error("合成满槽失败"));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("合成满槽失败"));
    expect(screen.getByRole("button", { name: "添加参考素材" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "重试上传" })).toBeEnabled();
    rerender(<WorkflowMediaInputs {...props} values={{ last: "image-v1", sound: "audio-v1" }} />);
    expect(screen.getByRole("alert")).toHaveTextContent("合成满槽失败");
    await clickControl(screen.getByRole("button", { name: "重试上传" }));
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(2));
    expect(upload).toHaveBeenLastCalledWith(expect.objectContaining({ key: "first" }), file, { requireEmptySlot: true });
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
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
    expect(screen.getByRole("textbox", { name: "附加风格" })).toHaveAttribute("maxlength", "20");
    const seed = screen.getByRole("spinbutton", { name: "种子 *" });
    expect(seed).toHaveAttribute("min", "0");
    expect(seed).toHaveAttribute("max", "100");
    expect(seed).toHaveAttribute("step", "1");
    const seconds = screen.getByRole("spinbutton", { name: "时长 *" });
    expect(seconds).toHaveValue(45);
    expect(seconds).toHaveAttribute("min", "1");
    expect(seconds).toHaveAttribute("max", "60");
    expect(screen.queryByText("10.name")).not.toBeInTheDocument();
    expect(screen.getByText("附加风格", { selector: "strong" })).toHaveAttribute("title", "LoRA · 10.name");
  });

  it("allows inspecting running-task values but disables every portal control and keeps close usable", async () => {
    const close = vi.fn();
    render(<WorkflowParametersDialog open onOpenChange={close} definition={definition} values={{ mode: 2, strength: 0.75 }} prompt="" durationSeconds={20}
      disabled onChange={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "扩展参数" });
    for (const control of within(dialog).getAllByRole("combobox")) expect(control).toBeDisabled();
    for (const control of within(dialog).getAllByRole("spinbutton")) expect(control).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "附加风格" })).toBeDisabled();
    expect(screen.getByRole("spinbutton", { name: "变化强度 *" })).toHaveValue(0.75);
    await clickControl(within(dialog).getByRole("button", { name: "关闭" }));
    expect(close).toHaveBeenCalledWith(false);
  });
});
