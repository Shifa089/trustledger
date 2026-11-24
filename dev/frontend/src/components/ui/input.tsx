import type { ComponentProps } from 'react'
import { cn } from '@/lib/utils'

// shadcn/ui Input (new-york style), plus a native <select> styled the same way. Native controls keep keyboard
// use and screen-reader behaviour for free.
const fieldClass =
  'flex h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-xs transition-[color,box-shadow] outline-none placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive aria-invalid:ring-destructive/20 md:text-sm'

function Input({ className, type, ...props }: ComponentProps<'input'>) {
  return <input type={type} data-slot="input" className={cn(fieldClass, className)} {...props} />
}

function Select({ className, ...props }: ComponentProps<'select'>) {
  return <select data-slot="select" className={cn(fieldClass, 'pr-8', className)} {...props} />
}

function Textarea({ className, ...props }: ComponentProps<'textarea'>) {
  return <textarea data-slot="textarea" className={cn(fieldClass, 'h-auto min-h-20 py-2', className)} {...props} />
}

export { Input, Select, Textarea }
