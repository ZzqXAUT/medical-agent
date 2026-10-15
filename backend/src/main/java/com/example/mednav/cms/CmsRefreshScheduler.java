package com.example.mednav.cms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "mednav.cms.refresh-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CmsRefreshScheduler {

    private final CmsRefreshCoordinator coordinator;

    public CmsRefreshScheduler(CmsRefreshCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "America/Los_Angeles")
    public void refreshCmsProviderSnapshot() {
        coordinator.refresh();
    }
}
