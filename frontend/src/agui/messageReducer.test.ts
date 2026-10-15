import { describe, expect, it } from 'vitest'
import { applyAgentEvent } from './messageReducer'
import { recommendationFromHistory } from './history'
import type {
  AgentEvent,
  NavigationRecommendationResult,
  PersistedConversationMessage,
} from '../types/agent'

function event(type: AgentEvent['type'], delta?: string): AgentEvent {
  return {
    eventId: crypto.randomUUID(),
    type,
    conversationId: 'conversation',
    sessionId: 'session',
    runId: 'run',
    messageId: 'message',
    sequence: 1,
    timestamp: new Date().toISOString(),
    delta,
  }
}

describe('applyAgentEvent', () => {
  it('builds an assistant message from streaming events', () => {
    let messages = applyAgentEvent([], event('TEXT_MESSAGE_START'))
    messages = applyAgentEvent(messages, event('TEXT_MESSAGE_CONTENT', 'hello'))
    messages = applyAgentEvent(messages, {
      ...event('TEXT_MESSAGE_END'),
      content: 'hello',
    })

    expect(messages).toEqual([
      {
        id: 'message',
        role: 'assistant',
        content: 'hello',
        status: 'complete',
      },
    ])
  })

  it('does not duplicate a persisted message when an active session replays events', () => {
    const persisted = [{
      id: 'message',
      role: 'assistant' as const,
      content: '已经保存的回复',
      status: 'complete' as const,
    }]

    let messages = applyAgentEvent(persisted, event('TEXT_MESSAGE_START'))
    messages = applyAgentEvent(messages, event('TEXT_MESSAGE_CONTENT', '已经保存的回复'))
    messages = applyAgentEvent(messages, {
      ...event('TEXT_MESSAGE_END'),
      content: '已经保存的回复',
    })

    expect(messages).toEqual(persisted)
  })
})

describe('recommendationFromHistory', () => {
  it('restores the latest structured recommendation snapshot', () => {
    const older: NavigationRecommendationResult = {
      contextVersion: 1,
      locationRevision: 1,
      clinicalRevision: 1,
      cmsSnapshotId: 'cms-old',
      status: 'READY',
      diseases: [],
      overallConfidence: {
        score: 70,
        band: 'MEDIUM',
        policyVersion: 'test',
        components: {},
      },
      verificationRounds: 1,
      semanticVerificationTriggered: false,
      generatedAt: new Date(0).toISOString(),
    }
    const latest: NavigationRecommendationResult = {
      ...older,
      cmsSnapshotId: 'cms-latest',
      overallConfidence: {
        ...older.overallConfidence,
        score: 88,
        band: 'HIGH',
      },
      generatedAt: new Date(2).toISOString(),
    }
    const rows = [
      {
        id: 'old',
        role: 'ASSISTANT',
        content: 'old',
        messageType: 'RECOMMENDATION_RESULT',
        structuredPayload: older,
        createdAt: new Date(0).toISOString(),
      },
      {
        id: 'text',
        role: 'USER',
        content: 'more symptoms',
        messageType: 'TEXT',
        createdAt: new Date(1).toISOString(),
      },
      {
        id: 'latest',
        role: 'ASSISTANT',
        content: 'latest',
        messageType: 'RECOMMENDATION_RESULT',
        structuredPayload: latest,
        createdAt: new Date(2).toISOString(),
      },
    ] satisfies PersistedConversationMessage[]

    expect(recommendationFromHistory(rows)).toBe(latest)
  })
})
