import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reproduces the on-sale stampede against a running service and checks that it behaved correctly.
 *
 * Run: java burst/Burst.java <BASE_URL> [--seats 1000] [--users 3000] [--requests 20000] [--hot-seats 5]
 *      [--storm 500] [--concurrency 1000] [--limit 4] [--admin-secret ...]   (or ./burst.sh <BASE_URL> ...)
 *
 * Phases, each released all at once through a latch:
 *   1. Hot-seat storm: --storm different users per hot seat, all grabbing it at the same instant.
 *   2. Stampede: the rest of --requests, skewed toward a block of "good" seats; some requests are sent twice with
 *      the same idempotency key (a client retry), a few reuse a key for different seats.
 *   3. Per-user limit: one user fires 10 parallel single-seat reserves on a limit-4 show.
 * Then it prints the outcome distribution and PASS/FAIL for every property the service must hold.
 *
 * No dependencies: plain JDK 21 (HttpClient + virtual threads), so it runs with `java Burst.java` and no build.
 */
public class Burst {

    private static final Pattern STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern SEATS_ARRAY = Pattern.compile("\"seats\"\\s*:\\s*\\[([^\\]]*)]");
    private static final int GOOD_BLOCK = 50;
    private static final int LIMIT_TEST_SEATS = 10;

    private final Config config;
    private final HttpClient http;
    private final Random random = new Random(42);
    private final List<Check> checks = new ArrayList<>();

    Burst(Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        boolean passed = new Burst(config).run();
        System.exit(passed ? 0 : 1);
    }

    boolean run() throws Exception {
        System.out.printf("Target: %s%n", config.baseUrl());
        waitUntilReady();

        // ---- Setup (not timed) ----
        String adminToken = mintToken("burst-admin", config.adminSecret());
        List<String> seats = seatLabels(config.seats());
        String showId = createShow(adminToken, seats);
        System.out.printf("Created show %s with %d seats, per-user limit %d%n", showId, seats.size(), config.limit());

        List<String> hotSeats = seats.subList(11, 11 + config.hotSeats());
        List<String> limitSeats = seats.subList(seats.size() - LIMIT_TEST_SEATS, seats.size());
        List<String> openSeats = seats.subList(0, seats.size() - LIMIT_TEST_SEATS);

        int stormRequests = hotSeats.size() * config.stormPerSeat();
        int stormUsers = Math.min(stormRequests, config.users());
        System.out.printf("Minting %d user tokens...%n", config.users() + 1);
        List<String> userIds = new ArrayList<>();
        String runId = UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < config.users(); i++) {
            userIds.add("b" + runId + "-" + i);
        }
        Map<String, String> tokens = mintTokens(userIds);
        String limitUser = "b" + runId + "-limit";
        tokens.put(limitUser, mintToken(limitUser, null));
        Map<String, Double> metricsBefore = scrapeMetrics();

        // ---- Phase 1: hot-seat storm ----
        List<Attempt> storm = new ArrayList<>();
        for (int i = 0; i < stormRequests; i++) {
            String user = userIds.get(i % stormUsers);
            storm.add(new Attempt("storm", user, List.of(hotSeats.get(i / config.stormPerSeat())), newKey()));
        }
        List<Outcome> stormOutcomes = fire("Hot-seat storm", showId, storm, tokens, null);

        // ---- Phase 2: stampede with retries ----
        List<Attempt> stampede = new ArrayList<>();
        int stampedeCount = Math.max(0, config.requests() - stormRequests - LIMIT_TEST_SEATS);
        while (stampede.size() < stampedeCount) {
            String user = userIds.get(random.nextInt(userIds.size()));
            List<String> wanted = pickSeats(openSeats);
            Attempt attempt = new Attempt("stampede", user, wanted, newKey());
            stampede.add(attempt);
            double roll = random.nextDouble();
            if (roll < 0.10) {
                stampede.add(new Attempt("retry-same-key", user, wanted, attempt.key()));
            } else if (roll < 0.12) {
                stampede.add(new Attempt("retry-different-seats", user, pickSeats(openSeats), attempt.key()));
            }
        }
        InvariantWatcher watcher = new InvariantWatcher(config.baseUrl(), showId);
        List<Outcome> stampedeOutcomes = fire("Stampede", showId, stampede, tokens, watcher);

        // ---- Phase 3: one user, 10 parallel reserves on a limit-4 show ----
        List<Attempt> limitBurst = new ArrayList<>();
        for (String seat : limitSeats) {
            limitBurst.add(new Attempt("per-user-limit", limitUser, List.of(seat), newKey()));
        }
        List<Outcome> limitOutcomes = fire("Per-user limit", showId, limitBurst, tokens, null);

