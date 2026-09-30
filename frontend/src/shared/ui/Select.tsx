import { CaretDown, Check } from "@phosphor-icons/react";
import { useEffect, useId, useRef, useState, type ComponentProps, type CSSProperties } from "react";
import { createPortal } from "react-dom";
import { DropdownMenu } from "./DropdownMenu";

type SelectProps = Omit<ComponentProps<"select">, "multiple" | "size" | "ref"> & {
  density?: "regular" | "compact";
};
type Option = { value: string; label: string; disabled: boolean; group?: string };
type Popup = { options: Option[]; label: string; style: CSSProperties };
const VIEWPORT_MARGIN = 8;
const MENU_GAP = 6;
const MENU_MAX_HEIGHT = 280;
const MENU_MIN_WIDTH = 160;
const TYPEAHEAD_TIMEOUT_MS = 600;

function readOptions(select: HTMLSelectElement): Option[] {
  return [...select.options].map((option) => {
    const group = option.parentElement instanceof HTMLOptGroupElement ? option.parentElement : null;
    return { value: option.value, label: option.label, disabled: option.disabled || !!group?.disabled, group: group?.label };
  });
}

function readLabel(select: HTMLSelectElement): string {
  const explicitLabel = select.getAttribute("aria-label");
  if (explicitLabel) return explicitLabel;
  const labelledBy = select.getAttribute("aria-labelledby");
  if (labelledBy) return labelledBy.split(/\s+/).map((id) => document.getElementById(id)?.textContent ?? "").join(" ");
  return [...(select.labels ?? [])].map((label) => {
    const copy = label.cloneNode(true) as HTMLElement;
    copy.querySelectorAll("select, .ui-select, input, textarea, small").forEach((element) => element.remove());
    return copy.textContent?.trim() ?? "";
  }).join(" ") || "选项";
}

/** Keeps native labels, form submission, validation and change events. The visible
 * option panel is shared across platforms and portaled out of clipped/zoomed canvas nodes. */
