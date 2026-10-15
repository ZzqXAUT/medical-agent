package com.example.mednav.agent;

import static com.example.mednav.agui.AgentEventType.RUN_FINISHED;
import static com.example.mednav.agui.AgentEventType.RUN_STARTED;
import static com.example.mednav.agui.AgentEventType.TEXT_MESSAGE_CONTENT;
import static com.example.mednav.agui.AgentEventType.TEXT_MESSAGE_END;
import static com.example.mednav.agui.AgentEventType.TEXT_MESSAGE_START;

import com.example.mednav.agui.AgentEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "mednav.agent.mode", havingValue = "stub", matchIfMissing = true)
public class StubMedicalAgentRuntime implements MedicalAgentRuntime {

    private final Duration eventDelay;

    public StubMedicalAgentRuntime(@Value("${mednav.agent.stub-delay:25ms}") Duration eventDelay) {
        this.eventDelay = eventDelay;
    }

    @Override
    public Mono<AgentRunResult> execute(AgentRunCommand command) {
        String messageId = UUID.randomUUID().toString();
        String response = "已收到你的消息：“" + command.userMessage()
                + "”。当前运行的是本地 Stub Agent；认证、症状分析与医院推荐将在后续阶段接入。";
        AtomicLong sequence = new AtomicLong();

        List<AgentEvent> events = List.of(
                event(command, sequence, RUN_STARTED, messageId, null, null),
                event(command, sequence, TEXT_MESSAGE_START, messageId, null, null),
                event(command, sequence, TEXT_MESSAGE_CONTENT, messageId, response, null),
                event(command, sequence, TEXT_MESSAGE_END, messageId, null, response),
                event(command, sequence, RUN_FINISHED, messageId, null, null));

        return Flux.fromIterable(events)
                .delayElements(eventDelay)
                .doOnNext(command.eventPublisher())
                .then(Mono.just(new AgentRunResult(command.runId(), "COMPLETED")));
    }

    private AgentEvent event(
            AgentRunCommand command,
            AtomicLong sequence,
            String type,
            String messageId,
            String delta,
            String content) {
        return new AgentEvent(
                UUID.randomUUID(),
                type,
                command.conversationId(),
                command.sessionId(),
                command.runId(),
                messageId,
                sequence.incrementAndGet(),
                Instant.now(),
                delta,
                content,
                null);
    }
}
