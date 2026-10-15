import http from "node:http";
import { performance } from "node:perf_hooks";

const args = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  args.set(process.argv[index], process.argv[index + 1]);
}

const baseUrl = new URL(args.get("--base-url") ?? "http://127.0.0.1:18080");
const targetRps = Number(args.get("--rps") ?? 50);
const durationSeconds = Number(args.get("--duration") ?? 15);
const sessionCount = Number(args.get("--sessions") ?? 50);
const drainTimeoutSeconds = Number(args.get("--drain-timeout") ?? 60);
const password = "LoadTest-Only-Password-2026";

if (![targetRps, durationSeconds, sessionCount, drainTimeoutSeconds].every(Number.isFinite)) {
  throw new Error("Numeric load-test arguments are invalid");
}

const requestAgent = new http.Agent({ keepAlive: true, maxSockets: 4096 });
const sseAgent = new http.Agent({ keepAlive: true, maxSockets: Math.max(64, sessionCount + 8) });

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function percentile(values, quantile) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.min(sorted.length - 1, Math.ceil(quantile * sorted.length) - 1);
  return Number(sorted[Math.max(0, index)].toFixed(2));
}

function requestJson(method, path, body, headers = {}, timeoutMs = 10_000) {
  const payload = body === undefined ? null : JSON.stringify(body);
  const startedAt = performance.now();
  return new Promise((resolve, reject) => {
    const request = http.request(
      {
        protocol: baseUrl.protocol,
        hostname: baseUrl.hostname,
        port: baseUrl.port,
        path,
        method,
        agent: requestAgent,
        headers: {
          Accept: "application/json",
          ...(payload
            ? {
                "Content-Type": "application/json",
                "Content-Length": Buffer.byteLength(payload),
              }
            : {}),
          ...headers,
        },
      },
      (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          let parsed = null;
          if (text) {
            try {
              parsed = JSON.parse(text);
            } catch {
              parsed = text;
            }
          }
          resolve({
            status: response.statusCode ?? 0,
            body: parsed,
            latencyMs: performance.now() - startedAt,
          });
        });
      },
    );
    request.setTimeout(timeoutMs, () => request.destroy(new Error(`request timed out after ${timeoutMs} ms`)));
    request.on("error", reject);
    if (payload) request.write(payload);
    request.end();
  });
}

class EventStream {
  constructor(conversationId, onEvent = () => {}) {
    this.conversationId = conversationId;
    this.onEvent = onEvent;
    this.waiters = [];
    this.request = null;
  }

  open() {
    return new Promise((resolve, reject) => {
      const request = http.get(
        {
          protocol: baseUrl.protocol,
          hostname: baseUrl.hostname,
          port: baseUrl.port,
          path: `/api/conversations/${this.conversationId}/events`,
          agent: sseAgent,
          headers: { Accept: "text/event-stream" },
        },
        (response) => {
          if (response.statusCode !== 200) {
            reject(new Error(`SSE returned HTTP ${response.statusCode}`));
            response.resume();
            return;
          }
          resolve();
          response.setEncoding("utf8");
          let buffer = "";
          response.on("data", (chunk) => {
            buffer += chunk.replaceAll("\r\n", "\n");
            let boundary;
            while ((boundary = buffer.indexOf("\n\n")) >= 0) {
              const frame = buffer.slice(0, boundary);
              buffer = buffer.slice(boundary + 2);
              const data = frame
                .split("\n")
                .filter((line) => line.startsWith("data:"))
                .map((line) => line.slice(5).trimStart())
                .join("\n");
              if (!data) continue;
              try {
                const event = JSON.parse(data);
                this.onEvent(event);
                for (const waiter of [...this.waiters]) {
                  if (waiter.predicate(event)) {
                    clearTimeout(waiter.timeout);
                    this.waiters.splice(this.waiters.indexOf(waiter), 1);
                    waiter.resolve(event);
                  }
                }
              } catch {
                // Ignore malformed test-stream frames; API errors are counted separately.
              }
            }
          });
        },
      );
      request.on("error", reject);
      this.request = request;
    });
  }

