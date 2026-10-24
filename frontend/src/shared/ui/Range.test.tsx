import { createRef, useState } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Range, setRangeValue } from "./Range";

describe("Range", () => {
  it("maps nonzero and negative bounds to the shared fill and forwards real changes", () => {
    const change = vi.fn();
    function Brightness() {
      const [value, setValue] = useState(0);
      return <Range aria-label="亮度" min={-100} max={100} value={value} onChange={(event) => {
        change(event.target.valueAsNumber); setValue(event.target.valueAsNumber);
      }} />;
    }
    render(<Brightness />);
    const input = screen.getByRole("slider", { name: "亮度" });
    expect(input.style.getPropertyValue("--ui-range-progress")).toBe("50%");
    fireEvent.change(input, { target: { value: "60" } });
    expect(change).toHaveBeenCalledWith(60);
    expect(input.style.getPropertyValue("--ui-range-progress")).toBe("80%");
  });

  it("updates media-clock refs without a React render and clamps to the browser range", () => {
    const ref = createRef<HTMLInputElement>();
    render(<Range ref={ref} aria-label="预演" variant="media" min={0} max={1000} defaultValue={250} />);
    expect(ref.current?.style.getPropertyValue("--ui-range-progress")).toBe("25%");
    setRangeValue(ref.current!, 750);
    expect(ref.current).toHaveValue("750");
    expect(ref.current?.style.getPropertyValue("--ui-range-progress")).toBe("75%");
    setRangeValue(ref.current!, 1200);
    expect(ref.current).toHaveValue("1000");
    expect(ref.current?.style.getPropertyValue("--ui-range-progress")).toBe("100%");
  });

  it("preserves form submission and synchronizes an uncontrolled form reset", async () => {
    render(<form aria-label="参数"><Range aria-label="大小" name="size" min={0} max={100} defaultValue={20} /></form>);
    const form = screen.getByRole("form") as HTMLFormElement;
    const input = screen.getByRole("slider");
    fireEvent.input(input, { target: { value: "80" } });
    expect(new FormData(form).get("size")).toBe("80");
    expect(input.style.getPropertyValue("--ui-range-progress")).toBe("80%");
    form.reset();
    await waitFor(() => expect(input.style.getPropertyValue("--ui-range-progress")).toBe("20%"));
    expect(new FormData(form).get("size")).toBe("20");
  });

  it("keeps label, value text, fieldset disabling and degenerate bounds", () => {
    render(<fieldset disabled><label htmlFor="seek">预览进度</label><Range id="seek" min={5} max={5} value={5}
      variant="media" aria-valuetext="00:05 / 00:05" readOnly /></fieldset>);
    const input = screen.getByRole("slider", { name: "预览进度" });
    expect(input).toBeDisabled();
    expect(input).toHaveAttribute("aria-valuetext", "00:05 / 00:05");
    expect(input.style.getPropertyValue("--ui-range-progress")).toBe("0%");
  });
});
