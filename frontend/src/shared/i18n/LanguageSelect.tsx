import { Check,GlobeSimple } from "@/shared/ui/icons";
import { useState } from "react";
import { LOCALE_NAMES,SUPPORTED_LOCALES,setLocale,t,useLocale } from ".";
import { Dialog } from "../ui/Dialog";
import { Button } from "../ui/primitives/button";

/** Shared by public entry screens, the sidebar, and system settings. */
export function LanguageSelect({ compact = false }: { compact?: boolean }) {
  const locale = useLocale();
  const [open, setOpen] = useState(false);
  const [saveFailed, setSaveFailed] = useState(false);
  const label = `${t("locale.label")} · ${LOCALE_NAMES[locale]}`;
  return <div className={`language-select${compact ? " language-select--compact" : ""}`}>
    <Button variant="outline" type="button" className="ui-language-trigger" aria-label={label} title={label}
      aria-haspopup="dialog" aria-expanded={open} onClick={() => { setSaveFailed(false); setOpen(true); }}>
      <GlobeSimple size={18} aria-hidden /><span className="ui-language-label" lang={locale}>{LOCALE_NAMES[locale]}</span>
    </Button>
    {open ? <Dialog compact title={t("locale.label")} onClose={() => setOpen(false)} onSubmit={(event) => event.preventDefault()}>
      <div className="ui-language-options">
        {SUPPORTED_LOCALES.map((value) => <Button variant="outline" key={value} type="button" className="ui-language-option"
          lang={value} aria-pressed={value === locale} onClick={() => {
            const saved = setLocale(value);
            setSaveFailed(!saved);
            if (saved) setOpen(false);
          }}>
          <span>{LOCALE_NAMES[value]}</span>{value === locale ? <Check size={18} aria-hidden /> : null}
        </Button>)}
      </div>
      {saveFailed ? <p className="ui-error" role="alert">{t("locale.storageFailed")}</p> : null}
    </Dialog> : null}
  </div>;
}
