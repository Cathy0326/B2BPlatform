package com.quipmarket.escrow.internal;

import com.quipmarket.escrow.Escrow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Settles ended auctions and retries unfinished saga steps. Disabled in tests, which call the service directly. */
@Component
class EscrowJobs {

    private final Escrow escrow;
    private final boolean enabled;

    EscrowJobs(Escrow escrow, @Value("${quipmarket.escrow.jobs-enabled:true}") boolean enabled) {
        this.escrow = escrow;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${quipmarket.escrow.job-delay-ms:15000}", initialDelay = 5_000)
    void run() {
        if (enabled) escrow.runPendingWork();
    }
}
