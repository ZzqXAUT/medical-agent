# MedNav 开发日志

本文件简要记录项目构建过程。只记录完成内容、关键技术决定、验证结果与后续事项，不保存冗长命令输出、Secret 或真实用户数据。

## 2026-09-13：M1 可运行项目骨架

完成内容：

- 建立 `backend`、`frontend`、`infra`、`contracts`、`evaluation` 和 `dataset` 等目录边界。
- 后端使用 Spring Boot WebFlux、MyBatis、Flyway、PostgreSQL、Redis 和 AgentScope Java 2.0.1。
- 引入 AgentScope Core、OpenAI 模型扩展和 AG-UI 扩展；未引入 OpenAI 官方 Java SDK。
- 建立 Conversation、Run、SSE 事件链路和本地 Stub Medical Agent。
- 实现同一 Session 的 Run 串行、不同 Session 并行的调度器。
- 建立 React、TypeScript、Vite 聊天界面，以及 OpenAPI、事件 Schema 和初始评测集。

关键决定：

- 先采用模块化单体，避免技术验证阶段过早拆分微服务。
- 无外部 API Key 时使用确定性 Stub，真实模型接入留到后续阶段。
- 修复 Session 队列回收与新 Run 入队的竞争窗口，并增加重复并发回归测试。

验证结果：

- 后端、前端测试和生产构建通过。
- Compose、事件 Schema 和初始评测样本通过格式校验。

## 2026-09-13：M2 对话式认证与用户画像

完成内容：

- 选择项目独立的 `mednav` PostgreSQL 数据库和 Compose volume，不复用或修改未知的本机数据库。
- 新增 Flyway v2，建立 `user_account`、`user_profile`、`session_clinical`、`session_summary`。
- 新增 MyBatis 账户和画像 Mapper、BCrypt 密码散列、用户名规范化与唯一约束。
- 实现新用户名注册、已有用户名登录、错误密码重试和并发重复注册冲突处理。
- 增加一次性 `AUTH_SECRET_REQUEST`；密码通过独立认证接口提交，不进入聊天消息、Agent、SSE 数据、Memory 或日志。
- 新用户画像一次合并询问，支持出生日期、性别、身高和体重；后续只询问缺失字段，最多三轮后降级。
- 支持厘米/千克与英尺/英寸/磅输入，增加日期和数值范围校验。
- 画像在 Session 中暂存，显式结束 Session 时才通过 MyBatis 合并到长期 `user_profile`。
- 前端增加安全密码卡、认证状态和“结束并保存 Session”操作。
- OpenAPI 和事件 Schema 更新至 M2，新增 10 条认证/画像评测样本。

验证结果：

- 后端 35 项测试通过并成功生成可执行 JAR。
- 前端测试及生产构建通过。
- 共 15 条 M1/M2 评测样本、事件 Schema 和 OpenAPI 均通过格式解析。
- 启动项目专属 PostgreSQL 16.15 与 Redis 容器，两者健康检查通过。
- 在真实 PostgreSQL 完成“创建会话—注册—画像采集—结束 Session—读取长期画像”端到端验证，Flyway schema 版本为 2。

当前未完成：

- Session 临时画像仍为进程内实现；Redis 短期记忆、10 分钟空闲终止和异常恢复在 Memory 阶段实现。
- 症状理解仍使用 Stub；真实 AgentScope OpenAI 模型和 Clinical Readiness 属于 M3。
- CMS 导入、定位/路线、医院与医生推荐尚未开始。

## 2026-09-14：M3 症状理解与临床就绪判断

完成内容：

