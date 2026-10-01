import { useState } from "react";
import { Check, GlobeSimple } from "@phosphor-icons/react";
import { Dialog } from "../ui/Dialog";
import { LOCALE_NAMES, SUPPORTED_LOCALES, setLocale, t, useLocale } from ".";

/** Shared by public entry screens, the sidebar, and system settings. */
export function LanguageSelect({ compact = false }: { compact?: boolean }) {
  const locale = useLocale();
  const [open, setOpen] = useState(false);
  const [saveFailed, setSaveFailed] = useState(false);
  const label = `${t("界面语言")} · ${LOCALE_NAMES[locale]}`;
  return <div className={`language-select${compact ? " language-select--compact" : ""}`}>
    <button type="button" className="secondary-button ui-language-trigger" aria-label={label} title={label}
      aria-haspopup="dialog" aria-expanded={open} onClick={() => { setSaveFailed(false); setOpen(true); }}>
      <GlobeSimple size={18} aria-hidden /><span className="ui-language-label" lang={locale}>{LOCALE_NAMES[locale]}</span>
    </button>
    {open ? <Dialog compact title={t("界面语言")} onClose={() => setOpen(false)} onSubmit={(event) => event.preventDefault()}>
      <div className="ui-language-options">
        {SUPPORTED_LOCALES.map((value) => <button key={value} type="button" className="secondary-button ui-language-option"
          lang={value} aria-pressed={value === locale} onClick={() => {
            const saved = setLocale(value);
            setSaveFailed(!saved);
            if (saved) setOpen(false);
          }}>
          <span>{LOCALE_NAMES[value]}</span>{value === locale ? <Check size={18} aria-hidden /> : null}
        </button>)}
      </div>
      {saveFailed ? <p className="ui-error" role="alert">{t("语言已切换，但浏览器未能保存偏好。")}</p> : null}
    </Dialog> : null}
  </div>;
}
