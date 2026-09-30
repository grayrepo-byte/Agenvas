import { render, screen } from "@testing-library/react";
import { useRef, useState } from "react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { DropdownMenu } from "./DropdownMenu";

describe("DropdownMenu", () => {
  it("traverses enabled items and wraps without triggering a choice", async () => {
    render(<DropdownMenu aria-label="工具"><button role="menuitem">选择</button>
      <button role="menuitem" disabled>停用</button><button role="menuitemradio">手形</button></DropdownMenu>);
    const user = userEvent.setup();
    screen.getByRole("menuitem", { name: "选择" }).focus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitemradio")).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "选择" })).toHaveFocus();
    await user.keyboard("{End}{Home}");
    expect(screen.getByRole("menuitem", { name: "选择" })).toHaveFocus();
  });

  it("dismisses with Escape and restores the trigger, while outside clicks keep their destination", async () => {
    function Menu() {
      const anchor = useRef<HTMLDivElement>(null);
      const trigger = useRef<HTMLButtonElement>(null);
      const [open, setOpen] = useState(false);
      return <><div ref={anchor}><button ref={trigger} onClick={() => setOpen(true)}>打开</button>
        {open ? <DropdownMenu anchorRef={anchor} triggerRef={trigger} onDismiss={() => setOpen(false)} focusOnOpen>
          <button role="menuitem">选项</button></DropdownMenu> : null}</div><button>外部</button></>;
    }
    render(<Menu />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "打开" }));
    expect(screen.getByRole("menuitem")).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开" })).toHaveFocus();
    await user.click(screen.getByRole("button", { name: "打开" }));
    await user.click(screen.getByRole("button", { name: "外部" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "外部" })).toHaveFocus();
  });
});
