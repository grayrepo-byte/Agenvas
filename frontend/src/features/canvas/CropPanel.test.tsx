import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { changeControl, clickControl } from "../../test/controls";
import { CropPanel } from "./CropPanel";

const STAGE_BOUNDS = {
  x: 0,
  y: 0,
  left: 0,
  top: 0,
  right: 900,
  bottom: 600,
  width: 900,
  height: 600,
  toJSON: () => ({}),
};

function showCrop(onSubmit = vi.fn()) {
  render(
    <CropPanel
      sourceUrl="/source.png"
      sourceWidth={1200}
      sourceHeight={800}
      busy={false}
      error={null}
      onClose={vi.fn()}
      onSubmit={onSubmit}
    />,
  );
  vi.spyOn(screen.getByLabelText("裁剪工作区"), "getBoundingClientRect").mockReturnValue(STAGE_BOUNDS);
  return onSubmit;
}

describe("CropPanel", () => {
  it("waits for real dimensions before allowing a crop", async () => {
    const onSubmit = vi.fn();
    render(<CropPanel sourceUrl="/source.png" busy={false} error={null} onClose={vi.fn()} onSubmit={onSubmit} />);
    expect(screen.getByRole("combobox", { name: "裁剪比例" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "移动裁剪框" })).toBeDisabled();
    await clickControl(screen.getByRole("button", { name: "确定" }));
    expect(onSubmit).not.toHaveBeenCalled();

    const image = screen.getByRole("img");
    Object.defineProperties(image, { naturalWidth: { value: 1600 }, naturalHeight: { value: 800 } });
    fireEvent.load(image);
    await changeControl(screen.getByRole("combobox", { name: "裁剪比例" }), { target: { value: "1:1" } });
    await clickControl(screen.getByRole("button", { name: "确定" }));
    expect(onSubmit).toHaveBeenCalledWith({ x: 0.29, y: 0.08, width: 0.42, height: 0.84 });
  });

  it("uses metadata arriving after opening without resetting an adjusted crop", async () => {
    const onSubmit = vi.fn();
    const props = { sourceUrl: "/source.png", busy: false, error: null, onClose: vi.fn(), onSubmit };
    const view = render(<CropPanel {...props} />);
    view.rerender(<CropPanel {...props} sourceWidth={1600} sourceHeight={800} />);
    await changeControl(screen.getByRole("combobox", { name: "裁剪比例" }), { target: { value: "1:1" } });
    fireEvent.keyDown(screen.getByRole("button", { name: "移动裁剪框" }), { key: "ArrowRight" });
    const image = screen.getByRole("img");
    Object.defineProperties(image, { naturalWidth: { value: 1600 }, naturalHeight: { value: 800 } });
    fireEvent.load(image);
    await clickControl(screen.getByRole("button", { name: "确定" }));
    expect(onSubmit).toHaveBeenCalledWith({ x: 0.3, y: 0.08, width: 0.42, height: 0.84 });
  });

  it("moves the crop rectangle directly on the image and submits normalized coordinates", async () => {
    const onSubmit = showCrop();
    const selection = screen.getByRole("button", { name: "移动裁剪框" });
    fireEvent.pointerDown(selection, { clientX: 200, clientY: 150 });
    fireEvent.pointerMove(window, { clientX: 245, clientY: 180 });
    fireEvent.pointerUp(window);
    await clickControl(screen.getByRole("button", { name: "确定" }));

    expect(onSubmit).toHaveBeenCalledWith({ x: 0.13, y: 0.13, width: 0.84, height: 0.84 });
  });

  it("applies a selected aspect ratio and exposes keyboard-adjustable handles", async () => {
    const onSubmit = showCrop();
    await changeControl(screen.getByRole("combobox", { name: "裁剪比例" }), {
      target: { value: "1:1" },
    });
    fireEvent.keyDown(screen.getByRole("button", { name: "移动裁剪框" }), {
      key: "ArrowRight",
    });
    await clickControl(screen.getByRole("button", { name: "确定" }));

    expect(screen.getByRole("button", { name: "调整裁剪框右下角" })).toBeInTheDocument();
    expect(onSubmit).toHaveBeenCalledWith({ x: 0.23, y: 0.08, width: 0.56, height: 0.84 });
  });
});
