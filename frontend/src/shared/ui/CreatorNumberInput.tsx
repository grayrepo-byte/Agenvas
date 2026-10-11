import { useEffect, useRef, type ComponentProps } from "react";
import { Input } from "./primitives/input";

/** Keep the browser's intermediate decimal/empty text while a scalar draft value changes. */
export function CreatorNumberInput({ value, onValueChange, onBlur, ...props }: Omit<ComponentProps<typeof Input>,
  "type" | "value" | "defaultValue" | "onChange" | "ref"> & {
  value: number | undefined; onValueChange: (value: number | undefined) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (input.current && document.activeElement !== input.current) input.current.value = value === undefined ? "" : String(value);
  }, [value]);
  return <Input {...props} ref={input} type="number" defaultValue={value ?? ""}
    onChange={(event) => onValueChange(event.target.value === "" ? undefined : Number(event.target.value))}
    onBlur={(event) => {
      event.target.value = value === undefined ? "" : String(value);
      onBlur?.(event);
    }} />;
}