- 建立 `ClinicalAssessmentRequest`、`SymptomCase`、`ClinicalDecision`、疾病假设和严重程度强类型契约。
- 建立确定性 Stub Clinical Engine，使无 API Key 环境可复现四种 readiness 路径。
- 增加 AgentScope Java `MedicalNavigationAgentFactory` 和 OpenAI Clinical Engine；真实模型通过 AgentScope OpenAI 扩展调用。
- 配置主模型、Summary 模型与 Semantic Verification 模型三个延迟加载 Bean；M3 只调用主模型。
- 以 AgentScope Skill 注册 SymptomExtraction、ClinicalReadiness 和 DiseaseInference 三项能力，保持可孤立评测。
- 实现症状上下文合并、一次一个追问、问题去重、最多三轮和 0.60 降级阈值。
- 增加输出合法性与范围门禁：主诉/事实、严重程度、概率、疾病数量和内部专业均需满足契约。
- 接入 Conversation Run；READY 后的新用户消息仍会创建 Run、合并症状并增加 clinical revision。
- 增加临床进度、追问、结果和验证降级事件；公开结果不包含内部 `targetSpecialties`。
- 前端增加临床状态、追问进度和信息完整度展示。
- 新增 M3 输入/输出 JSON Schema 和 10 条模块评测样本。

关键决定：

- 临床路径不使用固定业务转换表：模型选择四态，后端 Gate 只强制结构、三轮预算和阈值。
- `local/test` 默认走 Stub；`openai` 配置为空不会阻止启动，本次 Run 返回明确的可恢复降级结果。
- Summary 与 Semantic Verification 只建立模型边界，不在普通症状 Turn 调用；后者留给最终推荐的条件验证。

验证结果：

- 后端 43 项自动化测试通过，包括四态、追问去重、边界门禁、模型不可用和认证到疾病假设的端到端路径。
- 前端测试和生产构建通过。
- M3 JSON 契约和 10 条评测样本通过格式解析。
- 使用项目专属 PostgreSQL 16.15 与 Redis 7.4.11 启动打包应用，真实跑通“注册—画像—症状—三个疾病假设—结束 Session”；结果为 `READY`，内部专业字段未出现在公开事件中。

当前未完成：

- 未配置真实 OpenAI API，因此尚未执行真实模型抽样评测；当前完成的是可运行适配和可恢复故障路径。
- `session_clinical` 的长期写入、Redis 工作记忆和 Session Summary 留到 M7 Memory 阶段。
- CMS 医院/医生导入属于 M4；定位、Google Route Matrix 和并行 Join 属于 M5；最终三级推荐与条件验证属于 M6。

## 2026-09-14：M4 CMS 本地医院/医生事实库

完成内容：

- 新增 Flyway v3，建立 CMS 快照、源文件、staging、医院、医生、专业、执业地点、任职关系、ACTIVE 指针和刷新租约表。
- 以 CMS 稳定 Dataset ID 获取 Metastore 元数据，再动态解析实际 CSV 分发 URL；支持 ETag、Last-Modified、304 和 SHA-256 内容寻址留档。
- 使用 Apache Commons CSV 流式读取，使用 JDBC batch 写 staging，避免将全量 CSV 或逐行 MyBatis 调用堆在内存和网络往返中。
- 对三份文件执行列名、数量、顺序、类型、非空和校验和关卡；任一文件失败时不切换 ACTIVE。
- 在单一数据库事务中完成标准化、Join 完整性验证和 ACTIVE 指针切换；完成或失败后清理 staging，保留快照、源文件与失败审计元数据。
- 支持按 state/city 查询医院、读取当前专业词表，以及按医院和专业查询医生候选；同一 NPI 的多条 enrollment/practice 记录确定性归一，相同医生命中多个专业时只返回一个候选并携带多条专业事实。
- 增加数据库租约防止多实例重复刷新，增加洛杉矶时区每天凌晨 4 点任务，以及 CMS snapshot 健康指标。
- 新增两个 CMS JSON Schema 和 13 条 M4 评测样本。

真实数据适配决定：

- Facility ID 并非纯数字，存在 `01014F` 等值，因此固定按六位字母数字字符串保存，禁止数值化以保护前导零。
- DAC 的 assignment 字段存在 `M`、`O` 等非 Y/N 分类；当前保留原始 CSV，只将 Y/N 映射为布尔值，其他分类在布尔投影中记为未知。
- Facility Affiliation 包含 Home Health、Hospice 等非 Hospital 行，并且三份公开数据的 NPI/Facility 覆盖不完全；完整性门禁只统计 `facility_type=Hospital` 的适用关系，要求至少存在 Join 结果且 Join 比率默认不低于 0.10。
- 医生没有评分字段，M4 不对医生做质量排序；Provider 查询只做 affiliation + CMS 专业硬匹配和 NPI 去重，M6 再按稳定规则截断。

