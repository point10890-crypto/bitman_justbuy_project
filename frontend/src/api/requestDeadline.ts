/** Bound both the request and response-body reading on unreliable mobile links. */
export async function withRequestDeadline<T>(
  operation: (signal: AbortSignal) => Promise<T>,
  timeoutMs: number,
  timeoutMessage: string,
  parent?: AbortSignal | null,
): Promise<T> {
  if (parent?.aborted) throw new DOMException('Request cancelled', 'AbortError')
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  let cancel = () => {}
  const deadline = new Promise<never>((_, reject) => {
    cancel = () => {
      reject(new DOMException('Request cancelled', 'AbortError'))
      controller.abort()
    }
    parent?.addEventListener('abort', cancel, { once: true })
    timer = setTimeout(() => {
      reject(new Error(timeoutMessage))
      controller.abort()
    }, timeoutMs)
  })
  try {
    return await Promise.race([operation(controller.signal), deadline])
  } finally {
    clearTimeout(timer)
    parent?.removeEventListener('abort', cancel)
  }
}
