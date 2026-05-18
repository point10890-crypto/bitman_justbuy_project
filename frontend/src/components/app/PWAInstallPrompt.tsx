import type { AriaRole } from 'react'
import { usePWAInstall } from '../../hooks/usePWAInstall'

type InstallVariant = 'menu-item' | 'nav-button' | 'hero-button' | 'banner' | 'compact'

interface PWAInstallPromptProps {
  variant?: InstallVariant
  label?: string
  description?: string
  className?: string
  role?: AriaRole
  showWhenDismissed?: boolean
  onOpen?: () => void
}

function InstallGuides({
  isiOS,
  showIOSGuide,
  closeIOSGuide,
  showDesktopGuide,
  closeDesktopGuide,
  triggerNativeInstall,
  hasNativePrompt,
}: ReturnType<typeof usePWAInstall>) {
  const handleNativeInstall = async () => {
    await triggerNativeInstall()
    closeDesktopGuide()
  }

  return (
    <>
      {showIOSGuide && (
        <>
          <button className="ios-guide-overlay" type="button" aria-label="설치 안내 닫기" onClick={closeIOSGuide} />
          <section className="ios-guide-modal" role="dialog" aria-modal="true" aria-labelledby="ios-install-title">
            <button className="ios-guide-close" type="button" aria-label="닫기" onClick={closeIOSGuide}>×</button>
            <div className="ios-guide-icon logo-gold"><span className="logo-gold-text">B</span></div>
            <h2 className="ios-guide-title" id="ios-install-title">홈 화면에 앱 설치</h2>
            <p className="ios-guide-subtitle">iPhone/iPad에서는 Safari 공유 메뉴에서 설치합니다.</p>
            <div className="ios-guide-steps">
              <div className="ios-guide-step">
                <span className="ios-guide-step-num">1</span>
                <div><p className="ios-guide-step-title">Safari에서 열기</p><p className="ios-guide-step-desc">주소창 아래 또는 위의 공유 버튼을 누릅니다.</p></div>
              </div>
              <div className="ios-guide-step">
                <span className="ios-guide-step-num">2</span>
                <div><p className="ios-guide-step-title">홈 화면에 추가</p><p className="ios-guide-step-desc">메뉴에서 “홈 화면에 추가”를 선택합니다.</p></div>
              </div>
              <div className="ios-guide-step">
                <span className="ios-guide-step-num">3</span>
                <div><p className="ios-guide-step-title">BitMan 실행</p><p className="ios-guide-step-desc">설치된 아이콘으로 앱처럼 바로 실행합니다.</p></div>
              </div>
            </div>
            <button className="ios-guide-done" type="button" onClick={closeIOSGuide}>확인</button>
          </section>
        </>
      )}

      {showDesktopGuide && !isiOS && (
        <>
          <button className="ios-guide-overlay" type="button" aria-label="설치 안내 닫기" onClick={closeDesktopGuide} />
          <section className="ios-guide-modal" role="dialog" aria-modal="true" aria-labelledby="desktop-install-title">
            <button className="ios-guide-close" type="button" aria-label="닫기" onClick={closeDesktopGuide}>×</button>
            <div className="ios-guide-icon logo-gold"><span className="logo-gold-text">B</span></div>
            <h2 className="ios-guide-title" id="desktop-install-title">BitMan 앱 설치</h2>
            <p className="ios-guide-subtitle">Chrome/Edge/Android에서 앱처럼 홈 화면에 추가합니다.</p>
            <div className="ios-guide-steps">
              {hasNativePrompt ? (
                <div className="ios-guide-step">
                  <span className="ios-guide-step-num">1</span>
                  <div><p className="ios-guide-step-title">지금 설치 누르기</p><p className="ios-guide-step-desc">브라우저 설치 확인창이 뜨면 설치를 선택합니다.</p></div>
                </div>
              ) : (
                <>
                  <div className="ios-guide-step">
                    <span className="ios-guide-step-num">1</span>
                    <div><p className="ios-guide-step-title">브라우저 메뉴 열기</p><p className="ios-guide-step-desc">주소창 오른쪽 메뉴 또는 설치 아이콘을 누릅니다.</p></div>
                  </div>
                  <div className="ios-guide-step">
                    <span className="ios-guide-step-num">2</span>
                    <div><p className="ios-guide-step-title">앱 설치 선택</p><p className="ios-guide-step-desc">“앱 설치”, “홈 화면에 추가” 또는 “페이지를 앱으로 설치”를 선택합니다.</p></div>
                  </div>
                </>
              )}
            </div>
            <button className="ios-guide-done" type="button" onClick={hasNativePrompt ? handleNativeInstall : closeDesktopGuide}>
              {hasNativePrompt ? '지금 설치' : '확인'}
            </button>
          </section>
        </>
      )}
    </>
  )
}

export function PWAInstallPrompt({
  variant = 'compact',
  label = '앱 설치',
  description = '홈 화면에 추가해서 바로 실행',
  className = '',
  role,
  showWhenDismissed = false,
  onOpen,
}: PWAInstallPromptProps) {
  const installState = usePWAInstall()
  const visible = showWhenDismissed ? !installState.isInstalled : installState.canInstall

  if (!visible) return <InstallGuides {...installState} />

  const openInstallGuide = () => {
    onOpen?.()
    installState.install()
  }

  if (variant === 'menu-item') {
    return (
      <>
        <button className={className} type="button" role={role} onClick={openInstallGuide}>
          <strong>{label}</strong>
          <span>{description}</span>
        </button>
        <InstallGuides {...installState} />
      </>
    )
  }

  if (variant === 'banner') {
    return (
      <>
        <div className={`pwa-install-banner ${className}`.trim()}>
          <div className="pwa-install-mark">B</div>
          <div className="pwa-install-copy">
            <strong>{label}</strong>
            <span>{description}</span>
          </div>
          <button className="pwa-install-action" type="button" onClick={openInstallGuide}>설치</button>
          <button className="pwa-install-dismiss" type="button" aria-label="설치 안내 숨기기" onClick={installState.dismiss}>×</button>
        </div>
        <InstallGuides {...installState} />
      </>
    )
  }

  return (
    <>
      <button className={`pwa-install-trigger pwa-install-${variant} ${className}`.trim()} type="button" onClick={openInstallGuide}>
        {label}
      </button>
      <InstallGuides {...installState} />
    </>
  )
}
