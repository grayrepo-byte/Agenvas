import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { Select } from "./Select";

function Form() {
  const [value, setValue] = useState("a");
  return <form aria-label="配置"><label>模型<Select name="model" value={value}
    onChange={(event) => setValue(event.target.value)}>
    <option value="a">Alpha</option><option value="b" disabled>Beta</option>
    <option value="c">Charlie</option><option value="d">Delta</option>
  </Select></label><button type="button">下一项</button></form>;
}

describe("Select", () => {
  it("selects from the shared panel and keeps native form values and focus", async () => {
    render(<Form />);
    const user = userEvent.setup();
    const select = screen.getByRole("combobox", { name: "模型" });
    await user.click(select);
    const list = screen.getByRole("listbox", { name: "模型" });
    expect(list.closest("form")).toBeNull();
    await user.click(within(list).getByRole("option", { name: "Charlie" }));
    expect(select).toHaveValue("c");
    expect(select).toHaveFocus();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(new FormData(screen.getByRole("form") as HTMLFormElement).get("model")).toBe("c");
  });

  it("navigates past disabled options with arrows, Home, End and typeahead", async () => {
    render(<Form />);
    const user = userEvent.setup();
    const select = screen.getByRole("combobox");
    select.focus();
    await user.keyboard("{ArrowDown}{ArrowDown}{Enter}");
    expect(select).toHaveValue("c");
    await user.keyboard("{Enter}{End}{Enter}");
    expect(select).toHaveValue("d");
    await user.keyboard("{Enter}{Home}{Enter}");
    expect(select).toHaveValue("a");
    await user.keyboard("{Enter}ch{Enter}");
    expect(select).toHaveValue("c");
  });

  it("dismisses with Escape, outside click and Tab without changing the value", async () => {
    render(<Form />);
    const user = userEvent.setup();
    const select = screen.getByRole("combobox");
    await user.click(select);
    await user.keyboard("{ArrowDown}{Escape}");
    expect(select).toHaveValue("a");
    expect(select).toHaveFocus();
    await user.click(select);
    await user.click(screen.getByRole("button", { name: "下一项" }));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    await user.click(select);
    await user.tab();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "下一项" })).toHaveFocus();
  });

  it("honors disabled fieldsets and disabled option groups", async () => {
    render(<><fieldset disabled><label>停用<Select defaultValue="a"><option value="a">Alpha</option></Select></label></fieldset>
      <label>分组<Select defaultValue="c"><optgroup label="停用组" disabled><option value="a">Alpha</option></optgroup>
        <option value="c">Charlie</option><option value="d">Delta</option></Select></label></>);
    const user = userEvent.setup();
    await user.click(screen.getByRole("combobox", { name: "停用" }));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    const select = screen.getByRole("combobox", { name: "分组" });
    await user.click(select);
    expect(within(screen.getByRole("listbox")).getByRole("option", { name: "Alpha" })).toBeDisabled();
    await user.keyboard("{Home}{Enter}");
    expect(select).toHaveValue("c");
  });

  it("fits the viewport and opens upward near the bottom", async () => {
    render(<Form />);
    const select = screen.getByRole("combobox");
    vi.spyOn(select, "getBoundingClientRect").mockReturnValue({
      x: window.innerWidth - 70, y: window.innerHeight - 45, left: window.innerWidth - 70,
      right: window.innerWidth - 10, top: window.innerHeight - 45, bottom: window.innerHeight - 5,
      width: 60, height: 40, toJSON: () => ({}),
    });
    await userEvent.setup().click(select);
    const list = screen.getByRole("listbox");
    expect(list.style.bottom).toBe("51px");
    expect(parseFloat(list.style.left) + parseFloat(list.style.width)).toBeLessThanOrEqual(window.innerWidth - 8);
    fireEvent(window, new Event("resize"));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("does not choose an option removed during a background refresh", async () => {
    const change = vi.fn();
    const view = render(<Select aria-label="模型" defaultValue="a" onChange={change}>
      <option value="a">Alpha</option><option value="c">Charlie</option></Select>);
    const user = userEvent.setup();
    await user.click(screen.getByRole("combobox"));
    view.rerender(<Select aria-label="模型" defaultValue="a" onChange={change}><option value="a">Alpha</option></Select>);
    await waitFor(() => expect(within(screen.getByRole("listbox")).queryByRole("option", { name: "Charlie" })).not.toBeInTheDocument());
    expect(change).not.toHaveBeenCalled();
    expect(screen.getByRole("combobox")).toHaveValue("a");
  });
});
