import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { CanvasSelectionCheckbox } from "./CanvasSelectionCheckbox";

describe("CanvasSelectionCheckbox", () => {
  it("toggles with pointer and Space without triggering node gestures", async () => {
    const user = userEvent.setup();
    const pointerDown = vi.fn();
    const click = vi.fn();
    const doubleClick = vi.fn();
    const toggle = vi.fn();
    function Node() {
      const [selected, setSelected] = useState(false);
      return (
        <div onPointerDown={pointerDown} onClick={click} onDoubleClick={doubleClick}>
          <CanvasSelectionCheckbox
            title="镜头一"
            selected={selected}
            onToggle={() => {
              toggle();
              setSelected(current => !current);
            }}
          />
        </div>
      );
    }
    render(<Node />);
    const checkbox = screen.getByRole("checkbox", { name: "选择卡片：镜头一" });
    expect(checkbox).toHaveClass("nodrag", "nopan");
    expect(checkbox).not.toBeChecked();
    await user.click(checkbox);
    expect(checkbox).toBeChecked();
    expect(toggle).toHaveBeenCalledTimes(1);
    await user.keyboard(" ");
    expect(checkbox).not.toBeChecked();
    expect(toggle).toHaveBeenCalledTimes(2);
    fireEvent.doubleClick(checkbox);
    expect(pointerDown).not.toHaveBeenCalled();
    expect(click).not.toHaveBeenCalled();
    expect(doubleClick).not.toHaveBeenCalled();
  });
});
