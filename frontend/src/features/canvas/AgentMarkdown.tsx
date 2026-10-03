import { memo, useId } from "react";
import Markdown, { type Components } from "react-markdown";
import remarkBreaks from "remark-breaks";
import remarkGfm from "remark-gfm";
import "./AgentMarkdown.css";

const REMARK_PLUGINS = [remarkGfm, remarkBreaks];
const SAFE_LINK_PROTOCOL = /^(https?:|mailto:)/i;

// Model text cannot navigate to application actions or executable URL schemes.
function safeLink(url: string) {
  return SAFE_LINK_PROTOCOL.test(url) || url.startsWith("#") ? url : "";
}

const COMPONENTS: Components = {
  a: ({ href, children, title, id, "aria-label": ariaLabel, "aria-describedby": describedBy }) => href
    ? <a href={href} id={id} title={title} aria-label={ariaLabel} aria-describedby={describedBy}
      target={href.startsWith("#") ? undefined : "_blank"} rel="noopener noreferrer">{children}</a>
    : <span>{children}</span>,
  // Reading a reply must not automatically fetch arbitrary model-supplied media.
  img: ({ src, alt }) => typeof src === "string" && src
    ? <a href={src} target="_blank" rel="noopener noreferrer">{alt || src}</a>
    : <span>{alt}</span>,
  pre: ({ children }) => <pre className="nodrag nowheel" tabIndex={0}>{children}</pre>,
  table: ({ children }) => <div className="agent-markdown__table nodrag nowheel" tabIndex={0}>
    <table>{children}</table>
  </div>,
};

/** Present public Markdown without changing its durable source or executing HTML. */
export const AgentMarkdown = memo(function AgentMarkdown({ text }: { text: string }) {
  const id = useId();
  return <div className="agent-markdown">
    <Markdown remarkPlugins={REMARK_PLUGINS} components={COMPONENTS} urlTransform={safeLink}
      remarkRehypeOptions={{ clobberPrefix: `agent-${id}-` }} skipHtml>{text}</Markdown>
  </div>;
});
