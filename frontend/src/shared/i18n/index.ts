import { useSyncExternalStore } from "react";
import zh from "./locales/zh.json";
import en from "./locales/en.json";
import ru from "./locales/ru.json";
import ja from "./locales/ja.json";

export const SUPPORTED_LOCALES = ["en", "zh", "ru", "ja"] as const;
export type Locale = typeof SUPPORTED_LOCALES[number];
export const DEFAULT_LOCALE: Locale = "zh";
export const LOCALE_STORAGE_KEY = "agenvas.locale.v1";
export const LOCALE_NAMES: Record<Locale, string> = { en: "English", zh: "中文", ru: "Русский", ja: "日本語" };
const FORMAT_LOCALES: Record<Locale, string> = { en: "en-US", zh: "zh-CN", ru: "ru-RU", ja: "ja-JP" };
type Catalog = Readonly<Record<string, string>>;
const catalogs: Record<Locale, Catalog> = { en, zh, ru, ja };
const listeners = new Set<() => void>();
type Parameter = string | number | bigint | boolean | null | undefined;

/** Region variants use their supported base language; malformed/unsupported values fall back. */
export function resolveLocale(value: string | null | undefined): Locale | undefined {
  if (!value || !/^[a-z]{2}(?:[-_][a-z0-9]{2,8})*$/i.test(value)) return undefined;
  const language = value.toLowerCase().split(/[-_]/)[0];
  return SUPPORTED_LOCALES.find((locale) => locale === language);
}

export function detectLocale(): Locale {
  try {
    const saved = resolveLocale(localStorage.getItem(LOCALE_STORAGE_KEY));
    if (saved) return saved;
  } catch { /* A blocked preference store must not prevent the application from starting. */ }
  for (const language of navigator.languages) {
    const supported = resolveLocale(language);
    if (supported) return supported;
  }
  return DEFAULT_LOCALE;
}

let currentLocale = detectLocale();
export function getLocale(): Locale { return currentLocale; }
export function getFormatLocale(): string { return FORMAT_LOCALES[currentLocale]; }
function publish(locale: Locale) {
  currentLocale = locale;
  document.documentElement.lang = locale;
  listeners.forEach((listener) => listener());
}

/** Only a UI preference is stored; changing language never remounts routes or clears drafts. */
export function setLocale(locale: Locale): boolean {
  publish(locale);
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale);
    return true;
  } catch { return false; }
}

document.documentElement.lang = currentLocale;
window.addEventListener("storage", (event) => {
  if (event.key === LOCALE_STORAGE_KEY || event.key === null) {
    publish(resolveLocale(event.newValue) ?? detectLocale());
  }
});

/** Subscribe even in memoized canvas cards; language changes retain component identity. */
function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useLocale(): Locale {
  return useSyncExternalStore(subscribe, getLocale, () => DEFAULT_LOCALE);
}

/** Source messages are gettext-style IDs. Only registered UI copy belongs here, never user content. */
export function translate(locale: Locale, message: string, parameters: Readonly<Record<string, Parameter>> = {}): string {
  const template = catalogs[locale][message] ?? catalogs[DEFAULT_LOCALE][message] ?? message;
  return template.replace(/\{(\w+)\}/g, (token, name: string) =>
    Object.hasOwn(parameters, name) ? String(parameters[name] ?? "") : token);
}

export function t(message: string, parameters?: Readonly<Record<string, Parameter>>): string {
  return translate(currentLocale, message, parameters);
}

export function formatDate(value: string | Date, options?: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(getFormatLocale(), options).format(new Date(value));
}

export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(getFormatLocale(), options).format(value);
}
