import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { setLocale, SUPPORTED_LOCALES, translate } from "../../shared/i18n";
import { MediaConnectionAddressField } from "./MediaConnectionAddressField";

describe("MediaConnectionAddressField", () => {
  it.each(SUPPORTED_LOCALES)(
    "shows Docker guidance and a neutral ComfyUI placeholder in %s without filling an address",
    locale => {
      setLocale(locale);
      const onChange = vi.fn();
      render(<MediaConnectionAddressField platform="COMFYUI" origin="" onChange={onChange} />);
      const field = screen.getByRole("textbox", { name: translate(locale, "settings.mediaSettings.comfyUrl") });
      expect(field).toHaveValue("");
      expect(field).toHaveAttribute("placeholder", translate(locale, "settings.mediaSettings.comfyAddressPlaceholder"));
      expect(field).not.toHaveAttribute("placeholder", "http://127.0.0.1:8188");
      expect(screen.getByText(translate(locale, "settings.mediaSettings.comfyHostHint"))).toBeVisible();
      expect(field).toHaveAccessibleDescription(translate(locale, "settings.mediaSettings.comfyHostHint"));
      expect(onChange).not.toHaveBeenCalled();
    },
  );

  it("preserves the manually entered ComfyUI address across locale changes", async () => {
    function AddressDraft() {
      const [origin, setOrigin] = useState("");
      return <MediaConnectionAddressField platform="COMFYUI" origin={origin} onChange={setOrigin} />;
    }
    render(<AddressDraft />);
    const user = userEvent.setup();
    const field = screen.getByRole("textbox", { name: "ComfyUI 地址" });
    await user.type(field, "http://192.168.1.2:8188/proxy/");
    act(() => {
      setLocale("en");
    });
    expect(field).toHaveValue("http://192.168.1.2:8188/proxy/");
    expect(field).toHaveAccessibleDescription(translate("en", "settings.mediaSettings.comfyHostHint"));
  });

  it.each(["KLING", "MINIMAX"] as const)("accepts a custom %s service address", async platform => {
    const user = userEvent.setup();
    function Draft() {
      const [origin, setOrigin] = useState("");
      return <MediaConnectionAddressField platform={platform} origin={origin} onChange={setOrigin} />;
    }
    render(<Draft />);
    const field = screen.getByRole("textbox");
    await user.type(field, "http://localhost:8090");
    expect(field).toHaveValue("http://localhost:8090");
  });

  it("keeps other platform placeholders and caller-provided descriptions", () => {
    render(
      <>
        <p id="address-note">Connection guidance</p>
        <MediaConnectionAddressField platform="OPENAI" origin="" onChange={vi.fn()} describedBy="address-note" />
      </>,
    );
    const field = screen.getByRole("textbox");
    expect(field).toHaveAttribute("placeholder", "https://api.openai.com/v1");
    expect(field).toHaveAccessibleDescription("Connection guidance");
    expect(screen.queryByText(translate("zh", "settings.mediaSettings.comfyHostHint"))).not.toBeInTheDocument();
  });
});
