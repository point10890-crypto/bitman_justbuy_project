function resolveDefaultApiBase() {
  if (typeof window === 'undefined') return ''

  const host = window.location.hostname
  const isLocal = host === 'localhost' || host === '127.0.0.1'
  const isApiHost = host === 'api.bit-man.net'

  return isLocal || isApiHost ? '' : 'https://api.bit-man.net'
}

export const API_BASE = import.meta.env.VITE_API_BASE_URL || resolveDefaultApiBase()