验证结果：

- 固定 fixture 可发布并完成医院—专业—医生查询；故意调换任职文件 Header 会返回 `CMS_SCHEMA_DRIFT`，ACTIVE snapshot 保持不变。
- 下载客户端测试覆盖稳定 Dataset ID 到分发 URL 的解析、ETag 条件请求、304 和已校验原文件复用；数据库租约并发测试通过。
- 项目提供的真实文件已导入项目 PostgreSQL：Hospital 5,419 行，DAC 1,048,575 行，Affiliation 1,048,575 行；ACTIVE snapshot 为 `65bcc505-14a1-451e-99a9-d5f957850ec7`。
- ACTIVE 标准化结果为医院 5,419、医生 678,252、专业事实 758,137、执业地点 1,032,067、医院任职关系 430,239；全量导入约 4 分 21 秒。
- 实际数据中适用 Hospital affiliation 的三表 Join 比率约 48.40%，高于默认门禁。全量验收后的 6,585,341 条 staging 记录已事务清理，ACTIVE 正式数据、原始文件和审计元数据保留。
- 后端 46 项自动化测试通过；前端测试和生产构建通过；CMS 契约与 13 条 M4 JSONL 样本通过格式解析。

当前未完成：

- 尚未对凌晨 4 点真实网络任务做跨日等待验收；调度表达式、固定时区、下载协议和并发租约已分别测试或检查。
- CMS 查询当前为后端内部 Service，尚未提供独立管理 API；后续推荐链路直接在服务内使用 ACTIVE snapshot。
- 定位、Google Routes 多交通方式、疾病与位置并行 Join 属于 M5；疾病—医院—医生三级树、距离门限和条件语义验证属于 M6。

## 2026-09-14：M5 定位、路线与并行 Join

完成内容：

- 新增浏览器位置授权接口、手动地址接口和导航任务状态查询接口；位置接口只接受已认证 Conversation。
- 前端在认证成功后异步请求浏览器 Geolocation；拒绝、超时或后端解析失败时显示手动地址/City+State/ZIP 输入卡，认证状态不受影响。
- 新增 Google Geocoding v3 HTTP 客户端，支持经纬度反向解析和文本地址正向解析；Location Gate 要求有效坐标、美国 country code、city 和两位 state。
- 新增 Google Routes Compute Route Matrix HTTP 客户端和精确 Field Mask，支持 DRIVE、TRANSIT、WALK 三种交通方式及逐元素 SUCCESS/NO_ROUTE/FAILED。
- 从 CMS ACTIVE snapshot 查询同 city/state 的全部医院，按 Facility ID 去重，不做数量截断或直线距离粗筛；医院地址直接作为 Route Matrix destination。
- 单个交通方式每批最多 100 家医院；交通方式与批次组成独立请求并受控并行，单批失败只把对应元素降级为 FAILED。
- 新增元素级路线缓存，默认 TTL 10 分钟；Key 包含五位小数规范化位置、CMS snapshot、Facility ID、交通方式和路线参数版本。
- 新增 `contextVersion`、`locationRevision` 和 `clinicalPublishedRevision` 门禁；新位置会取消旧 LocationTask，已完成的过期 revision 不能提交 Context 或发布 Join-ready。
- LocationTask 不进入 Session Run 队列，因此可与画像采集或临床 Run 重叠；同一 Session 的消息 Run 仍由原调度器严格串行。
- Location 与 Clinical 均 READY 后按 revision 对只发布一次 `NAVIGATION_CONTEXT_READY`；M5 只发布汇合上下文，不提前生成 M6 推荐树。
- 新增 `LOCATION_STATUS`、`ROUTE_MATRIX_RESULT`、`NAVIGATION_CONTEXT_READY` SSE 事件，以及 Location/Route JSON Schema 和 14 条 M5 评测样本。

关键决定：

