/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { describe, expect, it } from 'vitest'

const source = readFileSync(new URL('../../public/sw.js', import.meta.url), 'utf8')
const origin = 'https://bitman-justbuy.pages.dev'
const html = '<link rel="stylesheet" href="/assets/current.css"><script src="/assets/current.js"></script><main>Current app shell</main>'

function worker() {
  const listeners: Record<string, (event: any) => void> = {}
  const stores = new Map<string, Map<string, Response>>()
  let offline = false
  let documentBody = html
  let navigations = 0
  const key = (value: string | { url: string }) => new URL(typeof value === 'string' ? value : value.url, origin).pathname
  const network = async (input: string | { url: string }) => {
    if (offline) throw new TypeError('Network unavailable')
    const path = key(input)
    return new Response(path === '/assets/current.js' ? 'current app code' : path === '/assets/current.css' ? 'current styles' : path === '/manifest.json' ? '{"name":"BitMan"}' : documentBody)
  }
  const caches = {
    keys: async () => [...stores.keys()],
    delete: async (name: string) => stores.delete(name),
    match: async (input: string | { url: string }) => {
      for (const entries of stores.values()) if (entries.has(key(input))) return entries.get(key(input))!.clone()
      return undefined
    },
    open: async (name: string) => {
      if (!stores.has(name)) stores.set(name, new Map())
      const entries = stores.get(name)!
      return {
        addAll: async (inputs: string[]) => { for (const input of inputs) entries.set(key(input), await network(input)) },
        put: async (input: string | { url: string }, response: Response) => { entries.set(key(input), response.clone()) },
        match: async (input: string | { url: string }) => entries.get(key(input))?.clone(),
      }
    },
  }
  runInNewContext(source, { URL, Request, Response, fetch: network, caches, self: {
    location: { origin }, skipWaiting: () => {}, addEventListener: (name: string, handler: (event: any) => void) => { listeners[name] = handler },
    clients: { claim: async () => {}, matchAll: async () => [{ url: `${origin}/subscribe`, navigate: () => { navigations++ } }] },
  } })
  return {
    stores,
    setOffline: () => { offline = true },
    setHtml: (value: string) => { documentBody = value },
    navigationCount: () => navigations,
    lifecycle: async (name: 'install' | 'activate') => {
      let pending: Promise<unknown> | undefined
      listeners[name]({ waitUntil: (value: Promise<unknown>) => { pending = value } })
      await pending
    },
    request: async (path: string, options: { method?: string; mode?: string; destination?: string } = {}) => {
      let response: Promise<Response> | undefined
      listeners.fetch({ request: { url: new URL(path, origin).href, method: 'GET', mode: 'navigate', destination: 'document', ...options }, respondWith: (value: Promise<Response>) => { response = value } })
      return response ? await response : undefined
    },
  }
}

describe('service worker app-shell recovery', () => {
  it('keeps the current installed shell and its script/styles usable without a network', async () => {
    const app = worker()
    await app.lifecycle('install')
    await app.lifecycle('activate')
    app.setOffline()

    expect(await (await app.request('/subscribe?plan=SIX_MONTHS&renewal=1'))!.text()).toBe(html)
    expect(await (await app.request('/assets/current.js', { mode: 'cors', destination: 'script' }))!.text()).toBe('current app code')
    expect(await (await app.request('/assets/current.css', { mode: 'cors', destination: 'style' }))!.text()).toBe('current styles')
  })

  it('removes old BitMan caches without deleting the new cache or reloading an open form', async () => {
    const app = worker()
    app.stores.set('bitman-old', new Map())
    app.stores.set('unrelated-cache', new Map())
    await app.lifecycle('install')
    await app.lifecycle('activate')

    expect(app.stores.has('bitman-old')).toBe(false)
    expect(app.stores.has('unrelated-cache')).toBe(true)
    expect([...app.stores.values()].some(entries => entries.has('/index.html'))).toBe(true)
    expect(app.navigationCount()).toBe(0)
  })

  it('prefers the online page and retains it as the next offline fallback', async () => {
    const app = worker()
    await app.lifecycle('install')
    app.setHtml('<main>Updated app shell</main>')
    expect(await (await app.request('/my'))!.text()).toBe('<main>Updated app shell</main>')
    app.setOffline()
    expect(await (await app.request('/my'))!.text()).toBe('<main>Updated app shell</main>')
  })

  it('does not cache authenticated API responses or intercept mutations and other origins', async () => {
    const app = worker()
    expect(await app.request('/api/auth/me')).toBeUndefined()
    expect(await app.request('/subscribe', { method: 'POST' })).toBeUndefined()
    expect(await app.request('https://api.bit-man.net/api/subscription/plans')).toBeUndefined()
    expect(app.stores.size).toBe(0)
  })
})
