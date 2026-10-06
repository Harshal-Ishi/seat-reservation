package com.paytm.seatreservation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Runs a transaction, and re-runs it if InnoDB picked it as a deadlock victim.
 *
 * Sorted locking prevents deadlocks between seat locks, but InnoDB can still deadlock when several transactions
 * insert the same new unique key and the first one rolls back: the waiters each hold a shared lock on that key and
 * each need an exclusive one. InnoDB aborts one of them with error 1213. The whole transaction was rolled back, so
 * running it again is safe. A lock wait timeout (1205) is not retried: it already waited, so it goes to 429.
 */
@Component
public class TransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(TransactionRunner.class);
    private static final int MYSQL_DEADLOCK_ERROR = 1213;
    private static final int MAX_ATTEMPTS = 5;
    private static final int BASE_BACKOFF_MILLIS = 10;

    private final TransactionTemplate transactionTemplate;

    public TransactionRunner(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    public void runWithDeadlockRetry(Runnable work) {
        runWithDeadlockRetry(() -> {
            work.run();
            return null;
        });
    }

    public <T> T runWithDeadlockRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> work.get());
            } catch (PessimisticLockingFailureException e) {
                if (!isDeadlock(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("Deadlock on attempt {}, retrying", attempt);
                backOff(attempt);
            }
        }
    }

    private boolean isDeadlock(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DEADLOCK_ERROR) {
                return true;
            }
        }
        return false;
    }

    // Random jitter so the retrying transactions don't collide again in lockstep.
    private void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(BASE_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS * 4 * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off after a deadlock", e);
        }
    }
}
