import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import type { Transaction } from '@/api/types'
import { fakeAuth, renderRoutes } from '@/test/render'
import { CORE, problem, server } from '@/test/server'
import { TransactionHistory } from './TransactionHistory'

function tx(n: number): Transaction {
  return {
    id: `t${n}`,
    type: n % 2 === 0 ? 'TOPUP' : 'TRANSFER_OUT',
    status: n % 2 === 0 ? 'CAPTURED' : 'COMPLETED',
    amount: `${n}00.00`,
    currency: 'INR',
    counterpartyWalletId: n % 2 === 0 ? null : '00000000-0000-7000-8000-00000000d00d',
    note: null,
    createdAt: '2026-11-20T05:00:00Z',
  }
}

/** 45 transactions: 3 pages of 20. Records each request's query string. */
function serve(total = 45) {
  const requests: URLSearchParams[] = []
  server.use(
    http.get(`${CORE}/wallets/me/transactions`, ({ request }) => {
      const search = new URL(request.url).searchParams
      requests.push(search)
      const pageNo = Number(search.get('page') ?? '0')
      const size = Number(search.get('size') ?? '20')
      const items = Array.from({ length: Math.max(0, Math.min(size, total - pageNo * size)) }, (_, i) =>
        tx(pageNo * size + i + 1),
      )
      return HttpResponse.json({
        items,
        page: pageNo,
        size,
        totalElements: total,
        totalPages: Math.ceil(total / size),
      })
    }),
  )
  return { requests, last: () => requests.at(-1) }
}

function render(path = '/customer/history') {
  return renderRoutes([{ path: '/customer/history', element: <TransactionHistory /> }], {
    path,
    auth: fakeAuth(['CUSTOMER']).auth,
  })
}

