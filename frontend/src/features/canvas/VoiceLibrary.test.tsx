import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setLocale, SUPPORTED_LOCALES, t } from "../../shared/i18n";
import { selectValue } from "../../test/controls";
import { VoiceLibrary } from "./VoiceLibrary";
import { getVoiceDisplayName, VOICES } from "./voiceCatalog";

function mount() {
  const onSelect = vi.fn();
  render(<VoiceLibrary selected=""
      containerRef={createRef<HTMLDivElement>()} onClose={vi.fn()} onSelect={onSelect} />);
  return onSelect;
}

describe("official voice samples", () => {
  afterEach(() => vi.restoreAllMocks());
  beforeEach(() => {
    setLocale("zh");
    localStorage.removeItem("agenvas.voice-preferences.v1");
    vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  });

  it("plays an official sample without a configured model or any generation request", async () => {
    const onSelect = mount();
    const request = vi.spyOn(globalThis, "fetch");
    await userEvent.setup().click(screen.getByRole("button", { name: "试听 小何 2.0" }));
    const audio = document.querySelector<HTMLAudioElement>("audio[data-voice-id='zh_female_xiaohe_uranus_bigtts']");
    expect(audio?.src).toMatch(/^https:\/\/lf3-static\.bytednsdoc\.com\/.*zh_female_xiaohe_uranus_bigtts\.mp3$/);
    expect(HTMLMediaElement.prototype.play).toHaveBeenCalledOnce();
    expect(request).not.toHaveBeenCalled();
    expect(onSelect).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "停止试听 小何 2.0" })).toBeEnabled();
  });

  it("stops the previous voice and ignores its late play result", async () => {
    let resolvePrevious!: () => void;
    vi.mocked(HTMLMediaElement.prototype.play).mockImplementationOnce(() => new Promise<void>((resolve) => { resolvePrevious = resolve; }));
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "试听 小何 2.0" }));
    const previous = document.querySelector<HTMLAudioElement>("audio[data-voice-id='zh_female_xiaohe_uranus_bigtts']")!;
    previous.currentTime = 2;
    await user.click(screen.getByRole("button", { name: "试听 云舟 2.0" }));
    expect(previous.currentTime).toBe(0);
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalled();
    await act(async () => { resolvePrevious(); });
    fireEvent.error(previous);
    expect(screen.getByRole("button", { name: "停止试听 云舟 2.0" })).toBeEnabled();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "停止试听 云舟 2.0" }));
    expect(screen.getByRole("button", { name: "试听 云舟 2.0" })).toBeEnabled();
  });

  it("reports sample playback failure and allows an explicit retry", async () => {
    vi.mocked(HTMLMediaElement.prototype.play).mockRejectedValueOnce(new Error("sample unavailable"));
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "试听 小何 2.0" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("样音播放失败");
    await user.click(screen.getByRole("button", { name: "试听 小何 2.0" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "停止试听 小何 2.0" })).toBeEnabled();
  });

  it("stops playback when the library unmounts and resets on natural completion", async () => {
    const mounted = render(<VoiceLibrary selected="" containerRef={createRef()} onClose={vi.fn()} onSelect={vi.fn()} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "试听 小何 2.0" }));
    const audio = document.querySelector<HTMLAudioElement>("audio[data-voice-id='zh_female_xiaohe_uranus_bigtts']")!;
    fireEvent.ended(audio);
    expect(screen.getByRole("button", { name: "试听 小何 2.0" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "试听 小何 2.0" }));
    vi.mocked(HTMLMediaElement.prototype.pause).mockClear();
    mounted.unmount();
    expect(HTMLMediaElement.prototype.pause).toHaveBeenCalledOnce();
  });
});