- `local/test` 使用确定性 Maps Stub；Stub 不会把用户真实浏览器坐标伪装为 fixture 地址，浏览器坐标会安全降级到手动地址。手动 fixture 支持 `Dothan, AL 36301` 和 `Boaz, AL 35957`。
- `openai` Profile 同时启用真实 Google 客户端；API Key 缺失不阻止应用启动，但定位任务会返回可恢复的 `MAPS_API_KEY_MISSING`。
- DRIVE 的成功/失败作为医院路线主状态；TRANSIT/WALK 可以无路线且不移除医院。预计时间和多交通路线在 M5 保存，M6 只有 DRIVE distance 参与门限与排序。
- 路线缓存当前是进程内 TTL Cache，不是长期事实源；多实例共享缓存和 Redis 化属于性能优化，不改变 M5 契约。

验证结果：

- 101 家唯一医院加 1 条重复输入，经 Facility ID 去重后覆盖 101 家；三种交通方式共 303 个元素，按 100 元素拆为 6 批，测试确认请求存在并行重叠且没有静默截断。
- 相同输入第二次计算 303 个元素全部命中缓存且不再调用路线客户端；到达 10 分钟边界后缓存失效。
- Google HTTP 契约测试验证 Geocoding query、Routes API Key/Field Mask、请求交通方式、成功元素和 NO_ROUTE 解析。
- API 集成测试跑通“注册—拒绝浏览器定位—手动地址—CMS 医院—三种路线—临床 READY—revision Join”，并验证未认证位置请求被拒绝。
- 并发回归测试验证第二个 LocationTask 会取消并淘汰第一个任务，旧 task 不能成为当前导航结果。
- 使用本机 PostgreSQL ACTIVE CMS 全量快照执行运行态冒烟验证：手动地址 `Dothan, AL 36301` 命中 2 家医院，生成 DRIVE/TRANSIT/WALK 共 6 个路线元素；导航查询返回 `joinReady=true`，绑定 `locationRevision=1`、`clinicalRevision=1` 和同一 CMS snapshot。
- 后端 53 项测试通过；前端 1 项测试和生产构建通过；OpenAPI、6 个 JSON Schema 和共 52 条 M1 至 M5 JSONL 样本通过格式解析。

当前未完成：

- 没有配置真实 Google API Key，因此本阶段未产生真实 Google 调用费用，也未执行真实地址/路线抽样；已完成真实客户端、Mock HTTP 契约和无密钥降级路径。
- 路线缓存为单实例内存实现；如果技术验证扩展为多后端实例，可迁移到 Redis 并保持相同 Key/TTL 语义。
- M5 尚不执行 5/10 miles 严重程度门限、医院评分排序、医生挂载和最终解释；这些属于 M6 三级推荐树与 Verification。

## 2026-09-14：M6 疾病—医院—医生三级推荐与验证

完成内容：

- 新增 `recommendation` 模块，定义公开疾病节点、医院节点、医生节点、Evidence、Confidence、Verification Finding 和最终结果强类型契约；公开疾病节点不包含内部 `targetSpecialties`。
- Join-ready 后异步启动 Recommendation Pipeline；结果绑定当前 `locationTaskId/contextVersion/locationRevision/clinicalRevision/cmsSnapshotId`，新位置或新临床 Run 会取消旧推荐，完成的过期结果不能提交。
- 每个疾病分支独立执行范围规则：SEVERE 当前 city 全量，MODERATE DRIVE ≤ 5 miles，MILD DRIVE ≤ 10 miles；边界值包含 5.0/10.0。
- 医院按 CMS Overall Rating DESC NULLS LAST、DRIVE distance ASC NULLS LAST、Facility ID ASC 排序并最多保留 3 家；不使用预计时间、TRANSIT 或 WALK 排序。
- 医生查询显式使用路线绑定的 CMS snapshot，并通过专业与 Facility Affiliation 硬过滤；按 NPI ASC 稳定截断最多 2 名，不计算或展示医生评分/名次。
- 没有匹配医生时保留医院并返回明确 limitation；没有范围内医院时保留疾病节点并返回疾病级降级说明，不复制或虚构结果。
- 新增 `confidence-v1`，按可计算 component 生成疾病、医院、医生、解释和整体 0–100 链路置信度；UI 明示该分数不等于疾病概率。
- 新增确定性 Recommendation Gate 与最多三轮局部修复；仅移除、重排或截断失败条目，硬门禁不能被高置信度抵消。
- 新增无 Tool/Memory 的 AgentScope Semantic Verification 客户端；只有低于配置阈值或解释风险时调用，辅助模型只返回 findings，最终状态仍由确定性服务决定。
- 新增 `/api/navigation/runs/{taskId}/results`、`RECOMMENDATION_RESULT`、`LOCAL_REPAIR_PROGRESS`；前端增加三级树、Evidence 话术、路线、医生理由和置信度展示。
- 将同城医院查询改为先绑定 ACTIVE snapshot 再查该快照；即使城市内医院为零，Route/Recommendation 仍携带快照版本并可安全降级。

