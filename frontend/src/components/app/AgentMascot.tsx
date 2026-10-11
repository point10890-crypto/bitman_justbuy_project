import { useEffect, useState } from 'react'
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
  const [reducedMotion, setReducedMotion] = useState(getReducedMotionPreference)
  const [paused, setPaused] = useState(false)
  const [greeting, setGreeting] = useState(!reducedMotion)
  const motionDisabled = paused || reducedMotion

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return

    const mediaQuery = window.matchMedia(motionQuery)
    const updatePreference = () => setReducedMotion(mediaQuery.matches)
    updatePreference()

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
    if (reducedMotion) setGreeting(false)
  }, [reducedMotion])

  const greet = () => {
    if (!motionDisabled) setGreeting(true)
  }

  const finishGreeting = (event: AnimationEvent<HTMLButtonElement>) => {
    if (event.target === event.currentTarget && event.animationName === 'bitman-mascot-greeting') {
      setGreeting(false)
    }
  }

  const motionLabel = reducedMotion
    ? '모션 감소 설정으로 로봇 움직임이 꺼져 있습니다'
    : paused ? '로봇 움직임 재생' : '로봇 움직임 멈추기'
  const greetingLabel = reducedMotion
    ? 'BitMan 로봇, 모션 감소 설정이 적용되어 있습니다'
    : paused ? 'BitMan 로봇, 움직임이 멈춰 있습니다' : 'BitMan 로봇에게 인사하기'

  return (
    <div
      className={['bitman-mascot', className].filter(Boolean).join(' ')}
      data-motion={reducedMotion ? 'reduced' : paused ? 'paused' : 'running'}
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
            src="/images/bitman-agent-v1.png"
            alt="BitMan 로고를 달고 인사하는 금색 AI 로봇"
            width="1280"
            height="1280"
            fetchPriority="high"
            draggable={false}
          />
        </button>
      </div>
      <button
        className="bitman-mascot-motion-toggle"
        type="button"
        aria-label={motionLabel}
        aria-pressed={paused}
        title={motionLabel}
        disabled={reducedMotion}
        onClick={() => setPaused((value) => !value)}
      >
        <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" focusable="false">
          {motionDisabled
            ? <path d="M4 2.5 11 7 4 11.5Z" fill="currentColor" />
            : <path d="M4.5 3v8M9.5 3v8" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />}
        </svg>
      </button>
    </div>
  )
}
