import type { ReactNode } from "react";
import "./Dropdown.css";

/** Descriptive menu rows keep the label separate from supporting information. */
export function OptionContent({ icon, title, description, note }: {
  icon?: ReactNode; title: ReactNode; description?: ReactNode; note?: ReactNode;
}) {
  return <span className="ui-option-content">
    {icon ? <span className="ui-option-icon" aria-hidden="true">{icon}</span> : null}
    <span className="ui-option-copy">
      <span className="ui-option-heading"><span className="ui-option-title">{title}</span>
        {note ? <span className="ui-option-note">{note}</span> : null}</span>
      {description ? <span className="ui-option-description">{description}</span> : null}
    </span>
  </span>;
}
