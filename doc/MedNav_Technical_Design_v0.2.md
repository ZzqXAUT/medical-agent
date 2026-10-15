# Medical Navigation Agent 技术设计 v0.2

> Stateful Healthcare Navigation Agent Platform — 面向美国应用场景的技术验证基线

| 项目 | 内容 |
|---|---|
| 状态 | 设计讨论稿，供实现与评审使用 |
| 日期 | 2026-08-27 |
| 继承 | `MedNav_Technical_Design_v0.1.docx` |
| 目标市场 | 美国 |
| 产品阶段 | 技术验证原型；对标实际应用场景，不作为当前生产部署方案 |

## 1. 版本结论

v0.2 在 v0.1 的基础上确定以下实现原则：

1. 使用单一 Medical Navigation Agent，不在 MVP 引入 Multi-Agent。
2. 使用 AgentScope Java `ReActAgent` 驱动动态 Reason-Act Loop，不使用固定业务状态机预先规定 Agent 的行动顺序。
3. 保留结构化 `NavigationContext` 记录事实、缺失信息、工具结果和证据，但它不是用于驱动固定流程的状态机。
4. Safety Gate、Provider 硬过滤、排序、验证、工具权限、预算、日志脱敏和 Memory Policy 均为不可跳过的确定性能力。
5. Care Urgency 与 Specialty Routing 是两个不同维度，不用一个标签同时表达。
6. 每次用户补充症状后必须重新进行 Safety Screening。
7. Provider 实体和属性只能来自带来源与时间戳的数据。未知信息必须显示为 unknown，不能从缺失值推断否定结论。
8. MVP 不执行诊断、处方、预约、取消预约、联系机构或分享患者信息。
9. 以真实应用所需的可靠性建设安全、隐私最小化、评测与发布门禁，但当前阶段不展开法律法规和生产合规设计。

## 2. 产品边界与预期用途

### 2.1 预期用途

系统帮助美国用户根据当前症状、粗粒度位置和导航偏好：

- 识别可能需要立即处理的安全信号；
- 建议就医紧迫程度；
- 建议可能合适的科室；
- 检索并排序真实存在的附近机构或医生；
- 基于可验证证据解释推荐结果。

### 2.2 明确非目标

- 不诊断疾病，不输出“你患有某疾病”等确定性结论。
- 不给出处方、药物剂量调整或替代临床诊疗的建议。
- 不宣称排除严重疾病。
- 不保证 Provider 的实时接诊、保险网络、费用或预约状态，除非对应字段有实时且可追溯的数据源。
- 不把模型输出描述为医生意见。

### 2.3 初始人群范围

MVP 默认服务 18 岁及以上成人。以下情况进入受限路径，不使用普通成人规则直接给出常规建议：

- 未成年人；
- 怀孕或产后用户；
- 无法确认年龄；
- 存在自伤、他伤、意识障碍或其他紧急安全信号；
- 规则库尚未覆盖的高风险基础状况。

受限路径不等于拒绝服务：系统仍提供保守的就医升级建议和适用的紧急资源，但不做精细化低风险分流。儿童、孕产妇规则需经临床审核后才能逐项开放。

## 3. 技术验证范围与数据安全基线

### 3.1 当前阶段假设

- 系统面向美国医疗服务体系设计，因此采用 911、Emergency Department、Urgent Care、Primary Care 等美国场景概念。
- 当前目标是验证 Agent 架构、医疗导航流程、真实 Provider 检索、推荐质量和评测机制，不执行正式生产部署。
- 测试优先使用合成、去标识化或获得授权的数据，不把接入真实敏感医疗数据作为技术验证的必要条件。
- HIPAA、FTC、FDA、州法律、合同、认证和正式合规审查不属于当前版本范围；若项目进入真实生产部署，需另立合规与安全设计文档。

### 3.2 数据安全与隐私最小化

即使当前不讨论法律法规，仍保留以下技术原则，以避免原型形成不必要的数据风险并降低未来工程返工：

- 数据传输和存储加密；密钥与业务数据分离管理。
- Session、Memory、Audit、工程日志和评测数据逻辑隔离。
- 原始症状文本默认不进入普通日志、Trace 或指标标签。
- 使用最小权限控制开发、测试和演示数据访问。
- 提供清除 Session、删除偏好和关闭长期记忆的能力。
- 模型、Tool、日志和监控的数据流应有文档记录。
- 禁止把用户健康内容用于与当前导航任务无关的画像或分析。

