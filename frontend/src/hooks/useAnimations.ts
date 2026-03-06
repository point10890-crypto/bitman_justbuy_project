import { useState, useEffect, useRef } from 'react'

/** Animated count-up hook for numeric strings */
export function useCountUp(target: string, duration = 1200) {
  const [display, setDisplay] = useState('0')
  const rafRef = useRef<number>(0)

  useEffect(() => {
    const cleanTarget = target.replace(/[^0-9.]/g, '')
    const end = parseFloat(cleanTarget)
    if (isNaN(end)) { setDisplay(target); return }

    const decimals = cleanTarget.includes('.') ? cleanTarget.split('.')[1].length : 0
    const hasComma = target.includes(',')
    const startTime = performance.now()

    function animate(now: number) {
      const elapsed = now - startTime
      const progress = Math.min(elapsed / duration, 1)
      const eased = progress === 1 ? 1 : 1 - Math.pow(2, -10 * progress)
      const current = end * eased

      let formatted = current.toFixed(decimals)
      if (hasComma) {
        const parts = formatted.split('.')
        parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',')
        formatted = parts.join('.')
      }
      setDisplay(formatted)
      if (progress < 1) rafRef.current = requestAnimationFrame(animate)
    }
    rafRef.current = requestAnimationFrame(animate)
    return () => cancelAnimationFrame(rafRef.current)
  }, [target, duration])
  return display
}

/** Delayed visibility hook for blink-in effects */
export function useBlinkOnMount(delay = 0) {
  const [visible, setVisible] = useState(false)
  useEffect(() => {
    const timer = setTimeout(() => setVisible(true), delay)
    return () => clearTimeout(timer)
  }, [delay])
  return visible
}
