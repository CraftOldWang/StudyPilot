import { useEffect, useState } from 'react'
import type { ReviewCard } from '../learningTypes'
import { SourceLink } from './SourceDrawer'
import { Field, MultilineInput } from './ui/Field'
import { DEMO_MODE } from '../deployment'

export function CardDrafts({ cards, busy, confirming, onSave, onConfirm, onRewrite }: {
  cards: ReviewCard[]; busy: boolean; confirming: boolean;
  onSave: (cards: ReviewCard[]) => Promise<void>; onConfirm: (cards: ReviewCard[]) => Promise<void>; onRewrite: () => void;
}) {
  const [drafts, setDrafts] = useState(cards)
  useEffect(() => setDrafts(cards), [cards])
  if (!cards.length) return null
  const invalid = drafts.some(c => !c.front.trim() || !c.back.trim())
  const update = (id: string, field: 'front' | 'back', value: string) => setDrafts(rows => rows.map(c => c.id === id ? { ...c, [field]: value } : c))
  return <section className="cards-block" aria-label="待确认复习卡片">
    <h3>{cards.length} 张复习卡片 · {confirming ? '已确认，等待完成写入' : '等待你确认'}</h3>
    <p className="muted">可直接编辑，或通过对话让我重写。{DEMO_MODE ? '确认后保存在本站，再进入下一知识点。在线演示不连接本机 Anki。' : '确认全部卡片后写入 Anki，再进入下一知识点。'}</p>
    <div className="review-cards">{drafts.map((card, i) => <article key={card.id}>
      <Field id={`card-front-${card.id}`} label={`第 ${i + 1} 张 · 正面`}><MultilineInput id={`card-front-${card.id}`} rows={2} value={card.front} disabled={busy || confirming} onChange={e => update(card.id, 'front', e.target.value)} /></Field>
      <Field id={`card-back-${card.id}`} label="背面"><MultilineInput id={`card-back-${card.id}`} rows={4} value={card.back} disabled={busy || confirming} onChange={e => update(card.id, 'back', e.target.value)} /></Field>
      <SourceLink chunkId={card.sourceChunkId} />
    </article>)}</div>
    <div className="action-row">
      {!confirming && <><button type="button" className="secondary" disabled={busy || invalid} onClick={() => void onSave(drafts)}>保存修改</button>
        <button type="button" className="secondary" disabled={busy} onClick={onRewrite}>通过对话重写</button></>}
      <button type="button" disabled={busy || invalid} onClick={() => void onConfirm(drafts)}>{busy ? '正在处理…' : confirming ? '重试保存并继续' : DEMO_MODE ? '确认全部卡片并继续' : '确认全部卡片并写入 Anki'}</button>
    </div>
  </section>
}
