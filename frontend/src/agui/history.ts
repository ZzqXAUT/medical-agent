import type {
  NavigationRecommendationResult,
  PersistedConversationMessage,
} from '../types/agent'

export function recommendationFromHistory(
  rows: PersistedConversationMessage[],
): NavigationRecommendationResult | null {
  for (let index = rows.length - 1; index >= 0; index -= 1) {
    const message = rows[index]
    const payload = message.structuredPayload
    if (
      message.messageType === 'RECOMMENDATION_RESULT' &&
      payload &&
      Array.isArray(payload.diseases) &&
      payload.overallConfidence
    ) {
      return payload
    }
  }
  return null
}
