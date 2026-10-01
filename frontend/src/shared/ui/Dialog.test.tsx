import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Dialog } from "./Dialog";

const preventSubmit = (event: React.FormEvent<HTMLFormElement>) => event.preventDefault();

describe("Dialog stacking", () => {
  it("restores the original scroll setting when an entire nested stack unmounts", () => {
    document.body.style.overflow = "auto";
    const { unmount } = render(<Dialog title="Parent" onClose={() => {}} onSubmit={preventSubmit} footer={null}>
      <Dialog title="Child" onClose={() => {}} onSubmit={preventSubmit} footer={null}>child</Dialog>
    </Dialog>);
    expect(document.body.style.overflow).toBe("hidden");
    unmount();
    expect(document.body.style.overflow).toBe("auto");
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
