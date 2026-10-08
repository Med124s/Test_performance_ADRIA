import { useEffect, useState } from 'react'
import { formatFrDate, maskFrDate, parseFrDate } from './executionsModel'

// Date au format français jj/mm/aaaa (indépendant de la langue du navigateur) ; la valeur
// transmise reste AAAA-MM-JJ. Une date incomplète ou inexistante est signalée sans être appliquée.

export default function FrDateInput({ value, onChange, label }: { value: string; onChange: (isoDay: string) => void; label: string }) {
  const [text, setText] = useState(formatFrDate(value))
  useEffect(() => { setText(formatFrDate(value)) }, [value])
  const invalid = text.length === 10 && parseFrDate(text) == null

  return (
    <input
      className={`pt-form-control ex-date ${invalid ? 'is-invalid' : ''}`}
      inputMode="numeric"
      placeholder="jj/mm/aaaa"
      maxLength={10}
      value={text}
      aria-label={label}
      aria-invalid={invalid || undefined}
      title={invalid ? 'Date inexistante' : 'Format jj/mm/aaaa'}
      onChange={(e) => {
        const masked = maskFrDate(e.target.value)
        setText(masked)
        if (masked === '') onChange('')
        const iso = parseFrDate(masked)
        if (iso) onChange(iso)
      }}
    />
  )
}
