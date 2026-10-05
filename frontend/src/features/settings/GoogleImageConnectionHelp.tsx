import { t, useLocale } from "../../shared/i18n";
const GOOGLE_IMAGE_API_VERSION = { STABLE: "v1", BETA: "v1beta" } as const;
const GOOGLE_IMAGE_API_LABEL = {
  get [GOOGLE_IMAGE_API_VERSION.STABLE]() { return t("settings.googleImage.vOne"); },
  get [GOOGLE_IMAGE_API_VERSION.BETA]() { return t("settings.googleImage.vOneBeta"); },
} as const;

/** Mirrors the adapter's API base suffix; model names do not select a request format. */
export function googleImageApiLabel(origin: string | null): string {
  if (!origin?.trim()) return GOOGLE_IMAGE_API_LABEL[GOOGLE_IMAGE_API_VERSION.STABLE];
  try {
    const path = new URL(origin.trim()).pathname.replace(/\/+$/, "");
    const version = path.endsWith(`/${GOOGLE_IMAGE_API_VERSION.BETA}`)
      ? GOOGLE_IMAGE_API_VERSION.BETA : GOOGLE_IMAGE_API_VERSION.STABLE;
    return GOOGLE_IMAGE_API_LABEL[version];
  } catch {
    return t("settings.googleImage.endpointMissing");
  }
}

export function GoogleImageConnectionHelp({ id, origin }: { id: string; origin: string }) {
  useLocale();
  return <aside id={id} role="note" aria-label={t("settings.googleImage.title")} className="media-api-help">
    <strong>{t("settings.googleImage.currentFormat", { "0": googleImageApiLabel(origin) })}</strong>
    <p>{t("settings.googleImage.formatHint")}</p>
    <dl>
      <div><dt>{t("settings.googleImage.vOne")}</dt><dd>{t("settings.googleImage.endpointPrefix")}<code>/v1</code> {t("settings.googleImage.vOneEndpointSuffix")}</dd></div>
      <div><dt>{t("settings.googleImage.vOneBeta")}</dt><dd>{t("settings.googleImage.endpointPrefix")}<code>/v1beta</code> {t("settings.googleImage.betaEndpointSuffix")}</dd></div>
    </dl>
    <p>{t("settings.googleImage.rootEndpointPrefix")}<code>/models/…:generateContent</code> {t("settings.googleImage.customEndpointInfix")}<code>/v1/draw/nano-banana</code> {t("settings.googleImage.unsupportedFormatSuffix")}</p>
    <p>{t("settings.googleImage.exampleEndpointPrefix")}<code>https://grsai.dakka.com.cn/v1beta</code>{t("settings.googleImage.customModelInfix")}<code>nano-banana-2-lite</code>{t("settings.googleImage.otherProvidersSuffix")}</p>
  </aside>;
}
