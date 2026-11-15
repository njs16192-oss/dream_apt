import { useId, type InputHTMLAttributes } from 'react'

type Props = Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'onChange'> & {
  label: string
  value: string
  onChange: (value: string) => void
  /** 화면 검사 오류 문구. 있으면 칸 아래에 보여 주고 aria-invalid로 표시한다 */
  error?: string
  /** 입력 규칙 안내 (예: "8자 이상"). 오류가 있으면 오류 문구가 대신 나온다 */
  hint?: string
}

/**
 * 라벨 + 입력 칸 + 안내·오류 문구. 입력값은 부모의 state에 둔다 (제어 컴포넌트).
 * 문구는 aria-describedby로 칸에 이어서, 화면 읽기 프로그램이 칸을 읽을 때 같이 읽는다.
 */
export function FormField({ label, value, onChange, error, hint, ...inputProps }: Props) {
  const id = useId()
  const descriptionId = `${id}-description`
  const description = error ?? hint
  return (
    <div>
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      <input
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={description ? descriptionId : undefined}
        className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-slate-900 focus:border-blue-600 focus:ring-1 focus:ring-blue-600 focus:outline-none aria-invalid:border-red-500"
        {...inputProps}
      />
      {description && (
        <p id={descriptionId} className={`mt-1 text-sm ${error ? 'text-red-600' : 'text-slate-500'}`}>
          {description}
        </p>
      )}
    </div>
  )
}
