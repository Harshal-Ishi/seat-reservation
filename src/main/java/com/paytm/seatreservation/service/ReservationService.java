package com.paytm.seatreservation.service;

import com.paytm.seatreservation.dao.ReservationDao;
import com.paytm.seatreservation.dao.SeatDao;
import com.paytm.seatreservation.dao.ShowDao;
import com.paytm.seatreservation.exception.BadRequestException;
import com.paytm.seatreservation.exception.ConflictException;
import com.paytm.seatreservation.exception.NotFoundException;
import com.paytm.seatreservation.model.DeclineReason;
import com.paytm.seatreservation.model.Reservation;
import com.paytm.seatreservation.model.ReservationStatus;
import com.paytm.seatreservation.model.Show;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class ReservationService {

    private final ShowDao showDao;
    private final SeatDao seatDao;
    private final ReservationDao reservationDao;
    private final TransactionTemplate transactionTemplate;

    public ReservationService(ShowDao showDao,
                              SeatDao seatDao,
                              ReservationDao reservationDao,
                              TransactionTemplate transactionTemplate) {
        this.showDao = showDao;
        this.seatDao = seatDao;
        this.reservationDao = reservationDao;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Reserves all requested seats for the user, or none of them (all-or-nothing).
     * The seat labels must already be distinct and well-formed.
     */
    public Reservation reserve(UUID showId, String userId, List<String> seatLabels) {
        Show show = showDao.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show not found"));

        // Sorted once and used for both locking and the response. Java's String order matches the
        // ascii_bin collation of seat_label, so every transaction locks overlapping seats in the same order.
        List<String> sortedLabels = seatLabels.stream().sorted().toList();
        Reservation reservation = new Reservation(
                UUID.randomUUID(),
                showId,
                userId,
                sortedLabels,
                Math.multiplyExact(show.pricePaise(), sortedLabels.size()),
                ReservationStatus.CONFIRMED);

        // Any exception thrown inside rolls the whole transaction back: no reservation row, no seat changed.
        transactionTemplate.executeWithoutResult(status -> {
            reservationDao.insert(reservation);

            for (String label : sortedLabels) {
                if (seatDao.lockSeat(showId, label).isEmpty()) {
                    throw new BadRequestException("Unknown seat: " + label);
                }
            }

            int confirmed = seatDao.confirmIfAvailable(showId, sortedLabels, reservation.id());
            if (confirmed != sortedLabels.size()) {
                throw new ConflictException(DeclineReason.SEAT_TAKEN, "One or more requested seats are not available");
            }
        });
        return reservation;
    }
}
