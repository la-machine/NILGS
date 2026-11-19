// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
// The applicant's view of their case's procedure (ADR-042): each stage, its status and when it was last recorded;
// never the officer, the finding or the evidence (the API carries none of them). Nothing for a type without one. When
// the office asks for part of the procedure again, the stages and what the applicant must do; never the reason. When the
// approved case is reconsidered (ADR-043 step 5b), that a new decision will follow; never why.
import type { Lang, Texts } from '../../shared/i18n'
import { Card } from '../../shared/ui/Card'
import { Alert, Badge } from '../../shared/ui/Feedback'
import { fill } from '../../shared/i18n'
import type { ProcedureInstruction, StageStatus } from './api'
import { STATUS_TONE } from './stages'

export interface StageProgressItem { stage: string; status: StageStatus; at: string | null }

export interface ReconsiderationItem { round: number; stages: string[]; openedAt: string; decided: boolean }

export function StageProgress({ texts, lang, stages, instructions, reconsiderations }: {
  texts: Texts; lang: Lang; stages: StageProgressItem[] | undefined; instructions?: ProcedureInstruction[]
  reconsiderations?: ReconsiderationItem[]
}) {
  const k = texts.procedures
  if (!stages || stages.length === 0) return null
  const names = (list: string[]) => list.map((s) => (k.stageNames as Record<string, string>)[s] ?? s).join(', ')
  return (
    <Card title={k.applicantTitle} icon="checkCircle" description={k.applicantIntro}>
      {(reconsiderations ?? []).filter((c) => !c.decided).map((c) => (
        <Alert key={c.round} tone="info" title={fill(k.reconsideredTitle, { n: String(c.round) })}>
          <div data-reconsidered={c.round}>{fill(k.reconsideredText, { stages: names(c.stages), date: new Date(c.openedAt).toLocaleDateString(lang) })}</div>
        </Alert>
      ))}
      {(instructions ?? []).filter((i) => i.pending).map((i) => (
        <Alert key={i.at} tone="warning" title={k.againTitle}>
          <div data-instruction>{fill(k.againStages, { stages: names(i.stages) })}</div>
          {i.action && <div data-instruction-action>{fill(k.againAction, { text: i.action })}</div>}
        </Alert>
      ))}
      <ol className="plain-list" data-stage-progress>
        {stages.map((s) => (
          <li key={s.stage} data-stage={s.stage} data-status={s.status}>
            <Badge tone={STATUS_TONE[s.status]}>{k.statuses[s.status]}</Badge>{' '}
            {(k.stageNames as Record<string, string>)[s.stage] ?? s.stage}
            {s.at && <span className="small muted num"> · {new Date(s.at).toLocaleDateString(lang)}</span>}
          </li>
        ))}
      </ol>
    </Card>
  )
}
