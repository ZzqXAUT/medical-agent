export type Conversation = {
  conversationId: string
  sessionId: string
  createdAt: string
}

export type RunAccepted = {
  runId: string
  conversationId: string
  sessionId: string
  status: 'QUEUED'
  acceptedAt: string
}

export type AgentEvent = {
  eventId: string
  type:
    | 'RUN_STARTED'
    | 'TEXT_MESSAGE_START'
    | 'TEXT_MESSAGE_CONTENT'
    | 'TEXT_MESSAGE_END'
    | 'RUN_FINISHED'
    | 'RUN_ERROR'
    | 'AUTH_SECRET_REQUEST'
    | 'AUTH_RESULT'
    | 'PROFILE_UPDATED'
    | 'PIPELINE_PROGRESS'
    | 'CLINICAL_QUESTION'
    | 'CLINICAL_RESULT'
    | 'LOCATION_STATUS'
    | 'ROUTE_MATRIX_RESULT'
    | 'NAVIGATION_CONTEXT_READY'
    | 'RECOMMENDATION_RESULT'
    | 'LOCAL_REPAIR_PROGRESS'
    | 'VALIDATION_FINDING'
    | 'SESSION_COMPLETED'
  conversationId: string
  sessionId: string
  runId: string
  messageId?: string
  sequence: number
  timestamp: string
  delta?: string
  content?: string
  errorCode?: string
  data?: Record<string, unknown>
}

export type AuthPurpose = 'LOGIN' | 'REGISTER'

export type AuthSecretRequest = {
  requestId: string
  purpose: AuthPurpose
  username: string
}

export type AuthConversationResponse = {
  runId: string
  conversationId: string
  userId: string
  username: string
  newUser: boolean
  status: 'AUTHENTICATED'
}

export type ConversationCompletion = {
  conversationId: string
  sessionId: string
  status: 'COMPLETED'
  profilePersisted: boolean
  completedAt: string
}

export type UserSession = {
  sessionId: string
  conversationId: string
  title: string
  status: 'ACTIVE' | 'COMPLETED'
  resumable: boolean
  createdAt: string
  lastActivityAt: string
  completedAt?: string
}

export type PersistedConversationMessage = {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  messageType: 'TEXT' | 'RECOMMENDATION_RESULT'
  structuredPayload?: NavigationRecommendationResult
  createdAt: string
}

export type LocationTaskAccepted = {
  taskId: string
  conversationId: string
  locationRevision: number
  status: 'RUNNING' | 'DENIED'
  acceptedAt: string
}

export type ChatMessage = {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  status?: 'streaming' | 'complete' | 'error'
}

export type ConfidenceResult = {
  score: number
  band: 'HIGH' | 'MEDIUM' | 'LOW'
  policyVersion: string
  components: Record<string, number>
}

export type RouteMeasure = {
  distanceMiles: number
  durationMinutes: number
}

export type DoctorRecommendation = {
  npi: string
  name: string
  credential?: string
  primarySpecialty?: string
  secondarySpecialties: string[]
  medicalSchool?: string
  graduationYear?: number
  affiliationFacilityId: string
  recommendationReason: string
  recommendationReasons: string[]
  evidenceIds: string[]
  doctorConfidence: ConfidenceResult
}

export type HospitalRecommendation = {
  hospitalId: string
  name: string
  hospitalRankWithinDisease: number
  cmsRating?: number
  address: string
  recommendationReason: string
  diseaseHospitalMatchReasons: string[]
  drive?: RouteMeasure
  transit?: RouteMeasure
  walk?: RouteMeasure
  doctors: DoctorRecommendation[]
  hospitalConfidence: ConfidenceResult
  explanationConfidence: ConfidenceResult
  evidenceIds: string[]
  limitations: string[]
}

export type DiseaseRecommendation = {
  hypothesis: {
    name: string
    severity: 'SEVERE' | 'MODERATE' | 'MILD'
    estimatedProbability: number
    supportingFacts: string[]
    limitations: string[]
  }
  diseaseRank: number
  recommendationReason: string
  diseaseConfidence: ConfidenceResult
  hospitals: HospitalRecommendation[]
  evidenceIds: string[]
  limitations: string[]
}

export type NavigationRecommendationResult = {
  contextVersion: number
  locationRevision: number
  clinicalRevision: number
  cmsSnapshotId: string
  displayLanguage?: 'zh-CN' | 'en-US'
  status: 'READY' | 'READY_WITH_WARNINGS'
  overallConfidence: ConfidenceResult
  verificationRounds: number
  semanticVerificationTriggered: boolean
  diseases: DiseaseRecommendation[]
  generatedAt: string
}