关键决定：

- 医院可以在 CMS 专业匹配医生为空时保留，但必须展示限制；这遵循 V4 第 15 节“没有合格医生时仍可保留医院”的显式降级规则。
- 同一家医院可以出现在不同疾病分支；只在单个疾病分支内去重。医生专业是医生事实展示，不形成科室推荐层。
- Semantic Verification 默认启用配置但不默认产生调用；本地 Stub 与高置信度结果会记录 `NO_SEMANTIC_RISK`，真实调用仅发生在 `openai` Profile 且风险策略命中时。

验证结果：

- 排序测试覆盖评分高于距离、评分相同时距离、无评分置后和 Facility ID 稳定排序。
- 距离测试覆盖 SEVERE 全市、MODERATE 5 miles、MILD 10 miles；医生测试覆盖同快照、专业/affiliation、NPI 稳定截断和空医生降级。
- 局部修复测试注入超范围医院、非法/重复医生和错误顺序，确认一轮只清理失败条目，保留有效分支且不调用语义模型。
- API 集成测试跑通“注册—定位—路线—临床 READY—Join—三级推荐”，并从结果接口验证疾病、医院、医生、NPI 和 Semantic skip。
- 使用 PostgreSQL 全量 ACTIVE 快照执行两组运行态冒烟：Dothan + severe chest pain 返回 3 个疾病、2 个医院节点和空医生限制；随后同 Session 更新为 Boaz + severe headache，revision 从 `1/1` 前进到 `2/2`，返回 1 个医院节点和 2 个真实 CMS 医生叶节点。两次均为 `READY` 且绑定快照 `65bcc505-14a1-451e-99a9-d5f957850ec7`。
- 用 Draft 2020-12 Schema 校验实际 Boaz API 响应通过，并确认序列化结果中不存在内部 `targetSpecialties`。
- 后端 57 项测试通过；前端 1 项测试和生产构建通过；OpenAPI 0.6.0、7 个 JSON Schema 和共 67 条 M1 至 M6 JSONL 样本通过格式解析。

当前未完成：

- 未配置真实 `VERIFICATION_MODEL` 和 Google API Key，因此未执行付费语义验证或真实路线抽样；已验证 AgentScope 模型边界、条件调用策略、确定性客户端契约和 Stub E2E。
- M7 尚未实现完整 Redis Session 工作缓存、空闲 10 分钟 Finalizer、Session 症状/摘要事务与综合评测报告。

## 2026-09-22：M7 Memory 一致性与 Session Finalizer

完成内容：

