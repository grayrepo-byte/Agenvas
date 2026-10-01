import { useState } from "react";
import { Select } from "../ui/Select";
import { LOCALE_NAMES, SUPPORTED_LOCALES, resolveLocale, setLocale, t, useLocale } from ".";

/** Shared by public entry screens, the sidebar, and system settings. */
export function LanguageSelect() {
  const locale = useLocale();
  const [saveFailed, setSaveFailed] = useState(false);
  return <div className="language-select">
    <Select aria-label={t("界面语言")} value={locale} onChange={(event) => {
      const next = resolveLocale(event.target.value);
      if (next) setSaveFailed(!setLocale(next));
    }}>
      {SUPPORTED_LOCALES.map((value) => <option key={value} value={value} lang={value}>{LOCALE_NAMES[value]}</option>)}
    </Select>
    {saveFailed ? <small role="alert">{t("语言已切换，但浏览器未能保存偏好。")}</small> : null}
  </div>;
}
