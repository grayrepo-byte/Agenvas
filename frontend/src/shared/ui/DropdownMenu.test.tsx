import { render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem } from "./primitives/dropdown-menu";
import { Button } from "./primitives/button";

function Menu({ onChoice = () => {} }: { onChoice?: () => void }) {
  const [open, setOpen] = useState(false);
  return <><DropdownMenu open={open} onOpenChange={setOpen} modal={false}>
    <DropdownMenuTrigger asChild><Button>打开</Button></DropdownMenuTrigger>
    <DropdownMenuContent aria-label="工具" aria-labelledby={undefined} loop><DropdownMenuGroup>
      <DropdownMenuItem onSelect={onChoice}>选择</DropdownMenuItem>
      <DropdownMenuItem disabled>停用</DropdownMenuItem>
      <DropdownMenuItem onSelect={onChoice}>手形</DropdownMenuItem>
    </DropdownMenuGroup></DropdownMenuContent>
  </DropdownMenu><Button>外部</Button></>;
}

describe("shadcn DropdownMenu", () => {
  it("traverses enabled items and wraps without triggering a choice", async () => {
    const choose = vi.fn(); render(<Menu onChoice={choose} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "打开" }));
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "选择" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "手形" })).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByRole("menuitem", { name: "选择" })).toHaveFocus();
    expect(choose).not.toHaveBeenCalled();
    await user.keyboard("{Enter}"); expect(choose).toHaveBeenCalledOnce();
  });

  it("dismisses with Escape and restores the trigger, while outside clicks keep their destination", async () => {
    render(<Menu />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "打开" }));
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "打开" })).toHaveFocus());
    await user.click(screen.getByRole("button", { name: "打开" }));
    await user.click(screen.getByRole("button", { name: "外部" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "外部" })).toHaveFocus();
  });
});
