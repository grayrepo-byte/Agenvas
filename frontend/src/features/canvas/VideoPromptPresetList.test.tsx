import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLocale } from "../../shared/i18n";
import { VideoPromptPresetList } from "./VideoPromptPresetList";
import { VIDEO_PROMPT_PRESETS } from "./videoPromptPresets";

class MeasuringObserver implements ResizeObserver {
  static instances: MeasuringObserver[] = [];
  readonly targets = new Set<Element>();
  constructor(readonly callback: ResizeObserverCallback) { MeasuringObserver.instances.push(this); }
  observe(target: Element) { this.targets.add(target); }
  unobserve(target: Element) { this.targets.delete(target); }
  disconnect() { this.targets.clear(); }
}

function list() { return screen.getByRole("group", { name: "运镜预设列表" }); }
function options() { return within(list()).getAllByRole("button"); }
function resize(target: Element, height: number) {
  act(() => {
    for (const observer of MeasuringObserver.instances) {
      if (observer.targets.has(target)) observer.callback([{ target, borderBoxSize: [{ blockSize: height, inlineSize: 320 }] } as unknown as ResizeObserverEntry], observer);
    }
  });
}

beforeEach(() => {
  MeasuringObserver.instances = [];
  vi.stubGlobal("ResizeObserver", MeasuringObserver);
  vi.stubGlobal("matchMedia", () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
  vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(600);
  vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(320);
});
afterEach(() => { setLocale("zh"); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function setup(resetKey = "all") {
  const onSelect = vi.fn();
  const onReset = vi.fn();
  const result = render(<VideoPromptPresetList presets={VIDEO_PROMPT_PRESETS} selectedId={VIDEO_PROMPT_PRESETS[0]!.id} onSelect={onSelect} resetKey={resetKey} onReset={onReset} />);
  return { ...result, onSelect, onReset };
}

describe("VideoPromptPresetList", () => {
  it("mounts a bounded window, reaches the final option, and releases observers", () => {
    const { onSelect, unmount } = setup();
    expect(options().length).toBeLessThanOrEqual(20);
    expect(options()[0]).toHaveAccessibleName(VIDEO_PROMPT_PRESETS[0]!.copy.zh.name);
    fireEvent.scroll(list(), { target: { scrollTop: 100000 } });
    const last = VIDEO_PROMPT_PRESETS.at(-1)!;
    fireEvent.click(within(list()).getByRole("button", { name: last.copy.zh.name }));
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(last);
    expect(options().length).toBeLessThanOrEqual(20);
    unmount();
    expect(MeasuringObserver.instances.every((observer) => observer.targets.size === 0)).toBe(true);
  });

  it("resets the rendered window when filters change without requiring a scroll event", () => {
    const { rerender, onSelect, onReset } = setup();
    fireEvent.scroll(list(), { target: { scrollTop: 100000 } });
    rerender(<VideoPromptPresetList presets={VIDEO_PROMPT_PRESETS.slice(0, 6)} selectedId={undefined} onSelect={onSelect} resetKey="filtered" onReset={onReset} />);
    expect(list().scrollTop).toBe(0);
    expect(options()).toHaveLength(6);
    expect(options()[0]).toHaveAccessibleName(VIDEO_PROMPT_PRESETS[0]!.copy.zh.name);
  });

  it("measures natural row heights and recomputes subsequent offsets", () => {
    setup();
    const first = options()[0]!;
    const second = options()[1]!;
    const before = Number.parseFloat(second.style.top);
    resize(first, 180);
    expect(Number.parseFloat(second.style.top)).toBeGreaterThan(before);
    expect(first.style.height).not.toBe("104px");
    fireEvent.scroll(list(), { target: { scrollTop: 100000 } });
    expect(within(list()).getByRole("button", { name: VIDEO_PROMPT_PRESETS.at(-1)!.copy.zh.name })).toBeInTheDocument();
  });

  it("uses a bounded horizontal window on small screens", () => {
    vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    const { onSelect } = setup();
    expect(list()).toHaveAttribute("data-orientation", "horizontal");
    expect(options().length).toBeLessThanOrEqual(20);
    fireEvent.scroll(list(), { target: { scrollLeft: 100000 } });
    const last = VIDEO_PROMPT_PRESETS.at(-1)!;
    fireEvent.click(within(list()).getByRole("button", { name: last.copy.zh.name }));
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(last);
    expect(options().length).toBeLessThanOrEqual(20);
  });

  it("remeasures wrapped titles when the available width or language changes", () => {
    let measuredHeight = 120;
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(() => new DOMRect(0, 0, 320, measuredHeight));
    setup();
    const group = list();
    const [first, second] = options();
    expect(Number.parseFloat(second!.style.top)).toBe(126);
    measuredHeight = 180;
    vi.spyOn(HTMLElement.prototype, "clientWidth", "get").mockReturnValue(240);
    resize(group, 600);
    expect(Number.parseFloat(second!.style.top)).toBe(186);
    measuredHeight = 220;
    act(() => setLocale("en"));
    expect(first).toHaveAccessibleName(VIDEO_PROMPT_PRESETS[0]!.copy.en.name);
    expect(Number.parseFloat(second!.style.top)).toBe(226);
    expect(first!.style.height).toBe("");
  });

  it("keeps the DOM and virtual offsets aligned after switching scroll orientation", () => {
    const media = { matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() };
    vi.stubGlobal("matchMedia", () => media);
    setup();
    fireEvent.scroll(list(), { target: { scrollTop: 100000 } });
    media.matches = true;
    resize(list(), 600);
    expect(list()).toHaveAttribute("data-orientation", "horizontal");
    expect(list().scrollTop).toBe(0);
    expect(list().scrollLeft).toBe(0);
    expect(options()[0]).toHaveAccessibleName(VIDEO_PROMPT_PRESETS[0]!.copy.zh.name);
  });

  it.each([true, false])("scrolls the final keyboard option fully inside a padded viewport (horizontal=%s)", async (horizontal) => {
    vi.stubGlobal("matchMedia", () => ({ matches: horizontal, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    const user = userEvent.setup();
    render(<><VideoPromptPresetList presets={VIDEO_PROMPT_PRESETS} selectedId={undefined} onSelect={vi.fn()} resetKey="all" onReset={vi.fn()} /><button>After</button></>);
    const group = list();
    group.style.padding = horizontal ? "0 12px 12px" : "0 10px 16px";
    resize(group, 600);
    screen.getByRole("button", { name: "After" }).focus();
    await user.tab({ shift: true });
    expect(document.activeElement).toHaveAccessibleName(VIDEO_PROMPT_PRESETS.at(-1)!.copy.zh.name);
    const content = group.querySelector<HTMLElement>(".video-presets-virtual-content")!;
    const extent = Number.parseFloat(horizontal ? content.style.width : content.style.height);
    expect(horizontal ? group.scrollLeft : group.scrollTop).toBe(extent - (horizontal ? 320 - 24 : 600 - 16));
  });

  it.each(["orientation", "height"])("keeps the focused option visible after a viewport %s change", async (change) => {
    const media = { matches: change === "orientation", addEventListener: vi.fn(), removeEventListener: vi.fn() };
    vi.stubGlobal("matchMedia", () => media);
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(<><VideoPromptPresetList presets={VIDEO_PROMPT_PRESETS} selectedId={VIDEO_PROMPT_PRESETS[0]!.id} onSelect={onSelect} resetKey="all" onReset={vi.fn()} /><button>After</button></>);
    screen.getByRole("button", { name: "After" }).focus();
    await user.tab({ shift: true });
    const focused = document.activeElement;
    media.matches = false;
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(120);
    resize(list(), 120);
    const content = list().querySelector<HTMLElement>(".video-presets-virtual-content")!;
    expect(focused).toHaveFocus();
    expect(list().scrollTop).toBe(Number.parseFloat(content.style.height) - 120);
    expect(onSelect).not.toHaveBeenCalled();
    resize(options()[0]!, 180);
    expect(list().scrollTop).toBe(Number.parseFloat(content.style.height) - 120);
    fireEvent.scroll(list(), { target: { scrollTop: 0 } });
    resize(options()[0]!, 180);
    expect(list().scrollTop).toBe(0);
    expect(focused).toHaveFocus();
  });

  it("aligns the start of an oversized row so its title stays visible in short landscape dialogs", async () => {
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(80);
    const user = userEvent.setup();
    render(<><VideoPromptPresetList presets={VIDEO_PROMPT_PRESETS} selectedId={undefined} onSelect={vi.fn()} resetKey="all" onReset={vi.fn()} /><button>After</button></>);
    screen.getByRole("button", { name: "After" }).focus();
    await user.tab({ shift: true });
    const focused = document.activeElement as HTMLButtonElement;
    expect(focused).toHaveAccessibleName(VIDEO_PROMPT_PRESETS.at(-1)!.copy.zh.name);
    expect(list().scrollTop).toBe(Number.parseFloat(focused.style.top));
  });

  it("keeps every option reachable in both tab directions and lets focus leave the list", async () => {
    const user = userEvent.setup();
    render(<><button>Before</button><VideoPromptPresetList presets={VIDEO_PROMPT_PRESETS} selectedId={undefined} onSelect={vi.fn()} resetKey="all" onReset={vi.fn()} /><button>After</button></>);
    screen.getByRole("button", { name: "Before" }).focus();
    for (const preset of VIDEO_PROMPT_PRESETS) {
      await user.tab();
      expect(document.activeElement).toHaveAccessibleName(preset.copy.zh.name);
      expect(options().length).toBeLessThanOrEqual(20);
    }
    await user.tab();
    expect(screen.getByRole("button", { name: "After" })).toHaveFocus();
    for (const preset of [...VIDEO_PROMPT_PRESETS].reverse()) {
      await user.tab({ shift: true });
      expect(document.activeElement).toHaveAccessibleName(preset.copy.zh.name);
    }
    await user.tab({ shift: true });
    expect(screen.getByRole("button", { name: "Before" })).toHaveFocus();
  });

  it("keeps a focused option mounted when the user scrolls it out of the visible window", () => {
    setup();
    const focused = options()[0]!;
    act(() => focused.focus());
    fireEvent.scroll(list(), { target: { scrollTop: 100000 } });
    expect(focused).toHaveFocus();
    expect(focused.isConnected).toBe(true);
    expect(options().length).toBeLessThanOrEqual(20);
  });

  it("keeps the existing empty state reset action", () => {
    const onReset = vi.fn();
    render(<VideoPromptPresetList presets={[]} selectedId={undefined} onSelect={vi.fn()} resetKey="empty" onReset={onReset} />);
    expect(screen.getByText("没有找到合适的镜头")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "清除筛选" }));
    expect(onReset).toHaveBeenCalledOnce();
  });
});