- 新增 Redis 用户快照缓存和 Session 工作内存；用户快照 TTL 为 10 分钟，Session 工作内存不依赖 TTL 删除。
- 登录/注册后从 PostgreSQL 加载长期画像与历史 Session 摘要，在当前 Session 只保存增量画像、症状和最多 3 个初诊疾病。
- 对话模型请求使用“长期记忆 + Session 增量”合并视图；历史初诊只作为背景，不允许直接复用为当前诊断。
- 新增 AgentScope Session Summary 生成器和确定性降级摘要；Summary Model 失败不阻塞 Session 收口。
- 新增幂等 Finalizer：显式 `complete` 或空闲 10 分钟都经由同一 Session 调度器收口。
- 在单个 MyBatis/Spring 事务中写入 `user_profile`、`session_clinical` 和 `session_summary`；只在提交成功后刷新用户缓存并删除 Session 工作内存。
- 新增历史 Session Memory 查询/删除 API；运行中 Session 不允许被当作历史删除。
- 新增只识别用户明确陈述的疾病史/核心约束提取 Hook，约束类别白名单为药物过敏、食物过敏、用药禁忌和饮食限制；普通当前症状不会被当作既往疾病。
- 新增 Finalizer 成功、失败、Summary 降级和耗时 Micrometer 指标，以及 OpenAPI 0.7.0、Memory 请求字段和 17 条 M7 评测样本。
- 修复主模型不可用或结构校验失败时只在错误文案中声称“已保留信息”、但未写入工作内存的问题；现在先保存本轮原始症状、清除可能过期的初诊结果并推进 clinical revision，再返回可恢复错误。

关键决定：

- PostgreSQL 是长期事实源，Redis 只是长期数据的加速副本与当前 Session 的工作副本。
- 不在 TTL 到期时才写长期记忆；进程内定时扫描空闲 Session，但真正删除工作缓存以数据库事务成功为前提。
- 当前不保存完整会话 Transcript，不引入用户偏好表、向量检索或另一份跨 Session 事实库。

验证结果：

- 后端 71 项自动化测试通过，包括提交成功后删缓存、数据库失败保留缓存、Redis 工作内存不可用时不落库/不删缓存、模型失败保留原始症状并执行具体追问降级、结构化文本回退解析、模型选项兼容、幂等重复收口、空闲收口、历史查询/删除、显式长期事实提取和 API 端到端。
- 前端 1 项测试通过，TypeScript/Vite 生产构建通过。
- OpenAPI 0.7.0 与 17 条 M7 JSONL 样本通过语法校验；M1 至 M7 评测集共 86 条。
- 在本地健康 PostgreSQL/Redis 容器上使用 `local` Stub Profile 跑通“注册—画像—症状—收口—历史查询”；收口前 Session Key 存在，事务成功后 Session Key 删除、用户 Key 保留，长期记忆包含 3 个初诊疾病和非空摘要。
- 使用 `openai` Profile 对 Dothan, AL 去标识样本执行真实外部联调：Google Geocoding 成功解析 city/state，经 CMS ACTIVE snapshot 找到 2 家同城医院；Google Routes 对 DRIVE、TRANSIT、WALK 共 6 个元素全部返回成功。
- 首轮 OpenAI 请求因账号额度耗尽返回 `429 credit_balance_exhausted`，验证了主模型不可用和 Summary 确定性降级。补充额度后再次验收，GPT-5.6 Terra 临床调用成功返回 `READY`、3 个疾病假设，并记录 1,897 input / 381 output tokens；Session Summary 成功返回，未使用降级摘要。
- 真实调用暴露并修复 AgentScope 2.0.1 兼容问题：Chat Completions 的 function tools 不接受非 `none` reasoning effort；自动生成的原生 response schema 缺少严格字段；关闭原生 Schema 后框架未将 JSON 文本填入 `structuredData`。最终采用 `reasoning_effort=none`、显式 JSON Prompt 契约和统一文本回退解析。
- 使用仅限验收的 `semantic-threshold=101` 强制触发 GPT-5.6 Luna；日志确认 `semantic_verification_triggered`，最终推荐以 `READY_WITH_WARNINGS`、`semanticTriggered=true` 提交。生产默认阈值仍为 65。

### 2026-09-22 首次人工对话回归修复

- 人工对话发现画像提取器只接受严格 `YYYY-MM-DD`，对点号日期和中文年月日重复追问；现已支持 `YYYY-M-D` 的点号、斜杠、连字符及中文年月日写法，并统一归一化为 ISO 日期。
- 人工对话发现主临床模型异常时连续返回通用重试文案；现由确定性降级推理重新解析已保留的前序症状，针对缺失字段提出一个具体且未问过的问题。
- 扩充中文腹痛、中文数字时长、反酸和便血词条。模糊的“有点”不再被强行换算成 `2/10`，仍会询问明确严重程度。
- 新增画像格式、中文症状和模型失败降级回归测试；完整后端 71 项测试全部通过，0 失败、0 错误。
- M2/M3 各新增 1 条真实问题派生的去标识评测样本，M1 至 M7 合计 86 条。

