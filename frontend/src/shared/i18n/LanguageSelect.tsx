import { Check,GlobeSimple } from "@/shared/ui/icons";
import { useState } from "react";
import { SUPPORTED_LOCALES,localeName,setLocale,t,useLocale } from ".";
import { Dialog } from "../ui/Dialog";
import { Button } from "../ui/primitives/button";

/** Shared by public entry screens, the sidebar, and system settings. */
export function LanguageSelect({ compact = false }: { compact?: boolean }) {
  const locale = useLocale();
  const [open, setOpen] = useState(false);
  const [saveFailed, setSaveFailed] = useState(false);
  const label = `${t("locale.label")} · ${localeName(locale)}`;
  return <div className={`language-select${compact ? " language-select--compact" : ""}`}>
    <Button variant="outline" type="button" className="ui-language-trigger" aria-label={label} title={label}
      aria-haspopup="dialog" aria-expanded={open} onClick={() => { setSaveFailed(false); setOpen(true); }}>
      <GlobeSimple size={18} aria-hidden /><span className="ui-language-label" lang={locale}>{localeName(locale)}</span>
    </Button>
    {open ? <Dialog compact title={t("locale.label")} onClose={() => setOpen(false)} onSubmit={(event) => event.preventDefault()}>
      <div className="ui-language-options">
        {SUPPORTED_LOCALES.map((value) => <Button variant="outline" key={value} type="button" className="ui-language-option"
          lang={locale} aria-pressed={value === locale} onClick={() => {
            const saved = setLocale(value);
            setSaveFailed(!saved);
            if (saved) setOpen(false);
          }}>
          <span>{localeName(value)}</span>{value === locale ? <Check size={18} aria-hidden /> : null}
        </Button>)}
      </div>
      {saveFailed ? <p className="ui-error" role="alert">{t("locale.storageFailed")}</p> : null}
    </Dialog> : null}
  </div>;
}
