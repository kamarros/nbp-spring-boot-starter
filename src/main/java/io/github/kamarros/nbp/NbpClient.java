package io.github.kamarros.nbp;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Client for the NBP Web API ({@code https://api.nbp.pl}).
 *
 * <p>Instances are thread-safe. Create one with {@link #builder()}; in a Spring Boot
 * application the auto-configuration registers a ready-to-use bean.
 *
 * <p>Rates for dates in the past never change, so they (and the information that a day has
 * no table) are cached in memory unless caching is disabled.
 */
public final class NbpClient {

    /** Default base URL of the NBP Web API. */
    public static final String DEFAULT_BASE_URL = "https://api.nbp.pl/api";

    private static final ZoneId NBP_ZONE = ZoneId.of("Europe/Warsaw");
    private static final Pattern CODE = Pattern.compile("[A-Za-z]{3}");
    private static final String PLN = "PLN";
    private static final int MAX_LAST = 255;
    private static final int MAX_LOOKBACK_DAYS = 90;
    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final String baseUrl;
    private final String table;
    private final int maxLookbackDays;
    private final Duration timeout;
    private final boolean cacheEnabled;
    private final HttpClient http;
    private final Clock clock;
    private final Gson gson = new Gson();
    private final Map<String, Optional<Rate>> cache = new ConcurrentHashMap<>();

    private NbpClient(Builder b) {
        this.baseUrl = b.baseUrl;
        this.table = b.table;
        this.maxLookbackDays = b.maxLookbackDays;
        this.timeout = b.timeout;
        this.cacheEnabled = b.cacheEnabled;
        this.clock = b.clock;
        this.http = b.httpClient != null
                ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(b.timeout).build();
    }

    /** Returns a builder with sensible defaults (table A, 10 s timeout, cache on). */
    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ exchange rates

    /**
     * Average rate published exactly on {@code date}.
     *
     * @param code ISO 4217 code, case-insensitive; {@code PLN} always returns 1
     * @throws RateNotFoundException if no table was published that day (weekend, holiday)
     */
    public BigDecimal getRate(String code, LocalDate date) {
        String c = normalizeCode(code);
        requireDate(date);
        if (PLN.equals(c)) {
            return BigDecimal.ONE;
        }
        return cached("E|" + table + "|" + c + "|" + date, date, () -> fetchExact(c, date))
                .orElseThrow(() -> new RateNotFoundException(
                        "No " + c + " rate published on " + date + " (table " + table + ")"))
                .mid();
    }

    /**
     * Average rate from {@code date} or, if NBP published nothing that day, from the closest
     * earlier day (looking back at most {@code maxLookbackDays}).
     *
     * @throws RateNotFoundException if nothing was found in the look-back window
     */
    public BigDecimal getRateOrPrevious(String code, LocalDate date) {
        String c = normalizeCode(code);
        requireDate(date);
        if (PLN.equals(c)) {
            return BigDecimal.ONE;
        }
        return cached("P|" + table + "|" + c + "|" + date, date, () -> fetchOnOrBefore(c, date))
                .orElseThrow(() -> new RateNotFoundException(
                        "No " + c + " rate published within " + maxLookbackDays
                                + " days up to " + date + " (table " + table + ")"))
                .mid();
    }

    /**
     * Average rate from the last day <em>before</em> {@code date} that has a table.
     * Useful where the rule is "rate from the last business day preceding the event"
     * (e.g. foreign-currency invoices). Check which rule applies to your case.
     */
    public BigDecimal getRateBefore(String code, LocalDate date) {
        requireDate(date);
        return getRateOrPrevious(code, date.minusDays(1));
    }

    /**
     * The last {@code n} published rates of a currency, oldest first. Not cached.
     *
     * @param n between 1 and 255
     */
    public CurrencyRates getLastRates(String code, int n) {
        String c = normalizeCode(code);
        requireLast(n);
        String body = get("/exchangerates/rates/" + table + "/" + c + "/last/" + n)
                .orElseThrow(() -> new RateNotFoundException(
                        "No rates for " + c + " in table " + table));
        return parseRates(body);
    }

    /**
     * Converts {@code amount} between currencies through PLN using the rate from
     * {@code date} or the closest earlier day. The result is rounded to 2 decimal places
     * (HALF_UP).
     */
    public BigDecimal convert(BigDecimal amount, String from, String to, LocalDate date) {
        Objects.requireNonNull(amount, "amount");
        BigDecimal fromRate = getRateOrPrevious(from, date);
        BigDecimal toRate = getRateOrPrevious(to, date);
        return amount.multiply(fromRate).divide(toRate, 2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ gold

    /**
     * The last {@code n} gold prices, oldest first.
     *
     * @param n between 1 and 255
     */
    public List<GoldPrice> getLastGoldPrices(int n) {
        requireLast(n);
        String body = get("/cenyzlota/last/" + n)
                .orElseThrow(() -> new RateNotFoundException("No gold prices found"));
        return parseGold(body);
    }

    /**
     * Gold price from exactly {@code date}.
     *
     * @throws RateNotFoundException if there is no quotation that day
     */
    public GoldPrice getGoldPrice(LocalDate date) {
        requireDate(date);
        String body = get("/cenyzlota/" + date)
                .orElseThrow(() -> new RateNotFoundException("No gold price on " + date));
        return firstOrThrow(parseGold(body), "No gold price on " + date);
    }

    /**
     * Gold price from {@code date} or the closest earlier day (within the look-back window).
     */
    public GoldPrice getGoldPriceOrPrevious(LocalDate date) {
        requireDate(date);
        String msg = "No gold price within " + maxLookbackDays + " days up to " + date;
        String body = get("/cenyzlota/" + date.minusDays(maxLookbackDays) + "/" + date)
                .orElseThrow(() -> new RateNotFoundException(msg));
        List<GoldPrice> prices = parseGold(body);
        return prices.stream()
                .max(Comparator.comparing(GoldPrice::date))
                .orElseThrow(() -> new RateNotFoundException(msg));
    }

    // ------------------------------------------------------------------ internals

    private Optional<Rate> fetchExact(String code, LocalDate date) {
        return get("/exchangerates/rates/" + table + "/" + code + "/" + date)
                .flatMap(body -> newest(parseRates(body)));
    }

    private Optional<Rate> fetchOnOrBefore(String code, LocalDate date) {
        String start = date.minusDays(maxLookbackDays).toString();
        return get("/exchangerates/rates/" + table + "/" + code + "/" + start + "/" + date)
                .flatMap(body -> newest(parseRates(body)));
    }

    private static Optional<Rate> newest(CurrencyRates rates) {
        return rates.rates().stream().max(Comparator.comparing(Rate::effectiveDate));
    }

    /** Caches only results for days that are already over; "today" may still change. */
    private Optional<Rate> cached(String key, LocalDate date, Supplier<Optional<Rate>> loader) {
        if (!cacheEnabled || !date.isBefore(LocalDate.now(clock))) {
            return loader.get();
        }
        Optional<Rate> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Optional<Rate> loaded = loader.get();
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(key, loaded);
        return loaded;
    }

    /** Returns the body for HTTP 200, empty for 404, throws for anything else. */
    private Optional<String> get(String path) {
        URI uri = URI.create(baseUrl + path + "?format=json");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "nbp-spring-boot-starter")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 200) {
                return Optional.of(response.body());
            }
            if (status == 404) {
                return Optional.empty();
            }
            throw new NbpException("NBP API returned HTTP " + status + " for " + uri);
        } catch (IOException e) {
            throw new NbpException("Cannot reach NBP API: " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NbpException("Interrupted while calling NBP API: " + uri, e);
        }
    }

    private CurrencyRates parseRates(String body) {
        try {
            RatesJson json = gson.fromJson(body, RatesJson.class);
            if (json == null || json.rates == null) {
                throw new NbpException("Unexpected response from NBP API");
            }
            List<Rate> rates = new ArrayList<>(json.rates.size());
            for (RateJson r : json.rates) {
                if (r.mid == null) {
                    throw new NbpException("NBP response has no 'mid' value (table "
                            + json.table + " is not supported)");
                }
                rates.add(new Rate(r.no, LocalDate.parse(r.effectiveDate), r.mid));
            }
            return new CurrencyRates(json.table, json.currency, json.code, rates);
        } catch (JsonParseException | DateTimeParseException e) {
            throw new NbpException("Cannot parse NBP API response", e);
        }
    }

    private List<GoldPrice> parseGold(String body) {
        try {
            GoldJson[] json = gson.fromJson(body, GoldJson[].class);
            if (json == null) {
                throw new NbpException("Unexpected response from NBP API");
            }
            List<GoldPrice> prices = new ArrayList<>(json.length);
            for (GoldJson g : json) {
                prices.add(new GoldPrice(LocalDate.parse(g.data), g.cena));
            }
            return List.copyOf(prices);
        } catch (JsonParseException | DateTimeParseException e) {
            throw new NbpException("Cannot parse NBP API response", e);
        }
    }

    private static GoldPrice firstOrThrow(List<GoldPrice> prices, String message) {
        return prices.stream().findFirst().orElseThrow(() -> new RateNotFoundException(message));
    }

    private static String normalizeCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Currency code must be 3 letters (ISO 4217): " + code);
        }
        return code.toUpperCase(java.util.Locale.ROOT);
    }

    private static void requireDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
    }

    private static void requireLast(int n) {
        if (n < 1 || n > MAX_LAST) {
            throw new IllegalArgumentException("n must be between 1 and " + MAX_LAST + ": " + n);
        }
    }

    // JSON shapes returned by NBP (parsed with Gson, then mapped to the public records).
    private static final class RatesJson {
        String table;
        String currency;
        String code;
        List<RateJson> rates;
    }

    private static final class RateJson {
        String no;
        String effectiveDate;
        BigDecimal mid;
    }

    private static final class GoldJson {
        String data;
        BigDecimal cena;
    }

    // ------------------------------------------------------------------ builder

    /** Builder for {@link NbpClient}. */
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String table = "A";
        private Duration timeout = Duration.ofSeconds(10);
        private int maxLookbackDays = 10;
        private boolean cacheEnabled = true;
        private HttpClient httpClient;
        private Clock clock = Clock.system(NBP_ZONE);

        private Builder() {
        }

        /** Base URL without a trailing slash, default {@value NbpClient#DEFAULT_BASE_URL}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** NBP table: {@code A} (main currencies, default) or {@code B} (other currencies). */
        public Builder table(String table) {
            this.table = table;
            return this;
        }

        /** Connect and request timeout, default 10 seconds. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /** How many days back {@code ...OrPrevious} methods look, 1-90, default 10. */
        public Builder maxLookbackDays(int days) {
            this.maxLookbackDays = days;
            return this;
        }

        /** Enables or disables the in-memory cache, default enabled. */
        public Builder cacheEnabled(boolean enabled) {
            this.cacheEnabled = enabled;
            return this;
        }

        /** Custom {@link HttpClient} (proxy, TLS, ...). Its own timeouts then apply to connecting. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Clock used to decide which dates are "in the past" for caching (mainly for tests). */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public NbpClient build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl must not be blank");
            }
            baseUrl = baseUrl.strip().replaceAll("/+$", "");
            if (table == null || !(table.equalsIgnoreCase("A") || table.equalsIgnoreCase("B"))) {
                throw new IllegalArgumentException("table must be A or B: " + table);
            }
            table = table.toUpperCase(java.util.Locale.ROOT);
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maxLookbackDays < 1 || maxLookbackDays > MAX_LOOKBACK_DAYS) {
                throw new IllegalArgumentException(
                        "maxLookbackDays must be between 1 and " + MAX_LOOKBACK_DAYS);
            }
            Objects.requireNonNull(clock, "clock");
            return new NbpClient(this);
        }
    }
}
