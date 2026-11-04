import { render,screen,within } from "@testing-library/react";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { describe,expect,it,vi } from "vitest";
import { Dialog } from "./Dialog";

const preventSubmit = (event: React.FormEvent<HTMLFormElement>) => event.preventDefault();

describe("Dialog stacking", () => {
  it.each(["click", "Enter"] as const)("submits only the active child dialog with %s and keeps the parent usable", async (method) => {
    const outerSubmit = vi.fn(preventSubmit);
    const parentSubmit = vi.fn(preventSubmit);
    const childSubmit = vi.fn(preventSubmit);
    function Stack() {
      const [childOpen, setChildOpen] = useState(true);
      return <form onSubmit={outerSubmit}>
        <Dialog title="Parent" onClose={() => {}} onSubmit={parentSubmit} footer={<button type="submit">Save parent</button>}>
          <input aria-label="Parent draft" defaultValue="Unsaved parent" />
          {childOpen ? <Dialog title="Child" onClose={() => setChildOpen(false)} onSubmit={childSubmit} footer={<button type="submit">Save child</button>}>
            <input aria-label="Child draft" defaultValue="Unsaved child" />
          </Dialog> : null}
        </Dialog>
      </form>;
    }
    render(<Stack />);
    const user = userEvent.setup();
    const child = screen.getByRole("dialog", { name: "Child" });
    if (method === "click") await user.click(within(child).getByRole("button", { name: "Save child" }));
    else { await user.click(within(child).getByRole("textbox")); await user.keyboard("{Enter}"); }
    expect(childSubmit).toHaveBeenCalledOnce();
    expect(parentSubmit).not.toHaveBeenCalled();
    expect(outerSubmit).not.toHaveBeenCalled();
    expect(within(child).getByRole("textbox")).toHaveValue("Unsaved child");
    await user.keyboard("{Escape}");
    const parent = screen.getByRole("dialog", { name: "Parent" });
    expect(within(parent).getByRole("textbox")).toHaveValue("Unsaved parent");
    await user.click(within(parent).getByRole("button", { name: "Save parent" }));
    expect(parentSubmit).toHaveBeenCalledOnce();
    expect(childSubmit).toHaveBeenCalledOnce();
    expect(outerSubmit).not.toHaveBeenCalled();
  });

  it("restores the original scroll setting when an entire nested stack unmounts", () => {
    document.body.style.overflow = "auto";
    const { unmount } = render(<Dialog title="Parent" onClose={() => {}} onSubmit={preventSubmit} footer={null}>
      <Dialog title="Child" onClose={() => {}} onSubmit={preventSubmit} footer={null}>child</Dialog>
    </Dialog>);
    expect(document.body).toHaveAttribute("data-scroll-locked");
    unmount();
    expect(document.body.style.overflow).toBe("auto");
    expect(document.body).not.toHaveAttribute("data-scroll-locked");
    document.body.style.overflow = "";
  });

  it("does not bubble child Escape or Tab events into the parent focus trap", async () => {
    const parentClose = vi.fn();
    const childClose = vi.fn();
    const user = userEvent.setup();
    render(<Dialog title="Parent" onClose={parentClose} onSubmit={preventSubmit} footer={<button>parent last</button>}>
      <Dialog title="Child" onClose={childClose} onSubmit={preventSubmit} footer={<button>child last</button>}>child</Dialog>
    </Dialog>);
    const child = screen.getByRole("dialog", { name: "Child" });
    await user.click(within(child).getByRole("button", { name: "child last" }));
    await user.tab();
    expect(within(child).getByRole("button", { name: "关闭窗口" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(childClose).toHaveBeenCalledOnce();
    expect(parentClose).not.toHaveBeenCalled();
  });
});
