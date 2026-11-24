import type { ReactNode } from 'react'

export function FullPageMessage({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <main className="mx-auto flex min-h-[60vh] max-w-md flex-col items-center justify-center gap-4 p-6 text-center">
      <h1 className="text-xl font-semibold">{title}</h1>
      {children}
    </main>
  )
}
