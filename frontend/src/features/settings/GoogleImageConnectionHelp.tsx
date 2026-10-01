import { t, useLocale } from "../../shared/i18n";
const GOOGLE_IMAGE_API_VERSION = { STABLE: "v1", BETA: "v1beta" } as const;
const GOOGLE_IMAGE_API_LABEL = {
  get [GOOGLE_IMAGE_API_VERSION.STABLE]() { return t("Gemini v1（默认）"); },
  get [GOOGLE_IMAGE_API_VERSION.BETA]() { return t("Gemini v1beta（兼容）"); },
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
    return t("待填写有效 API 地址");
  }
}

export function GoogleImageConnectionHelp({ id, origin }: { id: string; origin: string }) {
  useLocale();
  return <aside id={id} role="note" aria-label={t("Nano Banana 接口配置说明")} className="media-api-help">
    <strong>{t("当前接口格式：{0}", { "0": googleImageApiLabel(origin) })}</strong>
    <p>{t("同一 Gemini 适配器支持两种请求格式，由 API 地址的版本后缀决定。请按服务商文档填写，中转站并不都使用 v1beta。")}</p>
    <dl>
      <div><dt>{t("Gemini v1（默认）")}</dt><dd>{t("地址以 ")}<code>/v1</code> {t(" 结尾；留空使用官方地址，裸主机地址也默认使用 v1。")}</dd></div>
      <div><dt>{t("Gemini v1beta（兼容）")}</dt><dd>{t("地址以 ")}<code>/v1beta</code> {t(" 结尾，用于提供该 Gemini 兼容接口的服务商。")}</dd></div>
    </dl>
    <p>{t("填写 API 根地址，包含版本后缀；不要粘贴 ")}<code>/models/…:generateContent</code> {t(" 的完整调用地址。 服务商自定义的 ")}<code>/v1/draw/nano-banana</code> {t(" 接口不属于这两种格式。")}</p>
    <p>{t("已验证的 grsai 示例：地址 ")}<code>https://grsai.dakka.com.cn/v1beta</code>{t("；发布 Nano Banana 能力时， 选择“自定义兼容模型”，模型名填 ")}<code>nano-banana-2-lite</code>{t("。其他服务商请使用其文档中的地址和模型名。")}</p>
  </aside>;
}
