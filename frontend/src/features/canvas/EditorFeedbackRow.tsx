import { CheckCircle, CircleNotch, Info, WarningCircle } from "@/shared/ui/icons";
import type { ReactNode } from "react";
import "./EditorFeedbackRow.css";

/** Inline feedback below the prompt toolbar; actions stay beside the message. */
export function EditorFeedbackRow({ children, action, tone = "neutral", title }: {
  children?: ReactNode;
  action?: ReactNode;
  tone?: "neutral" | "loading" | "warning" | "danger" | "success";
  title?: string;
}) {
  const Icon = tone === "loading" ? CircleNotch
    : tone === "warning" || tone === "danger" ? WarningCircle
      : tone === "success" ? CheckCircle : Info;
  return <div className="editor-feedback-row" data-tone={tone}
    role={tone === "danger" ? "alert" : "status"}>
    <Icon className="editor-feedback-icon" aria-hidden="true" />
    <div className="editor-feedback-copy">
      {title ? <strong>{title}</strong> : null}
      {children ? <span>{children}</span> : null}
    </div>
    {action ? <div className="editor-feedback-action">{action}</div> : null}
  </div>;
}
