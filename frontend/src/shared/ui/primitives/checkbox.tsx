import { Check as CheckIcon, Minus as MinusIcon } from "@/shared/ui/icons"
import { cn } from "cn"
import { Checkbox as CheckboxPrimitive } from "radix-ui"
import * as React from "react"

function Checkbox({
  className,
  checked,
  ...props
}: React.ComponentProps<typeof CheckboxPrimitive.Root>) {
  return (
    <CheckboxPrimitive.Root
      data-slot="checkbox"
      checked={checked}
      className={cn(
        "peer shrink-0 ui-focusable",
        className
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator
        data-slot="checkbox-indicator"
        className="grid place-content-center text-current transition-none"
      >
        <CheckIcon data-check-icon />
        <MinusIcon data-mixed-icon />
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  )
}

export { Checkbox }
