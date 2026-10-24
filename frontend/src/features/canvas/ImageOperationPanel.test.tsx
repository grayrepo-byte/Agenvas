import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { clickControl } from "../../test/controls";
import { imageFunctionsFixture } from "../../test/imageFunctionsFixture";
import { ImageOperationPanel } from "./ImageOperationPanel";

function show(overrides: Partial<Parameters<typeof ImageOperationPanel>[0]> = {}) {
  const onSubmit = vi.fn();
  const capability = imageFunctionsFixture().connections[1]!.capabilities[0]!;
  const props: Parameters<typeof ImageOperationPanel>[0] = { operation: "EXPRESSION_EDIT", capabilities: [capability],
    sourceUrl: "/synthetic-source.png", sourceTitle: "参考图片", sourceWidth: 1200, sourceHeight: 800,
    busy: false, error: null, extraControls: null, submitDisabled: false, onClose: vi.fn(), onSubmit, ...overrides };
  const view = render(<ImageOperationPanel {...props} />);
  return { onSubmit, capability, rerender: (changes: Partial<typeof props>) => view.rerender(<ImageOperationPanel {...props} {...changes} />) };
}

describe("visual image operations", () => {
  it("lets a preset become an editable instruction without generating on selection", async () => {
    const { onSubmit } = show();
    expect(screen.getByRole("button", { name: "开始处理" })).toBeDisabled();
    await clickControl(screen.getByRole("radio", { name: "微笑" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox", { name: "目标表情" })).toHaveValue("自然微笑，嘴角轻轻上扬，保留人物身份与面部特征。");
    fireEvent.change(screen.getByRole("textbox", { name: "目标表情" }), { target: { value: "轻轻微笑，闭唇" } });
    expect(screen.getByRole("radio", { name: "微笑" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "开始处理" }));
    expect(onSubmit).toHaveBeenCalledWith({}, "轻轻微笑，闭唇");
    expect(screen.getByRole("img", { name: "参考图片" })).toHaveAttribute("src", "/synthetic-source.png");
  });

  it("only offers supported frames and sends the selected outpaint ratio", async () => {
    const capability = imageFunctionsFixture().connections[1]!.capabilities[0]!;
    const { onSubmit } = show({ operation: "OUTPAINT", capabilities: [{ ...capability, supportedImageAspectRatios: ["9:16", "1:1"] }] });
    expect(screen.queryByRole("radio", { name: "16:9" })).not.toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "1:1" })).toBeChecked();
    await clickControl(screen.getByRole("radio", { name: "9:16" }));
    fireEvent.click(screen.getByRole("button", { name: "开始处理" }));
    expect(onSubmit).toHaveBeenCalledWith({ aspectRatio: "9:16" }, "");
  });

  it("preserves a camera choice on failure and disables edits while accepting", async () => {
    const view = show({ operation: "VIEW_ANGLE" });
    await clickControl(screen.getByRole("radio", { name: "仰视" }));
    view.rerender({ error: new Error("failed"), busy: true });
    expect(screen.getByRole("radio", { name: "仰视" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "正面" })).toBeDisabled();
    expect(screen.getByRole("textbox")).toBeDisabled();
    expect(screen.getByRole("button", { name: "正在受理…" })).toBeDisabled();
    view.rerender({ error: new Error("failed"), busy: false });
    fireEvent.click(screen.getByRole("button", { name: "开始处理" }));
    expect(view.onSubmit).toHaveBeenCalledWith({ viewAngle: "LOW_ANGLE" }, "");
  });

  it("blocks transparent output on an incompatible method and permits a background layer", async () => {
    const capability = imageFunctionsFixture().connections[1]!.capabilities[0]!;
    const { onSubmit } = show({ operation: "LAYER_SPLIT", capabilities: [{ ...capability, supportsTransparentBackground: false }] });
    expect(screen.getByRole("button", { name: "开始处理" })).toBeDisabled();
    await clickControl(screen.getByRole("radio", { name: /背景层/ }));
    fireEvent.click(screen.getByRole("button", { name: "开始处理" }));
    expect(onSubmit).toHaveBeenCalledWith({ layerTarget: "BACKGROUND" }, "");
  });
});
