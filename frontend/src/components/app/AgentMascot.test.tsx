// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { AgentMascot } from './AgentMascot'

// jsdom cannot fetch/decode the real PNG or expose OS media preferences.
// Mock those browser platforms; render and interact with the real component.
function mockMotionPreference(initial: boolean) {
  let reduced = initial
  const events = new EventTarget()
  const media = {
    get matches() { return reduced },
    media: '(prefers-reduced-motion: reduce)',
    onchange: null,
    addEventListener: events.addEventListener.bind(events),
    removeEventListener: events.removeEventListener.bind(events),
    dispatchEvent: events.dispatchEvent.bind(events),
    addListener: (listener: EventListener) => events.addEventListener('change', listener),
    removeListener: (listener: EventListener) => events.removeEventListener('change', listener),
  } as unknown as MediaQueryList
  vi.stubGlobal('matchMedia', vi.fn(() => media))
  return {
    setReduced(next: boolean) {
      reduced = next
      const event = new Event('change')
      Object.defineProperties(event, { matches: { value: reduced }, media: { value: media.media } })
      act(() => { media.dispatchEvent(event) })
    },
  }
}

function renderMascot() {
  const { container } = render(<AgentMascot />)
  return {
    root: container.querySelector<HTMLDivElement>('.bitman-mascot')!,
    image: container.querySelector<HTMLImageElement>('img')!,
    greet: container.querySelector<HTMLButtonElement>('.bitman-mascot-greet')!,
    toggle: container.querySelector<HTMLButtonElement>('.bitman-mascot-motion-toggle')!,
  }
}

describe('AgentMascot browser motion behavior', () => {
  beforeEach(() => {
    vi.spyOn(HTMLImageElement.prototype, 'complete', 'get').mockReturnValue(false)
    vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(0)
    mockMotionPreference(false)
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('starts the initial greeting only after the PNG is ready', () => {
    const { image, greet } = renderMascot()

    expect(greet.classList.contains('is-greeting')).toBe(false)
    fireEvent.load(image)
    expect(greet.classList.contains('is-greeting')).toBe(true)
  })

  it('keeps reduced-motion autoplay static but lets the user explicitly play', () => {
    mockMotionPreference(true)
    const { root, image, greet, toggle } = renderMascot()
    fireEvent.load(image)

    expect(root.dataset.motion).toBe('reduced')
    expect(greet.classList.contains('is-greeting')).toBe(false)
    // Native click respects HTML disabled buttons, unlike synthetic dispatch.
    act(() => toggle.click())
    expect(root.dataset.motion).toBe('running')
    expect(toggle.disabled).toBe(false)
  })

  it('pauses and resumes an image that is already loaded', () => {
    const { root, image, toggle } = renderMascot()
    fireEvent.load(image)
    expect(root.dataset.motion).toBe('running')

    act(() => toggle.click())
    expect(root.dataset.motion).toBe('paused')
    act(() => toggle.click())
    expect(root.dataset.motion).toBe('running')
  })

  it('a new reduced-motion preference cancels autoplay and a manual play override', () => {
    const preference = mockMotionPreference(false)
    const { root, image, greet, toggle } = renderMascot()
    fireEvent.load(image)
    expect(root.dataset.motion).toBe('running')

    preference.setReduced(true)
    expect(root.dataset.motion).toBe('reduced')
    expect(greet.classList.contains('is-greeting')).toBe(false)
    act(() => toggle.click())
    expect(root.dataset.motion).toBe('running')

    preference.setReduced(false)
    preference.setReduced(true)
    expect(root.dataset.motion).toBe('reduced')
    expect(greet.classList.contains('is-greeting')).toBe(false)
  })

  it('starts the greeting when a cached PNG is already decoded on mount', () => {
    vi.spyOn(HTMLImageElement.prototype, 'complete', 'get').mockReturnValue(true)
    vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(1280)
    const { greet } = renderMascot()

    expect(greet.classList.contains('is-greeting')).toBe(true)
  })

  it('does not greet a failed image when the user clicks or hovers', () => {
    const { image, greet } = renderMascot()
    fireEvent.error(image)
    act(() => greet.click())
    fireEvent.pointerEnter(greet, { pointerType: 'mouse' })

    expect(greet.classList.contains('is-greeting')).toBe(false)
    expect(greet.getAttribute('aria-disabled')).toBe('true')
  })
})