  waitFor(predicate, timeoutMs = 10_000) {
    return new Promise((resolve, reject) => {
      const waiter = { predicate, resolve, reject, timeout: null };
      waiter.timeout = setTimeout(() => {
        this.waiters.splice(this.waiters.indexOf(waiter), 1);
        reject(new Error(`SSE wait timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.waiters.push(waiter);
    });
  }

  close() {
    this.request?.destroy();
  }
}

async function requireStatus(operation, expected) {
  const response = await operation;
  if (response.status !== expected) {
    throw new Error(`Expected HTTP ${expected}, received ${response.status}: ${JSON.stringify(response.body)}`);
  }
  return response;
}

async function mapLimit(items, concurrency, operation) {
  const results = new Array(items.length);
  let next = 0;
  async function worker() {
    while (true) {
      const index = next++;
      if (index >= items.length) return;
      results[index] = await operation(items[index], index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, worker));
  return results;
}

async function setupAuthenticatedConversation() {
  const created = await requireStatus(requestJson("POST", "/api/conversations"), 201);
  const conversationId = created.body.conversationId;
  const stream = new EventStream(conversationId);
  const streamOpening = stream.open();
  const username = `perf_${Date.now()}_${Math.floor(Math.random() * 1_000_000)}`;
  const accepted = await requireStatus(
    requestJson("POST", `/api/conversations/${conversationId}/runs`, { message: username }),
    202,
  );
  await streamOpening;
  const secret = await stream.waitFor(
    (event) => event.type === "AUTH_SECRET_REQUEST" && event.runId === accepted.body.runId,
  );
  await requireStatus(
    requestJson("POST", "/api/auth/conversation/register", {
      conversationId,
      requestId: secret.data.requestId,
      password,
    }),
    200,
  );
  stream.close();
  return { conversationId, username };
}

async function main() {
  const health = await requireStatus(requestJson("GET", "/actuator/health"), 200);
  if (health.body?.status !== "UP") throw new Error("Application health is not UP");

  const authentication = await setupAuthenticatedConversation();
  const sessionIndexes = Array.from({ length: sessionCount }, (_, index) => index);
  const conversations = await mapLimit(sessionIndexes, 16, async () => {
    const response = await requireStatus(
      requestJson("POST", "/api/users/me/sessions", undefined, {
        "X-Conversation-Id": authentication.conversationId,
      }),
      200,
    );
    return response.body;
  });

  const acceptedLatencies = [];
  const completionLatencies = [];
  const statusCounts = new Map();
  const pendingRuns = new Map();
  const earlyFinishes = new Map();
  let transportErrors = 0;
  let runErrors = 0;
  let sent = 0;
  let accepted = 0;
  let completed = 0;
  let inFlightHttp = 0;
  let peakInFlightHttp = 0;
  const requestPromises = [];

  function finishRun(runId, finishedAt) {
    const startedAt = pendingRuns.get(runId);
    if (startedAt === undefined) {
      earlyFinishes.set(runId, finishedAt);
      return;
    }
    pendingRuns.delete(runId);
    completionLatencies.push(finishedAt - startedAt);
    completed += 1;
  }

  const streams = conversations.map(
    (conversation) =>
      new EventStream(conversation.conversationId, (event) => {
        if (event.type === "RUN_FINISHED") finishRun(event.runId, performance.now());
        if (event.type === "RUN_ERROR") runErrors += 1;
      }),
  );
  await mapLimit(streams, 32, (stream) => stream.open());

  const startedAt = performance.now();
  const scheduledEnd = startedAt + durationSeconds * 1000;
  let nextSequence = 0;

  function dispatch(sequence) {
    sent += 1;
    inFlightHttp += 1;
    peakInFlightHttp = Math.max(peakInFlightHttp, inFlightHttp);
    const conversation = conversations[sequence % conversations.length];
    const submittedAt = performance.now();
    const operation = requestJson(
      "POST",
      `/api/conversations/${conversation.conversationId}/runs`,
      { message: `压测症状 ${sequence}：头痛两天，疼痛程度4/10，没有发烧。` },
      {},
      15_000,
    )
      .then((response) => {
        statusCounts.set(response.status, (statusCounts.get(response.status) ?? 0) + 1);
        if (response.status !== 202) return;
        accepted += 1;
        acceptedLatencies.push(response.latencyMs);
        const runId = response.body.runId;
        const earlyFinishedAt = earlyFinishes.get(runId);
        if (earlyFinishedAt !== undefined) {
          earlyFinishes.delete(runId);
          completionLatencies.push(earlyFinishedAt - submittedAt);
          completed += 1;
        } else {
          pendingRuns.set(runId, submittedAt);
        }
      })
      .catch(() => {
        transportErrors += 1;
      })
      .finally(() => {
        inFlightHttp -= 1;
      });
    requestPromises.push(operation);
  }

  while (true) {
    const now = performance.now();
    if (now >= scheduledEnd) break;
    const shouldHaveSent = Math.floor(((now - startedAt) * targetRps) / 1000);
    while (nextSequence < shouldHaveSent) dispatch(nextSequence++);
    await sleep(1);
  }
  while (nextSequence < Math.floor(durationSeconds * targetRps)) dispatch(nextSequence++);
  const sendFinishedAt = performance.now();
  await Promise.all(requestPromises);

  const drainDeadline = performance.now() + drainTimeoutSeconds * 1000;
  while (pendingRuns.size > 0 && performance.now() < drainDeadline) await sleep(25);
  const finishedAt = performance.now();
  streams.forEach((stream) => stream.close());
  requestAgent.destroy();
  sseAgent.destroy();

  const errors = sent - accepted + runErrors + Math.max(0, accepted - completed);
  const report = {
    generatedAt: new Date().toISOString(),
    environment: {
      baseUrl: baseUrl.origin,
      dependencyMode: "stub clinical model; stub maps; real local PostgreSQL and Redis",
      processTopology: "single MedNav JVM and one local load generator",
    },
    workload: {
      endpoint: "POST /api/conversations/{conversationId}/runs",
      targetRps,
      durationSeconds,
      sessionCount,
      sent,
    },
    admission: {
      accepted,
      achievedQps: Number((accepted / ((sendFinishedAt - startedAt) / 1000)).toFixed(2)),
      latencyMs: {
        average: acceptedLatencies.length
          ? Number((acceptedLatencies.reduce((sum, value) => sum + value, 0) / acceptedLatencies.length).toFixed(2))
          : null,
        p50: percentile(acceptedLatencies, 0.5),
        p95: percentile(acceptedLatencies, 0.95),
        p99: percentile(acceptedLatencies, 0.99),
        max: acceptedLatencies.length ? Number(Math.max(...acceptedLatencies).toFixed(2)) : null,
      },
      peakInFlightHttp,
      statusCounts: Object.fromEntries([...statusCounts.entries()].sort()),
      transportErrors,
    },
    processing: {
      completed,
      pendingAfterDrain: pendingRuns.size,
      runErrors,
      drainSeconds: Number(((finishedAt - sendFinishedAt) / 1000).toFixed(2)),
      completionLatencyMs: {
        average: completionLatencies.length
          ? Number((completionLatencies.reduce((sum, value) => sum + value, 0) / completionLatencies.length).toFixed(2))
          : null,
        p50: percentile(completionLatencies, 0.5),
        p95: percentile(completionLatencies, 0.95),
        p99: percentile(completionLatencies, 0.99),
        max: completionLatencies.length ? Number(Math.max(...completionLatencies).toFixed(2)) : null,
      },
    },
    reliability: {
      errors,
      errorRatePercent: Number(((errors / Math.max(1, sent)) * 100).toFixed(3)),
    },
  };
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error.message}\n`);
  process.exitCode = 1;
  requestAgent.destroy();
  sseAgent.destroy();
});
