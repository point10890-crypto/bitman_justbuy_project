/** BitMan Service Worker - freshness-first app shell */

const CACHE_NAME = 'bitman-v20261128-keyword-recovery-r31'
const SHELL_ASSETS = [
  '/manifest.json',
  '/install.html',
  '/install.js',
]

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME)
    const shell = await fetch('/index.html', { cache: 'no-store' })
    if (!shell.ok) throw new Error('App shell unavailable')
    const html = await shell.clone().text()
    const assets = [...html.matchAll(/(?:src|href)=["'](\/assets\/[^"']+)["']/g)].map(match => match[1])
    await cache.addAll([...SHELL_ASSETS, ...new Set(assets)])
    await cache.put('/index.html', shell)
  })())
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter(key => key.startsWith('bitman-') && key !== CACHE_NAME).map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  )
})

async function networkFirst(request, fallbackKey = request) {
  const cache = await caches.open(CACHE_NAME)
  try {
    const fetched = await fetch(request, { cache: 'no-store', redirect: 'follow' })
    // Pages redirects .html to extensionless URLs. Navigation responses must not
    // carry redirected=true back to a request whose redirect mode is manual.
    const response = fetched.redirected
      ? new Response(fetched.body, { status: fetched.status, statusText: fetched.statusText, headers: fetched.headers })
      : fetched
    if (response.ok) {
      try { await cache.put(fallbackKey, response.clone()) } catch { /* Full/unavailable cache must not block an online response. */ }
      return response
    }
    return (await cache.match(fallbackKey)) || response
  } catch {
    return (await cache.match(fallbackKey)) || Response.error()
  }
}

self.addEventListener('fetch', (event) => {
  const { request } = event
  const url = new URL(request.url)

  if (request.method !== 'GET') return
  if (url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/')) return

  // The independent installer must never replace the React app's offline shell.
  if (url.pathname === '/install' || url.pathname === '/install.html' || url.pathname === '/install.js') {
    event.respondWith(networkFirst(request, url.pathname === '/install' ? '/install.html' : url.pathname))
    return
  }

  if (request.destination === 'document' || request.mode === 'navigate') {
    event.respondWith(networkFirst(request, '/index.html'))
    return
  }

  if (
    url.pathname === '/index.html' ||
    request.destination === 'script' ||
    request.destination === 'style'
  ) {
    event.respondWith(networkFirst(request))
    return
  }

  if (url.pathname === '/sw.js') return

  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached
      return fetch(request).then((response) => {
        if (response.ok) {
          const clone = response.clone()
          caches.open(CACHE_NAME).then((cache) => cache.put(request, clone))
        }
        return response
      })
    })
  )
})
