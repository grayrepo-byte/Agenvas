import { t, useLocale } from "../../shared/i18n";
import type { ReactNode } from "react";
import { ApiError } from "../../shared/api/client";
import { Notice } from "../../shared/ui/PagePrimitives";
import { BrandLogo } from "../../shared/ui/BrandLogo";
import "./AuthPages.css";
import { LanguageSelect } from "../../shared/i18n/LanguageSelect";

/** Public entry screens share the same compact, dark application surface. */
export function AuthLayout({ title, description, children }: {
  title: string;
  description: string;
  children: ReactNode;
}) {
  useLocale();
  return (
    <main className="auth-page app-theme">
      <div className="auth-brand"><BrandLogo /></div>
      <LanguageSelect />
      <section className="auth-card" aria-labelledby="auth-title">
        <header className="auth-card-heading"><span className="auth-eyebrow">AGENT CANVAS</span><h1 id="auth-title">{title}</h1><p>{description}</p></header>
        {children}
      </section>
      <p className="auth-footnote">{t("自托管模式 · Provider 状态登录后可查看")}</p>
    </main>
  );
}

export function AuthField({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  useLocale();
  return <label className="ui-field"><span>{label}</span>{children}{hint ? <small>{hint}</small> : null}</label>;
}

export function FormError({ error }: { error: Error }) {
  useLocale();
  return <Notice tone="danger">{error instanceof ApiError ? error.message : t("请求未完成，请稍后重试。")}</Notice>;
}
