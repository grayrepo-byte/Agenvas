import { cn } from "cn";
import { useCallback, useLayoutEffect, useRef, type ComponentProps } from "react";
import { Input } from "./primitives/input";
import "./Range.css";

type RangeProps = Omit<ComponentProps<typeof Input>, "type"> & {
  variant?: "parameter" | "media" | "temperature" | "waveform";
};

function syncProgress(element: HTMLInputElement) {
  const min = element.min === "" ? 0 : Number(element.min);
  const max = element.max === "" ? 100 : Number(element.max);
  const progress = max > min ? Math.max(0, Math.min(1, (element.valueAsNumber - min) / (max - min))) : 0;
  element.style.setProperty("--ui-range-progress", `${Number.isFinite(progress) ? progress * 100 : 0}%`);
}

/** Animation clocks can update an uncontrolled range without rerendering their canvas.
 * Read back the browser's sanitized value so the fill and native thumb stay aligned. */
export function setRangeValue(element: HTMLInputElement, value: number) {
  element.value = String(value);
  syncProgress(element);
}

/** Single-thumb extension of shadcn Input. Native range semantics preserve keyboard,
 * form, pointer and media-clock behavior; all visual variants belong to this layer. */
export function Range({ variant = "parameter", className, ref, onChange, onInput, ...props }: RangeProps) {
  const input = useRef<HTMLInputElement>(null);
  const bind = useCallback((element: HTMLInputElement | null) => {
    input.current = element;
    if (typeof ref === "function") return ref(element);
    if (ref) ref.current = element;
  }, [ref]);
  useLayoutEffect(() => { if (input.current) syncProgress(input.current); });
  useLayoutEffect(() => {
    const element = input.current;
    const form = element?.form;
    if (!element || !form) return;
    let attached = true;
    const reset = () => queueMicrotask(() => { if (attached) syncProgress(element); });
    form.addEventListener("reset", reset);
    return () => { attached = false; form.removeEventListener("reset", reset); };
  }, []);
  return <Input {...props} type="range" ref={bind} data-slot="range" data-variant={variant}
    className={cn("ui-range", className)} onInput={(event) => { syncProgress(event.currentTarget); onInput?.(event); }}
    onChange={(event) => { syncProgress(event.currentTarget); onChange?.(event); }} />;
}