当前未完成：

- Redis 不可用已有单元故障注入和可重试 `503 MEMORY_CACHE_UNAVAILABLE` 语义；仍可补充真实容器断开/恢复的系统级演练。
- Google 与 OpenAI 当前只覆盖少量去标识样本；仍需扩展不同州、无 TRANSIT 路线、模型追问、多轮症状更新和 Verification 不同 findings 的回归矩阵。
- 疾病史/核心约束当前采用保守的显式短语提取；尚未支持“取消某条旧约束”等删除语义，也不会从模糊表达中推断长期事实。

### 2026-09-22 M8 多 Session 与历史消息

完成内容：

- 新增 Flyway V4：`conversation_session` 保存 Session 所有权、标题、状态和活动时间，`conversation_message` 按 Session sequence 增量保存可见消息。
- 新增同用户 Session 创建/列表、历史消息读取和恢复 API；所有读取和恢复都校验当前认证用户所有权。
- 显式结束的 Session 标记为 `COMPLETED/EXPLICIT`；空闲满 10 分钟标记为 `COMPLETED/IDLE_TIMEOUT`，两者都可回放但不可恢复。
- 页面关闭或切换不主动结束 Session；后端 Runtime 仍在且空闲未满 10 分钟时可恢复原 Session 继续提交 Run。
- 前端新增 Session 侧栏、新建会话、历史消息回放、可恢复/只读标识和只读输入门禁；增加持久化 Assistant 消息与 SSE 重放去重。
- OpenAPI 升级为 0.8.0，新增 5 条 M8 评测样本。

关键决定：

- Transcript 是产品会话历史，不是 Agent Memory；使用 PostgreSQL 独立持久化，不依赖网页、本地存储或 Redis，也不参与画像和临床事实合并。
- 密码继续只提交到专用 Secret API，禁止进入消息表、SSE、Agent 输入、Memory 和日志。
- 当前不通过 Transcript 重建 Agent 运行态；后端进程重启后历史仍可查看，但此前 ACTIVE Session 不承诺恢复运行。

验证结果：

- 后端 72 项测试通过，覆盖同用户多 Session、所有权列表、顺序回放、ACTIVE 恢复、完成后 `409 SESSION_NOT_RESUMABLE` 和已完成历史可读。
- 前端 2 项测试与生产构建通过，覆盖持久化消息/SSE 回放去重。
- OpenAPI 0.8.0、全部 JSON Schema 及 M1 至 M8 共 91 条 JSONL 样本通过契约门禁。
- 已在本项目健康的 PostgreSQL 16 容器上实际应用 Flyway V4，并只读确认 `conversation_session`、`conversation_message` 两张表存在；临时验证后端已停止。

### 2026-09-22 M8 首次界面回归修复

- 修复长推荐树场景下三栏 Grid 高度由左侧上下文内容驱动、消息容器又受 `max-height` 限制而产生大块空白的问题。桌面工作区现在受 viewport 高度约束，Session、Context、Messages 三栏分别滚动；移动端继续使用自然页面高度。
- 定位“新会话”误报 `MEMORY_CACHE_UNAVAILABLE` 的原因：同步 Redis 适配器在 WebFlux HTTP 事件循环中调用阻塞等待，并非 Redis 服务必然不可用。
- 将创建/列表/读取/恢复 Session 四个包含 MyBatis 或 Redis 同步操作的端点统一切换至 Reactor bounded-elastic 调度器。
- 浏览器实测确认消息区 `overflow-y:auto`、Grid 首行 `minmax(0, 1fr)` 且输入框紧邻消息区；前端生产构建与测试通过。

### 2026-09-23 推荐解释与历史快照

