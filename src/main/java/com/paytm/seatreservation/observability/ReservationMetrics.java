package com.paytm.seatreservation.observability;

import com.paytm.seatreservation.dao.SeatDao;
import com.paytm.seatreservation.dao.ShowDao;
import com.paytm.seatreservation.model.DeclineReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records every reservation outcome twice from the same call: a Prometheus counter and one structured log line.
 * Because both come from one place, the metrics and the logs always agree.
 *
 * Exposed at /actuator/prometheus as reservations_confirmed_total, reservations_declined_total{reason},
 * reservations_cancelled_total and seats_available{show_id}.
 * Callers record only after the transaction has committed, so a rolled-back attempt is never counted as confirmed.
 */
@Component
public class ReservationMetrics {

    private static final Logger log = LoggerFactory.getLogger(ReservationMetrics.class);

    private final MeterRegistry registry;
    private final SeatDao seatDao;
    private final ShowDao showDao;
    private final Counter confirmed;
    private final Counter cancelled;
    private final Map<DeclineReason, Counter> declined = new EnumMap<>(DeclineReason.class);

    public ReservationMetrics(MeterRegistry registry, SeatDao seatDao, ShowDao showDao) {
        this.registry = registry;
        this.seatDao = seatDao;
        this.showDao = showDao;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations created (HTTP 201)")
                .register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Reservations cancelled by their owner")
                .register(registry);
        // Registered up front so every reason shows as 0 before its first decline, instead of being missing.
        for (DeclineReason reason : DeclineReason.values()) {
            declined.put(reason, Counter.builder("reservations.declined")
                    .description("Reserve requests that reserved nothing new, by reason")
                    .tag("reason", reason.value())
                    .register(registry));
        }
    }

    public void confirmed(UUID showId, UUID reservationId, int seatCount, long durationMillis) {
        confirmed.increment();
        log.atInfo()
                .addKeyValue("event", "reserve")
                .addKeyValue("outcome", "confirmed")
                .addKeyValue("show_id", showId)
                .addKeyValue("reservation_id", reservationId)
                .addKeyValue("seat_count", seatCount)
                .addKeyValue("duration_ms", durationMillis)
                .log("Reservation confirmed");
    }

    /** reservationId is the original reservation for a replay, otherwise null. */
    public void declined(DeclineReason reason, UUID showId, UUID reservationId, int seatCount, long durationMillis) {
        declined.get(reason).increment();
        log.atInfo()
                .addKeyValue("event", "reserve")
                .addKeyValue("outcome", "declined")
                .addKeyValue("reason", reason.value())
                .addKeyValue("show_id", showId)
                .addKeyValue("reservation_id", reservationId)
                .addKeyValue("seat_count", seatCount)
                .addKeyValue("duration_ms", durationMillis)
                .log("Reservation declined: {}", reason.value());
    }

    public void cancelled(UUID showId, UUID reservationId, int seatCount) {
        cancelled.increment();
        log.atInfo()
                .addKeyValue("event", "cancel")
                .addKeyValue("outcome", "cancelled")
                .addKeyValue("show_id", showId)
                .addKeyValue("reservation_id", reservationId)
                .addKeyValue("seat_count", seatCount)
                .log("Reservation cancelled");
    }

    /**
     * Seats available for one show, read from the database on every scrape rather than tracked in memory,
     * so it always equals what GET /shows/{id} reports, including after a restart.
     */
    public void registerShow(UUID showId) {
        Gauge.builder("seats.available", () -> seatDao.countAvailable(showId))
                .description("Seats currently available for the show")
                .tag("show_id", showId.toString())
                .register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerExistingShows() {
        try {
            showDao.findAllIds().forEach(this::registerShow);
        } catch (DataAccessException e) {
            // Normally unreachable: Flyway has already needed the database during startup. Not fatal either way;
            // readiness reports the database problem, and gauges for new shows are registered as they are created.
            log.warn("Could not register seats_available gauges for existing shows: {}", e.getMessage());
        }
    }
}
