import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ImageResizePanel } from "./ImageResizePanel";
import { imageResizeDimensions } from "./imageResize";

function mount(overrides: Partial<Parameters<typeof ImageResizePanel>[0]> = {}) {
  const onSubmit = vi.fn();
  const onRetryMetadata = vi.fn();
  render(<ImageResizePanel sourceWidth={1200} sourceHeight={800} loading={false} metadataError={null}
    busy={false} error={null} extraControls={null} submitDisabled={false} onClose={vi.fn()}
    onSubmit={onSubmit} onRetryMetadata={onRetryMetadata} {...overrides} />);
  return { onSubmit, onRetryMetadata };
}

describe("ImageResizePanel", () => {
  it("previews and submits a fractional percentage", () => {
    const { onSubmit } = mount();
    expect(screen.getByText("目标：600 × 400 px")).toBeVisible();
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "33.3" } });
    expect(screen.getByText("目标：400 × 266 px")).toBeVisible();
    fireEvent.submit(screen.getByRole("dialog"));
    expect(onSubmit).toHaveBeenCalledWith({ resizeMode: "PERCENTAGE", percentage: 33.3 });
  });
  it("uses the longest side for portrait images and remembers both values", () => {
    const { onSubmit } = mount({ sourceWidth: 800, sourceHeight: 1200 });
    fireEvent.click(screen.getByRole("radio", { name: "最长边像素" }));
    expect(screen.getByText("目标：683 × 1024 px")).toBeVisible();
    fireEvent.submit(screen.getByRole("dialog"));
    expect(onSubmit).toHaveBeenCalledWith({ resizeMode: "LONGEST_EDGE", longestEdge: 1024 });
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "512" } });
    fireEvent.click(screen.getByRole("radio", { name: "百分比" }));
    expect(screen.getByRole("spinbutton")).toHaveValue(50);
    fireEvent.click(screen.getByRole("radio", { name: "最长边像素" }));
    expect(screen.getByRole("spinbutton")).toHaveValue(512);
  });
  it("blocks empty, fractional pixel and oversized output", () => {
    const { onSubmit } = mount();
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "" } });
    expect(screen.getByRole("button", { name: "开始缩放" })).toBeDisabled();
    fireEvent.click(screen.getByRole("radio", { name: "最长边像素" }));
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "1.5" } });
    expect(screen.getByRole("button", { name: "开始缩放" })).toBeDisabled();
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "40000" } });
    expect(screen.getByText("目标图片不能超过 4000 万像素。")).toBeVisible();
    fireEvent.submit(screen.getByRole("dialog"));
    expect(onSubmit).not.toHaveBeenCalled();
  });
  it("allows reading failed dimensions again and blocks execution", () => {
    const { onRetryMetadata } = mount({ metadataError: new Error("failed") });
    expect(screen.getByRole("button", { name: "开始缩放" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(onRetryMetadata).toHaveBeenCalledOnce();
  });
  it("keeps parameters on submission failure and locks them while pending", () => {
    mount({ busy: true, error: new Error("冲突，请刷新") });
    expect(screen.getByRole("alert")).toHaveTextContent("冲突，请刷新");
    expect(screen.getByRole("spinbutton")).toHaveValue(50);
    expect(screen.getByRole("spinbutton")).toBeDisabled();
    expect(screen.getByRole("button", { name: "处理中…" })).toBeDisabled();
  });
  it("rounds thin and tiny images to at least one pixel", () => {
    expect(imageResizeDimensions(40000, 1, "LONGEST_EDGE", 1)).toEqual({ width: 1, height: 1 });
    expect(imageResizeDimensions(1, 1, "PERCENTAGE", 0.01)).toEqual({ width: 1, height: 1 });
  });
});