- 疾病、医院、医生三级节点新增一段式 `recommendationReason`。段落只组合用户已述事实、CMS 医院评分、Google 驾车路线、CMS 医生专业和 Facility Affiliation，并保留“非确诊”“非质量排名”等边界话术。
- 原有 supporting facts、医院匹配要点、医生专业/任职要点和 Evidence ID 继续保留，前端在关键点之外展示完整推荐段落。
- 每个疾病分支的医院上限由 3 家调整为 2 家，验证 Gate、局部修复、JSON Schema、测试和评测集同步为 `3 × 2 × 2`。
- 新增 Flyway V5，在 `conversation_message` 增加 `message_type` 和 `structured_payload`。最终推荐先持久化为 `RECOMMENDATION_RESULT` 快照，再发布 SSE 成功事件。
- 历史 Session 加载时读取最新结构化推荐快照并恢复三级推荐卡片；旧版只保存文本、没有快照的历史推荐无法回填。
- OpenAPI 升级为 0.8.1；M6/M8 各增加 1 条评测样本，M1 至 M8 共 93 条。

验证结果：

- 后端完整回归 72 项测试通过，0 失败、0 错误；前端 3 项测试与 TypeScript/Vite 生产构建通过。
- PostgreSQL 16 实例已实际执行 Flyway V5，并只读确认 schema version、`system_probe` 均为 5，`conversation_message.message_type` 与 `structured_payload` 两列存在。
- 测试覆盖每个疾病最多 2 家医院、一段式三级推荐理由，以及推荐快照持久化后从历史消息接口恢复。

### 2026-09-23 患者话术与语言匹配

- 将疾病、医院和医生的一段式理由从工程审计表述改为患者可读话术；公开数据来源和原始字段仍保留在 Evidence，不直接混入可见段落。
- 临床请求记录 `responseLanguage`，推荐快照记录 `displayLanguage`。系统从当前症状消息识别中文或英文，主模型 Prompt、确定性降级、推荐生成和前端渲染统一遵循该语言。
- 中文界面将 `MILD/MODERATE/SEVERE`、`overall rating`、`miles`、医生专业和数据关联字段分别转换为较轻/中等/较重、医院综合评分、英里以及自然语言说明；医院、医生和地址官方专名保留原文。
- 前端上下文面板同步隐藏 `READY`、`RUNNING`、`Session`、`revision` 等内部状态词，中文界面统一显示“已就绪”“进行中”“会话”“版本”等用户可读标签。
- 对 V5 之前已经保存旧版推荐话术的历史快照，前端依据快照中的症状事实、评分、路线和医生关系重新生成中文展示文案，不修改原始 Evidence，也不要求重新调用模型。
- 增加临床语言校验 Hook。模型输出的患者可见疾病名、事实或追问与请求语言不一致时判为无效，并进入确定性降级。
- OpenAPI 升级为 0.8.2；M3/M6 各增加 1 条语言评测样本，M1 至 M8 共 95 条。
- 完整回归结果：后端 74 项测试通过，0 失败、0 错误；前端 3 项测试通过，TypeScript/Vite 生产构建成功。

## 2026-09-24：本地并发性能基线

- 增加无第三方依赖的 Node.js 固定速率压测工具，通过 HTTP `202 Accepted` 和 SSE `RUN_FINISHED` 分别测量接入延迟与 Run 完成延迟。
- 使用真实本地 PostgreSQL/Redis、Stub 临床模型与 Stub 地图，完成 200 QPS、100 个并行 Session、三轮各 60 秒的重复测试；36,000 个 Run 全部完成，观测错误率 0%。
- 三轮最差接入 P99 为 331.44 ms，最差 Stub Run 完成 P99 为 478.05 ms，满足 P99 小于 500 ms、错误率小于 1% 的本地性能基线。
- 增加 `MedNav_Performance_Test_Report_v0.1.md` 和机器可读结果摘要；500 QPS 探索测试未满足 500 ms 延迟 SLO，后续重点为同步持久化、连接池和有界调度优化。

## 后续记录约定

每完成一个可验证的小阶段，追加以下信息：

- 完成了什么；
- 做了哪些关键决定或范围调整；
- 测试、构建或联调结果；
- 仍未完成或已知限制。

日志不得记录密码、API Key、Token、真实医疗信息或完整外部 Tool payload。
