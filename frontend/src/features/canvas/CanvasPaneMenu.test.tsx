import { fireEvent,render,screen,waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe,expect,it,vi } from "vitest";
import { CanvasPaneMenu } from "./CanvasPaneMenu";

function setup(overrides: Partial<React.ComponentProps<typeof CanvasPaneMenu>> = {}) {
  const props = { position: { x: 400, y: 200 }, onClose: vi.fn(), onAdd: vi.fn(), onUpload: vi.fn(),
    onArrange: vi.fn(), uploading: false, creatingText: false, arranging: false, canArrange: true, ...overrides };
  render(<CanvasPaneMenu {...props} />);
  return props;
}

describe("CanvasPaneMenu", () => {
  it("opens the four-type cascade with the keyboard and selects a node", async () => {
    const props = setup();
    const user = userEvent.setup();
    const add = screen.getByRole("menuitem", { name: "添加" });
    add.focus();
    await user.keyboard("{ArrowRight}");
    await screen.findByRole("menuitem", { name: "图片" });
    expect(screen.getAllByRole("menuitem")).toHaveLength(7);
    await user.click(screen.getByRole("menuitem", { name: "音频" }));
    expect(props.onAdd).toHaveBeenCalledWith("AUDIO");
  });

  it("opens the cascade on hover", async () => {
    setup();
    fireEvent.pointerMove(screen.getByRole("menuitem", { name: "添加" }), { pointerType: "mouse" });
    expect(await screen.findByRole("menuitem", { name: "图片" })).toBeVisible();
  });

  it.each(["上传", "一键整理"])("runs %s directly", async (label) => {
    const props = setup();
    await userEvent.setup().click(screen.getByRole("menuitem", { name: label }));
    expect(label === "上传" ? props.onUpload : props.onArrange).toHaveBeenCalledOnce();
  });

  it.each([{ canArrange: false }, { arranging: true }])("disables unavailable or pending arrangement", async (overrides) => {
    const props = setup(overrides);
    const item = screen.getByRole("menuitem", { name: "一键整理" });
    expect(item).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(item);
    expect(props.onArrange).not.toHaveBeenCalled();
  });

  it("disables upload while a file is being uploaded", () => {
    const props = setup({ uploading: true });
    const upload = screen.getByRole("menuitem", { name: "上传" });
    expect(upload).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(upload);
    expect(props.onUpload).not.toHaveBeenCalled();
  });

  it("closes on Escape and on outside pointer input", async () => {
    const props = setup();
    await userEvent.setup().keyboard("{Escape}");
    expect(props.onClose).toHaveBeenCalled();
    vi.mocked(props.onClose).mockClear();
    fireEvent.pointerDown(document.body);
    await waitFor(() => expect(props.onClose).toHaveBeenCalled());
  });
});
