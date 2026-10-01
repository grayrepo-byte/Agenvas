import { t, useLocale } from "../../shared/i18n";
import { useQuery } from "@tanstack/react-query";
import { Link, Navigate } from "react-router";
import { ApiError, getCallDebug, type DebugBody } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice } from "../../shared/ui/PagePrimitives";

const UNAUTHORIZED_STATUS = 401;
const FORBIDDEN_STATUS = 403;

/** Bodies are fetched only while expanded and discarded from the query cache on collapse. */
export function CallDebugDetails({ id }: { id: string }) {
  useLocale();
  const details = useQuery({ queryKey: ["call-debug", id], queryFn: () => getCallDebug(id), retry: false, gcTime: 0 });
  if (details.error instanceof ApiError && details.error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;
  const forbidden = details.error instanceof ApiError && details.error.status === FORBIDDEN_STATUS;
  return <section className="call-log-debug" aria-label={t("原始调用内容")}>
    <h3>{t("原始调用内容")}</h3>
    {details.isPending ? <LoadingState compact label={t("正在读取调用正文")} /> : null}
    {details.isError ? <Notice tone="danger" title={forbidden ? t("无权查看调用正文") : t("读取调用正文失败")}>
      <button className="secondary-button" type="button" disabled={details.isFetching} onClick={() => void details.refetch()}>{t("重试读取正文")}</button>
    </Notice> : null}
    {details.data && !details.isError ? details.data.captured ? <>
      <p className="ui-muted">{t("以下内容来自实际 HTTP 调用，所有 header 均省略，凭证和私有推理已脱敏。请谨慎分享。")}</p>
      {details.data.exchanges.length === 0 ? <p className="ui-muted">{t("没有已采集的 HTTP 请求。Mock 调用没有真实请求地址或 HTTP 正文。")}</p> : null}
      {details.data.exchanges.map((exchange, index) => <div className="call-log-exchange" key={index}>
        <h4>{t("请求 {0} · {1} · {2}", { "0": index + 1, "1": exchange.method, "2": exchange.responseStatus === null ? t("未收到响应") : `HTTP ${exchange.responseStatus}` })}</h4>
        <p className="call-log-url">{exchange.url}</p>
        <DebugBodyContent title={t("请求正文")} body={exchange.requestBody} empty={t("无请求正文")} />
        <DebugBodyContent title={t("响应正文")} body={exchange.responseBody} empty={t("响应正文未采集")} />
      </div>)}
    </> : <p className="ui-muted">{t("本次调用未开启 debug 模式，没有保存原始正文。可在")}<Link to="/settings/general">{t("系统设置")}</Link>{t("中开启；旧日志不会补录。")}</p> : null}
  </section>;
}

function DebugBodyContent({ title, body, empty }: { title: string; body: DebugBody | null; empty: string }) {
  useLocale();
  return <details className="call-log-body"><summary>{title}{body ? ` · ${body.encoding}` : ""}{body?.truncated ? t(" · 超限或未读完") : ""}</summary>
    {body?.truncated ? <p className="ui-muted">{t("正文超过采集上限或响应未读完；这里只包含已采集部分。")}</p> : null}
    <pre>{body ? body.content : empty}</pre>
  </details>;
}
