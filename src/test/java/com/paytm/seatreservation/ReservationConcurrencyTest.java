package com.paytm.seatreservation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires many reservations at the same instant and checks the outcomes. Every test asserts zero 5xx,
 * at most one winner per seat, and a consistent database afterwards.
 */
class ReservationConcurrencyTest extends MySqlIntegrationTest {

    @Test
    void hotSeatHasExactlyOneWinner() {
        String showId = createShow(List.of("A12", "A13"), 25000);
        int buyers = 200;
        List<String> tokens = tokensFor("hot", buyers);

        List<ResponseEntity<String>> responses = runConcurrently(buyers,
                i -> reserve(showId, tokens.get(i), List.of("A12")));

        assertThat(countStatus(responses, HttpStatus.CREATED)).isEqualTo(1);
        assertThat(countStatus(responses, HttpStatus.CONFLICT)).isEqualTo(buyers - 1);
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(201, 409));
        assertThat(seatStatus(showId, "A13")).isEqualTo("available");
        assertConsistent(showId);
    }

    @Test
    void overlappingMultiSeatRequestsInOppositeOrdersNeitherDeadlockNorDoubleSell() throws Exception {
        String showId = createShow(List.of("A1", "A2", "A3"), 100);
        List<List<String>> shapes = List.of(List.of("A1", "A2"), List.of("A2", "A1"), List.of("A2", "A3"), List.of("A3", "A1"));
        int buyers = 120;
        List<String> tokens = tokensFor("multi", buyers);

        List<ResponseEntity<String>> responses = runConcurrently(buyers,
                i -> reserve(showId, tokens.get(i), shapes.get(i % shapes.size())));

        assertNo5xx(responses);
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(201, 409));
        assertNoSeatWonTwice(responses);
        // The four shapes pairwise overlap, so exactly one request can win.
        assertThat(countStatus(responses, HttpStatus.CREATED)).isEqualTo(1);
        assertConsistent(showId);
    }

    @Test
    void stampedeOnManySeatsKeepsStateConsistent() throws Exception {
        List<String> seats = IntStream.rangeClosed(1, 50).mapToObj(n -> "B" + n).toList();
        String showId = createShow(seats, 100);
        int buyers = 400;
        List<String> tokens = tokensFor("stampede", buyers);
        Random random = new Random(42);
        List<List<String>> requests = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            // Skewed toward the first 10 seats so many requests fight over the same "good" seats.
            int first = random.nextInt(random.nextBoolean() ? 10 : 50);
            int second = random.nextInt(50);
            requests.add(first == second || random.nextBoolean()
                    ? List.of(seats.get(first))
                    : List.of(seats.get(first), seats.get(second)));
        }

        List<ResponseEntity<String>> responses = runConcurrently(buyers,
                i -> reserve(showId, tokens.get(i), requests.get(i)));

        assertNo5xx(responses);
        assertNoSeatWonTwice(responses);
        int seatsWon = 0;
        for (ResponseEntity<String> r : responses) {
            if (r.getStatusCode() == HttpStatus.CREATED) {
                seatsWon += json.readTree(r.getBody()).get("seats").size();
            }
        }
        Integer confirmedInDb = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND status = 'confirmed'", Integer.class, showId);
        assertThat(confirmedInDb).isEqualTo(seatsWon);
        assertConsistent(showId);
    }

    /** Releases all tasks at the same moment through a latch, so they genuinely race. */
    private List<ResponseEntity<String>> runConcurrently(int count, IntFunction<ResponseEntity<String>> task) {
        CountDownLatch startGate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return task.apply(index);
                }));
            }
            startGate.countDown();
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> tokensFor(String prefix, int count) {
        return IntStream.range(0, count).mapToObj(i -> userToken(prefix + "-" + i)).toList();
    }

    private long countStatus(List<ResponseEntity<String>> responses, HttpStatus status) {
        return responses.stream().filter(r -> r.getStatusCode() == status).count();
    }

    private void assertNo5xx(List<ResponseEntity<String>> responses) {
        assertThat(responses).noneSatisfy(r -> assertThat(r.getStatusCode().is5xxServerError()).isTrue());
    }

    private void assertNoSeatWonTwice(List<ResponseEntity<String>> responses) throws Exception {
        Map<String, String> winnerBySeat = new HashMap<>();
        for (ResponseEntity<String> r : responses) {
            if (r.getStatusCode() != HttpStatus.CREATED) {
                continue;
            }
            JsonNode body = json.readTree(r.getBody());
            for (JsonNode seat : body.get("seats")) {
                String previous = winnerBySeat.put(seat.asText(), body.get("user_id").asText());
                assertThat(previous).as("seat %s won twice", seat.asText()).isNull();
            }
        }
    }

    private String seatStatus(String showId, String label) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE show_id = ? AND seat_label = ?", String.class, showId, label);
    }
}
