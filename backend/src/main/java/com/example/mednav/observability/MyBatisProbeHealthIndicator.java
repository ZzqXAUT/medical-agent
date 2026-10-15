package com.example.mednav.observability;

import com.example.mednav.mapper.SystemProbeMapper;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component("myBatisProbe")
public class MyBatisProbeHealthIndicator implements ReactiveHealthIndicator {

    private final SystemProbeMapper mapper;

    public MyBatisProbeHealthIndicator(SystemProbeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Health> health() {
        return Mono.fromCallable(mapper::selectSchemaVersion)
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofSeconds(2))
                .map(version -> Health.up().withDetail("schemaVersion", version).build())
                .onErrorResume(error -> Mono.just(Health.down(error).build()));
    }
}

