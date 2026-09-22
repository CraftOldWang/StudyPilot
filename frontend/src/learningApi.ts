import { apiRequest } from './api'
import { readEventStream, type StreamEvent } from './stream'
import type {
  LearningSession,
  LearningTurn,
  ConversationTurn,
  PlanningView,
  SessionEntry,
} from './learningTypes'

const SESSION_PATH = '/api/learning/sessions'

export const learningApi = {
  currentPlan: (knowledgeBaseId: string, sessionId?: string | null) => apiRequest<PlanningView | null>(
    `/api/learning/plans/current?knowledgeBaseId=${knowledgeBaseId}${sessionId ? `&sessionId=${sessionId}` : ''}`),
  listSessions: (knowledgeBaseId: string) => apiRequest<SessionEntry[]>(`${SESSION_PATH}?knowledgeBaseId=${knowledgeBaseId}`),
  history: (sessionId: string) => apiRequest<ConversationTurn[]>(`${SESSION_PATH}/${sessionId}/messages`),
  streamMessage: async (sessionId: string, message: string, requestId: string,
    onEvent: (event: StreamEvent) => void, signal?: AbortSignal) => {
    const response = await fetch(`${SESSION_PATH}/${sessionId}/messages/stream`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-User-Id': '1' },
      body: JSON.stringify({ message, requestId }), signal,
    })
    await readEventStream(response, onEvent)
  },
  createPlan: (knowledgeBaseId: string, learningGoal: string, lessonDocumentIds: string[], exerciseDocumentIds: string[]) =>
    apiRequest<PlanningView>('/api/learning/plans', { method: 'POST',
      body: JSON.stringify({ knowledgeBaseId, learningGoal, lessonDocumentIds, exerciseDocumentIds }) }),
  getPlan: (id: string) => apiRequest<PlanningView>(`/api/learning/plans/${id}`),
  executePlan: (id: string) => apiRequest<PlanningView>(`/api/learning/plans/${id}/execute`, { method: 'POST' }),
  planSession: (id: string) => apiRequest<LearningSession>(`/api/learning/plans/${id}/session`, { method: 'POST' }),
  newConversation: (id: string) => apiRequest<LearningSession>(`/api/learning/plans/${id}/session?newConversation=true`, { method: 'POST' }),
  getSession: (sessionId: string) =>
    apiRequest<LearningSession>(`${SESSION_PATH}/${sessionId}`),
  sendMessage: (sessionId: string, message: string) =>
    apiRequest<LearningTurn>(`${SESSION_PATH}/${sessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ message }),
    }),
}
