(() => {
  const button = document.getElementById('install-button')
  const status = document.getElementById('install-status')
  const installUrl = new URL('/install.html', location.origin).href
  document.getElementById('install-url').value = installUrl
  let promptEvent = null
  let installed = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true
  const showInstalled = () => {
    installed = true
    promptEvent = null
    button.hidden = true
    status.textContent = '설치된 앱입니다. 아래의 웹으로 앱 열기를 눌러 시작하세요.'
  }
  if (installed) showInstalled()
  if (/Android/i.test(navigator.userAgent)) {
    const chrome = document.getElementById('chrome-link')
    chrome.hidden = false
    chrome.href = `intent://${location.host}/install.html#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url=${encodeURIComponent(installUrl)};end`
  }
  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault()
    if (installed) return
    promptEvent = event
    button.hidden = false
    button.disabled = false
    status.textContent = '설치할 준비가 됐습니다. 앱 설치하기를 눌러 주세요.'
  })
  window.addEventListener('appinstalled', showInstalled)
  button.addEventListener('click', async () => {
    const pending = promptEvent
    if (!pending) return
    promptEvent = null
    button.disabled = true
    try {
      await pending.prompt()
      const choice = await pending.userChoice
      if (!installed) status.textContent = choice.outcome === 'accepted'
        ? '설치 요청을 보냈습니다. 브라우저의 설치 완료를 확인해 주세요.'
        : '설치를 취소했습니다. 다시 설치하려면 Chrome 메뉴에서 홈 화면에 추가를 선택하세요.'
    } catch {
      if (!installed) status.textContent = '설치창을 열지 못했습니다. Chrome 메뉴에서 홈 화면에 추가를 선택하세요.'
    } finally { button.hidden = true }
  })
  document.getElementById('copy-link').addEventListener('click', async () => {
    const message = document.getElementById('copy-status')
    try {
      await navigator.clipboard.writeText(installUrl)
      message.textContent = '주소를 복사했습니다. Chrome 주소창에 붙여 넣으세요.'
    } catch {
      document.getElementById('install-url').select()
      message.textContent = '주소를 길게 눌러 복사한 뒤 Chrome에서 열어 주세요.'
    }
  })
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').then(reg => reg.update()).catch(() => {
      if (!installed && !promptEvent) status.textContent = '앱 설치 준비를 완료하지 못했습니다. 인터넷 연결과 Chrome 업데이트를 확인해 주세요.'
    })
  }
  const checkButton = document.getElementById('check-service')
  const check = async () => {
    checkButton.disabled = true
    const message = document.getElementById('service-status')
    message.textContent = '서버 연결을 확인하고 있습니다.'
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 8000)
    try {
      const response = await fetch('https://api.bit-man.net/api/health', { cache: 'no-store', signal: controller.signal })
      const body = await response.json()
      if (!response.ok || body.status !== 'ok') throw new Error('Unavailable')
      message.textContent = '서버 연결 정상 · 앱을 열 수 있습니다. 개별 분석 기능은 별도 확인이 필요할 수 있습니다.'
    } catch {
      message.textContent = '서버 연결을 확인하지 못했습니다. 휴대폰 인터넷 연결을 확인하고 다시 시도해 주세요.'
    } finally {
      clearTimeout(timeout)
      checkButton.disabled = false
    }
  }
  checkButton.addEventListener('click', check)
  void check()
})()
