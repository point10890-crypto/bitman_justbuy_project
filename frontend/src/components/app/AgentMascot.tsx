import { useCallback, useEffect, useRef, useState } from 'react'
import type { AnimationEvent } from 'react'
import './AgentMascot.css'

interface AgentMascotProps {
  className?: string
}

const motionQuery = '(prefers-reduced-motion: reduce)'

function getReducedMotionPreference() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia(motionQuery).matches
}

export function AgentMascot({ className }: AgentMascotProps) {
  const imageRef = useRef<HTMLImageElement>(null)
  const imageReadyRef = useRef(false)
  const [imageLoaded, setImageLoaded] = useState(false)
  const [reducedMotion, setReducedMotion] = useState(getReducedMotionPreference)
  const [paused, setPaused] = useState(false)
  const [greeting, setGreeting] = useState(false)
  const motionAllowed = !paused
  const motionDisabled = !imageLoaded || !motionAllowed
  const motionState = !imageLoaded ? 'loading'
    : paused ? 'paused' : 'running'

  const markImageReady = useCallback(() => {
    if (imageReadyRef.current) return
    imageReadyRef.current = true
    setImageLoaded(true)
    if (motionAllowed) setGreeting(true)
  }, [motionAllowed])

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return

    const mediaQuery = window.matchMedia(motionQuery)
    const updatePreference = () => {
      setReducedMotion(mediaQuery.matches)
    }
    setReducedMotion(mediaQuery.matches)

    if (typeof mediaQuery.addEventListener === 'function') {
      mediaQuery.addEventListener('change', updatePreference)
      return () => mediaQuery.removeEventListener('change', updatePreference)
    }

    if (typeof mediaQuery.addListener === 'function') {
      mediaQuery.addListener(updatePreference)
      return () => mediaQuery.removeListener(updatePreference)
    }
  }, [])

  useEffect(() => {
    const image = imageRef.current
    if (image?.complete && image.naturalWidth > 0) markImageReady()
  }, [markImageReady])

  const handleImageError = () => {
    imageReadyRef.current = false
    setImageLoaded(false)
    setGreeting(false)
  }

  const greet = () => {
    if (!motionDisabled) setGreeting(true)
  }

  const finishGreeting = (event: AnimationEvent<HTMLButtonElement>) => {
    if (event.target === event.currentTarget && (
      event.animationName === 'bitman-mascot-greeting'
      || event.animationName === 'bitman-mascot-greeting-gentle'
    )) {
      setGreeting(false)
    }
  }

  const toggleMotion = () => {
    if (motionAllowed) {
      setPaused(true)
    } else {
      setPaused(false)
      if (imageLoaded) setGreeting(true)
    }
  }

  const motionLabel = motionAllowed ? '로봇 애니메이션 멈추기' : '로봇 애니메이션 재생'
  const greetingLabel = !imageLoaded
    ? 'BitMan 로봇, 이미지가 준비되면 인사할 수 있습니다'
    : paused ? 'BitMan 로봇, 움직임이 멈춰 있습니다' : 'BitMan 로봇에게 인사하기'

  return (
    <div
      className={['bitman-mascot', className].filter(Boolean).join(' ')}
      data-motion={motionState}
      data-reduced-motion={reducedMotion ? 'true' : 'false'}
    >
      <div className="bitman-mascot-float">
        <button
          className={`bitman-mascot-greet${greeting ? ' is-greeting' : ''}`}
          type="button"
          aria-label={greetingLabel}
          aria-disabled={motionDisabled}
          onClick={greet}
          onPointerEnter={(event) => {
            if (event.pointerType === 'mouse') greet()
          }}
          onAnimationEnd={finishGreeting}
        >
          <img
            ref={imageRef}
            src="/images/bitman-agent-v1.png"
            alt="BitMan 로고를 달고 인사하는 금색 AI 로봇"
            width="1280"
            height="1280"
            fetchPriority="high"
            draggable={false}
            onLoad={markImageReady}
            onError={handleImageError}
          />
        </button>
      </div>
      <button
        className="bitman-mascot-motion-toggle"
        type="button"
        aria-label={motionLabel}
        aria-pressed={paused}
        title={motionLabel}
        onClick={toggleMotion}
      >
        <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" focusable="false">
          {!motionAllowed
            ? <path d="M4 2.5 11 7 4 11.5Z" fill="currentColor" />
            : <path d="M4.5 3v8M9.5 3v8" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />}
        </svg>
      </button>
    </div>
  )
}
