import { fireEvent, render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe,expect,it,vi } from "vitest";
import { Select } from "./Select";

function Form({ variant }: { variant: "default" | "ghost" }) {
  const [value, setValue] = useState("a");
  return <form aria-label="配置"><label>模型<Select variant={variant} name="model" value={value}
    onChange={(event) => setValue(event.target.value)}>
    <option value="a">Alpha</option><option value="b" disabled>Beta</option>
    <option value="c">Charlie</option><option value="d">Delta</option>
  </Select></label><button type="button">下一项</button></form>;
}

function EditorSelect({ variant }: { variant: "default" | "ghost" }) {
  const [container, setContainer] = useState<HTMLElement | null>(null);
  return <section ref={setContainer} role="dialog" aria-label="编辑图片">
    <Select variant={variant} aria-label="模型" defaultValue="a" portalContainer={container}>
      <option value="a">Alpha</option><option value="c">Charlie</option>
    </Select>
  </section>;
}

describe.each(["default", "ghost"] as const)("Select (%s)", (variant) => {
  it("keeps an editor's model popup in its overlay and restores focus after selection", async () => {
    render(<EditorSelect variant={variant} />);
    const editor = screen.getByRole("dialog", { name: "编辑图片" });
    const trigger = screen.getByRole("combobox", { name: "模型" });
    const user = userEvent.setup();
    await user.click(trigger);
    const list = within(editor).getByRole("listbox", { name: "模型" });
    await user.click(within(list).getByRole("option", { name: "Charlie" }));
    expect(trigger).toHaveValue("c");
    expect(trigger).toHaveFocus();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });
  it("selects from the shared panel and keeps native form values and focus", async () => {
    render(<Form variant={variant} />);
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
    render(<Form variant={variant} />);
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

  it("dismisses with Escape and outside pointer and Tab without changing the value", async () => {
    render(<Form variant={variant} />);
    const user = userEvent.setup();
    const select = screen.getByRole("combobox");
    await user.click(select);
    await user.keyboard("{ArrowDown}{Escape}");
    expect(select).toHaveValue("a");
    expect(select).toHaveFocus();
    await user.click(select);
    const outside = screen.getByRole("button", { name: "下一项", hidden: true });
    fireEvent.pointerDown(outside);
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    await user.click(select);
    await user.tab();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "下一项" })).toHaveFocus());
  });

  it("honors disabled fieldsets and disabled option groups", async () => {
    render(<><fieldset disabled><label>停用<Select variant={variant} defaultValue="a"><option value="a">Alpha</option></Select></label></fieldset>
      <label>分组<Select variant={variant} defaultValue="c"><optgroup label="停用组" disabled><option value="a">Alpha</option></optgroup>
        <option value="c">Charlie</option><option value="d">Delta</option></Select></label></>);
    const user = userEvent.setup();
    await user.click(screen.getByRole("combobox", { name: "停用" }));
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    const select = screen.getByRole("combobox", { name: "分组" });
    await user.click(select);
    expect(within(screen.getByRole("listbox")).getByRole("option", { name: "Alpha" })).toHaveAttribute("aria-disabled", "true");
    await user.keyboard("{Home}{Enter}");
    expect(select).toHaveValue("c");
  });

  it("delegates available viewport height to Radix positioning", async () => {
    render(<Form variant={variant} />);
    const select = screen.getByRole("combobox");
    vi.spyOn(select, "getBoundingClientRect").mockReturnValue({
      x: window.innerWidth - 70, y: window.innerHeight - 45, left: window.innerWidth - 70,
      right: window.innerWidth - 10, top: window.innerHeight - 45, bottom: window.innerHeight - 5,
      width: 60, height: 40, toJSON: () => ({}),
    });
    await userEvent.setup().click(select);
    const list = screen.getByRole("listbox");
    expect(list).toHaveAttribute("data-slot", "select-content");
    expect(list.style.getPropertyValue("--radix-select-content-available-height")).toBe("var(--radix-popper-available-height)");
    await userEvent.setup().keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });

  it("does not choose an option removed during a background refresh", async () => {
    const change = vi.fn();
    const view = render(<Select variant={variant} aria-label="模型" defaultValue="a" onChange={change}>
      <option value="a">Alpha</option><option value="c">Charlie</option></Select>);
    const user = userEvent.setup();
    await user.click(screen.getByRole("combobox"));
    view.rerender(<Select variant={variant} aria-label="模型" defaultValue="a" onChange={change}><option value="a">Alpha</option></Select>);
    await waitFor(() => expect(within(screen.getByRole("listbox")).queryByRole("option", { name: "Charlie" })).not.toBeInTheDocument());
    expect(change).not.toHaveBeenCalled();
    expect(screen.getByRole("combobox", { name: "模型", hidden: true })).toHaveValue("a");
  });

  it("selects a descriptive model row while keeping only its name in the trigger and form", async () => {
    render(<form aria-label="模型配置"><Select variant={variant} aria-label="模型" name="model" defaultValue="a"
      optionDetails={{ a: { icon: <svg aria-hidden="true" />, description: "Source A · model-a" },
        c: { icon: <svg aria-hidden="true" />, description: "Source C · model-c" } }}>
      <option value="a">Alpha</option><option value="c">Charlie</option>
    </Select></form>);
    const user = userEvent.setup();
    const trigger = screen.getByRole("combobox", { name: "模型" });
    await user.click(trigger);
    expect(screen.getByText("Source C · model-c")).toBeVisible();
    await user.click(screen.getByRole("option", { name: /Charlie/ }));
    expect(trigger).toHaveTextContent(/^Charlie$/);
    expect(trigger).toHaveFocus();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(new FormData(screen.getByRole("form") as HTMLFormElement).get("model")).toBe("c");
  });
});
