package io.github.kamarros.nbp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NbpClientTest {

    private record Stub(int status, String body) {
    }

    private HttpServer server;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestURI().toString();
            hits.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            Stub stub = stubs.getOrDefault(key, new Stub(404, "404 NotFound - Not Found"));
            byte[] body = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stub.status(), body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private NbpClient.Builder client() {
        return NbpClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api")
                .clock(Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneId.of("Europe/Warsaw")));
    }

    private void stub(String path, int status, String body) {
        stubs.put("/api" + path + "?format=json", new Stub(status, body));
    }

    private int hits(String path) {
        AtomicInteger n = hits.get("/api" + path + "?format=json");
        return n == null ? 0 : n.get();
    }

    private static String rates(String code, String name, String... noDateMid) {
        StringBuilder sb = new StringBuilder("{\"table\":\"A\",\"currency\":\"" + name
                + "\",\"code\":\"" + code + "\",\"rates\":[");
        for (int i = 0; i < noDateMid.length; i += 3) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"no\":\"").append(noDateMid[i]).append("\",\"effectiveDate\":\"")
                    .append(noDateMid[i + 1]).append("\",\"mid\":").append(noDateMid[i + 2]).append('}');
        }
        return sb.append("]}").toString();
    }

    @Test
    void getRateReturnsMidForExactDate() {
        stub("/exchangerates/rates/A/EUR/2026-11-06", 200,
                rates("EUR", "euro", "214/A/NBP/2026", "2026-11-06", "4.2500"));

        assertEquals(new BigDecimal("4.2500"), client().build().getRate("eur", LocalDate.parse("2026-11-06")));
    }

    @Test
    void getRateThrowsRateNotFoundOn404() {
        NbpClient nbp = client().build();

        assertThrows(RateNotFoundException.class, () -> nbp.getRate("EUR", LocalDate.parse("2026-11-07")));
    }

    @Test
    void plnIsAlwaysOneWithoutCallingApi() {
        assertEquals(BigDecimal.ONE, client().build().getRate("PLN", LocalDate.parse("2026-11-08")));
        assertEquals(0, hits.size());
    }

    @Test
    void getRateOrPreviousUsesRangeAndPicksNewest() {
        // Sunday 2026-11-08, default look-back 10 days -> range starts 2026-10-29
        stub("/exchangerates/rates/A/EUR/2026-10-29/2026-11-08", 200, rates("EUR", "euro",
                "211/A/NBP/2026", "2026-11-04", "4.2000",
                "212/A/NBP/2026", "2026-11-05", "4.2300",
                "213/A/NBP/2026", "2026-11-06", "4.2500"));

        assertEquals(new BigDecimal("4.2500"),
                client().build().getRateOrPrevious("EUR", LocalDate.parse("2026-11-08")));
    }

    @Test
    void getRateBeforeLooksAtThePreviousDay() {
        // event on Monday 2026-11-09 -> rate on or before Sunday 2026-11-08
        stub("/exchangerates/rates/A/USD/2026-10-29/2026-11-08", 200,
                rates("USD", "dolar amerykański", "213/A/NBP/2026", "2026-11-06", "3.6000"));

        assertEquals(new BigDecimal("3.6000"),
                client().build().getRateBefore("USD", LocalDate.parse("2026-11-09")));
    }

    @Test
    void getRateOrPreviousThrowsWhenWindowIsEmpty() {
        NbpClient nbp = client().maxLookbackDays(3).build();

        assertThrows(RateNotFoundException.class,
                () -> nbp.getRateOrPrevious("EUR", LocalDate.parse("2026-11-08")));
    }

    @Test
    void convertGoesThroughPlnAndRoundsToTwoPlaces() {
        stub("/exchangerates/rates/A/EUR/2026-10-27/2026-11-06", 200,
                rates("EUR", "euro", "213/A/NBP/2026", "2026-11-06", "4.2500"));
        stub("/exchangerates/rates/A/USD/2026-10-27/2026-11-06", 200,
                rates("USD", "dolar amerykański", "213/A/NBP/2026", "2026-11-06", "3.6000"));
        NbpClient nbp = client().build();
        LocalDate day = LocalDate.parse("2026-11-06");

        assertEquals(new BigDecimal("425.00"), nbp.convert(new BigDecimal("100"), "EUR", "PLN", day));
        assertEquals(new BigDecimal("23.53"), nbp.convert(new BigDecimal("100"), "PLN", "EUR", day));
        assertEquals(new BigDecimal("84.71"), nbp.convert(new BigDecimal("100"), "USD", "EUR", day));
    }

    @Test
    void pastDatesAreCachedButTodayIsNot() {
        String past = "/exchangerates/rates/A/EUR/2026-11-02";
        String today = "/exchangerates/rates/A/EUR/2026-11-06";
        stub(past, 200, rates("EUR", "euro", "210/A/NBP/2026", "2026-11-02", "4.1000"));
        stub(today, 200, rates("EUR", "euro", "214/A/NBP/2026", "2026-11-06", "4.2500"));
        NbpClient nbp = client().build();

        nbp.getRate("EUR", LocalDate.parse("2026-11-02"));
        nbp.getRate("EUR", LocalDate.parse("2026-11-02"));
        nbp.getRate("EUR", LocalDate.parse("2026-11-06"));
        nbp.getRate("EUR", LocalDate.parse("2026-11-06"));

        assertEquals(1, hits(past));
        assertEquals(2, hits(today));
    }

    @Test
    void missingTableForPastDayIsCachedToo() {
        NbpClient nbp = client().build();
        LocalDate saturday = LocalDate.parse("2026-10-31");

        assertThrows(RateNotFoundException.class, () -> nbp.getRate("EUR", saturday));
        assertThrows(RateNotFoundException.class, () -> nbp.getRate("EUR", saturday));

        assertEquals(1, hits("/exchangerates/rates/A/EUR/2026-10-31"));
    }

    @Test
    void cacheCanBeDisabled() {
        String past = "/exchangerates/rates/A/EUR/2026-11-02";
        stub(past, 200, rates("EUR", "euro", "210/A/NBP/2026", "2026-11-02", "4.1000"));
        NbpClient nbp = client().cacheEnabled(false).build();

        nbp.getRate("EUR", LocalDate.parse("2026-11-02"));
        nbp.getRate("EUR", LocalDate.parse("2026-11-02"));

        assertEquals(2, hits(past));
    }

    @Test
    void getLastRatesParsesWholeSeries() {
        stub("/exchangerates/rates/A/EUR/last/2", 200, rates("EUR", "euro",
                "213/A/NBP/2026", "2026-11-05", "4.2300",
                "214/A/NBP/2026", "2026-11-06", "4.2500"));

        CurrencyRates result = client().build().getLastRates("EUR", 2);

        assertEquals("EUR", result.code());
        assertEquals("euro", result.currency());
        assertEquals(2, result.rates().size());
        assertEquals(LocalDate.parse("2026-11-06"), result.rates().get(1).effectiveDate());
    }

    @Test
    void goldPricesAreParsed() {
        stub("/cenyzlota/last/2", 200,
                "[{\"data\":\"2026-11-05\",\"cena\":480.10},{\"data\":\"2026-11-06\",\"cena\":481.25}]");
        stub("/cenyzlota/2026-10-29/2026-11-08", 200,
                "[{\"data\":\"2026-11-05\",\"cena\":480.10},{\"data\":\"2026-11-06\",\"cena\":481.25}]");
        stub("/cenyzlota/2026-11-06", 200, "[{\"data\":\"2026-11-06\",\"cena\":481.25}]");
        NbpClient nbp = client().build();

        List<GoldPrice> last = nbp.getLastGoldPrices(2);
        assertEquals(new GoldPrice(LocalDate.parse("2026-11-06"), new BigDecimal("481.25")), last.get(1));
        assertEquals(new BigDecimal("481.25"), nbp.getGoldPrice(LocalDate.parse("2026-11-06")).price());
        assertEquals(LocalDate.parse("2026-11-06"),
                nbp.getGoldPriceOrPrevious(LocalDate.parse("2026-11-08")).date());
        assertThrows(RateNotFoundException.class, () -> nbp.getGoldPrice(LocalDate.parse("2026-11-07")));
    }

    @Test
    void serverErrorIsNbpExceptionNotRateNotFound() {
        stub("/exchangerates/rates/A/EUR/2026-11-06", 500, "boom");
        NbpClient nbp = client().build();

        NbpException e = assertThrows(NbpException.class, () -> nbp.getRate("EUR", LocalDate.parse("2026-11-06")));
        assertEquals(NbpException.class, e.getClass());
    }

    @Test
    void malformedJsonIsWrappedInNbpException() {
        stub("/exchangerates/rates/A/EUR/2026-11-06", 200, "<xml/>");

        assertThrows(NbpException.class,
                () -> client().build().getRate("EUR", LocalDate.parse("2026-11-06")));
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnyRequest() {
        NbpClient nbp = client().build();
        LocalDate day = LocalDate.parse("2026-11-06");

        assertThrows(IllegalArgumentException.class, () -> nbp.getRate("EU1", day));
        assertThrows(IllegalArgumentException.class, () -> nbp.getRate("../x", day));
        assertThrows(IllegalArgumentException.class, () -> nbp.getRate(null, day));
        assertThrows(NullPointerException.class, () -> nbp.getRate("EUR", null));
        assertThrows(IllegalArgumentException.class, () -> nbp.getLastRates("EUR", 0));
        assertThrows(IllegalArgumentException.class, () -> nbp.getLastGoldPrices(256));
        assertEquals(0, hits.size());
    }

    @Test
    void builderValidatesConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> NbpClient.builder().table("C").build());
        assertThrows(IllegalArgumentException.class, () -> NbpClient.builder().maxLookbackDays(0).build());
        assertThrows(IllegalArgumentException.class, () -> NbpClient.builder().maxLookbackDays(91).build());
        assertThrows(IllegalArgumentException.class, () -> NbpClient.builder().baseUrl(" ").build());
    }
}
