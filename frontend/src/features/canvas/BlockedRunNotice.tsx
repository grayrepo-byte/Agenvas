import { WarningCircle } from "@phosphor-icons/react";
import "./AgentChatPanels.css";
import { useQuery } from "@tanstack/react-query";
import { listRunTasks } from "../../shared/api/client";

/** Explains a durable BLOCKED Run using only safe Task codes, never model messages. */
export function BlockedRunNotice({ projectId, runId, presentation = "panel" }: {
  projectId: string; runId: string; presentation?: "panel" | "chat";
}) {
  const isChat = presentation === "chat";
  const tasks = useQuery({
    queryKey: ["run-tasks", projectId, runId],
    queryFn: () => listRunTasks(projectId, runId),
  });
  const modelFailure = tasks.data?.filter((task) => task.kind === "AGENT_TURN" &&
    task.status === "FAILED" && task.errorCode).at(-1);
  const staleMedia = tasks.data?.find((task) =>
    (task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION") &&
    task.status === "BLOCKED" && task.errorCode === "TASK_INPUT_STALE");
  const archivedMedia = tasks.data?.find((task) =>
    (task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION") &&
    task.status === "BLOCKED" && task.errorCode === "TASK_PROJECT_ARCHIVED");
  const explanation = archivedMedia ?
    "项目已归档，尚未提交的媒体任务已阻断，不会再发起生成或扣除这笔预留。已受理的外部请求仍会核对，晚到结果仅归档到历史，不会继续编排。" : staleMedia ?
    "镜头、参考图或人工选定的关键帧版本已变化，旧计划尚未提交的媒体任务已阻断且不会自动重试。请核对最新输入版本，停止此 Run，重新绑定当前镜头与所需素材后发起新 Run；新图片和视频计划仍须分别由你审批。" :
    switchOnFailure(modelFailure?.errorCode);

  return <section aria-label="运行已阻断" className={isChat ? "agent-chat-panel agent-chat-blocked"
    : "col-span-full border-b border-red-300 bg-red-50 px-6 py-3 text-sm text-red-950"}>
    <p className={isChat ? "agent-chat-panel-notice-title" : "font-semibold"}>{isChat ? <WarningCircle aria-hidden="true" /> : null}运行已阻断；系统不会自动重复调用模型或提交媒体任务。</p>
    {tasks.isPending ? <p>正在读取持久化任务原因…</p> : null}
    {tasks.error ? <p role="alert">暂时无法读取阻断原因，请检查运行记录。</p> : null}
    {tasks.error ? <button className={isChat ? "agent-chat-panel-text-button" : "underline"} onClick={() => void tasks.refetch()} type="button">重试读取</button> : null}
    {tasks.data ? <p>{explanation}</p> : null}
    {archivedMedia ? <p className="text-xs">诊断码：TASK_PROJECT_ARCHIVED</p> : null}
    {staleMedia ? <p className="text-xs">诊断码：TASK_INPUT_STALE</p> : null}
    {!archivedMedia && !staleMedia && modelFailure?.errorCode ? <p className="text-xs">诊断码：{modelFailure.errorCode}</p> : null}
  </section>;
}

/** Stable, allowlisted explanations prevent raw Provider failures from reaching the page. */
function switchOnFailure(code: string | null | undefined): string {
  switch (code) {
    case "LLM_CONFIG_UNAVAILABLE":
      return "此 Run 固定的模型配置或工具调用能力不可用。请管理员检查配置；系统不会擅自切换到另一个模型。";
    case "CREDENTIAL_KEY_VERSION_MISSING":
      return "此 Run 使用的历史加密密钥版本缺失。请管理员恢复相应密钥；不要用新密钥假装解开旧配置。";
    case "MODEL_OUTPUT_INVALID":
      return "模型输出未通过结构或领域校验，且已达到两次修复或回合上限；无效回合的工具操作未写入业务数据。请调整指令或模型配置后重新运行。";
    case "MODEL_TURN_LIMIT":
      return "模型连续请求工具，已达到本次 Run 的 12 回合上限。系统未再调用模型或自动重试；请检查运行记录后调整指令并发起新 Run。";
    case "AGENT_TURN_FAILED":
      return "模型回合未能完成，可能是无效输出或服务故障。已停止后续编排；可展开 Agent 运行记录查看任务状态。";
    default:
      return "请检查下方 UNKNOWN 核对提示或展开 Agent 运行记录确认受阻任务；取消 Run 不代表外部已停止或退款。";
  }
}
