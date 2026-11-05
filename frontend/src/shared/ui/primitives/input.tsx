import { cn } from "cn"
import * as React from "react"

type InputProps = React.ComponentProps<"input"> & {
  density?: "default" | "compact"
  variant?: "default" | "ghost"
}

/** Density is explicit so fields do not change appearance with the enclosing page. */
function Input({ className, type, density = "default", variant = "default", ...props }: InputProps) {
  return (
    <input
      type={type}
      data-slot="input"
      data-density={density}
      data-variant={variant}
      className={cn(
        "w-full min-w-0 ui-focusable",
        className
      )}
      {...props}
    />
  )
}

export { Input }