export function Select({ children, className = "", density = "regular", onKeyDown,
  onMouseDown, onBlur, onChange, ...props }: SelectProps) {
  const id = useId();
  const control = useRef<HTMLSelectElement>(null);
  const menu = useRef<HTMLDivElement>(null);
  const search = useRef({ text: "", at: 0 });
  const [popup, setPopup] = useState<Popup | null>(null);
  const [active, setActive] = useState(0);

  function open() {
    const select = control.current;
    // :disabled includes a disabled parent fieldset.
    if (!select || select.matches(":disabled")) return;
    const options = readOptions(select);
    const rect = select.getBoundingClientRect();
    const below = window.innerHeight - rect.bottom - VIEWPORT_MARGIN - MENU_GAP;
    const above = rect.top - VIEWPORT_MARGIN - MENU_GAP;
    const upward = below < MENU_MAX_HEIGHT && above > below;
    const maxHeight = Math.max(0, Math.min(MENU_MAX_HEIGHT, upward ? above : below));
    const width = Math.min(Math.max(rect.width, MENU_MIN_WIDTH), window.innerWidth - VIEWPORT_MARGIN * 2);
    const selected = options.findIndex((option) => option.value === select.value && !option.disabled);
    setActive(selected >= 0 ? selected : Math.max(0, options.findIndex((option) => !option.disabled)));
    search.current = { text: "", at: 0 };
    setPopup({ options, label: readLabel(select), style: { position: "fixed", width, maxHeight,
      left: Math.max(VIEWPORT_MARGIN, Math.min(rect.left, window.innerWidth - width - VIEWPORT_MARGIN)),
      ...(upward ? { bottom: window.innerHeight - rect.top + MENU_GAP } : { top: rect.bottom + MENU_GAP }) } });
  }

  function choose(index: number) {
    const option = popup?.options[index];
    const select = control.current;
    if (!option || option.disabled || !select || select.matches(":disabled")) return;
    const currentOption = [...select.options].find((candidate) => candidate.value === option.value);
    if (!currentOption || currentOption.disabled || (currentOption.parentElement instanceof HTMLOptGroupElement && currentOption.parentElement.disabled)) return;
    select.value = option.value;
    select.dispatchEvent(new Event("change", { bubbles: true }));
    setPopup(null);
    select.focus();
  }

  useEffect(() => {
    if (!popup) return;
    const select = control.current;
    // A background capability refresh must not leave stale or newly disabled options clickable.
    const observer = new MutationObserver(() => {
      if (!select || select.matches(":disabled")) { setPopup(null); return; }
      const options = readOptions(select);
      const activeValue = popup.options[active]?.value;
      const next = options.findIndex((option) => option.value === activeValue && !option.disabled);
      setActive(next >= 0 ? next : Math.max(0, options.findIndex((option) => !option.disabled)));
      setPopup((current) => current ? { ...current, options } : null);
    });
    if (select) observer.observe(select, { childList: true, subtree: true, attributes: true,
      attributeFilter: ["disabled", "label", "value"], characterData: true });
    function dismiss(event: Event) {
      if (event.target instanceof Node && (menu.current?.contains(event.target) || control.current?.contains(event.target))) return;
      setPopup(null);
    }
    const close = () => setPopup(null);
    document.addEventListener("pointerdown", dismiss, true);
    window.addEventListener("scroll", dismiss, true);
    window.addEventListener("resize", close);
    return () => {
      observer.disconnect();
      document.removeEventListener("pointerdown", dismiss, true);
      window.removeEventListener("scroll", dismiss, true);
      window.removeEventListener("resize", close);
    };
  }, [popup, active]);

  useEffect(() => { menu.current?.querySelector(`[data-index='${active}']`)?.scrollIntoView?.({ block: "nearest" }); }, [active, popup]);

  return <span className={`ui-select ui-select--${density} nodrag nowheel nopan`}>
    <select {...props} ref={control} className={`ui-select-control ${className}`}
      aria-expanded={!!popup} aria-controls={popup ? `${id}-options` : undefined}
      aria-activedescendant={popup ? `${id}-option-${active}` : undefined}
      onChange={(event) => { setPopup(null); onChange?.(event); }}
      onMouseDown={(event) => {
        onMouseDown?.(event);
        if (event.defaultPrevented || event.button !== 0) return;
        event.preventDefault(); event.stopPropagation();
        control.current?.focus();
        if (popup) setPopup(null); else open();
      }} onBlur={(event) => { onBlur?.(event); setPopup(null); }}
      onKeyDown={(event) => {
        onKeyDown?.(event);
        if (event.defaultPrevented) return;
        if (event.key === "Escape" && popup) {
          event.preventDefault(); event.stopPropagation(); setPopup(null); return;
        }
        if (event.key === "Tab") { setPopup(null); return; }
        if (["ArrowDown", "ArrowUp", "Home", "End", "Enter", " "].includes(event.key)) {
          event.preventDefault(); event.stopPropagation();
          if (!popup) { open(); return; }
          if (event.key === "Enter" || event.key === " ") { choose(active); return; }
          const enabled = popup.options.map((option, index) => ({ option, index })).filter(({ option }) => !option.disabled);
          if (!enabled.length) return;
          const current = enabled.findIndex(({ index }) => index === active);
          const next = event.key === "Home" ? 0 : event.key === "End" ? enabled.length - 1
            : current < 0 ? (event.key === "ArrowDown" ? 0 : enabled.length - 1)
              : (current + (event.key === "ArrowDown" ? 1 : enabled.length - 1)) % enabled.length;
          setActive(enabled[next]?.index ?? active);
        } else if (popup && event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
          event.preventDefault(); event.stopPropagation();
          const now = Date.now();
          search.current = { text: (now - search.current.at < TYPEAHEAD_TIMEOUT_MS ? search.current.text : "") + event.key, at: now };
          const match = popup.options.findIndex((option) => !option.disabled && option.label.toLocaleLowerCase().startsWith(search.current.text.toLocaleLowerCase()));
          if (match >= 0) setActive(match);
        }
      }}>{children}</select>
    <CaretDown className="ui-select-caret" size={14} aria-hidden />
    {popup ? createPortal(<DropdownMenu ref={menu} id={`${id}-options`} role="listbox"
      aria-label={popup.label}
      className="ui-select-options" style={popup.style}
      onPointerDown={(event) => { event.preventDefault(); event.stopPropagation(); }}
      onMouseDown={(event) => event.preventDefault()}>
      {popup.options.map((option, index) => <div key={`${option.value}-${index}`}>
        {option.group && option.group !== popup.options[index - 1]?.group ? <p className="ui-dropdown-heading">{option.group}</p> : null}
        <button type="button" role="option" tabIndex={-1} id={`${id}-option-${index}`} data-index={index}
          aria-selected={option.value === control.current?.value} disabled={option.disabled}
          data-active={index === active} onPointerMove={() => { if (!option.disabled) setActive(index); }}
          onClick={(event) => { event.stopPropagation(); choose(index); }}>
          <span>{option.label}</span>{option.value === control.current?.value ? <Check size={14} aria-hidden /> : null}
        </button>
      </div>)}
      {!popup.options.length ? <p className="ui-dropdown-heading">暂无可选项</p> : null}
    </DropdownMenu>, control.current?.closest("dialog") ?? document.body) : null}
  </span>;
}