### 3.3 紧急信息

- 普通医疗紧急情况：明确提示用户拨打 911 或前往最近的 Emergency Department。
- 自伤/自杀相关信号：展示 988 call/text/chat 资源；若存在迫在眉睫的危险，同时提示 911。
- 不应声称系统已通知急救机构；除非未来确实实现且完成明确授权与成功确认。
- 紧急响应不应被 Provider API、LLM 或地理编码失败阻塞。

## 4. 总体架构

```text
Client
  ↓
Spring Boot API ── Authentication / Consent / Rate Limit
  ↓
MedicalNavigationAgent（AgentScope Java ReActAgent）
  ├── AgentState / Session
  ├── NavigationContext
  ├── Toolkit
  ├── Hooks
  ├── maxIters / Budget / Timeout
  └── Model + Formatter
        ↕ Reason / Act
Domain Guardrails
  ├── InputSafetyGate
  ├── ToolPolicyGate
  ├── SafetyEngine
  ├── RankingService
  ├── RecommendationVerifier
  └── ResponseSafetyGate
        ↓
Data and Services
  ├── Versioned Medical Routing Knowledge
  ├── Geocoding / Distance
  ├── Provider Registry and Verification
  ├── AgentStateStore / Session Store
  └── Consent-governed Preference Store
```

### 4.1 技术栈

| 层次 | 选型 |
|---|---|
| Language | Java 17+ |
| Application | Spring Boot |
| Agent Framework | AgentScope Java |
| Agent | `ReActAgent`；技术验证初期不默认启用完整 `HarnessAgent` |
| Tool | AgentScope `Toolkit` + `@Tool` |
| Session State | `AgentState` + `AgentStateStore` |
| Schema | Java record/POJO + Bean Validation / JSON Schema |
| Persistence | PostgreSQL；Redis 按多实例和 Session 需要引入 |
| Observability | OpenTelemetry + Micrometer |
| Test | JUnit 5 + Testcontainers + Agent evaluation runner |

选择 Java 版 AgentScope 的原因：项目可以保留 Spring Boot 生态、强类型 Domain Model、现有 Java 服务能力，同时获得 ReAct、Toolkit、Hook、Session/State 和可观测性。AgentScope Python 不是本项目的必要依赖。

技术验证阶段优先使用轻量 `ReActAgent`，由项目显式控制医疗 Memory、Tool 和 Guardrail。完整 `HarnessAgent` 的 Workspace、自动长期记忆、Session 日志和自学习能力只有在逐项验证其数据生命周期及必要性后才启用，禁止直接采用无限积累的默认长期记忆。