        // ---- Report ----
        List<Outcome> all = new ArrayList<>();
        all.addAll(stormOutcomes);
        all.addAll(stampedeOutcomes);
        all.addAll(limitOutcomes);
        printDistribution(all);
        checkNo5xx(all);
        checkHotSeats(stormOutcomes, hotSeats);
        Map<String, String> winnerBySeat = checkNoSeatWonTwice(all);
        checkIdempotency(all);
        checkPerUserLimit(limitOutcomes, all);
        checkInvariantDuringBurst(watcher);
        Map<String, Integer> counts = checkFinalState(showId, winnerBySeat.size());
        checkMetrics(metricsBefore, scrapeMetrics(), all, showId, counts);
        return printChecks();
    }

    // ------------------------------------------------------------------------------------------------ firing

    /** Releases every attempt at the same instant; --concurrency caps how many are in flight at once. */
    private List<Outcome> fire(String phase, String showId, List<Attempt> attempts, Map<String, String> tokens,
                               InvariantWatcher watcher) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        Semaphore inFlight = new Semaphore(config.concurrency());
        List<Future<Outcome>> futures = new ArrayList<>();
        long startNanos;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Attempt attempt : attempts) {
                futures.add(executor.submit(reserveTask(showId, attempt, tokens.get(attempt.userId()), gate, inFlight)));
            }
            if (watcher != null) {
                watcher.start();
            }
            startNanos = System.nanoTime();
            gate.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            double seconds = (System.nanoTime() - startNanos) / 1e9;
            if (watcher != null) {
                watcher.stop();
            }
            List<Long> latencies = outcomes.stream().map(Outcome::latencyMillis).sorted().toList();
            System.out.printf("%-15s %6d requests in %6.2fs  (%5.0f req/s, p50 %4d ms, p99 %5d ms)%n",
                    phase, outcomes.size(), seconds, outcomes.size() / seconds,
                    percentile(latencies, 0.50), percentile(latencies, 0.99));
            return outcomes;
        }
    }

    private Callable<Outcome> reserveTask(String showId, Attempt attempt, String token, CountDownLatch gate, Semaphore inFlight) {
        return () -> {
            gate.await();
            inFlight.acquire();
            long start = System.nanoTime();
            try {
                String body = "{\"seats\": [" + String.join(", ", attempt.seats().stream().map(s -> "\"" + s + "\"").toList())
                        + "], \"idempotency_key\": \"" + attempt.key() + "\"}";
                HttpResponse<String> response = post("/shows/" + showId + "/reserve", body, token);
                return new Outcome(attempt, response.statusCode(), field(response.body(), "error"),
                        field(response.body(), "reservation_id"), seatsOf(response.body()), elapsedMillis(start));
            } catch (HttpTimeoutException e) {
                return new Outcome(attempt, -1, "client-timeout", null, List.of(), elapsedMillis(start));
            } catch (IOException e) {
                return new Outcome(attempt, -1, "connection-error", null, List.of(), elapsedMillis(start));
            } finally {
                inFlight.release();
            }
        };
    }

    // ------------------------------------------------------------------------------------------------ checks

    private void printDistribution(List<Outcome> all) {
        System.out.println();
        System.out.println("Outcome distribution (all phases):");
        Map<String, Integer> byOutcome = new TreeMap<>();
        for (Outcome o : all) {
            String key = o.status() == -1 ? "transport  " + o.error()
                    : o.status() + "        " + (o.error() == null ? describeSuccess(o.status()) : o.error());
            byOutcome.merge(key, 1, Integer::sum);
        }
        byOutcome.forEach((k, v) -> System.out.printf("  %-45s %7d%n", k, v));
        System.out.printf("  %-45s %7d%n", "total", all.size());
    }

    private String describeSuccess(int status) {
        return switch (status) {
            case 201 -> "confirmed";
            case 200 -> "idempotent-replay";
            default -> "";
        };
    }

    private void checkNo5xx(List<Outcome> all) {
        long serverErrors = all.stream().filter(o -> o.status() >= 500).count();
        long transport = all.stream().filter(o -> o.status() == -1).count();
        check("Zero 5xx across the burst", serverErrors == 0, serverErrors + " responses were 5xx");
        if (transport > 0) {
            warn("Transport errors (client side: timeouts / refused connections)", transport + " requests got no HTTP response");
        }
    }

    private void checkHotSeats(List<Outcome> storm, List<String> hotSeats) {
        for (String seat : hotSeats) {
            List<Outcome> forSeat = storm.stream().filter(o -> o.attempt().seats().contains(seat)).toList();
            long wins = forSeat.stream().filter(o -> o.status() == 201).count();
            long cleanDeclines = forSeat.stream().filter(o -> o.status() == 409).count();
            check("Hot seat " + seat + ": exactly one 201, everyone else 409",
                    wins == 1 && wins + cleanDeclines == forSeat.size(),
                    wins + " × 201, " + cleanDeclines + " × 409 of " + forSeat.size());
        }
    }

    private Map<String, String> checkNoSeatWonTwice(List<Outcome> all) {
        Map<String, String> winnerBySeat = new HashMap<>();
        int doubles = 0;
        for (Outcome o : all) {
            if (o.status() != 201) {
                continue;
            }
            for (String seat : o.seats()) {
                if (winnerBySeat.put(seat, o.attempt().userId()) != null) {
                    doubles++;
                }
            }
        }
        check("No seat confirmed to two users", doubles == 0, doubles + " seats appeared in two 201 responses");
        return winnerBySeat;
    }

    private void checkIdempotency(List<Outcome> all) {
        Map<String, Set<String>> reservationsByKey = new HashMap<>();
        Map<String, Integer> createdByKey = new HashMap<>();
        for (Outcome o : all) {
            if (o.status() == 200 || o.status() == 201) {
                String key = o.attempt().userId() + "/" + o.attempt().key();
                reservationsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(o.reservationId());
                if (o.status() == 201) {
                    createdByKey.merge(key, 1, Integer::sum);
                }
            }
        }
        long keysWithTwoReservations = reservationsByKey.values().stream().filter(ids -> ids.size() > 1).count();
        long keysCreatedTwice = createdByKey.values().stream().filter(n -> n > 1).count();
        long replays = all.stream().filter(o -> o.status() == 200).count();
        long keyReused = all.stream().filter(o -> "idempotency-key-reused".equals(o.error())).count();
        check("Idempotency: one reservation per key, retries replay it",
                keysWithTwoReservations == 0 && keysCreatedTwice == 0,
                replays + " replays (200), " + keyReused + " same-key-different-seats → 409, "
                        + keysWithTwoReservations + " keys with >1 reservation");
    }

    private void checkPerUserLimit(List<Outcome> limitOutcomes, List<Outcome> all) {
        long wins = limitOutcomes.stream().filter(o -> o.status() == 201).count();
        check("Per-user limit: 10 parallel reserves end with at most " + config.limit(),
                wins <= config.limit(), wins + " × 201");
        Map<String, Integer> seatsByUser = new HashMap<>();
        all.stream().filter(o -> o.status() == 201)
                .forEach(o -> seatsByUser.merge(o.attempt().userId(), o.seats().size(), Integer::sum));
        int max = seatsByUser.values().stream().max(Integer::compare).orElse(0);
        check("Per-user limit across the whole burst", max <= config.limit(), "most seats won by one user: " + max);
    }

    private void checkInvariantDuringBurst(InvariantWatcher watcher) {
        check("Invariant held during the burst (available + held + confirmed == total)",
                watcher.violations() == 0 && watcher.polls() > 0,
                watcher.polls() + " snapshots taken while the stampede ran, " + watcher.violations() + " violations");
    }

    private Map<String, Integer> checkFinalState(String showId, int seatsWon) throws Exception {
        Map<String, Integer> counts = showCounts(showId);
        int available = counts.get("available");
        int held = counts.get("held");
        int confirmed = counts.get("confirmed");
        int total = counts.get("total");
        System.out.printf("%nFinal show state: available %d + held %d + confirmed %d = %d, total_seats %d%n",
                available, held, confirmed, available + held + confirmed, total);
        check("Final reconciliation: available + held + confirmed == total_seats",
                available + held + confirmed == total, (available + held + confirmed) + " vs " + total);
        check("Confirmed seats == seats in 201 responses", confirmed == seatsWon, confirmed + " vs " + seatsWon);
        return counts;
    }

    private void checkMetrics(Map<String, Double> before, Map<String, Double> after, List<Outcome> all,
                              String showId, Map<String, Integer> counts) {
        if (after.isEmpty()) {
            warn("Metrics", "could not scrape /actuator/prometheus");
            return;
        }
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("reservations_confirmed_total", all.stream().filter(o -> o.status() == 201).count());
        expected.put("reservations_declined_total{reason=\"idempotent-replay\"}", all.stream().filter(o -> o.status() == 200).count());
        for (String reason : List.of("seat-taken", "per-user-limit", "idempotency-key-reused")) {
            expected.put("reservations_declined_total{reason=\"" + reason + "\"}",
                    all.stream().filter(o -> o.status() == 409 && reason.equals(o.error())).count());
        }
        expected.put("reservations_declined_total{reason=\"overloaded\"}", all.stream().filter(o -> o.status() == 429).count());
        boolean allMatch = true;
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            long delta = Math.round(after.getOrDefault(e.getKey(), 0.0) - before.getOrDefault(e.getKey(), 0.0));
            allMatch &= delta == e.getValue();
            detail.append(String.format("%n      %-62s metric Δ %6d   observed %6d", e.getKey(), delta, e.getValue()));
        }
        check("Metrics reconcile with observed responses (assumes no other traffic during the run)", allMatch, detail.toString());
        double gauge = after.getOrDefault("seats_available{show_id=\"" + showId + "\"}", -1.0);
        check("seats_available gauge == API available", Math.round(gauge) == counts.get("available"),
                "gauge " + Math.round(gauge) + " vs API " + counts.get("available"));
    }

    private boolean printChecks() {
        System.out.println();
        System.out.println("Checks:");
        boolean passed = true;
        for (Check c : checks) {
            System.out.printf("  [%s] %s%n        %s%n", c.status(), c.name(), c.detail());
            passed &= !c.status().equals("FAIL");
        }
        System.out.println();
        System.out.println(passed ? "RESULT: PASS" : "RESULT: FAIL");
        return passed;
    }

    private void check(String name, boolean ok, String detail) {
        checks.add(new Check(ok ? "PASS" : "FAIL", name, detail));
    }

    private void warn(String name, String detail) {
        checks.add(new Check("WARN", name, detail));
    }

    // ------------------------------------------------------------------------------------------------ API helpers

    /** Free tiers sleep when idle; wait for a cold start instead of failing the run. */
    private void waitUntilReady() throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (true) {
            try {
                if (get("/actuator/health/readiness").statusCode() == 200) {
                    System.out.println("Service is ready.");
                    return;
                }
            } catch (IOException e) {
                // not up yet
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Service did not become ready within 3 minutes");
            }
            System.out.println("Waiting for readiness (cold start?)...");
            Thread.sleep(3000);
        }
    }

    private String mintToken(String userId, String adminSecret) throws Exception {
        String body = adminSecret == null
                ? "{\"user_id\": \"" + userId + "\"}"
                : "{\"user_id\": \"" + userId + "\", \"admin_secret\": \"" + adminSecret + "\"}";
        HttpResponse<String> response = post("/auth/token", body, null);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token request failed (" + response.statusCode() + "): " + response.body()
                    + (adminSecret != null ? "  -- check --admin-secret / ADMIN_SECRET" : ""));
        }
        return field(response.body(), "token");
    }

    private Map<String, String> mintTokens(List<String> userIds) throws Exception {
        Map<String, String> tokens = new ConcurrentHashMap<>();
        Semaphore inFlight = new Semaphore(Math.min(200, config.concurrency()));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (String userId : userIds) {
                futures.add(executor.submit(() -> {
                    inFlight.acquire();
                    try {
                        tokens.put(userId, mintToken(userId, null));
                    } finally {
                        inFlight.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        return tokens;
    }

    private String createShow(String adminToken, List<String> seats) throws Exception {
        String body = "{\"name\": \"burst-" + System.currentTimeMillis() + "\", \"price_paise\": 25000, \"per_user_limit\": "
                + config.limit() + ", \"seats\": [" + String.join(",", seats.stream().map(s -> "\"" + s + "\"").toList()) + "]}";
        HttpResponse<String> response = post("/shows", body, adminToken);
        if (response.statusCode() != 201) {
            throw new IllegalStateException("Create show failed (" + response.statusCode() + "): " + response.body());
        }
        return field(response.body(), "id");
    }

    private Map<String, Integer> showCounts(String showId) throws Exception {
        String body = get("/shows/" + showId).body();
        Map<String, Integer> counts = new HashMap<>();
        for (String name : List.of("available", "held", "confirmed", "total")) {
            Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(body);
            counts.put(name, m.find() ? Integer.parseInt(m.group(1)) : -1);
        }
        return counts;
    }

    private Map<String, Double> scrapeMetrics() {
        Map<String, Double> metrics = new HashMap<>();
        try {
            for (String line : get("/actuator/prometheus").body().split("\n")) {
                if (line.startsWith("reservations_") || line.startsWith("seats_available")) {
                    int space = line.lastIndexOf(' ');
                    metrics.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
                }
            }
        } catch (Exception e) {
            return Map.of();
        }
        return metrics;
    }

    private HttpResponse<String> post(String path, String body, String token) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + path)).timeout(Duration.ofSeconds(30)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------------------------------------ small helpers

    private List<String> pickSeats(List<String> openSeats) {
        int count = random.nextDouble() < 0.8 ? 1 : 2;
        List<String> picked = new ArrayList<>();
        while (picked.size() < count) {
            // Half the requests fight over the first rows ("good" seats), the rest spread over the hall.
            int bound = random.nextBoolean() ? Math.min(GOOD_BLOCK, openSeats.size()) : openSeats.size();
            String seat = openSeats.get(random.nextInt(bound));
            if (!picked.contains(seat)) {
                picked.add(seat);
            }
        }
        return picked;
    }

    /** A1..A50, B1..B50, ... (50 seats per row; rows after Z are AA, AB, ...). */
    private static List<String> seatLabels(int count) {
        List<String> labels = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int row = i / 50;
            String rowName = row < 26 ? String.valueOf((char) ('A' + row))
                    : String.valueOf((char) ('A' + row / 26 - 1)) + (char) ('A' + row % 26);
            labels.add(rowName + (i % 50 + 1));
        }
        return labels;
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile(String.format(STRING_FIELD.pattern(), name)).matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> seatsOf(String json) {
        Matcher m = SEATS_ARRAY.matcher(json == null ? "" : json);
        if (!m.find() || m.group(1).isBlank()) {
            return List.of();
        }
        List<String> seats = new ArrayList<>();
        for (String part : m.group(1).split(",")) {
            seats.add(part.trim().replace("\"", ""));
        }
        return seats;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static long percentile(List<Long> sorted, double p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }
}

/** Polls GET /shows/{id} while the stampede runs and counts snapshots where the counts don't add up. */
class InvariantWatcher {

    private final String baseUrl;
    private final String showId;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Boolean> results = new ConcurrentLinkedQueue<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private Thread thread;

    InvariantWatcher(String baseUrl, String showId) {
        this.baseUrl = baseUrl;
        this.showId = showId;
    }

    void start() {
        running.set(true);
        thread = Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/shows/" + showId)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        // e.g. 429 under load: no counts to check, so not a violation either way.
                        Thread.sleep(200);
                        continue;
                    }
                    String body = response.body();
                    int[] c = new int[4];
                    String[] names = {"available", "held", "confirmed", "total"};
                    for (int i = 0; i < 4; i++) {
                        Matcher m = Pattern.compile("\"" + names[i] + "\"\\s*:\\s*(\\d+)").matcher(body);
                        c[i] = m.find() ? Integer.parseInt(m.group(1)) : -1;
                    }
                    results.add(c[0] + c[1] + c[2] == c[3]);
                    Thread.sleep(200);
                } catch (Exception e) {
                    // A failed poll is not a violation; the request checks catch server errors.
                }
            }
        });
    }

    void stop() throws InterruptedException {
        running.set(false);
        thread.join();
    }

    long polls() {
        return results.size();
    }

    long violations() {
        return results.stream().filter(ok -> !ok).count();
    }
}

