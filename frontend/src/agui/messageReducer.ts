import type { AgentEvent, ChatMessage } from '../types/agent'

export function applyAgentEvent(
  messages: ChatMessage[],
  event: AgentEvent,
): ChatMessage[] {
  const messageId = event.messageId ?? event.runId

  switch (event.type) {
    case 'TEXT_MESSAGE_START':
      if (messages.some((message) => message.id === messageId)) return messages
      return [
        ...messages,
        { id: messageId, role: 'assistant', content: '', status: 'streaming' },
      ]
    case 'TEXT_MESSAGE_CONTENT':
      return messages.map((message) =>
        message.id === messageId && message.status === 'streaming'
          ? { ...message, content: message.content + (event.delta ?? '') }
          : message,
      )
    case 'TEXT_MESSAGE_END':
      return messages.map((message) =>
        message.id === messageId
          ? {
              ...message,
              content: event.content ?? message.content,
              status: 'complete',
            }
          : message,
      )
    case 'RUN_ERROR':
      return [
        ...messages,
        {
          id: `error-${event.runId}`,
          role: 'system',
          content: event.content ?? 'Agent Run 执行失败，请稍后重试。',
          status: 'error',
        },
      ]
    default:
      return messages
  }
}