参考：[AgentScope Java 官方仓库](https://github.com/agentscope-ai/agentscope-java)、[AgentScope Java Quickstart](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/quickstart.md)。

## 5. AgentScope ReAct 控制循环

```java
ReActAgent agent = ReActAgent.builder()
    .name("MedicalNavigationAgent")
    .sysPrompt(medicalNavigationPrompt)
    .model(chatModel)
    .toolkit(medicalToolkit)
    .maxIters(12)
    .build();

// Pre-call gate 在 Agent 推理前执行确定性安全预检。
// Pre-acting gate 校验每次 Tool 调用。
// Agent 动态决定下一步，不由业务状态机指定行动顺序。
Msg candidate = agent.call(userMessage, runtimeContext).block();

// 最终候选响应必须经过确定性验证，Agent 不能自行跳过。
return responseGate.verifyOrFallback(candidate, navigationContext);
```

约束：

- Agent 可以根据当前上下文动态选择提取信息、追问、检索知识、解析位置、查询 Provider、查看详情或准备回答。
- 不要求每次 Run 经过相同顺序，也不使用 `orchestrator.next()`、Transition 或 reducer 规定业务路径。
- 影响安全、路由和 Provider 事实的数据必须通过结构化 Tool/Schema 写入 `NavigationContext`；自由文本不能直接成为已验证事实。
- 单次 Run 默认 `maxIters = 12`、最多 2 次模型重试、每种只读 Tool 最多 2 次尝试。
- Retry 只适用于超时、限流和明确的临时故障；schema 错误只允许一次修复。
- 紧急信号、预算耗尽、用户取消或不可恢复错误会由 Hook/外层控制器终止 ReAct Loop。
- Agent 的行动顺序可以不确定，但 Safety、Authorization、Evidence 和 Final Verification 不变量固定。

## 6. 动态行动模型与固定不变量

```text
User message
  ↓
Deterministic input safety gate
  ↓
AgentScope ReAct Loop
  ├─ reason about missing information
  ├─ extract/update structured facts through Tool
  ├─ ask a clarification question and end this turn
  ├─ retrieve routing knowledge
  ├─ resolve location
  ├─ search facilities/doctors
  ├─ inspect Provider evidence
  └─ compose a candidate response
  ↓
Deterministic response verification
  ├─ PASS → return response
  ├─ REPAIRABLE → one bounded repair attempt
  └─ FAIL → safe fallback
```

系统不规定 Clarification、Routing、Location 和 Provider Discovery 的固定先后顺序。例如，Agent 可以先发现位置缺失并追问，也可以先识别症状中的紧急信号；但以下不变量不可违反：

1. 每条新症状信息在普通推荐前必须经过 Safety Engine。
2. 确定性规则产生的紧急等级不能被模型降低。
3. 未通过授权与 schema 校验的 Tool 不执行。
4. Provider 必须来自 Provider Tool，并携带来源和时间戳。
5. Ranking 只能由 `RankingService` 执行，模型不能声明自行排序分数。
6. 用户可见事实必须映射到 Evidence ID。
7. 最终响应必须经过 `RecommendationVerifier` 和 `ResponseSafetyGate`。
8. 达到迭代、Tool、Token 或时间预算后必须停止并安全降级。

## 7. 结构化 NavigationContext

`NavigationContext` 是 Agent 的领域黑板（blackboard），用于在不确定行动之间共享结构化事实。字段可以按任意合法顺序逐步补全；它不包含决定下一步行动的流程状态，也不构成有限状态机。

```yaml
NavigationContext:
  schemaVersion: string
  run:
    runId: uuid
    sessionId: uuid
    traceId: string
    startedAt: instant
    iterationCount: integer
    toolCallCount: integer
    policyVersions: map<string,string>
  patientContext:
    ageBand: enum[UNKNOWN, ADULT, OLDER_ADULT, MINOR]
    pregnancyStatus: enum[UNKNOWN, NOT_APPLICABLE, PREGNANT, POSTPARTUM, NOT_PREGNANT]
    language: string
    accessibilityNeeds: list<string>
    highRiskContext: list<CodedFact>
  symptoms:
    chiefConcern: string
    onset: nullable<DurationOrTime>
    severity: enum[UNKNOWN, MILD, MODERATE, SEVERE]
    trajectory: enum[UNKNOWN, IMPROVING, STABLE, WORSENING]
    bodySites: list<CodedFact>
    associatedSymptoms: list<CodedFact>
    negativeFindings: list<CodedFact>
    sourceSpans: list<EvidenceSpan>
    extractionConfidence: decimal
  safety:
    urgency: enum[EMERGENCY, URGENT, SOON, ROUTINE, SELF_CARE_POSSIBLE, UNKNOWN]
    matchedRules: list<RuleMatch>
    modelSignals: list<Signal>
    restrictedPopulation: boolean
    escalationInstructions: list<string>
    safetyPolicyVersion: string
  clarification:
    missingCriticalFields: list<string>
    questionsAsked: list<QuestionRecord>
    maxQuestionsRemaining: integer
  routing:
    careSetting: enum[ED, URGENT_CARE, SAME_DAY_PRIMARY_CARE, PRIMARY_CARE, SPECIALIST, TELEHEALTH, UNKNOWN]
    specialties: list<SpecialtyRecommendation>
    evidence: list<EvidenceRef>
    routingPolicyVersion: string
  location:
    inputText: sensitive<string>
    latitude: sensitive<decimal>
    longitude: sensitive<decimal>
    postalCode: sensitive<string>
    state: string
    precision: enum[EXACT, APPROXIMATE, ZIP_CENTROID, UNKNOWN]
  preferences:
    searchRadiusMiles: decimal
    travelMode: nullable<string>
    language: nullable<string>
    insurancePlan: nullable<sensitive<string>>
    persistedFields: list<string>
    consentId: nullable<uuid>
  providers:
    candidates: list<ProviderCandidate>
    ranked: list<RankedProvider>
    dataAsOf: instant
  verification:
    status: enum[NOT_RUN, PASS, REPAIRABLE_FAIL, HARD_FAIL]
    checks: list<VerificationCheck>
    unsupportedClaims: list<string>
  response:
    responseType: enum[CLARIFICATION, EMERGENCY, RECOMMENDATION, SAFE_FALLBACK]
    evidenceIds: list<string>
```

`AgentState` 保存 AgentScope 的消息上下文、Session 恢复信息和框架运行状态；`NavigationContext` 保存医疗导航领域事实。两者不能混为一体。字段级别同时定义 `sensitivity`、`retentionClass` 和 `allowedPurposes`，不能只依赖 Java 类型。

## 8. Agent 能力、Tool 与职责边界

下列能力由同一个 `MedicalNavigationAgent` 在 ReAct Loop 中组合，不拆成多个 Agent，也不要求依序执行：

| Capability | 输入 | 输出 | 不允许做的事 |
|---|---|---|---|
| SymptomUnderstanding | 用户文本、已有症状 | `SymptomExtraction` | 诊断、决定最终 urgency |
| SafetyTriage | Structured Symptoms、patient context | 模型安全信号 | 绕过 SafetyEngine、生成 Provider |
| Clarification | 缺失字段、安全与路由不确定性 | 0–3 个问题 | 为填表询问低价值问题 |
| CareUrgencyRouting | Safety result、routing evidence | care setting | 降低确定性安全等级 |
| SpecialtyRouting | symptoms、care setting、knowledge | 1–3 个 specialty | 凭模型常识制造指南事实 |
| ProviderDiscovery | location、routing、constraints | 查询计划、Provider IDs | 生成虚构 Provider |
| ProviderExplanation | verified evidence | 用户解释 | 引入 evidence 之外的新事实 |
| PreferenceManagement | 用户明确表达、consent | preference mutation proposal | 自动保存症状/推断偏好 |

AgentScope `Toolkit` 暴露原子只读 Tool，如 `updateSymptoms`、`retrieveRoutingKnowledge`、`resolveLocation`、`searchNearbyFacilities`、`searchDoctors` 和 `getProviderDetails`。`SafetyEngine`、`RankingService`、`RecommendationVerifier`、日志脱敏和授权校验不注册成 Agent 可选 Tool，而由 Hook 或外层 Service 强制执行，避免被模型选择性跳过。

## 9. Safety Engine

### 9.1 决策合并

```text
LLM semantic signals
       +
versioned deterministic rules
       +
restricted-population policy
       ↓
highest-risk-wins merge
       ↓
TriageDecision + matched evidence + rule version
```

- 规则命中可提升风险等级，模型不得降低规则产生的等级。
- 冲突或数据不足时采用更保守结果。
- 紧急规则命中后停止普通推荐，避免“911 提示后继续列普通门诊”的混合响应。
- 每条规则包含 `ruleId/version/jurisdiction/population/inputs/decision/rationale/source/reviewer/effectiveDate/expiryDate`。
- 医疗规则变更需临床 reviewer 审批、回归评测、shadow deployment 和可回滚版本。

### 9.2 Clarification

```text
QuestionValue = 4×SafetyImpact + 2×RoutingImpact + 1×ProviderImpact - UserBurden
```

仅询问答案可能改变安全级别、care setting、specialty 或关键 Provider 过滤条件的问题。单轮最多 3 个；累计最多 5 个。出现紧急信号时不等待追问答案再给紧急指导。

## 10. Provider Domain 与数据治理

### 10.1 统一模型

```yaml
Provider:
  providerId: string
  type: enum[PRACTITIONER, ORGANIZATION, FACILITY]
  npi: nullable<string>
  legalName: string
  displayName: string
  specialties: list<VerifiedAttribute>
  locations: list<PracticeLocation>
  facilityAffiliations: list<VerifiedAttribute>
  licenses: list<LicenseEvidence>
  exclusions: list<ExclusionEvidence>
  acceptingNewPatients: nullable<VerifiedAttribute>
  insuranceNetworks: list<VerifiedAttribute>
  availability: list<VerifiedAttribute>
  sourceRecords: list<SourceRecord>
  freshness: FreshnessStatus
```

`VerifiedAttribute` 必须包含 `value/source/retrievedAt/effectiveAt/verificationStatus`。

### 10.2 数据源策略

- NPPES V2 用于 NPI、名称、taxonomy 和 practice location 的基础发现。
- NPI 不是执照或 credential 验证。CMS 明确说明 NPI 的签发不能证明 Provider 已获执照或完成资质认证；UI 和排序不得把 NPI 状态描述成“已认证医生”。参考 [CMS NPPES NPI Files](https://download.cms.gov/nppes/NPI_Files.html)。
- 执照状态需接入相关州 Licensing Board 或经批准的 credentialing 数据源。
- 排除状态需要独立、定期更新的数据源。
- 保险网络和预约状态只能显示带时间戳的来源；过期后降级为 unknown。
- 地址去重使用 NPI、规范化名称、地址、电话和 affiliation 的组合，禁止仅按名称合并。

### 10.3 MVP 数据落地建议

第一阶段限定一个州和一组都会区，完成 NPPES V2 ingestion + 州执照验证 + 地理编码。这样可以建立真实、可测试的数据闭环，再扩展全国，而不是一开始依赖质量不明的全国聚合结果。

## 11. Provider 排序

### 11.1 先过滤后评分

硬过滤：

- Provider/Facility 记录存在且未停用；
- 目标地点有效；
- Facility 支持目标 care setting；
- Provider specialty 满足最低匹配阈值；
- 明确排除或执照无效时不可进入推荐；
- freshness 超过策略上限时不可用于肯定性 claim。

软评分（初始建议，需用评测集校准）：

```text
score = 0.35 * clinicalSpecialtyMatch
      + 0.20 * careSettingMatch
      + 0.15 * distanceScore
      + 0.10 * verifiedQualitySignals
      + 0.10 * preferenceMatch
      + 0.05 * insuranceEvidence
      + 0.05 * availabilityEvidence
      - missingDataPenalty
```

保险和 availability 缺失时记为 unknown，不记为 false。每个结果返回 feature contribution、policy version 和稳定 tie-breaker。不得使用受保护属性或不透明代理变量进行歧视性排序。

## 12. Recommendation Verification

发布响应前固定执行：

1. Safety 决策未被后续步骤降级或遗漏。
2. care setting 与 Safety/路由证据一致。
3. 每个 Provider ID 可回溯到数据源。
4. specialty、地址、距离和 affiliation 内部一致。
5. 推荐中所有事实 claim 均映射到 Evidence ID。
6. 保险、availability、执照和质量 claim 的验证级别及 freshness 满足策略。
7. 解释未出现诊断、保证性用语或证据外事实。
8. 紧急响应没有被普通推荐内容稀释。

修复最多一次；仍失败则返回不包含未经验证 Provider 的 Safe Fallback。

## 13. Memory Policy

| 数据 | 默认范围 | 长期保存 | 说明 |
|---|---|---|---|
| 原始症状文本 | 当前 Run/Session | 禁止默认保存 | 仅按明确目的和同意例外处理 |
| 结构化症状和 triage | 当前 Run/Session | 禁止默认保存 | 不将导航系统变成影子病历 |
| 粗粒度位置 | Session | 明确 opt-in | 默认不保存精确坐标 |
| 语言/无障碍偏好 | Session | 可提示用户 opt-in | 可随时查看、修改、删除 |
| 距离/交通偏好 | Session | 可提示用户 opt-in | 记录 consentId |
| 保险计划 | Session | 单独明确同意 | 敏感字段，加密且限制用途 |
| 推荐历史 | Session | 明确同意 | 需定义产品目的和保留期 |

禁止从对话推断并静默保存疾病、残障、怀孕、心理健康或其他敏感画像。

## 14. Hook 生命周期

顺序固定：

```text
BeforeRequest
→ AuthenticationAndConsent
→ InputMinimization
→ AgentScope PreCallEvent / InputSafetyGate
→ PreReasoningEvent
→ PostReasoningEvent / SchemaCheck
→ PreActingEvent / ToolPolicyGate
→ PostActingEvent / ToolSchemaAndProvenance
→ EmergencySafety（每次症状更新后）
→ PostCallEvent / BeforeResponseVerification
→ PHIRedaction（写日志前）
→ MemoryWritePolicy
→ AuditCommit
```

Hook 与外层 Gate 的统一结果为 `CONTINUE | BLOCK | TERMINATE | RETRY | SAFE_FALLBACK`。Safety、Authorization、Schema 和 Verification 逻辑不可被 Agent 捕获后忽略。Hook priority 必须显式配置并进行顺序测试。Audit commit 失败时不记录敏感 payload；技术验证策略决定是否阻断高风险响应。

## 15. 日志、Trace 与 Audit

- Application Log：服务健康、错误码、时延；禁止 PHI。
- Trace：run/step/skill/tool/service span；只保留白名单属性，禁止症状、地址、保险信息进入 span attribute。
- Safety Audit：规则、输入事实引用、结果和版本；严格访问控制。
- Recommendation Audit：Provider source、排序贡献、验证结果和响应 evidence mapping。
- Consent Audit：consent 文本版本、用途、字段、时间、撤回状态。
- Security Audit：访问、导出、删除、权限变更和异常查询。

审计记录不保存模型隐藏推理过程，只保存结构化输入摘要、决策结果、证据、版本和可重放参数。

## 16. Agent 评测机制

### 16.1 评测目标

评测必须证明系统在安全性、临床导航合理性、grounding、Provider 正确性、隐私、安全、公平性、鲁棒性和运行成本上达到发布标准。平均总分不能抵消关键安全失败。

### 16.2 四层评测

#### A. 确定性单元与契约测试

- Safety rule truth table、边界值和规则优先级。
- Agent 行动不变量：Safety、Tool Policy、Evidence 和 Final Verification 不可跳过。
- Tool 输入输出 schema、timeout、retry、idempotency。
- AgentScope Hook 触发顺序、阻断、终止和异常传播。
- `maxIters`、Tool 次数、Token 和时间预算耗尽时的安全降级。
- `AgentState` 与 `NavigationContext` 的 Session 隔离和恢复。
- Provider 去重、freshness、unknown 语义和硬过滤。
- Ranking 单调性：距离变差不应在其他特征不变时提高距离贡献。
- Redaction、Memory Write Policy 和 consent 撤回。

每次提交运行，不依赖外部 LLM 的稳定输出。

#### B. 离线场景集评测

建立版本化 `MedNav Evaluation Set`，每个 case 包含：

```yaml
caseId: string
population: demographic tags
conversationTurns: list
locationFixture: synthetic or consented
expectedSafetyRange: enum/set
mustAsk: list<clinical concept>
mustNotAsk: list<clinical concept>
acceptableCareSettings: list
acceptableSpecialties: list
providerFixtureSnapshot: string
mustIncludeClaims: list
mustNotIncludeClaims: list
riskTier: CRITICAL|HIGH|NORMAL
reviewerLabels: list
```

场景至少覆盖：明显红旗、隐晦红旗、否定表达、症状恶化、多轮补充、拼写错误、口语、Spanish/English、prompt injection、未成年人、孕产妇、高龄、高风险基础病、位置模糊、Provider 空结果、API 超时、数据冲突与陈旧数据。

由于 ReAct 路径不固定，同一个 case 至少使用多个随机种子重复运行。评测既判断最终结果，也判断轨迹约束：是否完成 Safety Check、调用了哪些 Tool、是否重复循环、是否使用无来源事实，以及在不同路径下是否稳定满足相同不变量。不能要求所有成功轨迹具有完全相同的步骤顺序。

金标准由至少两名合格临床 reviewer 独立标注；分歧通过 adjudication 解决。Provider 答案使用冻结数据快照保证可重放。

#### C. 端到端仿真与对抗评测

- 用户模拟器进行多轮任务完成测试，但不得将模拟器评分作为唯一真值。
- 红队测试医疗误导、紧急信号绕过、提示注入、数据外泄、越权 Tool、身份混淆和恶意 Provider 数据。
- 故障注入覆盖 LLM 超时、Provider API 5xx、部分结果、schema 漂移、Trace/Audit 不可用。
- 使用不同模型或独立规则 grader 检查表达质量；关键 Safety 与事实指标必须由程序或人工判定。

#### D. 生产前与生产中评测

- Shadow mode：不影响用户决策，比较新旧 policy/model。
- Canary：小流量、实时 Safety 告警、自动回滚开关。
- 上线后抽样人工审核、用户反馈分类、Provider 错误举报和近失事件复盘。
- 技术验证阶段优先使用合成案例；真实对话进入评测集前必须去标识化并经过项目数据负责人批准。

### 16.3 核心指标

| 维度 | 指标 | 说明 |
|---|---|---|
| Safety | Emergency recall | 关键紧急案例被识别比例 |
| Safety | Dangerous under-triage rate | 输出低于可接受安全下界的比例 |
| Safety | Over-triage rate | 监控用户负担，不能以降低 recall 为代价优化 |
| Routing | Care-setting accuracy | 与 reviewer 可接受集合匹配 |
| Routing | Specialty top-k recall | Top 1/3 中包含可接受 specialty |
| Clarification | Critical-question recall | 应问的关键安全问题是否问到 |
| Clarification | Unnecessary-question rate | 不改变决策的追问比例 |
| Grounding | Unsupported claim rate | 没有 Evidence ID 的事实 claim 比例 |
| Provider | Entity precision | 推荐实体是否真实对应数据记录 |
| Provider | Attribute accuracy/freshness | specialty/location/claim 是否正确且未过期 |
| Ranking | nDCG@k / constraint satisfaction | 排序相关性与硬约束满足率 |
| Privacy | PHI leakage rate | 日志、Trace、模型上下文和错误响应泄漏率 |
| Robustness | Safe fallback success | 依赖故障时是否安全降级 |
| Fairness | Subgroup safety gap | 各人群 under-triage 差异及置信区间 |
| UX | Task completion / comprehension | 用户是否理解下一步及理由 |
| Ops | p50/p95 latency, tokens, cost | 按步骤和完整 Run 统计 |

### 16.4 MVP 发布门禁（初始建议）

以下是工程启动阈值，必须由临床、安全和产品负责人评审后冻结：

- Critical emergency set：Emergency recall = 100%，不允许已知漏诊式下分流。
- 全量高风险集：dangerous under-triage rate ≤ 0.5%，且每个失败完成人工复盘。
- Unsupported provider/entity claim rate = 0。
- Provider entity precision = 100%；关键属性 claim accuracy ≥ 99%。
- 硬约束满足率 = 100%。
- PHI 写入普通日志/Trace 的测试泄漏率 = 0。
- Prompt injection 导致越权 Tool 或绕过 Safety 的成功率 = 0。
- Safe fallback success ≥ 99.5%。
- 各预定义人群的 emergency recall 不得低于总体现行门禁；样本不足时不得声称公平性已验证。
- p95 非紧急完整推荐延迟目标 ≤ 8 秒；紧急静态响应不等待外部依赖，目标 ≤ 1 秒。

任意 Critical case 回归、越权、PHI 泄漏或虚构 Provider 都是阻断发布事件，不能用平均分豁免。

### 16.5 评测数据与运行记录

每次评测生成不可变 Evaluation Run：

```yaml
evaluationRun:
  evalRunId: uuid
  datasetVersion: string
  applicationCommit: string
  modelProvider: string
  modelName: string
  modelSnapshotOrVersion: string
  promptVersions: map
  policyVersions: map
  agentScopeVersion: string
  agentConfigVersion: string
  maxIters: integer
  providerSnapshot: string
  startedAt: instant
  metrics: map
  failedCaseIds: list
  reviewerApprovalIds: list
  releaseDecision: PASS|FAIL|WAIVER_REQUIRED
```

不得在没有记录 model/prompt/policy/provider snapshot 的情况下比较两次评测结果。任何 waiver 必须具名、限时、说明风险与补救计划；Critical Safety/Privacy 门禁不可 waiver。

## 17. API MVP

```text
POST   /v1/navigation/runs
POST   /v1/navigation/runs/{runId}/messages
GET    /v1/navigation/runs/{runId}
DELETE /v1/navigation/runs/{runId}
GET    /v1/preferences
PATCH  /v1/preferences
DELETE /v1/preferences/{field}
POST   /v1/consents
DELETE /v1/consents/{consentId}
POST   /v1/provider-feedback
```

API 不返回内部 chain-of-thought。响应包含用户可理解的理由、证据时间、数据限制、明确下一步和适用的安全升级条件。

## 18. 推荐代码结构

```text
com.example.mednav
├── api
├── agent
│   ├── MedicalNavigationAgent
│   ├── AgentConfiguration
│   ├── prompt
│   └── context
│       ├── NavigationContext
│       └── NavigationContextStore
├── runtime
│   ├── session
│   ├── budget
│   └── termination
├── capability
├── tool
│   ├── MedicalNavigationToolkit
│   ├── contract
│   ├── adapter
│   └── policy
├── safety
├── routing
├── provider
│   ├── domain
│   ├── ingestion
│   ├── verification
│   └── ranking
├── verification
├── memory
├── consent
├── hook
│   ├── InputSafetyHook
│   ├── ToolPolicyHook
│   ├── ProvenanceHook
│   └── ResponseVerificationHook
├── policy
├── observability
├── audit
├── evaluation
│   ├── dataset
│   ├── runner
│   ├── grader
│   ├── metrics
│   └── report
└── security
```

## 19. 分阶段实现计划

### Phase 0：需求与技术边界

- 明确验证地区、成人范围、用户任务、输出边界和临床评审负责人。
- 建立数据流图、外部服务清单和技术风险登记册。
- 准备合成用户场景、Provider 数据 fixture 和安全测试集。
- 选择首个州、都会区和 Provider 数据源。

### Phase 1：无 LLM 的可靠骨架

- Spring Boot 与 AgentScope Java 基础集成。
- `NavigationContext`、AgentScope `AgentState`/Session、Budget、Hook 和 Audit。
- Safety 规则框架及测试 fixture。
- Provider domain、NPPES ingestion、州执照验证、地理检索。
- Ranking、Verification 和 Safe Fallback。
- Evaluation runner 与 CI 门禁。

### Phase 2：受限 LLM 能力

- `MedicalNavigationAgent` ReAct Loop 与受限 Toolkit。
- Structured symptom extraction、Clarification 和 Specialty Routing。
- Evidence-grounded explanation。
- 动态行动轨迹、Prompt injection、schema 和故障注入评测。

### Phase 3：临床与安全验证

- 临床标注集、独立复核、subgroup 分析。
- Shadow test、红队、隐私和安全测试。
- 通过技术验证门禁后进行受控演示或小规模用户研究；不等同于生产发布。

### Phase 4：扩展

- 新州和新人群必须作为独立 policy/data expansion 通过评测。
- 保险与 availability 先作为 Retrieval 能力引入。
- 预约等 Action Tool 需另行完成授权、确认、幂等、撤销和 Human-in-the-loop 设计。

## 20. 尚待确认的决策

1. MVP 首发州与都会区。
2. 目标用户画像以及技术验证采用模拟用户、研究参与者还是内部测试人员。
3. 临床规则审核人与规则质量治理流程。
4. 州执照与 Provider 数据的具体供应商。
5. 模型与云供应商的数据保留选项、成本、延迟和结构化输出能力。
6. 成人中的高风险群体首版覆盖边界。
7. 用户身份体系：匿名 Session、账户制或两者并存。
8. 生产数据 retention schedule 与删除 SLA。

## 21. Definition of Done

MVP 只有在以下条件全部满足后才视为完成技术验证：

- 用户任务、适用人群、输出边界和免责声明经产品与临床评审；
- Provider 数据链路真实、可追溯、可更新；
- Safety 与 Verification 不能被 LLM 跳过；
- 测试数据流、保留、清除和访问控制已落地；
- 评测集、临床标注、CI 门禁、红队和回滚机制可运行；
- 所有 Critical 门禁通过，失败案例已复盘；
- 演示监控能发现 under-triage、虚构/错误 Provider、敏感数据泄漏和依赖故障；
- 验证报告明确记录地区、人群、数据源、模型版本、已知限制和未覆盖风险。

进入真实生产场景不是本版本的 Definition of Done。届时必须补充法律合规、生产安全、运营响应和正式临床验证要求。
