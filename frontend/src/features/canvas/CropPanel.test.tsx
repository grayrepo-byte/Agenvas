import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CropPanel } from "./CropPanel";

const STAGE_BOUNDS = {
  x: 0, y: 0, left: 0, top: 0, right: 900, bottom: 600,
  width: 900, height: 600, toJSON: () => ({}),
};

function showCrop(onSubmit = vi.fn()) {
  render(<CropPanel sourceUrl="/source.png" sourceWidth={1200} sourceHeight={800}
    busy={false} error={null} onClose={vi.fn()} onSubmit={onSubmit} />);
  vi.spyOn(screen.getByLabelText("裁剪工作区"), "getBoundingClientRect")
    .mockReturnValue(STAGE_BOUNDS);
  return onSubmit;
}

describe("CropPanel", () => {
  it("moves the crop rectangle directly on the image and submits normalized coordinates", () => {
    const onSubmit = showCrop();
    const selection = screen.getByRole("button", { name: "移动裁剪框" });
    fireEvent.pointerDown(selection, { clientX: 200, clientY: 150 });
    fireEvent.pointerMove(window, { clientX: 245, clientY: 180 });
    fireEvent.pointerUp(window);
    fireEvent.click(screen.getByRole("button", { name: "确定" }));

    expect(onSubmit).toHaveBeenCalledWith({ x: 0.13, y: 0.13, width: 0.84, height: 0.84 });
  });

  it("applies a selected aspect ratio and exposes keyboard-adjustable handles", () => {
    const onSubmit = showCrop();
    fireEvent.change(screen.getByRole("combobox", { name: "裁剪比例" }), {
      target: { value: "1:1" },
    });
    fireEvent.keyDown(screen.getByRole("button", { name: "移动裁剪框" }), {
      key: "ArrowRight",
    });
    fireEvent.click(screen.getByRole("button", { name: "确定" }));

    expect(screen.getByRole("button", { name: "调整裁剪框右下角" })).toBeInTheDocument();
    expect(onSubmit).toHaveBeenCalledWith({ x: 0.23, y: 0.08, width: 0.56, height: 0.84 });
  });
});
