import { useRef, useEffect } from 'react'
import type { KnowledgePoint, OutlineNode } from '../learningTypes'

type Progress = Map<string, KnowledgePoint['status']>
function completion(node: OutlineNode, progress: Progress): { done: number; total: number } {
  if (!node.children.length) return { done: progress.get(node.id) === 'COMPLETED' ? 1 : 0, total: 1 }
  return node.children.map(child => completion(child, progress)).reduce(
    (sum, child) => ({ done: sum.done + child.done, total: sum.total + child.total }), { done: 0, total: 0 })
}
function Mark({ title, done, total }: { title: string; done: number; total: number }) {
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => { if (input.current) input.current.indeterminate = done > 0 && done < total }, [done, total])
  return <input ref={input} type="checkbox" checked={done === total} disabled
    aria-label={`${title}：${done === total ? '已完成' : done ? '部分完成' : '未完成'}`} />
}
function TreeNode({ node, progress }: { node: OutlineNode; progress: Progress }) {
  const { done, total } = completion(node, progress)
  const active = progress.has(node.id) && !['NEW', 'COMPLETED'].includes(progress.get(node.id)!)
  const label = <><Mark title={node.title} done={done} total={total} /><span className="outline-node-title">{node.title}</span>
    {['HIGH', 'MEDIUM'].includes(node.priority) && <span className="outline-important">重点</span>}
    {node.children.length > 0 && <small className="outline-count">{done}/{total}</small>}
    {active && <small className="outline-current">学习中</small>}</>
  return <li className={done === total ? 'outline-done' : undefined}>
    {node.children.length ? <details open><summary className="outline-row">{label}</summary>
      <ul>{node.children.map(child => <TreeNode key={child.id} node={child} progress={progress} />)}</ul>
    </details> : <div className={`outline-row outline-leaf${active ? ' active' : ''}`}>{label}</div>}
  </li>
}
export function LearningOutline({ nodes, points, completedNodeIds = [] }: { nodes: OutlineNode[]; points: KnowledgePoint[]; completedNodeIds?: string[] }) {
  const progress: Progress = new Map(points.map(point => [point.outlineNodeId || point.id, point.status]))
  completedNodeIds.forEach(id => progress.set(id, 'COMPLETED'))
  return <ul className="outline-tree" aria-label="学习待办大纲">{nodes.map(node => <TreeNode key={node.id} node={node} progress={progress} />)}</ul>
}
export function outlineLeafCount(nodes: OutlineNode[]): number {
  return nodes.reduce((sum, node) => sum + (node.children.length ? outlineLeafCount(node.children) : 1), 0)
}
