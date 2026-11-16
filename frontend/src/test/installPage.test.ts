// @vitest-environment jsdom
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { resolve } from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'
import { waitFor } from '@testing-library/dom'

const html = readFileSync(resolve('public/install.html'), 'utf8')
const script = readFileSync(resolve('public/install.js'), 'utf8')
const status = () => document.getElementById('install-status')!.textContent
function setup(healthy = true) {
  document.body.innerHTML = new DOMParser().parseFromString(html, 'text/html').body.innerHTML
  const events: Record<string, (event: any) => void> = {}
  const register = vi.fn().mockResolvedValue({ update: vi.fn().mockResolvedValue(undefined) })
  const clipboard = vi.fn().mockResolvedValue(undefined)
  runInNewContext(script, {
    document, location: { origin: 'https://bitman-justbuy.pages.dev', host: 'bitman-justbuy.pages.dev' },
    navigator: { userAgent: 'Android Chrome', serviceWorker: { register }, clipboard: { writeText: clipboard } },
    window: { matchMedia: () => ({ matches: false }), addEventListener: (name: string, handler: (event: any) => void) => { events[name] = handler } },
    URL, AbortController, setTimeout, clearTimeout,
    fetch: vi.fn().mockResolvedValue({ ok: healthy, json: async () => ({ status: healthy ? 'ok' : 'error' }) }),
  })
  return { events, register, clipboard, button: document.getElementById('install-button') as HTMLButtonElement }
}
afterEach(() => { document.body.innerHTML = '' })

it('offers Android browser instructions and a public launch link without login or an install prompt', async () => {
  const app = setup()
  expect(app.button.hidden).toBe(true)
  expect(document.getElementById('chrome-link')!.getAttribute('href')).toContain('package=com.android.chrome')
  expect(document.querySelector('a[href="/"]')).not.toBeNull()
  expect(app.register).toHaveBeenCalledWith('/sw.js')
  await waitFor(() => expect(document.getElementById('service-status')!.textContent).toContain('서버 연결 정상'))
})

it('opens the browser prompt on click and reports installation only after appinstalled', async () => {
  const app = setup()
  const prompt = vi.fn().mockResolvedValue(undefined)
  const event = { preventDefault: vi.fn(), prompt, userChoice: Promise.resolve({ outcome: 'accepted' }) }
  app.events.beforeinstallprompt(event)
  expect(app.button.hidden).toBe(false)
  app.button.click()
  await waitFor(() => expect(status()).toContain('설치 요청을 보냈습니다'))
  expect(prompt).toHaveBeenCalledTimes(1)
  app.events.appinstalled({})
  expect(status()).toContain('설치된 앱입니다')
  expect(app.button.hidden).toBe(true)
})

it('keeps manual installation instructions when the browser rejects the prompt', async () => {
  const app = setup()
  app.events.beforeinstallprompt({ preventDefault: vi.fn(), prompt: vi.fn().mockRejectedValue(new Error('Unavailable')) })
  app.button.click()
  await waitFor(() => expect(status()).toContain('Chrome 메뉴에서 홈 화면에 추가'))
  expect(status()).not.toContain('설치된 앱입니다')
})

it('reports failed health checks without claiming the app is available', async () => {
  setup(false)
  await waitFor(() => expect(document.getElementById('service-status')!.textContent).toContain('확인하지 못했습니다'))
})
