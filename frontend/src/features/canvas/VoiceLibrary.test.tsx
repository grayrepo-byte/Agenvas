import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createRef } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { setLocale, SUPPORTED_LOCALES, t } from "../../shared/i18n";
import { selectValue } from "../../test/controls";
import { VoiceLibrary } from "./VoiceLibrary";

function mount() {
  const onSelect = vi.fn();
  render(<QueryClientProvider client={createQueryClient()}>
    <VoiceLibrary projectId="project-test" canvasItemId="audio-test" selected="" mock
      containerRef={createRef<HTMLDivElement>()} onClose={vi.fn()} onSelect={onSelect} />
  </QueryClientProvider>);
  return onSelect;
}

describe("localized voice metadata", () => {
  beforeEach(() => localStorage.removeItem("agenvas.voice-preferences.v1"));

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

  it("updates metadata labels in all languages without resetting filters or translating voice names", async () => {
    mount();
    await selectValue(screen.getByRole("combobox", { name: t("audio.voiceLibrary.scene") }), "roleplay");
    const filter = screen.getByRole("combobox", { name: t("audio.voiceLibrary.scene") });
    for (const locale of SUPPORTED_LOCALES) {
      act(() => { setLocale(locale); });
      expect(screen.getByRole("combobox", { name: t("audio.voiceLibrary.scene") })).toBe(filter);
      expect(filter).toHaveAttribute("value", "roleplay");
      const voice = screen.getByText("知性灿灿 2.0", { selector: "strong" }).closest("button");
      expect(voice).toHaveTextContent(`${t("common.chinese")} · ${t("audio.voices.roleplay")}`);
      expect(screen.queryByText("Tim", { selector: "strong" })).not.toBeInTheDocument();
    }
  });
});
