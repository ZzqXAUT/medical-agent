# Event contracts

系统使用一组与 AG-UI 生命周期语义一致的 SSE 事件：

```text
RUN_STARTED
TEXT_MESSAGE_START
TEXT_MESSAGE_CONTENT
TEXT_MESSAGE_END
RUN_FINISHED | RUN_ERROR
```

M2 增加 `AUTH_SECRET_REQUEST`、`AUTH_RESULT`、`PROFILE_UPDATED`、`SESSION_COMPLETED`。
M3 增加 `PIPELINE_PROGRESS`、`CLINICAL_QUESTION`、`CLINICAL_RESULT`、`VALIDATION_FINDING`。其中公开的 `CLINICAL_RESULT` 会移除仅供内部医院匹配使用的 `targetSpecialties`。
M5 增加 `LOCATION_STATUS`、`ROUTE_MATRIX_RESULT`、`NAVIGATION_CONTEXT_READY`。LocationTask 使用自己的 `taskId` 作为事件 `runId`；临床 Run 与定位任务均可发出 Join-ready，但同一组 location/clinical revision 只发布一次。

M6 增加 `RECOMMENDATION_RESULT` 和 `LOCAL_REPAIR_PROGRESS`。最终推荐使用当前 LocationTask 的 `taskId` 作为事件 `runId`，并沿用该任务的递增 sequence；推荐结果必须绑定同一组 location/clinical revision 与 CMS snapshot。语义 Verification 仅在风险策略命中时触发，普通 Turn 不发布此类事件。

`contracts/json-schema/agent-event.schema.json` 是当前规范源。接入 AgentScope AG-UI 扩展时，必须通过兼容性测试证明事件字段和顺序没有破坏前端；不得让前端直接依赖 AgentScope 内部 Java 类型。
