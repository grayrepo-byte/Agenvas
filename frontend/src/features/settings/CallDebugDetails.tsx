import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link,Navigate } from "react-router";
import { HTTP_STATUS,ApiError,getCallDebug,type CallLog } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { FormattedCallExchange } from "./FormattedCallExchange";


/** Bodies are fetched only while the detail dialog is open and discarded from the query cache on closing. */
export function CallDebugDetails({ id, kind }: { id: string; kind?: CallLog["kind"] }) {
  useLocale();
  const [mode, setMode] = useState<"formatted" | "raw">("formatted");
  const details = useQuery({ queryKey: ["call-debug", id], queryFn: () => getCallDebug(id), retry: false, gcTime: 0 });
  if (details.error instanceof ApiError && details.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = details.error instanceof ApiError && details.error.status === HTTP_STATUS.FORBIDDEN;
  return <section className="call-log-debug" aria-label={t("调用内容")}>
    <h3>{t("调用内容")}</h3>
    {details.isPending ? <LoadingState compact label={t("正在读取调用正文")} /> : null}
    {details.isError ? <Notice tone="danger" title={forbidden ? t("无权查看调用正文") : t("读取调用正文失败")}>
      <Button variant="outline"  type="button" disabled={details.isFetching} onClick={() => void details.refetch()}>{t("重试读取正文")}</Button>
    </Notice> : null}
    {details.data && !details.isError ? details.data.captured ? <>
      <p className="ui-muted">{t("以下内容来自实际 HTTP 调用，所有 header 均省略，凭证和私有推理已脱敏。请谨慎分享。")}</p>
      {details.data.exchanges.length === 0 ? <p className="ui-muted">{t("没有已采集的 HTTP 请求。Mock 调用没有真实请求地址或 HTTP 正文。")}</p> : null}
      {details.data.exchanges.length ? <div className="call-log-view-toggle" role="group" aria-label={t("日志显示方式")}>
        <Button variant="outline"  type="button" aria-pressed={mode === "formatted"} onClick={() => setMode("formatted")}>{t("格式化显示")}</Button>
        <Button variant="outline"  type="button" aria-pressed={mode === "raw"} onClick={() => setMode("raw")}>{t("原始内容")}</Button>
      </div> : null}
      {details.data.exchanges.map((exchange, index) => <div className="call-log-exchange" key={index}>
        <h4>{t("请求 {0} · {1} · {2}", { "0": index + 1, "1": exchange.method, "2": exchange.responseStatus === null ? t("未收到响应") : `HTTP ${exchange.responseStatus}` })}</h4>
        <p className="call-log-url">{exchange.url}</p>
        <FormattedCallExchange exchange={exchange} mode={mode} llm={kind === undefined || kind === "LLM"} />
      </div>)}
    </> : <p className="ui-muted">{t("本次调用未开启 debug 模式，没有保存原始正文。可在")}<Link to="/settings/general?tab=logs">{t("系统设置")}</Link>{t("中开启；旧日志不会补录。")}</p> : null}
  </section>;
}
