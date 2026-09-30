import { CheckCircle, Info, WarningCircle } from "@phosphor-icons/react";
import { useId, type ReactNode } from "react";
import "./design-tokens.css";
import "./PageTheme.css";

/** Shared dark surfaces and status chips adapted from Beautiful UI (see beautiful-ui-LICENSE.txt). */
export function Panel({ title, description, actions, children, className = "" }: {
  title?: string; description?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string;
}) {
  const headingId = useId();
  return <section className={`ui-panel ${className}`} aria-labelledby={title ? headingId : undefined}>
    {title || description || actions ? <header className="ui-panel-header">
      <div>{title ? <h2 id={headingId}>{title}</h2> : null}{description ? <p>{description}</p> : null}</div>
      {actions ? <div className="ui-form-actions">{actions}</div> : null}
    </header> : null}
    {children}
  </section>;
}

export function Notice({ tone = "info", title, children }: {
  tone?: "info" | "success" | "warning" | "danger"; title?: string; children: ReactNode;
}) {
  const Icon = tone === "success" ? CheckCircle : tone === "info" ? Info : WarningCircle;
  return <div className={`ui-notice ui-notice--${tone}`} role={tone === "danger" ? "alert" : tone === "success" ? "status" : undefined}>
    <Icon size={18} aria-hidden /><div>{title ? <strong>{title}</strong> : null}<div>{children}</div></div>
  </div>;
}

export function StatusBadge({ tone = "neutral", children }: {
  tone?: "neutral" | "success" | "warning" | "danger"; children: ReactNode;
}) {
  return <span className={`ui-badge ui-badge--${tone}`}><span aria-hidden />{children}</span>;
}

export function EmptyState({ icon, title, description, action }: {
  icon?: ReactNode; title: string; description?: ReactNode; action?: ReactNode;
}) {
  return <div className="ui-empty-state">
    {icon ? <span className="ui-empty-icon" aria-hidden>{icon}</span> : null}
    <h3>{title}</h3>{description ? <p>{description}</p> : null}{action}
  </div>;
}

/** Compact factual summaries, shared by configuration pages. */
export function SummaryStrip({ items }: {
  items: { label: string; value: ReactNode; detail?: ReactNode }[];
}) {
  return <dl className="ui-summary-strip">{items.map((item) => <div key={item.label}>
    <dt>{item.label}</dt><dd>{item.value}</dd>
    {item.detail ? <dd className="ui-summary-detail">{item.detail}</dd> : null}
  </div>)}</dl>;
}
