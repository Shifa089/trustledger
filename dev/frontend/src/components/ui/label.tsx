import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

// shadcn/ui Label (new-york style) on a native <label>: no Radix dependency needed for htmlFor.
function Label({ className, ...props }: ComponentProps<'label'>) {
  return (
    <label
      data-slot="label"
      className={cn('flex items-center gap-2 text-sm leading-none font-medium select-none', className)}
      {...props}
    />
  )
}

export { Label }
