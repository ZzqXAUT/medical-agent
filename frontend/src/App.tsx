import { FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import {
  completeConversation,
  createConversation,
  createRun,
  createUserSession,
  getSessionMessages,
  listUserSessions,
  resumeUserSession,
  submitAuthSecret,
  submitBrowserLocation,
  submitManualLocation,
  subscribeToEvents,
} from './api/conversations'
import { applyAgentEvent } from './agui/messageReducer'
import { recommendationFromHistory } from './agui/history'
import type {
  AuthSecretRequest,
  AgentEvent,
  ChatMessage,
  Conversation,
  DiseaseRecommendation,
  DoctorRecommendation,
  HospitalRecommendation,
  NavigationRecommendationResult,
  PersistedConversationMessage,
  UserSession,
} from './types/agent'
import './styles.css'

const INTRO: ChatMessage = {
  id: 'intro',
  role: 'assistant',
  status: 'complete',
  content:
    '你好，我是 MedNav。请先告诉我你的用户名；我会在对话中判断是登录还是注册。密码会使用独立安全输入框，不会作为聊天消息发送。',
}

function toSecretRequest(data?: Record<string, unknown>): AuthSecretRequest | null {
  if (
    typeof data?.requestId !== 'string' ||
    (data.purpose !== 'LOGIN' && data.purpose !== 'REGISTER') ||
    typeof data.username !== 'string'
  ) {
    return null
  }
  return {
    requestId: data.requestId,
    purpose: data.purpose,
    username: data.username,
  }
}

function toRecommendationResult(
  value: unknown,
): NavigationRecommendationResult | null {
  if (!value || typeof value !== 'object') return null
  const candidate = value as Partial<NavigationRecommendationResult>
  return Array.isArray(candidate.diseases) && candidate.overallConfidence
    ? (candidate as NavigationRecommendationResult)
    : null
}

function toChatMessages(rows: PersistedConversationMessage[]): ChatMessage[] {
  return rows
    .filter((message) => message.messageType === 'TEXT')
    .map((message) => ({
    id: message.id,
    role:
      message.role === 'USER'
        ? 'user'
        : message.role === 'SYSTEM'
          ? 'system'
          : 'assistant',
    content: message.content,
    status: 'complete',
    }))
}

function isChineseRecommendation(result: NavigationRecommendationResult): boolean {
  if (result.displayLanguage) return result.displayLanguage === 'zh-CN'
  return result.diseases.some((disease) =>
    /[\u3400-\u9fff]/u.test(
      `${disease.hypothesis.name} ${disease.recommendationReason}`,
    ),
  )
}

function localizedSeverity(
  severity: 'SEVERE' | 'MODERATE' | 'MILD',
  chinese: boolean,
): string {
  if (!chinese) {
    return severity === 'SEVERE' ? 'Higher' : severity === 'MODERATE' ? 'Moderate' : 'Lower'
  }
  return severity === 'SEVERE' ? '较重' : severity === 'MODERATE' ? '中等' : '较轻'
}

function localizedDistance(distance: number, chinese: boolean): string {
  const value = new Intl.NumberFormat(chinese ? 'zh-CN' : 'en-US', {
    maximumFractionDigits: 2,
  }).format(distance)
  return chinese ? `${value} 英里` : `${value} miles`
}

function localizedWorkflowState(value: string, chinese: boolean): string {
  const labels: Record<string, [string, string]> = {
    READY: ['已就绪', 'Ready'],
    READY_WITH_WARNINGS: ['已生成（有提示）', 'Ready with notes'],
    READY_LIMITED: ['信息有限，已生成', 'Ready with limited information'],
    ASK_USER: ['等待补充信息', 'Waiting for more information'],
    INSUFFICIENT_INFORMATION: ['信息不足', 'Insufficient information'],
    RUNNING: ['进行中', 'In progress'],
    REQUESTING_PERMISSION: ['正在请求授权', 'Requesting permission'],
    DENIED: ['未授权', 'Permission denied'],
    INVALID: ['位置无效', 'Invalid location'],
    FAILED: ['处理失败', 'Failed'],
    DEGRADED: ['已降级处理', 'Completed with fallback'],
    WAITING: ['等待中', 'Waiting'],
    UNKNOWN: ['状态未知', 'Unknown'],
  }
  const label = labels[value]
  return label ? label[chinese ? 0 : 1] : value
}

function localizedSpecialty(specialty?: string): string {
  const normalized = specialty?.trim().toUpperCase()
  const labels: Record<string, string> = {
    'CARDIOVASCULAR DISEASE': '心血管疾病相关专业',
    CARDIOLOGY: '心血管疾病相关专业',
    GASTROENTEROLOGY: '消化系统疾病相关专业',
    'INTERNAL MEDICINE': '内科相关专业',
    'FAMILY PRACTICE': '家庭医学相关专业',
    'FAMILY MEDICINE': '家庭医学相关专业',
    'PULMONARY DISEASE': '呼吸系统疾病相关专业',
    'GENERAL SURGERY': '普通外科相关专业',
    NEUROLOGY: '神经系统疾病相关专业',
  }
  return normalized && labels[normalized]
    ? labels[normalized]
    : '与当前就诊方向相关的专业'
}

function visibleDiseaseReason(
  disease: DiseaseRecommendation,
  chinese: boolean,
): string {
  if (!chinese) return disease.recommendationReason
  const facts = disease.hypothesis.supportingFacts.join('、')
  return `你提到的情况包括${facts}。结合目前的信息，“${disease.hypothesis.name}”是值得进一步排查的方向之一，在本次候选中的相对可能性约为 ${Math.round(disease.hypothesis.estimatedProbability * 100)}%；症状程度暂评为${localizedSeverity(disease.hypothesis.severity, true)}。这项结果用于帮助选择后续就诊资源，不能替代医生面诊和必要检查。`
}

function visibleHospitalReason(
  hospital: HospitalRecommendation,
  chinese: boolean,
): string {
  if (!chinese) return hospital.recommendationReason
  const rating = hospital.cmsRating == null
    ? '公开资料暂未提供综合评分'
    : `医院综合评分为 ${hospital.cmsRating}/5`
  const travel = hospital.drive
    ? `预计驾车 ${localizedDistance(hospital.drive.distanceMiles, true)}，约 ${hospital.drive.durationMinutes} 分钟`
    : '暂时无法取得驾车路线'
  const provider = hospital.doctors.length === 0
    ? '暂未找到专业方向匹配且在该院执业的医生'
    : `另有 ${hospital.doctors.length} 名专业方向匹配且在该院执业的医生可供参考`
  return `这家医院进入推荐，主要是因为其${rating}，从你当前位置出发${travel}；${provider}。排序综合考虑医院评分和驾车距离，仅用于帮助你比较就诊选择，不代表治疗效果。`
}

function visibleHospitalFacts(
  hospital: HospitalRecommendation,
  chinese: boolean,
): string[] {
  if (!chinese) return hospital.diseaseHospitalMatchReasons
  const facts = [hospital.cmsRating == null
    ? '公开医院资料暂未提供该院的综合评分。'
    : `医院综合评分为 ${hospital.cmsRating}/5。`]
  if (hospital.drive) {
    facts.push(`预计驾车 ${localizedDistance(hospital.drive.distanceMiles, true)}，约 ${hospital.drive.durationMinutes} 分钟。`)
  }
  facts.push(hospital.doctors.length === 0
    ? '当前公开资料中暂未找到专业方向匹配且在该院执业的医生。'
    : `找到 ${hospital.doctors.length} 名专业方向匹配且在该院执业的医生。`)
  return facts
}

function visibleDoctorReason(
  doctor: DoctorRecommendation,
  chinese: boolean,
): string {
  if (!chinese) return doctor.recommendationReason
  return `这位医生的公开执业资料显示，其专业方向为${localizedSpecialty(doctor.primarySpecialty)}，与本次就诊需求相符，并且目前在这家医院执业。推荐依据是专业匹配和任职关系，不涉及医生能力排名，也不代表对诊疗结果的保证。`
}

function visibleDoctorFacts(
  doctor: DoctorRecommendation,
  chinese: boolean,
): string[] {
  if (!chinese) return doctor.recommendationReasons
  return [
    '公开执业资料显示，该医生的专业方向与本次就诊需求相符。',
    '公开任职资料显示，该医生目前在这家医院执业。',
  ]
}

export default function App() {
  const [conversation, setConversation] = useState<Conversation | null>(null)
  const [authConversationId, setAuthConversationId] = useState<string | null>(null)
  const [sessions, setSessions] = useState<UserSession[]>([])
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null)
  const [viewingHistory, setViewingHistory] = useState(false)
  const initialAuthHandled = useRef(false)
  const [messages, setMessages] = useState<ChatMessage[]>([INTRO])
  const [input, setInput] = useState('')
  const [status, setStatus] = useState('正在建立会话…')
  const [submitting, setSubmitting] = useState(false)
  const [secretRequest, setSecretRequest] = useState<AuthSecretRequest | null>(null)
  const [password, setPassword] = useState('')
  const [secretError, setSecretError] = useState('')
  const [authenticating, setAuthenticating] = useState(false)
  const [authenticated, setAuthenticated] = useState(false)
  const [sessionCompleted, setSessionCompleted] = useState(false)
  const [clinicalState, setClinicalState] = useState('尚未开始')
  const [clinicalConfidence, setClinicalConfidence] = useState<number | null>(null)
  const [clinicalQuestions, setClinicalQuestions] = useState(0)
  const [locationState, setLocationState] = useState('等待身份认证')
  const [locationDetail, setLocationDetail] = useState('尚未获取')
  const [manualLocationRequired, setManualLocationRequired] = useState(false)
  const [manualAddress, setManualAddress] = useState('')
  const [locationSubmitting, setLocationSubmitting] = useState(false)
  const [routedHospitals, setRoutedHospitals] = useState<number | null>(null)
  const [navigationReady, setNavigationReady] = useState(false)
  const [recommendation, setRecommendation] =
    useState<NavigationRecommendationResult | null>(null)
  const recommendationInChinese = recommendation
    ? isChineseRecommendation(recommendation)
    : true

  useEffect(() => {
    let disposed = false
    createConversation()
      .then((created) => {
        if (disposed) return
        setConversation(created)
        setSelectedSessionId(created.sessionId)
        setStatus('等待用户名')
      })
      .catch((error: unknown) => {
        setStatus(error instanceof Error ? error.message : '无法连接后端')
      })
    return () => {
      disposed = true
    }
  }, [])

  useEffect(() => {
    if (!conversation || viewingHistory) return
    return subscribeToEvents(
      conversation.conversationId,
      handleAgentEvent,
      () => setStatus('事件连接正在重试'),
    )
  }, [conversation?.conversationId, viewingHistory])

  function handleAgentEvent(event: AgentEvent) {
    setMessages((current) => applyAgentEvent(current, event))
    if (event.type === 'RUN_STARTED') setStatus('正在处理')
    if (event.type === 'AUTH_SECRET_REQUEST') {
      const request = toSecretRequest(event.data)
      if (request) {
        setSecretRequest(request)
        setSecretError('')
        setStatus(request.purpose === 'LOGIN' ? '等待登录密码' : '等待设置密码')
      }
    }
    if (event.type === 'AUTH_RESULT') {
      setAuthenticated(true)
      setAuthConversationId(event.conversationId)
      setSelectedSessionId(event.sessionId)
      setSecretRequest(null)
      setSecretError('')
      setStatus('已登录')
      void refreshSessions(event.conversationId)
      if (!initialAuthHandled.current) {
        initialAuthHandled.current = true
        beginBrowserLocation(event.conversationId)
      }
    }
    if (event.type === 'PROFILE_UPDATED') setStatus('画像已更新到当前会话')
    if (event.type === 'PIPELINE_PROGRESS') {
      if (event.data?.status === 'RUNNING') {
        setClinicalState('正在分析症状')
        setStatus('正在整理症状并判断信息完整度')
      }
      if (event.data?.status === 'DEGRADED') setClinicalState('本轮已降级')
    }
    if (event.type === 'CLINICAL_QUESTION' || event.type === 'CLINICAL_RESULT') {
      setRecommendation(null)
      if (typeof event.data?.decision === 'string') setClinicalState(event.data.decision)
      if (typeof event.data?.readinessConfidence === 'number') {
        setClinicalConfidence(event.data.readinessConfidence)
      }
      if (typeof event.data?.questionCount === 'number') {
        setClinicalQuestions(event.data.questionCount)
      }
      setStatus(event.type === 'CLINICAL_QUESTION' ? '需要补充一项症状信息' : '初步疾病假设已更新')
    }
    if (event.type === 'LOCATION_STATUS') {
      const next = typeof event.data?.status === 'string' ? event.data.status : 'UNKNOWN'
      setLocationState(next)
      if (next === 'RUNNING' || next === 'DENIED') {
        setRecommendation(null)
        setNavigationReady(false)
      }
      setLocationSubmitting(next === 'RUNNING')
      setManualLocationRequired(event.data?.manualLocationRequired === true)
      if (typeof event.data?.city === 'string' && typeof event.data?.state === 'string') {
        setLocationDetail(`${event.data.city}, ${event.data.state}`)
      }
      if (next === 'DENIED') setStatus('请提供地址以继续附近医院匹配')
      if (next === 'INVALID' || next === 'FAILED') setStatus('位置无法解析，请改用地址或邮政编码')
    }
    if (event.type === 'ROUTE_MATRIX_RESULT') {
      if (typeof event.data?.hospitalCount === 'number') setRoutedHospitals(event.data.hospitalCount)
      setStatus('附近医院多交通方式路线已计算')
    }
    if (event.type === 'NAVIGATION_CONTEXT_READY') {
      setNavigationReady(true)
      setStatus('定位与初诊结果已汇合，正在生成三级推荐')
    }
    if (event.type === 'LOCAL_REPAIR_PROGRESS') setStatus('正在局部修复未通过验证的推荐条目')
    if (event.type === 'RECOMMENDATION_RESULT') {
      const result = toRecommendationResult(event.data?.result)
      if (result) {
        setRecommendation(result)
        setStatus('疾病—医院—医生推荐已生成并通过门禁')
      }
    }
    if (event.type === 'VALIDATION_FINDING') setStatus('本轮分析已安全降级，可重新提交')
    if (event.type === 'SESSION_COMPLETED') {
      setSessionCompleted(true)
      setStatus('会话已结束，只能查看历史记录')
      void refreshSessions(event.conversationId)
    }
    if (event.type === 'RUN_FINISHED') {
      setSubmitting(false)
      if (initialAuthHandled.current) void refreshSessions(event.conversationId)
    }
    if (event.type === 'RUN_ERROR') {
      setStatus('本轮处理失败')
      setSubmitting(false)
    }
  }

  async function refreshSessions(conversationId: string) {
    try {
      setSessions(await listUserSessions(conversationId))
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '无法读取历史会话')
    }
  }

  function resetSessionIndicators() {
    setSessionCompleted(false)
    setClinicalState('尚未开始')
    setClinicalConfidence(null)
    setClinicalQuestions(0)
    setLocationState('等待定位')
    setLocationDetail('尚未获取')
    setManualLocationRequired(false)
    setLocationSubmitting(false)
    setRoutedHospitals(null)
    setNavigationReady(false)
    setRecommendation(null)
    setSecretRequest(null)
  }

  async function handleSelectSession(session: UserSession) {
    if (!authConversationId) return
    try {
      const storedMessages = await getSessionMessages(
        authConversationId,
        session.sessionId,
      )
      setMessages([INTRO, ...toChatMessages(storedMessages)])
      setSelectedSessionId(session.sessionId)
      setRecommendation(recommendationFromHistory(storedMessages))
      if (!session.resumable) {
        setViewingHistory(true)
        setSessionCompleted(true)
        setStatus('历史会话为只读，不能继续对话')
        return
      }
      const resumed = await resumeUserSession(
        authConversationId,
        session.sessionId,
      )
      resetSessionIndicators()
      setViewingHistory(false)
      setConversation(resumed)
      setAuthConversationId(resumed.conversationId)
      setStatus('已恢复会话，可继续对话')
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '无法打开会话')
      void refreshSessions(authConversationId)
    }
  }

  async function handleNewSession() {
    if (!authConversationId) return
    try {
      const created = await createUserSession(authConversationId)
      resetSessionIndicators()
      setMessages([])
      setViewingHistory(false)
      setConversation(created)
      setAuthConversationId(created.conversationId)
      setSelectedSessionId(created.sessionId)
      setStatus('新会话已创建')
      await refreshSessions(created.conversationId)
      beginBrowserLocation(created.conversationId)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '无法创建新会话')
    }
  }

  const canSubmit = useMemo(
    () =>
      Boolean(
        conversation &&
          input.trim() &&
          !submitting &&
          !secretRequest &&
          !sessionCompleted,
      ),
    [conversation, input, submitting, secretRequest, sessionCompleted],
  )

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!conversation || !canSubmit) return

    const content = input.trim()
    setInput('')
    setSubmitting(true)
    setMessages((current) => [
      ...current,
      { id: crypto.randomUUID(), role: 'user', content, status: 'complete' },
    ])

    try {
      await createRun(conversation.conversationId, content)
      setStatus('本轮请求已进入会话队列')
    } catch (error) {
      setSubmitting(false)
      setStatus(error instanceof Error ? error.message : '提交失败')
    }
  }

  async function handleSecretSubmit(event: FormEvent) {
    event.preventDefault()
    if (!conversation || !secretRequest || !password || authenticating) return

    const submittedPassword = password
    setPassword('')
    setSecretError('')
    setAuthenticating(true)
    try {
      await submitAuthSecret(
        conversation.conversationId,
        secretRequest.requestId,
        secretRequest.purpose,
        submittedPassword,
      )
    } catch (error) {
      setSecretError(error instanceof Error ? error.message : '认证失败')
      setStatus('认证失败，请重试')
    } finally {
      setAuthenticating(false)
    }
  }

  function beginBrowserLocation(conversationId: string) {
    setLocationState('REQUESTING_PERMISSION')
    setLocationSubmitting(true)
    if (!navigator.geolocation) {
      void submitBrowserLocation(conversationId, false).catch(() => {
        setLocationState('FAILED')
        setManualLocationRequired(true)
        setLocationSubmitting(false)
      })
      return
    }
    navigator.geolocation.getCurrentPosition(
      (position) => {
        void submitBrowserLocation(
          conversationId,
          true,
          position.coords.latitude,
          position.coords.longitude,
        ).catch(() => {
          setLocationState('FAILED')
          setManualLocationRequired(true)
          setLocationSubmitting(false)
        })
      },
      () => {
        void submitBrowserLocation(conversationId, false).catch(() => {
          setLocationState('FAILED')
          setManualLocationRequired(true)
          setLocationSubmitting(false)
        })
      },
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 },
    )
  }

  async function handleManualLocation(event: FormEvent) {
    event.preventDefault()
    if (!conversation || !manualAddress.trim() || locationSubmitting) return
    setLocationSubmitting(true)
    setLocationState('RUNNING')
    setNavigationReady(false)
    try {
      await submitManualLocation(conversation.conversationId, manualAddress.trim())
      setManualLocationRequired(false)
    } catch (error) {
      setLocationSubmitting(false)
      setManualLocationRequired(true)
      setLocationState('INVALID')
      setStatus(error instanceof Error ? error.message : '地址提交失败')
    }
  }

  async function handleCompleteSession() {
    if (!conversation || sessionCompleted) return
    setStatus('正在结束会话…')
    try {
      await completeConversation(conversation.conversationId)
    } catch (error) {
      setStatus(error instanceof Error ? error.message : '结束会话失败')
    }
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">医疗导诊助手</p>
          <h1>MedNav</h1>
        </div>
        <div className="status" aria-live="polite">
          <span className="status-dot" />
          {status}
        </div>
      </header>

      <section className="workspace">
        <aside className="session-sidebar" aria-label="历史会话">
          <div className="session-sidebar-header">
            <div>
              <p className="section-label">会话</p>
              <h2>历史会话</h2>
            </div>
            <button
              className="new-session-button"
              type="button"
              disabled={!authenticated || !authConversationId}
              onClick={handleNewSession}
            >
              ＋ 新会话
            </button>
          </div>
          {!authenticated ? (
            <p className="session-empty">登录后可查看和恢复自己的会话。</p>
          ) : sessions.length === 0 ? (
            <p className="session-empty">还没有历史会话。</p>
          ) : (
            <nav className="session-list">
              {sessions.map((session) => (
                <button
                  key={session.sessionId}
                  type="button"
                  className={`session-item ${selectedSessionId === session.sessionId ? 'selected' : ''}`}
                  onClick={() => handleSelectSession(session)}
                >
                  <strong>{session.title}</strong>
                  <span>{new Date(session.lastActivityAt).toLocaleString('zh-CN')}</span>
                  <em className={session.resumable ? 'active' : 'closed'}>
                    {session.resumable ? '可恢复' : '只读'}
                  </em>
                </button>
              ))}
            </nav>
          )}
        </aside>

        <aside className="context-panel">
          <p className="section-label">导诊上下文</p>
          <h2>三级导诊推荐</h2>
          <p>
            定位与症状分析独立执行；双方就绪后按版本汇合，旧结果不会覆盖新输入。
          </p>
          <dl>
            <div>
              <dt>会话编号</dt>
              <dd>{selectedSessionId?.slice(0, 8) ?? '创建中'}</dd>
            </div>
            <div>
              <dt>身份</dt>
              <dd>{authenticated ? '已认证' : '未认证'}</dd>
            </div>
            <div>
              <dt>临床状态</dt>
              <dd>{localizedWorkflowState(clinicalState, recommendationInChinese)}</dd>
            </div>
            <div>
              <dt>追问进度</dt>
              <dd>{clinicalQuestions} / 3</dd>
            </div>
            <div>
              <dt>信息完整度</dt>
              <dd>
                {clinicalConfidence === null
                  ? '待评估'
                  : `${Math.round(clinicalConfidence * 100)}%`}
              </dd>
            </div>
            <div>
              <dt>位置</dt>
              <dd>{localizedWorkflowState(locationState, recommendationInChinese)} · {locationDetail}</dd>
            </div>
            <div>
              <dt>医院路线</dt>
              <dd>{routedHospitals === null ? '待计算' : `${routedHospitals} 家`}</dd>
            </div>
            <div>
              <dt>并行汇合</dt>
              <dd>{navigationReady ? '已就绪' : '等待定位/初诊'}</dd>
            </div>
            <div>
              <dt>推荐门禁</dt>
              <dd>
                {recommendation
                  ? `${localizedWorkflowState(recommendation.status, recommendationInChinese)} · ${recommendation.overallConfidence.score}%`
                  : '等待生成'}
              </dd>
            </div>
          </dl>
          {authenticated && !sessionCompleted && (
            <button className="secondary-button" type="button" onClick={handleCompleteSession}>
              结束并保存会话
            </button>
          )}
        </aside>

        <section className="chat-panel" aria-label="MedNav 对话">
          <div className="messages">
            {viewingHistory && (
              <div className="readonly-banner">
                这是已关闭的历史会话，仅可查看，不能继续发送消息。
              </div>
            )}
            {messages.map((message) => (
              <article key={message.id} className={`message ${message.role}`}>
                <span className="message-role">
                  {message.role === 'user' ? '你' : message.role === 'system' ? '系统' : 'MedNav'}
                </span>
                <p>{message.content || '…'}</p>
              </article>
            ))}

            {secretRequest && (
              <form className="secret-card" onSubmit={handleSecretSubmit}>
                <span className="secret-badge">安全输入</span>
                <label htmlFor="auth-password">
                  {secretRequest.purpose === 'LOGIN' ? '登录' : '注册'}：{secretRequest.username}
                </label>
                <p>此密码不会加入聊天记录，也不会发送给模型。</p>
                <div className="secret-row">
                  <input
                    id="auth-password"
                    type="password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    minLength={8}
                    maxLength={100}
                    autoComplete={secretRequest.purpose === 'LOGIN' ? 'current-password' : 'new-password'}
                    autoFocus
                    required
                  />
                  <button type="submit" disabled={!password || authenticating}>
                    {authenticating ? '验证中' : secretRequest.purpose === 'LOGIN' ? '登录' : '注册'}
                  </button>
                </div>
                {secretError && <p className="secret-error">{secretError}</p>}
              </form>
            )}

            {authenticated && manualLocationRequired && !sessionCompleted && (
              <form className="location-card" onSubmit={handleManualLocation}>
                <span className="location-badge">需要位置信息</span>
                <label htmlFor="manual-location">输入美国地址、城市和州，或邮政编码</label>
                <p>没有可用位置时仍可查看疾病假设，但不会生成附近医院和路线。</p>
                <div className="secret-row">
                  <input
                    id="manual-location"
                    type="text"
                    value={manualAddress}
                    onChange={(event) => setManualAddress(event.target.value)}
                    maxLength={500}
                    placeholder="例如：Dothan, AL 36301"
                    required
                  />
                  <button type="submit" disabled={!manualAddress.trim() || locationSubmitting}>
                    {locationSubmitting ? '解析中' : '使用此位置'}
                  </button>
                </div>
              </form>
            )}

            {recommendation && (
              <section
                className="recommendation-tree"
                aria-label={recommendationInChinese ? '疾病医院医生推荐' : 'Condition hospital doctor recommendations'}
              >
                <header>
                  <span className="recommendation-badge">
                    {recommendationInChinese ? '导诊建议' : 'NAVIGATION GUIDANCE'}
                  </span>
                  <h2>{recommendationInChinese ? '疾病—医院—医生' : 'Condition—Hospital—Doctor'}</h2>
                  <p>
                    {recommendationInChinese
                      ? `信息可信度 ${recommendation.overallConfidence.score}%（反映信息完整性与数据一致性，不代表患病概率）`
                      : `Information confidence ${recommendation.overallConfidence.score}% (reflects completeness and consistency, not the probability of having a condition)`}
                  </p>
                </header>
                {recommendation.diseases.map((disease) => (
                  <article className="disease-card" key={`${disease.diseaseRank}-${disease.hypothesis.name}`}>
                    <div className="tree-heading">
                      <span>{recommendationInChinese ? '可能情况' : 'Possibility'} {disease.diseaseRank}</span>
                      <strong>{disease.hypothesis.name}</strong>
                      <em>
                        {recommendationInChinese
                          ? `相对可能性约 ${Math.round(disease.hypothesis.estimatedProbability * 100)}%`
                          : `Estimated relative likelihood ${Math.round(disease.hypothesis.estimatedProbability * 100)}%`}
                      </em>
                    </div>
                    <p>
                      {recommendationInChinese ? '症状程度' : 'Preliminary severity'}：
                      {localizedSeverity(disease.hypothesis.severity, recommendationInChinese)} ·{' '}
                      {recommendationInChinese ? '信息可信度' : 'Information confidence'}{' '}
                      {disease.diseaseConfidence.score}%
                    </p>
                    <p className="recommendation-reason">
                      {visibleDiseaseReason(disease, recommendationInChinese)}
                    </p>
                    <ul>
                      {disease.hypothesis.supportingFacts.map((fact) => <li key={fact}>{fact}</li>)}
                    </ul>
                    {disease.hospitals.map((hospital) => (
                      <section className="hospital-card" key={hospital.hospitalId}>
                        <div className="tree-heading">
                          <span>{recommendationInChinese ? '医院' : 'Hospital'} {hospital.hospitalRankWithinDisease}</span>
                          <strong>{hospital.name}</strong>
                          <em>
                            {hospital.cmsRating == null
                              ? (recommendationInChinese ? '暂无综合评分' : 'No overall rating')
                              : (recommendationInChinese
                                ? `医院综合评分 ${hospital.cmsRating}/5`
                                : `Hospital rating ${hospital.cmsRating}/5`)}
                          </em>
                        </div>
                        <p>{hospital.address}</p>
                        {hospital.drive && (
                          <p>
                            {recommendationInChinese ? '预计驾车' : 'Estimated drive'}{' '}
                            {localizedDistance(hospital.drive.distanceMiles, recommendationInChinese)} ·{' '}
                            {recommendationInChinese
                              ? `约 ${hospital.drive.durationMinutes} 分钟`
                              : `about ${hospital.drive.durationMinutes} minutes`}
                          </p>
                        )}
                        <p className="recommendation-reason">
                          {visibleHospitalReason(hospital, recommendationInChinese)}
                        </p>
                        <ul>
                          {visibleHospitalFacts(hospital, recommendationInChinese).map((reason) => <li key={reason}>{reason}</li>)}
                        </ul>
                        {hospital.doctors.length === 0 ? (
                          <p className="limitation">{hospital.limitations[0]}</p>
                        ) : hospital.doctors.map((doctor) => (
                          <article className="doctor-card" key={doctor.npi}>
                            <strong>{doctor.name}{doctor.credential ? `, ${doctor.credential}` : ''}</strong>
                            <span>
                              {recommendationInChinese ? '医生唯一编号（NPI）' : 'National Provider Identifier (NPI)'}{' '}
                              {doctor.npi}
                            </span>
                            <p className="recommendation-reason">
                              {visibleDoctorReason(doctor, recommendationInChinese)}
                            </p>
                            <ul>
                              {visibleDoctorFacts(doctor, recommendationInChinese).map((reason) => <li key={reason}>{reason}</li>)}
                            </ul>
                          </article>
                        ))}
                      </section>
                    ))}
                    {disease.hospitals.length === 0 && (
                      <p className="limitation">{disease.limitations[0]}</p>
                    )}
                  </article>
                ))}
              </section>
            )}
          </div>

          <form className="composer" onSubmit={handleSubmit}>
            <label htmlFor="message">
              {viewingHistory ? '历史会话（只读）' : authenticated ? '继续对话' : '输入用户名'}
            </label>
            <div className="composer-row">
              <textarea
                id="message"
                value={input}
                onChange={(event) => setInput(event.target.value)}
                placeholder={
                  authenticated
                    ? '可填写画像信息，或描述本次症状。'
                    : '例如：alex_chen'
                }
                rows={3}
                maxLength={8000}
                disabled={!conversation || Boolean(secretRequest) || sessionCompleted || viewingHistory}
              />
              <button type="submit" disabled={!canSubmit}>
                {submitting ? '处理中' : '发送'}
              </button>
            </div>
          </form>
        </section>
      </section>
    </main>
  )
}