describe("localized voice metadata", () => {
  afterEach(() => vi.restoreAllMocks());
  beforeEach(() => {
    setLocale("zh");
    localStorage.removeItem("agenvas.voice-preferences.v1");
    vi.spyOn(HTMLMediaElement.prototype, "play").mockResolvedValue();
    vi.spyOn(HTMLMediaElement.prototype, "pause").mockImplementation(() => {});
  });

  it("shows English catalog and selected voice labels without changing official names or selected IDs", async () => {
    setLocale("en");
    const onSelect = vi.fn();
    const original = VOICES.map((voice) => ({ ...voice }));
    const selected = "zh_female_xiaohe_uranus_bigtts";
    const { container } = render(<VoiceLibrary selected={selected} containerRef={createRef()} onClose={vi.fn()} onSelect={onSelect} />);
    expect(container.textContent).not.toMatch(/\p{Script=Han}/u);
    for (const element of container.querySelectorAll("[aria-label]")) expect(element.getAttribute("aria-label")).not.toMatch(/\p{Script=Han}/u);
    const current = screen.getByText("Xiaohe 2.0", { selector: "strong" }).closest("button")!;
    expect(current).toHaveAttribute("aria-pressed", "true");
    await userEvent.setup().click(current);
    expect(onSelect).toHaveBeenLastCalledWith(selected);
    expect(VOICES).toEqual(original);
    expect(VOICES.find((voice) => voice.id === selected)?.name).toBe("小何 2.0");
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
  });

  it("searches display aliases and official names while locale changes preserve selection and filters", async () => {
    setLocale("en");
    const selected = "zh_female_qingxinnvsheng_uranus_bigtts";
    const voice = VOICES.find((value) => value.id === selected)!;
    const onSelect = vi.fn();
    render(<VoiceLibrary selected={selected} containerRef={createRef()} onClose={vi.fn()} onSelect={onSelect} />);
    const user = userEvent.setup();
    const search = screen.getByRole("searchbox");
    await user.type(search, "Bright female");
    let choice = screen.getByText(getVoiceDisplayName(voice), { selector: "strong" }).closest("button")!;
    expect(choice).toHaveAttribute("aria-pressed", "true");
    await user.click(choice);
    expect(onSelect).toHaveBeenLastCalledWith(selected);
    act(() => { setLocale("ru"); });
    expect(search).toHaveValue("Bright female");
    expect(screen.getByText(t("audio.voiceLibrary.empty"))).toBeInTheDocument();
    await user.clear(search);
    choice = screen.getByText(getVoiceDisplayName(voice), { selector: "strong" }).closest("button")!;
    expect(choice).toHaveAttribute("aria-pressed", "true");
    act(() => { setLocale("en"); });
    await user.type(search, voice.name);
    expect(screen.getByText("Bright female voice 2.0", { selector: "strong" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: t("audio.voiceLibrary.favoriteNamed", { "0": getVoiceDisplayName(voice) }) }));
    expect(JSON.parse(localStorage.getItem("agenvas.voice-preferences.v1")!)).toEqual({ favorites: [selected], recent: [selected] });
    expect(voice.name).toBe("清新女声 2.0");
    expect(HTMLMediaElement.prototype.play).not.toHaveBeenCalled();
  });

  it("relocalizes pending, playing and failed previews without requesting any external sample in tests", async () => {
    const voice = VOICES.find((value) => value.id === "zh_female_xiaohe_uranus_bigtts")!;
    let resolvePlay!: () => void;
    vi.mocked(HTMLMediaElement.prototype.play).mockImplementationOnce(() => new Promise<void>((resolve) => { resolvePlay = resolve; }));
    const request = vi.spyOn(globalThis, "fetch");
    mount();
    await userEvent.setup().click(screen.getByRole("button", { name: t("audio.voiceLibrary.previewNamed", { "0": getVoiceDisplayName(voice) }) }));
    act(() => { setLocale("en"); });
    expect(screen.getByRole("status")).toHaveTextContent(t("audio.voiceLibrary.previewLoading", { "0": "Xiaohe 2.0" }));
    await act(async () => { resolvePlay(); });
    expect(screen.getByRole("status")).toHaveTextContent(t("audio.voiceLibrary.previewPlaying", { "0": "Xiaohe 2.0" }));
    act(() => { setLocale("ru"); });
    expect(screen.getByRole("status")).toHaveTextContent(t("audio.voiceLibrary.previewPlaying", { "0": getVoiceDisplayName(voice) }));
    const audio = document.querySelector<HTMLAudioElement>(`audio[data-voice-id='${voice.id}']`)!;
    fireEvent.error(audio);
    act(() => { setLocale("en"); });
    expect(screen.getByRole("alert")).toHaveTextContent(t("audio.voiceLibrary.previewFailed", { "0": "Xiaohe 2.0" }));
    expect(screen.getByRole("alert").textContent).not.toMatch(/\p{Script=Han}/u);
    expect(request).not.toHaveBeenCalled();
  });

  it("leaves unregistered provider voices and customized names unchanged", () => {
    setLocale("en");
    expect(getVoiceDisplayName({ id: "custom-provider-voice", name: "用户的声音" })).toBe("用户的声音");
    expect(getVoiceDisplayName({ id: "zh_female_xiaohe_uranus_bigtts", name: "我的自定义名称" })).toBe("我的自定义名称");
    for (const name of ["Tim", "Dacey", "Stokie", "Vivi 2.0"]) {
      expect(getVoiceDisplayName(VOICES.find((voice) => voice.name === name)!)).toBe(name);
    }
  });

  it("uses keyboard tabs to browse recent and favorite voices without losing the search", async () => {
    const onSelect = mount();
    const user = userEvent.setup();
    await user.type(screen.getByRole("searchbox"), "Vivi");
    await user.click(screen.getByRole("button", { name: t("audio.voiceLibrary.favoriteNamed", { "0": "Vivi 2.0" }) }));
    await user.click(screen.getByText("Vivi 2.0", { selector: "strong" }));
    expect(onSelect).toHaveBeenCalledWith("zh_female_vv_uranus_bigtts");
    screen.getByRole("tab", { name: t("common.all") }).focus();
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: t("audio.voiceLibrary.recent") })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("Vivi 2.0", { selector: "strong" })).toBeInTheDocument();
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: t("audio.voiceLibrary.favorites") })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("searchbox")).toHaveValue("Vivi");
    expect(screen.getByRole("button", { name: t("audio.voiceLibrary.previewNamed", { "0": "Vivi 2.0" }) })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: t("audio.voiceLibrary.favoriteNamed", { "0": "Vivi 2.0" }) }));
    expect(screen.getByText(t("audio.voiceLibrary.empty"))).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: t("audio.voiceLibrary.automaticVoice") }));
    expect(onSelect).toHaveBeenLastCalledWith("");
  });

  it("filters by stable language and scene identities and selects the provider voice ID", async () => {
    const onSelect = mount();
    await selectValue(screen.getByRole("combobox", { name: t("audio.voiceLibrary.language") }), "en");
    expect(screen.getByText("Tim", { selector: "strong" })).toBeInTheDocument();
    expect(screen.queryByText("Vivi 2.0", { selector: "strong" })).not.toBeInTheDocument();
    await selectValue(screen.getByRole("combobox", { name: t("audio.voiceLibrary.scene") }), "roleplay");
    expect(screen.getByText(t("audio.voiceLibrary.empty"))).toBeInTheDocument();
    await selectValue(screen.getByRole("combobox", { name: t("audio.voiceLibrary.language") }), "zh");
    await userEvent.setup().click(screen.getByText("知性灿灿 2.0", { selector: "strong" }));
    expect(onSelect).toHaveBeenCalledWith("zh_female_cancan_uranus_bigtts");
  });

  it("updates display aliases and metadata labels in all languages without resetting filters", async () => {
    mount();
    await selectValue(screen.getByRole("combobox", { name: t("audio.voiceLibrary.scene") }), "roleplay");
    const filter = screen.getByRole("combobox", { name: t("audio.voiceLibrary.scene") });
    for (const locale of SUPPORTED_LOCALES) {
      act(() => { setLocale(locale); });
      expect(screen.getByRole("combobox", { name: t("audio.voiceLibrary.scene") })).toBe(filter);
      expect(filter).toHaveAttribute("value", "roleplay");
      const voice = screen.getByText(getVoiceDisplayName(VOICES.find((value) => value.id === "zh_female_cancan_uranus_bigtts")!), { selector: "strong" }).closest("button");
      expect(voice).toHaveTextContent(`${t("common.chinese")} · ${t("audio.voices.roleplay")}`);
      expect(screen.queryByText("Tim", { selector: "strong" })).not.toBeInTheDocument();
    }
  });
});
