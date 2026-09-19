import type { ReviewCard } from '../learningTypes'
import { SourceLink } from './SourceDrawer'
import { AnkiCardExport } from './AnkiCardExport'
import { DEMO_MODE } from '../deployment'

export function ReviewCards({ cards, draft = false }: { cards: ReviewCard[]; draft?: boolean }) {
  if (cards.length === 0) return null
  return (
    <section className="cards-block">
      <div className="learning-section-heading">
        <div><span className="eyebrow">复习卡片</span><h2>{cards.length} 张复习卡片</h2></div>
      </div>
      <p className="muted">{draft ? '这是生成当时的草稿，可能已被编辑或重写。最终内容以确认后保存的卡片为准。' : DEMO_MODE ? '卡片已确认并保存在本站。在线演示不连接本机 Anki。' : '卡片已确认。打开本机 Anki 与 AnkiConnect 可查看写入状态，重复写入会复用已有笔记。'}</p>
      <div className="review-cards">
        {cards.map((card) => (
          <article key={card.id}>
            <strong>{card.front}</strong>
            <p>{card.back}</p>
            <SourceLink chunkId={card.sourceChunkId} />
            {!draft && !DEMO_MODE && <AnkiCardExport key={card.id} cardId={card.id} />}
          </article>
        ))}
      </div>
    </section>
  )
}
