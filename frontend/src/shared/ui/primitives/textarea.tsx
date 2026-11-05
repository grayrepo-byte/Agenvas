import { cn } from "cn"
import * as React from "react"

function Textarea({ className, variant = "default", ...props }: React.ComponentProps<"textarea"> & {
  variant?: "default" | "ghost"
}) {
  return (
    <textarea
      data-slot="textarea"
      data-variant={variant}
      className={cn(
        "ui-multiline flex field-sizing-content min-h-16 w-full ui-focusable",
        className
      )}
      {...props}
    />
  )
}

export { Textarea }
