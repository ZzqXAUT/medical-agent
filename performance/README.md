# MedNav 本地并发压测

`mednav-load.mjs` 测量 `POST /api/conversations/{conversationId}/runs` 的接入延迟，并通过 SSE 跟踪每个 Run 的实际完成时间。

压测脚本会注册一个专用测试用户，创建多个独立 Session，并以固定目标速率轮询这些 Session。这样既保持“同 Session 串行”的业务约束，也能验证“Session 间并行”。

示例：

```powershell
node .\performance\mednav-load.mjs --base-url http://127.0.0.1:18080 --rps 100 --duration 30 --sessions 50 --drain-timeout 60
```

报告区分两类延迟：

- `admission.latencyMs`：HTTP 请求提交到收到 `202 Accepted` 的时间，包括消息持久化和入队。
- `processing.completionLatencyMs`：请求提交到收到对应 SSE `RUN_FINISHED` 的时间。

本地容量测试应使用 Stub 模型和 Stub 地图、真实 PostgreSQL/Redis。真实 OpenAI 和 Google Maps 需要单独执行低速率依赖验收，不能用 Stub 压测结果代表外部 API 的端到端容量。
