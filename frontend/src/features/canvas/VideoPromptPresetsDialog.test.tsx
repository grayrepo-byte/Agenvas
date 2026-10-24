import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { setLocale } from "../../shared/i18n";
import { VideoPromptPresetsDialog } from "./VideoPromptPresetsDialog";
import { filterVideoPromptPresets, VIDEO_PROMPT_PRESETS, type VideoPromptPreset } from "./videoPromptPresets";

// Playback lifecycle and drawing are verified independently; this suite exercises selection and draft application.
vi.mock("./VideoCameraMotionPreview", () => ({ VideoCameraMotionPreview: ({ preset }: { preset: { id: string } }) =>
  <div data-testid="camera-preview" data-preset={preset.id} /> }));

function setup(prompt = "An existing scene.", maxLength = 20000, accepted = true) {
  const onApply = vi.fn(() => accepted);
  const onClose = vi.fn();
  render(<VideoPromptPresetsDialog prompt={prompt} maxLength={maxLength} onApply={onApply} onClose={onClose} />);
  return { onApply, onClose };
}

function expectPresetOptions(presets: readonly VideoPromptPreset[]) {
  const list = screen.getByRole("group", { name: "运镜预设列表" });
  const names = within(list).getAllByRole("button").map((button) => button.getAttribute("aria-label"));
  // Off-screen options are intentionally unmounted; the visible window retains catalog order.
  expect(names.length).toBeLessThanOrEqual(20);
  expect(names).toEqual(presets.slice(0, names.length).map((preset) => preset.copy.zh.name));
  expect(screen.getByRole("status")).toHaveTextContent(String(presets.length));
}

