import { act, fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";
import { LoginPage } from "../../features/auth/LoginPage";
import { getSetupStatus, uploadImageAsset } from "../api/client";
import { server } from "../../test/server";
import { LanguageSelect } from "./LanguageSelect";
import { DEFAULT_LOCALE, LOCALE_NAMES, LOCALE_STORAGE_KEY, SUPPORTED_LOCALES,
  detectLocale, formatDate, formatNumber, getLocale, resolveLocale, setLocale, t, translate } from ".";
import en from "./locales/en.json";
import zh from "./locales/zh.json";
import ru from "./locales/ru.json";
import ja from "./locales/ja.json";

describe("shared locale configuration", () => {
  it("matches supported regions and rejects unsupported languages", () => {
    expect(resolveLocale("EN-gb")).toBe("en");
    expect(resolveLocale("zh-Hans-CN")).toBe("zh");
    expect(resolveLocale("ru_RU")).toBe("ru");
    expect(resolveLocale("ja-JP")).toBe("ja");
    expect(resolveLocale("fr-FR")).toBeUndefined();
    expect(resolveLocale("english")).toBeUndefined();
    expect(resolveLocale("en--US")).toBeUndefined();
  });

  it("prefers saved choices, then supported browser languages, then Chinese", () => {
    const stored = vi.spyOn(Storage.prototype, "getItem").mockReturnValue("ru-RU");
    const languages = vi.spyOn(navigator, "languages", "get").mockReturnValue(["fr", "ja-JP", "en"]);
    expect(detectLocale()).toBe("ru");
    stored.mockReturnValue("unsupported");
    expect(detectLocale()).toBe("ja");
    stored.mockImplementation(() => { throw new DOMException("blocked"); });
    expect(detectLocale()).toBe("ja");
    languages.mockReturnValue(["fr-FR"]);
    expect(detectLocale()).toBe(DEFAULT_LOCALE);
  });

  it("keeps complete catalogs and identical interpolation tokens", () => {
    const keys = Object.keys(zh).sort();
    for (const catalog of [en, ru, ja]) {
      expect(Object.keys(catalog).sort()).toEqual(keys);
      for (const [key, value] of Object.entries(catalog)) {
        expect(value.trim(), key).not.toBe("");
        expect(value.match(/\{\w+\}/g)?.sort() ?? [], key)
          .toEqual(key.match(/\{\w+\}/g)?.sort() ?? []);
      }
    }
  });

  it("substitutes values as text and falls back without altering arbitrary content", () => {
    expect(translate("en", "已选 {0} 个节点", { "0": 3 })).toContain("3");
    expect(translate("ru", "not-a-catalog-key")).toBe("not-a-catalog-key");
    expect(translate("ja", "已选 {0} 个节点", { "0": "<script>$&{1}</script>" }))
      .toContain("<script>$&{1}</script>");
  });

  it("formats dates and numbers using the selected UI language", () => {
    setLocale("ru");
    expect(formatNumber(1234.5)).toBe(new Intl.NumberFormat("ru-RU").format(1234.5));
    const date = new Date("2026-10-01T08:00:00Z");
    expect(formatDate(date)).toBe(new Intl.DateTimeFormat("ru-RU").format(date));
  });

  it("switches the actual login page without losing inputs or remounting it", () => {
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><LoginPage /></MemoryRouter></QueryClientProvider>);
    const username = screen.getByRole("textbox", { name: t("登录名") });
    fireEvent.change(username, { target: { value: "my-admin" } });
    const password = screen.getByLabelText(t("密码"));
    fireEvent.change(password, { target: { value: "draft-password" } });
    for (const locale of SUPPORTED_LOCALES) {
      act(() => { setLocale(locale); });
      expect(screen.getByRole("heading", { name: translate(locale, "登录 Agenvas") })).toBeInTheDocument();
      expect(screen.getByRole("textbox", { name: translate(locale, "登录名") })).toBe(username);
      expect(username).toHaveValue("my-admin");
      expect(password).toHaveValue("draft-password");
      expect(document.documentElement.lang).toBe(locale);
      expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe(locale);
    }
  });

  it("offers all native names and reports blocked preference storage", async () => {
    render(<LanguageSelect />);
    const user = userEvent.setup();
    await user.click(screen.getByRole("combobox"));
    const list = within(screen.getByRole("listbox"));
    for (const locale of SUPPORTED_LOCALES) expect(list.getByRole("option", { name: LOCALE_NAMES[locale] })).toBeInTheDocument();
    const storage = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException("blocked"); });
    await user.click(list.getByRole("option", { name: "日本語" }));
    expect(getLocale()).toBe("ja");
    expect(screen.getByRole("alert")).toHaveTextContent(translate("ja", "语言已切换，但浏览器未能保存偏好。"));
    storage.mockRestore();
  });

  it("synchronizes other tabs without accepting unsupported languages", () => {
    render(<LanguageSelect />);
    act(() => window.dispatchEvent(new StorageEvent("storage", { key: LOCALE_STORAGE_KEY, newValue: "ru" })));
    expect(getLocale()).toBe("ru");
    expect(screen.getByRole("combobox")).toHaveTextContent("Русский");
    act(() => window.dispatchEvent(new StorageEvent("storage", { key: "unrelated", newValue: "en" })));
    expect(getLocale()).toBe("ru");
  });

  it("negotiates language for reads, CSRF, and multipart uploads", async () => {
    const headers: string[] = [];
    server.use(
      http.get("/api/v1/auth/setup-status", ({ request }) => {
        headers.push(request.headers.get("Accept-Language") ?? "");
        return HttpResponse.json({ initialized: false });
      }),
      http.get("/api/v1/auth/csrf", ({ request }) => {
        headers.push(request.headers.get("Accept-Language") ?? "");
        return HttpResponse.json({ token: "test-csrf", headerName: "X-CSRF-TOKEN" });
      }),
    );
    // Inspect the browser FormData before Node/MSW attempts to decode jsdom's File implementation.
    const actualFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
      if (input === "/api/v1/projects/project-id/assets") {
        const requestHeaders = new Headers(init?.headers);
        headers.push(requestHeaders.get("Accept-Language") ?? "");
        expect(requestHeaders.get("X-CSRF-TOKEN")).toBe("test-csrf");
        expect(requestHeaders.has("Content-Type")).toBe(false);
        expect(init?.body).toBeInstanceOf(FormData);
        return Promise.resolve(HttpResponse.json({ id: "asset-id" }));
      }
      return actualFetch(input, init);
    });
    setLocale("ja");
    await getSetupStatus();
    await uploadImageAsset("project-id", new File(["image"], "test.png", { type: "image/png" }));
    expect(headers).toEqual(["ja", "ja", "ja"]);
    setLocale(DEFAULT_LOCALE);
  });
});
