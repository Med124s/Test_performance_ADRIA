import { describe, expect, it } from 'vitest'
import {
  buildCron, describeCron, describeSchedule, isoToZoned, maskFrDateTime, p95Change, parseCron, parseFrDateTime,
  toFiveFields, zonedToIso,
} from './scheduleModel'

const base = { time: '02:00', weekDays: [1], monthDay: 1, customCron: '' }

describe('fréquences ↔ CRON', () => {
  it('construit le CRON de chaque fréquence', () => {
    expect(buildCron({ ...base, frequency: 'DAILY' })).toBe('0 0 2 * * *')
    expect(buildCron({ ...base, frequency: 'WEEKDAYS', time: '06:30' })).toBe('0 30 6 * * MON-FRI')
    expect(buildCron({ ...base, frequency: 'WEEKLY', time: '18:30', weekDays: [3, 1] })).toBe('0 30 18 * * MON,WED')
    expect(buildCron({ ...base, frequency: 'WEEKLY', weekDays: [] })).toBeNull()
    expect(buildCron({ ...base, frequency: 'MONTHLY', monthDay: 15, time: '03:00' })).toBe('0 0 3 15 * *')
    expect(buildCron({ ...base, frequency: 'CUSTOM', customCron: '*/5 * * * *' })).toBe('0 */5 * * * *')
    expect(buildCron({ ...base, frequency: 'CUSTOM', customCron: '* * *' })).toBeNull()
    expect(buildCron({ ...base, frequency: 'DAILY', time: '25:00' })).toBeNull()
  })

  it('retrouve la fréquence d’un CRON enregistré (modification)', () => {
    expect(parseCron('0 0 2 * * *')).toMatchObject({ frequency: 'DAILY', time: '02:00' })
    expect(parseCron('0 30 6 * * MON-FRI')).toMatchObject({ frequency: 'WEEKDAYS', time: '06:30' })
    expect(parseCron('0 30 18 * * MON,WED')).toMatchObject({ frequency: 'WEEKLY', weekDays: [1, 3] })
    expect(parseCron('0 0 3 31 * *')).toMatchObject({ frequency: 'MONTHLY', monthDay: 31 })
    expect(parseCron('0 */5 * * * *')).toMatchObject({ frequency: 'CUSTOM', customCron: '*/5 * * * *' })
    expect(toFiveFields('0 0 2 * * *')).toBe('0 2 * * *')
  })
})

describe('traduction du CRON en français', () => {
  it.each([
    ['0 2 * * *', 'Tous les jours à 02:00'],
    ['0 0 2 * * *', 'Tous les jours à 02:00'],
    ['30 6 * * MON-FRI', 'Du lundi au vendredi à 06:30'],
    ['30 6 * * 1-5', 'Du lundi au vendredi à 06:30'],
    ['30 18 * * MON,WED', 'Chaque lundi et mercredi à 18:30'],
    ['0 9 * * SAT,SUN', 'Le samedi et le dimanche à 09:00'],
    ['0 3 1 * *', 'Le 1er de chaque mois à 03:00'],
    ['0 3 15 * *', 'Le 15 de chaque mois à 03:00'],
    ['*/5 * * * *', 'Toutes les 5 minutes'],
    ['15 * * * *', 'Toutes les heures, à la minute 15'],
    ['0 */6 * * *', 'Toutes les 6 heures, à la minute 0'],
  ])('« %s » → « %s »', (cron, text) => {
    expect(describeCron(cron)).toBe(text)
  })

  it('refuse une expression invalide', () => {
    expect(describeCron('toutes les nuits')).toBeNull()
    expect(describeCron('0 25 * * *')).toBeNull()
    expect(describeCron('0 2 * * XYZ')).toBeNull()
    expect(describeCron('0 2 *')).toBeNull()
  })
})

describe('dates et fuseau horaire', () => {
  it('lit et masque la saisie jj/mm/aaaa hh:mm', () => {
    expect(maskFrDateTime('071020260200')).toBe('07/10/2026 02:00')
    expect(parseFrDateTime('07/10/2026 02:00')).toEqual({ day: '2026-10-07', time: '02:00' })
    expect(parseFrDateTime('31/02/2026 02:00')).toBeNull()
    expect(parseFrDateTime('07/10/2026 24:00')).toBeNull()
  })

  it('interprète l’heure dans le fuseau choisi, été comme hiver', () => {
    expect(zonedToIso('2026-10-07', '02:00', 'UTC')).toBe('2026-10-07T02:00:00.000Z')
    expect(zonedToIso('2026-01-15', '08:00', 'Europe/Paris')).toBe('2026-01-15T07:00:00.000Z')
    expect(zonedToIso('2026-07-15', '08:00', 'Europe/Paris')).toBe('2026-07-15T06:00:00.000Z')
    expect(zonedToIso('2026-10-07', '02:00', 'Asia/Tokyo')).toBe('2026-10-06T17:00:00.000Z')
    expect(isoToZoned('2026-07-15T06:00:00.000Z', 'Europe/Paris')).toEqual({ day: '2026-07-15', time: '08:00' })
  })

  it('décrit une planification pour la liste', () => {
    expect(describeSchedule({ scheduleType: 'ONE_TIME', cronExpression: null, runAt: '2026-10-07T01:00:00Z', timezone: 'Africa/Casablanca', endAt: null }))
      .toBe('Une fois, le 07/10/2026 à 02:00')
    expect(describeSchedule({ scheduleType: 'RECURRING_CRON', cronExpression: '0 0 2 * * *', runAt: null, timezone: 'UTC', endAt: '2026-12-31T23:59:59Z' }))
      .toBe('Tous les jours à 02:00 · jusqu’au 31/12/2026')
  })

  it('écart de P95 avec flèche', () => {
    expect(p95Change(35)).toEqual({ text: '↑ +35 %', tone: 'worse' })
    expect(p95Change(-12.5)).toEqual({ text: '↓ −12,5 %', tone: 'better' })
    expect(p95Change(2)).toEqual({ text: '+2 %', tone: 'same' })
    expect(p95Change(null)).toBeNull()
  })
})
