import { cn } from "cn";
import { Children,isValidElement,useId,useLayoutEffect,useRef,useState,type ComponentProps,type ReactNode } from "react";
import { createPortal } from "react-dom";
import { t,useLocale } from "../i18n";
import "./Dropdown.css";
import { SelectContent,SelectGroup,SelectItem,SelectLabel,Select as SelectRoot,SelectTrigger,SelectValue } from "./primitives/select";

type SelectProps = Omit<ComponentProps<"select">, "multiple" | "size" | "ref"> & { density?: "regular" | "compact" };
type Option = { value: string; label: ReactNode; disabled: boolean; group?: string };
// Prefix every UI value: Radix reserves the empty string, while filters use it as a real option.
const OPTION_PREFIX = "option:";
const MENU_GAP = 6;
const VIEWPORT_MARGIN = 8;

function optionsFrom(children: ReactNode, group?: string, disabled = false): Option[] {
  return Children.toArray(children).flatMap((child): Option[] => {
    if (!isValidElement<{ children?: ReactNode; value?: string | number; label?: string; disabled?: boolean }>(child)) return [];
    if (child.type === "option") return [{ value: String(child.props.value ?? child.props.children ?? ""),
      label: child.props.label ?? child.props.children, disabled: disabled || !!child.props.disabled, group }];
    return optionsFrom(child.props.children, child.type === "optgroup" ? child.props.label : group,
      disabled || !!child.props.disabled);
  });
}

/** shadcn owns popup positioning, typeahead and focus. A hidden native control
 * preserves browser form validation/submission and the application's change events. */
export function Select({ children, className, density = "regular", onChange, onBlur, onKeyDown, onMouseDown, ...props }: SelectProps) {
  useLocale();
  const generatedId = useId();
  const native = useRef<HTMLSelectElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const tabFocus = useRef<HTMLElement | null>(null);
  const options = optionsFrom(children);
  const [open, setOpen] = useState(false);
  const [uncontrolled, setUncontrolled] = useState(String(props.defaultValue ?? options[0]?.value ?? ""));
  const value = String(props.value ?? uncontrolled);
  const [container, setContainer] = useState<HTMLElement | null>(null);
  const [inheritedDisabled, setInheritedDisabled] = useState(false);
  const [label, setLabel] = useState(props["aria-label"]);
  useLayoutEffect(() => {
    const element = trigger.current;
    if (element) { setContainer(element.closest("form") ?? document.body); setInheritedDisabled(!!element.closest("fieldset[disabled]")); }
    if (!element || props["aria-label"] || props["aria-labelledby"]) return;
    const text = [...(element.labels ?? [])].map((item) => {
      const copy = item.cloneNode(true) as HTMLElement;
      copy.querySelectorAll(".ui-select, input, textarea, small").forEach((control) => control.remove());
      return copy.textContent?.trim() ?? "";
    }).join(" ");
    setLabel(text || t("选项"));
  });
  const groupNames = [...new Set(options.map((option) => option.group))];
  return <span className={cn("ui-select", density === "compact" && "ui-select--compact", className)}>
    {container ? createPortal(<select {...props} id={`${generatedId}-native`} ref={native} aria-hidden="true" aria-label={undefined} aria-labelledby={undefined} disabled={props.disabled || inheritedDisabled} tabIndex={-1}
      className="ui-select-native" onChange={(event) => { setUncontrolled(event.target.value); onChange?.(event); }}
      onBlur={onBlur} onKeyDown={onKeyDown} onMouseDown={onMouseDown}
      onInvalid={(event) => { event.preventDefault(); props.onInvalid?.(event); trigger.current?.focus(); }}>{children}</select>, container) : null}
    <SelectRoot open={open} onOpenChange={setOpen} value={OPTION_PREFIX + value} disabled={props.disabled || inheritedDisabled} onValueChange={(encoded) => {
      const element = native.current;
      const next = encoded.slice(OPTION_PREFIX.length);
      if (!element || element.matches(":disabled") || !options.some((option) => option.value === next && !option.disabled)) return;
      element.value = next;
      element.dispatchEvent(new Event("change", { bubbles: true }));
    }}>
      <SelectTrigger ref={trigger} id={props.id} value={value} aria-label={props["aria-label"] ?? label}
        aria-labelledby={props["aria-labelledby"]} aria-describedby={props["aria-describedby"]}
        aria-invalid={props["aria-invalid"]} aria-required={props.required}
        size={density === "compact" ? "sm" : "default"} className="w-full" disabled={props.disabled || inheritedDisabled}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent position="popper" sideOffset={MENU_GAP} collisionPadding={VIEWPORT_MARGIN}
        className="nodrag nowheel nopan" aria-label={props["aria-label"] ?? label}
        onCloseAutoFocus={(event) => {
          // Continue Tab traversal after Radix unmounts its focus scope, avoiding a
          // race with the library restoring focus to the trigger.
          const next = tabFocus.current;
          tabFocus.current = null;
          if (next?.isConnected) { event.preventDefault(); next.focus(); }
        }}
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key !== "Tab") return;
          // Keep the application's form traversal policy: Tab dismisses a select.
          event.preventDefault();
          const scope = trigger.current?.closest("[role=dialog]") ?? document;
          const controls = [...scope.querySelectorAll<HTMLElement>("button, input, select, textarea, a[href], [tabindex]")]
            .filter((control) => control.tabIndex >= 0 && !control.matches(":disabled") && !control.closest("[hidden]"));
          const index = controls.findIndex((control) => control === trigger.current);
          const next = controls[index + (event.shiftKey ? -1 : 1)];
          tabFocus.current = next ?? null;
          setOpen(false);
        }}>
        {groupNames.map((group) => <SelectGroup key={group ?? generatedId}>
          {group ? <SelectLabel>{group}</SelectLabel> : null}
          {options.filter((option) => option.group === group).map((option) => <SelectItem key={option.value}
            value={OPTION_PREFIX + option.value} data-value={option.value} disabled={option.disabled}>{option.label}</SelectItem>)}
        </SelectGroup>)}
      </SelectContent>
    </SelectRoot>
  </span>;
}
