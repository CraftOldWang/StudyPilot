import { apiRequest } from './api'

export interface LearningPreferences {
  explanationStyle: string
  learningGoal: string
  memoryEnabled: boolean
}
export interface LearningMemory {
  id: string
  sessionId: string
  topic: string
  correctCount: number
  questionCount: number
  mistakes: { question: string; sourceChunkId?: string }[]
  included: boolean
  recordedAt: string
}
export const memoryApi = {
  preferences: () => apiRequest<LearningPreferences>('/api/learning-memory/preferences'),
  save: (value: LearningPreferences) => apiRequest<LearningPreferences>('/api/learning-memory/preferences', { method: 'PUT', body: JSON.stringify(value) }),
  list: (kb: string) => apiRequest<LearningMemory[]>(`/api/learning-memory?knowledgeBaseId=${encodeURIComponent(kb)}`),
  include: (kb: string, id: string, included: boolean) => apiRequest(`/api/learning-memory/${id}/inclusion?knowledgeBaseId=${encodeURIComponent(kb)}`, {
    method: 'PUT', body: JSON.stringify({ included }),
  }),
}
