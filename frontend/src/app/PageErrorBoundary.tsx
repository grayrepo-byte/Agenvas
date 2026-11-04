import { Component, useEffect, useId, useRef, type ReactNode } from "react";
import { t, useLocale } from "../shared/i18n";
import { Notice } from "../shared/ui/PagePrimitives";
import { Button } from "../shared/ui/primitives/button";

type Props = { children: ReactNode; resetKey: string };
type State = { failed: boolean; resetKey: string };

/** Keep rejected route imports and render errors from removing every recovery control. */
export class PageErrorBoundary extends Component<Props, State> {
  state: State = { failed: false, resetKey: this.props.resetKey };

  static getDerivedStateFromError(): Partial<State> {
    return { failed: true };
  }

  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    return props.resetKey !== state.resetKey ? { failed: false, resetKey: props.resetKey } : null;
  }

  render() {
    return this.state.failed ? <PageRecovery /> : this.props.children;
  }
}

function PageRecovery() {
  useLocale();
  const headingId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, []);

  return <main className="app-page flex items-center justify-center p-4" data-page-recovery>
    <section className="w-full max-w-lg" aria-labelledby={headingId}>
      <Notice tone="danger">
        <h1 className="text-base font-semibold" id={headingId} ref={heading} tabIndex={-1}>{t("app.pageFailed.title")}</h1>
        <p>{t("app.pageFailed.description")}</p>
        <p>{t("app.pageFailed.unsavedHint")}</p>
        <Button type="button" onClick={() => window.location.reload()}>{t("app.pageFailed.reload")}</Button>
      </Notice>
    </section>
  </main>;
}
