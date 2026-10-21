import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { t, useLocale } from "../../shared/i18n";
import type { ReactNode } from "react";
import { ApiError } from "../../shared/api/client";
import { Notice } from "../../shared/ui/PagePrimitives";
import { BrandLogo } from "../../shared/ui/BrandLogo";
import "./AuthPages.css";
import { LanguageSelect } from "../../shared/i18n/LanguageSelect";
import { FilmStrip, ImageSquare, MusicNotes, ShieldCheck, Sparkle, TextT } from "../../shared/ui/icons";

/** Public entry screens introduce the studio while keeping credentials in one compact panel. */
export function AuthLayout({ title, description, children }: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  useLocale();
  return (
    <main className="auth-page app-theme">
      <header className="auth-topbar">
        <div className="auth-brand"><BrandLogo /></div>
        <LanguageSelect />
      </header>
      <div className="auth-layout">
        <section className="auth-introduction" aria-labelledby="auth-introduction-title">
          <span className="auth-eyebrow"><span aria-hidden="true" />AGENT CANVAS</span>
          <h2 id="auth-introduction-title">{t("auth.shared.studioTitle")}</h2>
          <p>{t("projects.emptyHint")}</p>
          <ul className="auth-capabilities">
            <li><TextT size={21} weight="duotone" aria-hidden="true" /><span>{t("common.text")}</span></li>
            <li><ImageSquare size={21} weight="duotone" aria-hidden="true" /><span>{t("common.image")}</span></li>
            <li><FilmStrip size={21} weight="duotone" aria-hidden="true" /><span>{t("common.video")}</span></li>
            <li><MusicNotes size={21} weight="duotone" aria-hidden="true" /><span>{t("common.audio")}</span></li>
          </ul>
        </section>
        <section className="auth-card" aria-labelledby="auth-title">
          <header className="auth-card-heading"><span className="auth-card-symbol" aria-hidden="true"><Sparkle size={24} weight="duotone" /></span><h1 id="auth-title">{title}</h1><p>{description}</p></header>
          {children}
        </section>
      </div>
      <footer className="auth-footnote"><ShieldCheck size={15} aria-hidden="true" /><p>{t("auth.shared.selfHostedHint")}</p></footer>
    </main>
  );
}

export function AuthField({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  useLocale();
  return <Field><FieldLabel className="ui-field block"><span>{label}</span>{children}{hint ? <small>{hint}</small> : null}</FieldLabel></Field>;
}

export function FormError({ error }: { error: Error }) {
  useLocale();
  return <Notice tone="danger">{error instanceof ApiError ? error.message : t("auth.shared.requestFailed")}</Notice>;
}
