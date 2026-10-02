import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { MediaCapability } from "../../shared/api/client";
import { SmartEditDialog } from "./SmartEditDialog";

const capability: MediaCapability = {
  id: "model", name: "Image model", enabled: true, version: 0, capabilityVersion: 1,
  adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION", minimumSeconds: 0, maximumSeconds: 0,
  maxReferenceAudios: 0, maxReferenceImages: 4, supportedVideoInputModes: [], defaultVideoInputMode: null,
  supportsEndFrame: false, supportedImageAspectRatios: ["AUTO"], supportedImageResolutions: ["1K"],
  supportedImageQualities: ["high"], supportsTransparentBackground: true, supportsImageMask: true,
  mappingSha256: "a".repeat(64), settings: {},
};
function mount() {
  const onSubmit = vi.fn();
  function Harness() {
    const [open, setOpen] = useState(false);
    return <><button onClick={() => setOpen(true)}>打开编辑</button><button>背景操作</button>
      {open ? <SmartEditDialog projectId="project" sourceVersionId="source" sourceTitle="Image"
        sourceUrl="/image.png" capabilities={[capability]} busy={false} error={null}
        onClose={() => setOpen(false)} onSubmit={onSubmit} /> : null}</>;
  }
  render(<QueryClientProvider client={createQueryClient()}><Harness /></QueryClientProvider>);
}
it("isolates keyboard focus, closes only the model menu on Escape and retains the instruction", async () => {
  mount();
  const user = userEvent.setup();
  await user.click(screen.getByRole("button", { name: "打开编辑" }));
  const dialog = screen.getByRole("dialog", { name: "智能编辑图片" });
  expect(dialog).toContainElement(document.activeElement as HTMLElement);
  await user.tab();
  expect(dialog).toContainElement(document.activeElement as HTMLElement);
  await user.type(screen.getByRole("textbox", { name: "智能编辑提示词" }), "保留输入");
  await user.click(screen.getByRole("combobox", { name: "图片能力" }));
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "智能编辑提示词" })).toHaveValue("保留输入");
  await user.click(screen.getByRole("button", { name: "退出智能编辑" }));
  const confirmation = await screen.findByRole("dialog", { name: "有未保存的修改" });
  await user.click(within(confirmation).getByRole("button", { name: "继续编辑" }));
  expect(screen.getByRole("textbox", { name: "智能编辑提示词" })).toHaveValue("保留输入");
  await user.click(screen.getByRole("button", { name: "退出智能编辑" }));
  await user.click(screen.getByRole("button", { name: "放弃修改" }));
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "打开编辑" })).toHaveFocus();
});
it("restores focus after closing a clean editor with Escape", async () => {
  mount();
  const user = userEvent.setup();
  const trigger = screen.getByRole("button", { name: "打开编辑" });
  await user.click(trigger);
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(trigger).toHaveFocus();
});
