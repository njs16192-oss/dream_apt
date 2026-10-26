import { PERIODS, type Period } from '../lib/month'

/** 1년/3년/5년/전체. 받아 둔 전체 데이터를 화면에서 자르므로 바꿔도 다시 요청하지 않는다 (ADR 008 ②) */
export function PeriodSelector({ value, onChange }: { value: Period; onChange: (next: Period) => void }) {
  return (
    <div role="group" aria-label="기간" className="inline-flex rounded-lg border border-slate-200 bg-white p-0.5 text-sm">
      {PERIODS.map((period) => (
        <button
          key={period.value}
          type="button"
          aria-pressed={period.value === value}
          onClick={() => onChange(period.value)}
          className={`rounded-md px-3 py-1 font-medium transition-colors ${
            period.value === value ? 'bg-slate-900 text-white' : 'text-slate-600 hover:bg-slate-100'
          }`}
        >
          {period.label}
        </button>
      ))}
    </div>
  )
}
