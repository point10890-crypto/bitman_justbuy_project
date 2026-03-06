import { useState, type InputHTMLAttributes, type ReactNode } from 'react'

interface FormInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'className'> {
  label: string
  icon?: ReactNode
  error?: string
  delay?: number
}

export default function FormInput({ label, icon, error, type, delay = 0, ...props }: FormInputProps) {
  const [showPassword, setShowPassword] = useState(false)
  const [focused, setFocused] = useState(false)
  const isPassword = type === 'password'
  const inputType = isPassword && showPassword ? 'text' : type

  return (
    <div
      className="flex flex-col gap-1.5 animate-slide-up"
      style={{ animationDelay: `${delay}s`, animationFillMode: 'backwards' }}
    >
      <label className="text-[13px] font-medium" style={{ color: 'var(--text-secondary)' }}>{label}</label>
      <div
        className="relative flex items-center transition-all duration-200"
        style={{
          backgroundColor: focused ? 'rgba(30, 35, 45, 0.9)' : 'var(--bg-elevated)',
          border: `1.5px solid ${error ? 'var(--color-bear)' : focused ? 'rgba(255,215,0,0.4)' : 'var(--border-default)'}`,
          borderRadius: '12px',
          padding: '0 14px',
          boxShadow: focused
            ? error
              ? '0 0 0 3px rgba(255,23,68,0.1)'
              : '0 0 0 3px rgba(255,215,0,0.08)'
            : 'none',
        }}
      >
        {icon && (
          <span
            className="flex-shrink-0 mr-2.5 transition-colors duration-200"
            style={{ color: focused ? 'rgba(255,215,0,0.7)' : 'var(--text-muted)' }}
          >
            {icon}
          </span>
        )}
        <input
          type={inputType}
          className="w-full bg-transparent outline-none text-[14px] py-3.5"
          style={{ color: 'var(--text-primary)' }}
          onFocus={e => { setFocused(true); props.onFocus?.(e) }}
          onBlur={e => { setFocused(false); props.onBlur?.(e) }}
          {...props}
        />
        {isPassword && (
          <button
            type="button"
            className="flex-shrink-0 ml-2 p-1 transition-colors duration-200"
            onClick={() => setShowPassword(v => !v)}
            tabIndex={-1}
            style={{ color: focused ? 'var(--text-secondary)' : 'var(--text-muted)' }}
          >
            {showPassword ? (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M17.94 17.94A10.07 10.07 0 0112 20c-7 0-11-8-11-8a18.45 18.45 0 015.06-5.94M9.9 4.24A9.12 9.12 0 0112 4c7 0 11 8 11 8a18.5 18.5 0 01-2.16 3.19m-6.72-1.07a3 3 0 11-4.24-4.24" />
                <line x1="1" y1="1" x2="23" y2="23" />
              </svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                <circle cx="12" cy="12" r="3" />
              </svg>
            )}
          </button>
        )}
      </div>
      {error && (
        <span className="text-[12px] font-medium flex items-center gap-1" style={{ color: 'var(--color-bear)' }}>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          {error}
        </span>
      )}
    </div>
  )
}
