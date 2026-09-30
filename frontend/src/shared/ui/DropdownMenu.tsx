import { useEffect, useRef, type ComponentProps, type Ref, type RefObject } from "react";
import "./design-tokens.css";
import "./Dropdown.css";

type DropdownMenuProps = ComponentProps<"div"> & {
  ref?: Ref<HTMLDivElement>;
  anchorRef?: RefObject<HTMLElement | null>;
  triggerRef?: RefObject<HTMLElement | null>;
  onDismiss?: () => void;
  focusOnOpen?: boolean;
};

const MENU_OPTIONS = "[role='menuitem'], [role='menuitemradio'], [role='menuitemcheckbox']";
const ENABLED_MENU_OPTIONS = MENU_OPTIONS.split(", ").map((selector) => `${selector}:not(:disabled):not([aria-disabled='true'])`).join(", ");

/** Shared menu surface and keyboard traversal. Callers retain their own anchor,
 * dismissal and async state, so a menu never changes a canvas/business selection by itself. */
export function DropdownMenu({ children, className = "", onKeyDown, ref, role = "menu",
  anchorRef, triggerRef, onDismiss, focusOnOpen = false, ...props }: DropdownMenuProps) {
  const localRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (focusOnOpen) {
      const first = localRef.current?.querySelector<HTMLButtonElement>(ENABLED_MENU_OPTIONS);
      (first ?? localRef.current)?.focus();
    }
  }, [focusOnOpen]);
  useEffect(() => {
    if (!onDismiss || !anchorRef) return;
    function dismiss(event: PointerEvent) {
      if (event.target instanceof Node && !anchorRef?.current?.contains(event.target)) onDismiss?.();
    }
    document.addEventListener("pointerdown", dismiss);
    return () => document.removeEventListener("pointerdown", dismiss);
  }, [anchorRef, onDismiss]);
  return <div {...props} role={role} className={`ui-dropdown nodrag nowheel nopan ${className}`}
    tabIndex={props.tabIndex ?? (focusOnOpen ? -1 : undefined)}
    ref={(element) => {
      localRef.current = element;
      if (typeof ref === "function") return ref(element);
      if (ref) ref.current = element;
    }} onKeyDown={(event) => {
      onKeyDown?.(event);
      if (event.defaultPrevented || role !== "menu") return;
      if (event.key === "Escape" && onDismiss) {
        event.preventDefault(); event.stopPropagation(); onDismiss(); triggerRef?.current?.focus(); return;
      }
      if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
      const options = [...(localRef.current?.querySelectorAll<HTMLButtonElement>(MENU_OPTIONS) ?? [])]
        .filter((option) => !option.disabled && option.getAttribute("aria-disabled") !== "true");
      if (!options.length) return;
      event.preventDefault(); event.stopPropagation();
      const current = options.indexOf(document.activeElement as HTMLButtonElement);
      const next = event.key === "Home" ? 0 : event.key === "End" ? options.length - 1
        : current < 0 ? (event.key === "ArrowDown" ? 0 : options.length - 1)
          : (current + (event.key === "ArrowDown" ? 1 : options.length - 1)) % options.length;
      options[next]?.focus();
    }}>{children}</div>;
}
