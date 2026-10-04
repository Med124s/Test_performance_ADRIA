import { describe, expect, it } from 'vitest'
import { analyzeCsv, csvTemplate, readCsvFile, templateColumns, undefinedVariables, validateCsv } from './csvModel'
import { newStep, validateStep } from './wizardModel'

// Mêmes cas et mêmes messages que CsvDataSourceTest (backend) : une seule règle des deux côtés.
describe('csvModel — parité avec le moteur (CsvDataSource.java)', () => {
  it('gère BOM, séparateur « ; » et guillemets', () => {
    const a = analyzeCsv('﻿user;note\r\nagent001;"a;b"\r\nagent002;"il dit ""oui"""')
    expect(a.separator).toBe(';')
    expect(a.columns).toEqual(['user', 'note'])
    expect(a.rows).toEqual([['agent001', 'a;b'], ['agent002', 'il dit "oui"']])
    expect(a.error).toBeNull()
  })

  it('« , » l’emporte si l’en-tête en contient plus, retours à la ligne entre guillemets conservés', () => {
    const a = analyzeCsv('a,b\n"x;y","ligne1\nligne2"')
    expect(a.rows).toEqual([['x;y', 'ligne1\nligne2']])
    expect(a.error).toBeNull()
  })

  it('renvoie des erreurs claires', () => {
    expect(validateCsv('')).toBeUndefined()
    expect(validateCsv('username,password\nu1,p1')).toBeUndefined()
    expect(validateCsv('username,password')).toMatch(/au moins une ligne de données/)
    expect(validateCsv('user name,x\na,b')).toMatch(/Nom de colonne invalide « user name »/)
    expect(validateCsv('1col\na')).toMatch(/Nom de colonne invalide/)
    expect(validateCsv('a,,b\n1,2,3')).toMatch(/une colonne n’a pas de nom/)
    expect(validateCsv('a,a\n1,2')).toMatch(/Colonne en double/)
    expect(validateCsv('a,b\n1,2\n\n3')).toBe('Ligne 4 : 1 colonne(s) au lieu de 2.')
    expect(validateCsv('a\n"non fermé')).toMatch(/Guillemet non fermé \(ligne 2\)/)
    expect(validateCsv('a\n' + 'x'.repeat(1_000_000))).toMatch(/1 000 000/)
  })
})

describe('csvModel — cohérence avec les étapes', () => {
  const steps = [
    newStep({ name: 'Connexion', url: '/login', body: '{"u":"${username}"}', captureVariableName: 'token', captureJsonPath: '$.token' }),
    newStep({ name: 'Virement', url: '/transfers/${transferId}', headers: [{ key: 'Authorization', value: 'Bearer ${token}' }], body: '{"amount": ${amount}}' }),
    newStep({ name: 'Profil', url: '/me', headers: [{ key: 'X-Auth', value: '${authToken}' }] }),
  ]

  it('signale les variables ni dans le CSV, ni capturées avant, ni ${authToken}', () => {
    expect(undefinedVariables(steps, ['username'], true)).toEqual([
      { name: 'transferId', stepIndexes: [1] },
      { name: 'amount', stepIndexes: [1] },
    ])
    expect(undefinedVariables(steps, ['username', 'amount', 'transferId'], false)).toEqual([{ name: 'authToken', stepIndexes: [2] }])
  })

  it('génère un modèle avec les variables à fournir (hors captures et ${authToken})', () => {
    expect(templateColumns(steps)).toEqual(['username', 'transferId', 'amount'])
    const template = csvTemplate(['username', 'amount'])
    expect(template).toBe('﻿username,amount\r\n,\r\n')
    expect(analyzeCsv(template).columns).toEqual(['username', 'amount'])
  })

  it('lit un fichier UTF-8 (BOM retiré) et refuse un autre encodage', async () => {
    const utf8 = new File([new Uint8Array([0xef, 0xbb, 0xbf]), 'compte;libellé\nACC001;Café'], 'data.csv', { type: 'text/csv' })
    expect(await readCsvFile(utf8)).toBe('compte;libellé\nACC001;Café')
    const latin1 = new File([new Uint8Array([0x43, 0x61, 0x66, 0xe9])], 'latin1.csv', { type: 'text/csv' })
    await expect(readCsvFile(latin1)).rejects.toThrow(/UTF-8/)
  })
})

describe('validateStep — mêmes règles que StepRules.java', () => {
  it('URL, noms d’en-têtes et corps JSON', () => {
    expect(validateStep(newStep({ name: 'a', url: 'ftp://x/y' })).url).toMatch(/URL invalide/)
    expect(validateStep(newStep({ name: 'a', url: 'http://localhost:8888/x?id=${id}' })).url).toBeUndefined()
    expect(validateStep(newStep({ name: 'a', url: '/x', headers: [{ key: 'Mon en-tete', value: 'v' }] })).headers).toMatch(/Nom d’en-tête invalide/)
    expect(validateStep(newStep({ name: 'a', url: '/x', body: '{"amount": 12,}' })).body).toMatch(/Corps JSON invalide/)
    expect(validateStep(newStep({ name: 'a', url: '/x', body: '{"amount": ${amount}}' })).body).toBeUndefined()
    expect(validateStep(newStep({ name: 'a', url: '/x', body: 'username=a&password=b' })).body).toBeUndefined()
  })
})
