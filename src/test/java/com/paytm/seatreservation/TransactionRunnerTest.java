package com.paytm.seatreservation;

import com.paytm.seatreservation.service.TransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The deadlock retry, without a database. (The integration tests rarely reach it now: the fast path turns most
 * contended requests away before they open a transaction.)
 */
class TransactionRunnerTest {

    private static final int DEADLOCK = 1213;
    private static final int LOCK_WAIT_TIMEOUT = 1205;

    private TransactionRunner runner;
    private final AtomicInteger attempts = new AtomicInteger();

    @BeforeEach
    void setUp() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        runner = new TransactionRunner(template);
    }

    @Test
    void deadlockVictimIsRunAgainUntilItSucceeds() {
        String result = runner.runWithDeadlockRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw mysqlError(DEADLOCK);
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void givesUpAfterFiveAttempts() {
        assertThatThrownBy(() -> runner.runWithDeadlockRetry(() -> {
            attempts.incrementAndGet();
            throw mysqlError(DEADLOCK);
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(attempts).hasValue(5);
    }

    @Test
    void lockWaitTimeoutIsNotRetried() {
        assertThatThrownBy(() -> runner.runWithDeadlockRetry(() -> {
            attempts.incrementAndGet();
            throw mysqlError(LOCK_WAIT_TIMEOUT);
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(attempts).hasValue(1);
    }

    private static CannotAcquireLockException mysqlError(int errorCode) {
        return new CannotAcquireLockException("simulated", new SQLException("simulated", "40001", errorCode));
    }
}
