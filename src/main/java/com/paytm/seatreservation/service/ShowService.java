package com.paytm.seatreservation.service;

import com.paytm.seatreservation.dao.SeatDao;
import com.paytm.seatreservation.dao.ShowDao;
import com.paytm.seatreservation.exception.NotFoundException;
import com.paytm.seatreservation.model.Seat;
import com.paytm.seatreservation.model.SeatCounts;
import com.paytm.seatreservation.model.SeatStatus;
import com.paytm.seatreservation.model.Show;
import com.paytm.seatreservation.model.ShowState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class ShowService {

    static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowDao showDao;
    private final SeatDao seatDao;
    private final TransactionTemplate transactionTemplate;

    public ShowService(ShowDao showDao, SeatDao seatDao, TransactionTemplate transactionTemplate) {
        this.showDao = showDao;
        this.seatDao = seatDao;
        this.transactionTemplate = transactionTemplate;
    }

    public ShowState createShow(String name, List<String> seatLabels, long pricePaise, Integer perUserLimit) {
        int limit = perUserLimit != null ? perUserLimit : DEFAULT_PER_USER_LIMIT;
        Show show = new Show(UUID.randomUUID(), name, pricePaise, limit, seatLabels.size());

        // One transaction: a show is never visible without all of its seats.
        transactionTemplate.executeWithoutResult(status -> {
            showDao.insert(show);
            seatDao.insertAvailable(show.id(), seatLabels);
        });

        List<Seat> seats = seatLabels.stream()
                .map(label -> new Seat(label, SeatStatus.AVAILABLE))
                .toList();
        return new ShowState(show, seats, countSeats(seats));
    }

    public ShowState getShow(UUID showId) {
        Show show = showDao.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show not found"));
        // Counts are derived from this one seat query, never a separate COUNT query, so the
        // seat list and the counts come from the same snapshot and always agree, even mid-burst.
        List<Seat> seats = seatDao.findByShowId(showId);
        return new ShowState(show, seats, countSeats(seats));
    }

    private SeatCounts countSeats(List<Seat> seats) {
        int available = 0;
        int held = 0;
        int confirmed = 0;
        for (Seat seat : seats) {
            switch (seat.status()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case CONFIRMED -> confirmed++;
            }
        }
        return new SeatCounts(available, held, confirmed, seats.size());
    }
}
