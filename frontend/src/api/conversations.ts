import type {
  AgentEvent,
  AuthConversationResponse,
  AuthPurpose,
  Conversation,
  ConversationCompletion,
  LocationTaskAccepted,
  PersistedConversationMessage,
  RunAccepted,
  UserSession,
} from '../types/agent'

async function assertOk(response: Response): Promise<Response> {
  if (!response.ok) {
    const body = await response.text()
    let message = body
    try {
      const parsed = JSON.parse(body) as { message?: unknown }
      if (typeof parsed.message === 'string') message = parsed.message
    } catch {
      // Keep the original non-JSON response body.
    }
    throw new Error(message || `Request failed with status ${response.status}`)
  }
  return response
}

export async function createConversation(): Promise<Conversation> {
  const response = await assertOk(
    await fetch('/api/conversations', { method: 'POST' }),
  )
  return response.json() as Promise<Conversation>
}

export async function createRun(
  conversationId: string,
  message: string,
): Promise<RunAccepted> {
  const response = await assertOk(
    await fetch(`/api/conversations/${conversationId}/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message }),
    }),
  )
  return response.json() as Promise<RunAccepted>
}

export async function submitAuthSecret(
  conversationId: string,
  requestId: string,
  purpose: AuthPurpose,
  password: string,
): Promise<AuthConversationResponse> {
  const operation = purpose === 'LOGIN' ? 'login' : 'register'
  const response = await assertOk(
    await fetch(`/api/auth/conversation/${operation}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId, requestId, password }),
    }),
  )
  return response.json() as Promise<AuthConversationResponse>
}

export async function completeConversation(
  conversationId: string,
): Promise<ConversationCompletion> {
  const response = await assertOk(
    await fetch(`/api/conversations/${conversationId}/complete`, {
      method: 'POST',
    }),
  )
  return response.json() as Promise<ConversationCompletion>
}

function sessionHeaders(conversationId: string): HeadersInit {
  return { 'X-Conversation-Id': conversationId }
}

export async function listUserSessions(
  conversationId: string,
): Promise<UserSession[]> {
  const response = await assertOk(
    await fetch('/api/users/me/sessions', {
      headers: sessionHeaders(conversationId),
    }),
  )
  return response.json() as Promise<UserSession[]>
}

export async function createUserSession(
  conversationId: string,
): Promise<Conversation> {
  const response = await assertOk(
    await fetch('/api/users/me/sessions', {
      method: 'POST',
      headers: sessionHeaders(conversationId),
    }),
  )
  return response.json() as Promise<Conversation>
}

export async function getSessionMessages(
  conversationId: string,
  sessionId: string,
): Promise<PersistedConversationMessage[]> {
  const response = await assertOk(
    await fetch(`/api/users/me/sessions/${sessionId}/messages`, {
      headers: sessionHeaders(conversationId),
    }),
  )
  return response.json() as Promise<PersistedConversationMessage[]>
}

export async function resumeUserSession(
  conversationId: string,
  sessionId: string,
): Promise<Conversation> {
  const response = await assertOk(
    await fetch(`/api/users/me/sessions/${sessionId}/resume`, {
      method: 'POST',
      headers: sessionHeaders(conversationId),
    }),
  )
  return response.json() as Promise<Conversation>
}

export async function submitBrowserLocation(
  conversationId: string,
  granted: boolean,
  latitude?: number,
  longitude?: number,
): Promise<LocationTaskAccepted> {
  const response = await assertOk(
    await fetch('/api/location/consent', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId, granted, latitude, longitude }),
    }),
  )
  return response.json() as Promise<LocationTaskAccepted>
}

export async function submitManualLocation(
  conversationId: string,
  address: string,
): Promise<LocationTaskAccepted> {
  const response = await assertOk(
    await fetch('/api/location/manual', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ conversationId, address }),
    }),
  )
  return response.json() as Promise<LocationTaskAccepted>
}

const EVENT_TYPES: AgentEvent['type'][] = [
  'RUN_STARTED',
  'TEXT_MESSAGE_START',
  'TEXT_MESSAGE_CONTENT',
  'TEXT_MESSAGE_END',
  'RUN_FINISHED',
  'RUN_ERROR',
  'AUTH_SECRET_REQUEST',
  'AUTH_RESULT',
  'PROFILE_UPDATED',
  'PIPELINE_PROGRESS',
  'CLINICAL_QUESTION',
  'CLINICAL_RESULT',
  'LOCATION_STATUS',
  'ROUTE_MATRIX_RESULT',
  'NAVIGATION_CONTEXT_READY',
  'RECOMMENDATION_RESULT',
  'LOCAL_REPAIR_PROGRESS',
  'VALIDATION_FINDING',
  'SESSION_COMPLETED',
]

export function subscribeToEvents(
  conversationId: string,
  onEvent: (event: AgentEvent) => void,
  onConnectionError: () => void,
): () => void {
  const eventSource = new EventSource(
    `/api/conversations/${conversationId}/events`,
  )

  for (const type of EVENT_TYPES) {
    eventSource.addEventListener(type, (message) => {
      onEvent(JSON.parse((message as MessageEvent<string>).data) as AgentEvent)
    })
  }
  eventSource.onerror = onConnectionError

  return () => eventSource.close()
}
