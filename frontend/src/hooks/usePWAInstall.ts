/** PWA Install Prompt Hook — 모든 환경 지원 */
import { useState, useEffect, useCallback } from 'react'

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>
}

// 전역 캡처 — React 마운트 전에 이벤트가 발생해도 놓치지 않음
let _deferredPrompt: BeforeInstallPromptEvent | null = null
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault()
  _deferredPrompt = e as BeforeInstallPromptEvent
})

function isIOS() {
  return /iPad|iPhone|iPod/.test(navigator.userAgent) && !(window as any).MSStream
}

function isStandalone() {
  return window.matchMedia('(display-mode: standalone)').matches
    || (navigator as any).standalone === true
}

export function usePWAInstall() {
  const [isInstalled, setIsInstalled] = useState(false)
  const [dismissed, setDismissed] = useState(false)
  const [showIOSGuide, setShowIOSGuide] = useState(false)
  const [showDesktopGuide, setShowDesktopGuide] = useState(false)

  const isiOS = isIOS()

  useEffect(() => {
    if (isStandalone()) {
      setIsInstalled(true)
      return
    }

    const dismissedAt = localStorage.getItem('pwa_dismissed')
    if (dismissedAt) {
      const elapsed = Date.now() - parseInt(dismissedAt, 10)
      if (elapsed < 7 * 24 * 60 * 60 * 1000) {
        setDismissed(true)
      }
    }

    window.addEventListener('appinstalled', () => {
      setIsInstalled(true)
      _deferredPrompt = null
    })
  }, [])

  // "설치" 배너 클릭 → 무조건 가이드 모달 표시
  const install = useCallback(() => {
    if (isiOS) {
      setShowIOSGuide(true)
    } else {
      setShowDesktopGuide(true)
    }
  }, [isiOS])

  // 가이드 모달에서 "지금 설치" 클릭 → 네이티브 프롬프트 시도
  const triggerNativeInstall = useCallback(async () => {
    const p = _deferredPrompt
    if (p) {
      try {
        await p.prompt()
        const { outcome } = await p.userChoice
        if (outcome === 'accepted') {
          setIsInstalled(true)
        }
      } catch {
        // prompt already used
      }
      _deferredPrompt = null
    }
  }, [])

  const dismiss = useCallback(() => {
    setDismissed(true)
    setShowIOSGuide(false)
    setShowDesktopGuide(false)
    localStorage.setItem('pwa_dismissed', String(Date.now()))
  }, [])

  const closeIOSGuide = useCallback(() => {
    setShowIOSGuide(false)
  }, [])

  const closeDesktopGuide = useCallback(() => {
    setShowDesktopGuide(false)
  }, [])

  const canInstall = !isInstalled && !dismissed
  const hasNativePrompt = !!_deferredPrompt

  return { canInstall, isInstalled, install, dismiss, isiOS, showIOSGuide, closeIOSGuide, showDesktopGuide, closeDesktopGuide, triggerNativeInstall, hasNativePrompt }
}