describe('TransactionHistory', () => {
  it('asks for the first page, newest first, without filters', async () => {
    const { last } = serve()
    render()

    expect(await screen.findByText('Page 1 of 3 · 45 transactions')).toBeInTheDocument()
    expect(last()?.toString()).toBe('page=0&size=20&sort=createdAt%2Cdesc')
  })

  it('reads the filters, sort and page from the URL', async () => {
    const { last } = serve()
    render('/customer/history?from=2026-11-01&to=2026-11-24&type=TOPUP&sort=amount%2Casc&page=2')

    expect(await screen.findByText(/Page 2 of 3/)).toBeInTheDocument()
    const query = last()
    expect(query?.get('from')).toBe('2026-11-01')
    expect(query?.get('to')).toBe('2026-11-24')
    expect(query?.get('type')).toBe('TOPUP')
    expect(query?.get('sort')).toBe('amount,asc')
    expect(query?.get('page')).toBe('1')
    expect(screen.getByLabelText('Type')).toHaveValue('TOPUP')
    expect(screen.getByLabelText('From')).toHaveValue('2026-11-01')
  })

  it('ignores bad URL values instead of sending them', async () => {
    const { last } = serve()
    render('/customer/history?from=yesterday&type=REFUND&sort=id&page=-3')

    await screen.findByText(/Page 1 of 3/)
    expect(last()?.toString()).toBe('page=0&size=20&sort=createdAt%2Cdesc')
  })

  it('changing the type filter changes the request and the URL, back to page 1', async () => {
    const { last } = serve()
    const user = userEvent.setup()
    const { router } = render('/customer/history?page=2')
    await screen.findByText(/Page 2 of 3/)

    await user.selectOptions(screen.getByLabelText('Type'), 'TRANSFER_OUT')

    await waitFor(() => {
      expect(last()?.get('type')).toBe('TRANSFER_OUT')
    })
    expect(last()?.get('page')).toBe('0')
    expect(router.state.location.search).toBe('?type=TRANSFER_OUT')
  })

  it('changing the date range changes the request and the URL', async () => {
    const { last } = serve()
    const { router } = render()
    await screen.findByText(/Page 1 of 3/)

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-01' } })

    await waitFor(() => {
      expect(last()?.get('from')).toBe('2026-11-01')
    })
    expect(router.state.location.search).toBe('?from=2026-11-01')
  })

  it('refuses a range that ends before it starts, without asking the server', async () => {
    const { requests } = serve()
    render('/customer/history?from=2026-11-20&to=2026-11-01')

    expect(await screen.findByText('"From" must not be after "To".')).toBeInTheDocument()
    expect(requests).toHaveLength(0)
  })

  it('Next and Previous page through the server pages', async () => {
    const { last } = serve()
    const user = userEvent.setup()
    const { router } = render()
    await screen.findByText(/Page 1 of 3/)
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText(/Page 2 of 3/)).toBeInTheDocument()
    expect(last()?.get('page')).toBe('1')
    expect(router.state.location.search).toBe('?page=2')

    await user.click(screen.getByRole('button', { name: 'Next' }))
    expect(await screen.findByText(/Page 3 of 3/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Previous' }))
    expect(await screen.findByText(/Page 2 of 3/)).toBeInTheDocument()
    expect(last()?.get('page')).toBe('1')
  })

  it('sorting by a column changes the server sort; a second click reverses it', async () => {
    const { last } = serve()
    const user = userEvent.setup()
    render('/customer/history?page=2')
    await screen.findByText(/Page 2 of 3/)
    const table = screen.getByRole('table', { name: 'Transactions' })
    expect(within(table).getByRole('columnheader', { name: /Date/ })).toHaveAttribute('aria-sort', 'descending')

    await user.click(within(table).getByRole('button', { name: /Amount/ }))
    await waitFor(() => {
      expect(last()?.get('sort')).toBe('amount,desc')
    })
    expect(last()?.get('page')).toBe('0')
    expect(within(table).getByRole('columnheader', { name: /Amount/ })).toHaveAttribute('aria-sort', 'descending')

    await user.click(within(table).getByRole('button', { name: /Amount/ }))
    await waitFor(() => {
      expect(last()?.get('sort')).toBe('amount,asc')
    })
  })

  it('shows rows with signed, exactly formatted amounts', async () => {
    serve(2)
    render()

    const table = await screen.findByRole('table', { name: 'Transactions' })
    const rows = within(table).getAllByRole('row').slice(1)
    expect(rows[0]).toHaveTextContent('Sent')
    expect(rows[0]).toHaveTextContent('-₹100.00')
    expect(rows[1]).toHaveTextContent('Money added')
    expect(rows[1]).toHaveTextContent('₹200.00')
  })

  it('shows a loading state while the first page loads', async () => {
    server.use(
      http.get(`${CORE}/wallets/me/transactions`, async () => {
        await delay('infinite')
        return HttpResponse.json({})
      }),
    )
    render()

    expect(await screen.findByRole('status', { name: 'Loading transactions' })).toBeInTheDocument()
  })

  it('shows an empty state, and a different one when filters match nothing', async () => {
    serve(0)
    const { unmount } = render()
    expect(await screen.findByText('No transactions yet.')).toBeInTheDocument()
    unmount()

    render('/customer/history?type=TOPUP')
    expect(await screen.findByText('No transactions match these filters.')).toBeInTheDocument()
  })

  it('shows an error with its reference and can try again', async () => {
    let fail = true
    server.use(
      http.get(`${CORE}/wallets/me/transactions`, () =>
        fail ? problem(503, 'INTERNAL_ERROR') : HttpResponse.json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      ),
    )
    const user = userEvent.setup()
    render()

    expect(await screen.findByText("Couldn't load your transactions", {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getByText('Reference: corr-test-1')).toBeInTheDocument()
    fail = false
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('No transactions yet.')).toBeInTheDocument()
  })

  it('clears every filter at once', async () => {
    serve()
    const user = userEvent.setup()
    const { router } = render('/customer/history?type=TOPUP&from=2026-11-01')
    await screen.findByText(/Page 1 of 3/)

    await user.click(screen.getByRole('button', { name: 'Clear filters' }))

    expect(router.state.location.search).toBe('')
  })
})
