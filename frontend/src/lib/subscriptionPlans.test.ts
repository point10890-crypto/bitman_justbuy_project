import { describe, expect, it } from 'vitest'
import {
  authPath, parseSubscriptionPlan, postAuthPath, subscribePath,
  subscriptionPlanFromSearch, validateSubscriptionPlans,
} from './subscriptionPlans'

describe('subscription plan selection survives account entry', () => {
  it('defaults unknown or missing plan values to the monthly plan', () => {
    expect(parseSubscriptionPlan(null)).toBe('MONTHLY')
    expect(parseSubscriptionPlan('six_months')).toBe('MONTHLY')
    expect(parseSubscriptionPlan('YEARLY')).toBe('MONTHLY')
    expect(parseSubscriptionPlan('SIX_MONTHS')).toBe('SIX_MONTHS')
  })

  it('keeps an explicitly selected six month plan through signup and login', () => {
    expect(authPath('register', 'SIX_MONTHS')).toBe('/register?plan=SIX_MONTHS')
    expect(authPath('login', 'SIX_MONTHS')).toBe('/login?plan=SIX_MONTHS')
    expect(subscriptionPlanFromSearch('?plan=SIX_MONTHS')).toBe('SIX_MONTHS')
    expect(postAuthPath('?plan=SIX_MONTHS', false)).toBe('/subscribe?plan=SIX_MONTHS')
  })

  it('preserves the selected plan when an active member intends to renew', () => {
    expect(postAuthPath('?plan=SIX_MONTHS', true)).toBe('/subscribe?plan=SIX_MONTHS&renewal=1')
    expect(subscribePath('SIX_MONTHS', true)).toBe('/subscribe?plan=SIX_MONTHS&renewal=1')
    expect(authPath('login', 'SIX_MONTHS', true)).toBe('/login?plan=SIX_MONTHS&renewal=1')
  })

  it('routes ordinary active logins home and refuses arbitrary external redirects', () => {
    expect(postAuthPath('', true)).toBe('/')
    expect(postAuthPath('', false)).toBe('/subscribe')
    expect(postAuthPath('?next=https%3A%2F%2Fevil.example&plan=YEARLY', false)).toBe('/subscribe?plan=MONTHLY')
  })

  it('defaults an expired member to the previously approved plan without overriding an explicit choice', () => {
    expect(postAuthPath('', false, true, 'SIX_MONTHS')).toBe('/subscribe?plan=SIX_MONTHS&renewal=1')
    expect(postAuthPath('?renewal=1', false, false, 'SIX_MONTHS')).toBe('/subscribe?plan=SIX_MONTHS&renewal=1')
    expect(postAuthPath('?plan=MONTHLY', false, true, 'SIX_MONTHS')).toBe('/subscribe?plan=MONTHLY&renewal=1')
    expect(postAuthPath('?plan=YEARLY', false, true, 'SIX_MONTHS')).toBe('/subscribe?plan=MONTHLY&renewal=1')
    expect(postAuthPath('', false, true)).toBe('/subscribe?plan=MONTHLY&renewal=1')
    expect(postAuthPath('', true, false, 'SIX_MONTHS')).toBe('/')
  })

  it('keeps renewal intent through reauthentication without inventing a monthly choice', () => {
    expect(authPath('login', undefined, true)).toBe('/login?renewal=1')
    expect(authPath('register', undefined, true)).toBe('/register?renewal=1')
    expect(postAuthPath('?renewal=1', false, false, 'SIX_MONTHS')).toBe('/subscribe?plan=SIX_MONTHS&renewal=1')
    expect(authPath('login', 'MONTHLY', true)).toBe('/login?plan=MONTHLY&renewal=1')
  })

  it('rejects an incomplete or invalid server catalog before using it for application', () => {
    const monthly = { id: 'MONTHLY', label: '1개월', months: 1, price: 30000 }
    const sixMonths = { id: 'SIX_MONTHS', label: '6개월', months: 6, price: 150000 }
    expect(validateSubscriptionPlans([monthly, sixMonths])).toBe(true)
    expect(validateSubscriptionPlans([monthly])).toBe(false)
    expect(validateSubscriptionPlans([monthly, { ...sixMonths, price: -1 }])).toBe(false)
    expect(validateSubscriptionPlans([monthly, { ...sixMonths, price: 30000 }])).toBe(false)
    expect(validateSubscriptionPlans([monthly, { ...sixMonths, months: 1 }])).toBe(false)
    expect(validateSubscriptionPlans([monthly, monthly])).toBe(false)
  })
})