record Attempt(String phase, String userId, List<String> seats, String key) {
}

record Outcome(Attempt attempt, int status, String error, String reservationId, List<String> seats, long latencyMillis) {
}

record Check(String status, String name, String detail) {
}

record Config(String baseUrl, String adminSecret, int seats, int users, int requests, int hotSeats,
              int stormPerSeat, int concurrency, int limit) {

    static Config parse(String[] args) {
        if (args.length == 0 || args[0].startsWith("--")) {
            System.err.println("Usage: java burst/Burst.java <BASE_URL> [--seats N] [--users N] [--requests N] "
                    + "[--hot-seats N] [--storm N] [--concurrency N] [--limit N] [--admin-secret S]");
            System.exit(2);
        }
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            options.put(args[i], args[i + 1]);
        }
        String envSecret = System.getenv("ADMIN_SECRET");
        return new Config(
                args[0].replaceAll("/+$", ""),
                options.getOrDefault("--admin-secret", envSecret != null ? envSecret : "local-admin-secret"),
                Integer.parseInt(options.getOrDefault("--seats", "1000")),
                Integer.parseInt(options.getOrDefault("--users", "3000")),
                Integer.parseInt(options.getOrDefault("--requests", "20000")),
                Integer.parseInt(options.getOrDefault("--hot-seats", "5")),
                Integer.parseInt(options.getOrDefault("--storm", "500")),
                Integer.parseInt(options.getOrDefault("--concurrency", "1000")),
                Integer.parseInt(options.getOrDefault("--limit", "4")));
    }
}