describe("VideoPromptPresetsDialog", () => {
  it("bounds mounted options when the full camera library is open", () => {
    setup();
    const list = screen.getByRole("group", { name: "运镜预设列表" });
    expect(within(list).getAllByRole("button").length).toBeLessThanOrEqual(20);
    expect(screen.getByRole("status")).toHaveTextContent(String(VIDEO_PROMPT_PRESETS.length));
  });

  it("resets the scroll window when the active category is clicked again", () => {
    setup();
    const list = screen.getByRole("group", { name: "运镜预设列表" });
    fireEvent.scroll(list, { target: { scrollTop: 100000 } });
    expect(within(list).getByRole("button", { name: VIDEO_PROMPT_PRESETS.at(-1)!.copy.zh.name })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^全部镜头/ }));
    expect(screen.getByRole("group", { name: "运镜预设列表" }).scrollTop).toBe(0);
    expectPresetOptions(VIDEO_PROMPT_PRESETS);
  });

  it("shows the guide and full fixed prompt, then applies only the selected suffix", () => {
    const { onApply, onClose } = setup();
    const selected = VIDEO_PROMPT_PRESETS.find((preset) => /over.the.shoulder/i.test(preset.englishName))!;
    const details = screen.getByRole("region", { name: "镜头详情" });
    expect(within(details).getByText(selected.copy.zh.conditions)).toBeInTheDocument();
    expect(within(details).getByText(selected.copy.zh.effect)).toBeInTheDocument();
    expect(within(details).getByText(selected.copy.zh.caution)).toBeInTheDocument();
    expect(within(details).getByText(selected.prompt)).toBeInTheDocument();
    expect(screen.getAllByTestId("camera-preview")).toHaveLength(1);
    expect(screen.getByTestId("camera-preview")).toHaveAttribute("data-preset", selected.id);
    fireEvent.click(screen.getByRole("button", { name: "追加到提示词" }));
    expect(onApply).toHaveBeenCalledExactlyOnceWith(selected.prompt);
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("filters by category and keyword, and clears an empty search without applying", () => {
    const { onApply } = setup();
    fireEvent.click(screen.getByRole("button", { name: /变焦与对焦/ }));
    expectPresetOptions(VIDEO_PROMPT_PRESETS.filter(({ category }) => category === "lens"));
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "nonexistent-preset-xyz" } });
    expect(screen.getByText("没有找到合适的镜头")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "追加到提示词" })).toBeDisabled();
    expect(screen.queryByRole("region", { name: "镜头详情" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "清除筛选" }));
    expectPresetOptions(VIDEO_PROMPT_PRESETS);
    expect(onApply).not.toHaveBeenCalled();
  });

  it("exposes the supplemental rotation category and binds its selection to the corresponding preview and prompt", () => {
    const { onApply } = setup();
    fireEvent.click(screen.getByRole("button", { name: /摇摄与滚转/ }));
    const list = screen.getByRole("group", { name: "运镜预设列表" });
    expectPresetOptions(VIDEO_PROMPT_PRESETS.filter(({ category }) => category === "rotation"));
    const roll = VIDEO_PROMPT_PRESETS.find(({ id }) => id === "axial-barrel-roll")!;
    fireEvent.click(within(list).getByRole("button", { name: roll.copy.zh.name }));
    expect(screen.getByTestId("camera-preview")).toHaveAttribute("data-preset", roll.id);
    expect(screen.getByText(roll.copy.zh.conditions)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "追加到提示词" }));
    expect(onApply).toHaveBeenCalledExactlyOnceWith(roll.prompt);
  });

  it("combines a use case with category and search, updates counts and applies the visible result", () => {
    const { onApply } = setup();
    fireEvent.click(screen.getByRole("radio", { name: "产品展示" }));
    expect(screen.getByRole("radio", { name: "产品展示" })).toBeChecked();
    const products = filterVideoPromptPresets("", "all", "product");
    const list = screen.getByRole("group", { name: "运镜预设列表" });
    expectPresetOptions(products);
    expect(within(list).queryByRole("button", { name: "过肩镜头" })).not.toBeInTheDocument();
    expect(screen.getByTestId("camera-preview")).toHaveAttribute("data-preset", products[0]!.id);
    const lensCount = filterVideoPromptPresets("", "lens", "product").length;
    const lensCategory = screen.getByRole("button", { name: /变焦与对焦/ });
    expect(within(lensCategory).getByText(String(lensCount))).toBeInTheDocument();
    fireEvent.click(lensCategory);
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "微距" } });
    const macro = VIDEO_PROMPT_PRESETS.find(({ id }) => id === "macro-focus-travel")!;
    const macros = filterVideoPromptPresets("微距", "lens", "product");
    expectPresetOptions(macros);
    expect(screen.getByRole("button", { name: new RegExp(`^变焦与对焦\\s*${macros.length}$`) })).toHaveAttribute("aria-pressed", "true");
    const allMacroCount = filterVideoPromptPresets("微距", "all", "product").length;
    expect(screen.getByRole("button", { name: new RegExp(`^全部镜头\\s*${allMacroCount}$`) })).toBeInTheDocument();
    expect(screen.getByTestId("camera-preview")).toHaveAttribute("data-preset", macro.id);
    fireEvent.click(screen.getByRole("button", { name: "追加到提示词" }));
    expect(onApply).toHaveBeenCalledExactlyOnceWith(macro.prompt);
  });

  it("keeps category and keyword when clearing only the scene, and resets all filters from an empty result", () => {
    const { onApply } = setup();
    fireEvent.click(screen.getByRole("radio", { name: "人物对话" }));
    fireEvent.click(screen.getByRole("button", { name: /构图与视角/ }));
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "过肩" } });
    fireEvent.click(screen.getByRole("radio", { name: "产品展示" }));
    expect(screen.getByRole("button", { name: "追加到提示词" })).toBeDisabled();
    expect(screen.queryByTestId("camera-preview")).not.toBeInTheDocument();
    expect(onApply).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("radio", { name: "全部场景" }));
    expect(screen.getByRole("searchbox")).toHaveValue("过肩");
    const shoulders = filterVideoPromptPresets("过肩", "framing");
    expectPresetOptions(shoulders);
    expect(screen.getByRole("button", { name: new RegExp(`^构图与视角\\s*${shoulders.length}$`) })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("camera-preview")).toHaveAttribute("data-preset", "over-the-shoulder");
    fireEvent.click(screen.getByRole("radio", { name: "风景" }));
    fireEvent.click(screen.getByRole("button", { name: "清除筛选" }));
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(screen.getByRole("radio", { name: "全部场景" })).toBeChecked();
    expect(screen.getByRole("button", { name: new RegExp(`^全部镜头\\s*${VIDEO_PROMPT_PRESETS.length}$`) })).toHaveAttribute("aria-pressed", "true");
    expectPresetOptions(VIDEO_PROMPT_PRESETS);
  });

  it("offers a reset while results remain and restores a nonempty scene selection when toggled off", () => {
    setup();
    fireEvent.click(screen.getByRole("radio", { name: "追逐" }));
    fireEvent.click(screen.getByRole("radio", { name: "追逐" }));
    expect(screen.getByRole("radio", { name: "全部场景" })).toBeChecked();
    fireEvent.click(screen.getByRole("radio", { name: "人物出场" }));
    fireEvent.click(screen.getByRole("button", { name: "清除筛选" }));
    expect(screen.getByRole("radio", { name: "全部场景" })).toBeChecked();
  });

  it("blocks over-limit prompts without truncation and cancellation has no edit side effects", () => {
    const { onApply, onClose } = setup("Existing \uFFFC scene", 30);
    expect(screen.getByRole("alert")).toHaveTextContent("超过当前能力");
    fireEvent.click(screen.getByRole("button", { name: "追加到提示词" }));
    expect(onApply).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("updates the guide and appended instruction when another shot is selected", () => {
    const { onApply } = setup();
    const next = VIDEO_PROMPT_PRESETS.find((preset) => /steadicam/i.test(preset.englishName))!;
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "steadicam" } });
    fireEvent.click(screen.getByRole("button", { name: next.copy.zh.name }));
    expect(screen.getByRole("heading", { name: next.copy.zh.name })).toBeInTheDocument();
    expect(screen.getByText(next.copy.zh.conditions)).toBeInTheDocument();
    expect(screen.getByTestId("camera-preview")).toHaveAttribute("data-preset", next.id);
    fireEvent.click(screen.getByRole("button", { name: "追加到提示词" }));
    expect(onApply).toHaveBeenCalledExactlyOnceWith(next.prompt);
  });

  it("keeps the dialog open if the latest draft rejects application", () => {
    const { onApply, onClose } = setup("", 20000, false);
    fireEvent.click(screen.getByRole("button", { name: "追加到提示词" }));
    expect(onApply).toHaveBeenCalledOnce();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("草稿已变化");
  });

  it("switches localized guidance without translating the fixed creative prompt", () => {
    setLocale("en");
    try {
      setup();
      const selected = VIDEO_PROMPT_PRESETS.find((preset) => /over.the.shoulder/i.test(preset.englishName))!;
      expect(screen.getByRole("dialog", { name: "Camera language library" })).toBeInTheDocument();
      expect(screen.getByText(selected.copy.en.conditions)).toBeInTheDocument();
      expect(screen.getByText(selected.prompt)).toBeInTheDocument();
      expect(screen.getByRole("radiogroup", { name: "Use case" })).toBeInTheDocument();
      expect(screen.getByRole("radio", { name: "Product showcase" })).toBeInTheDocument();
    } finally { setLocale("zh"); }
  });
});
