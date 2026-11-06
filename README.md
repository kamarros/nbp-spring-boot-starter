# nbp-spring-boot-starter

Small, dependency-light client for the [NBP Web API](https://api.nbp.pl/) (exchange rates and gold prices)
with Spring Boot auto-configuration. The client itself is plain Java (JDK `HttpClient` + Gson) and also
works without Spring.

* Java 25, Spring Boot 4
* `BigDecimal` rates, never `double`
* Handles weekends and holidays (NBP publishes no table on those days)
* In-memory cache for past dates (they never change)
* Timeouts, clear exceptions, no static state

## Install

```xml
<dependency>
  <groupId>io.github.kamarros</groupId>
  <artifactId>nbp-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Use

```java
@Service
class InvoiceService {

    private final NbpClient nbp;

    InvoiceService(NbpClient nbp) {
        this.nbp = nbp;
    }

    BigDecimal toPln(BigDecimal eur, LocalDate date) {
        return eur.multiply(nbp.getRateOrPrevious("EUR", date));
    }
}
```

| Method | What it does |
|---|---|
| `getRate(code, date)` | Rate from exactly that day, `RateNotFoundException` if there is no table |
| `getRateOrPrevious(code, date)` | Rate from that day or the closest earlier one (one HTTP request) |
| `getRateBefore(code, date)` | Rate from the last day *before* `date` (check which rule applies to you) |
| `getLastRates(code, n)` | Last `n` rates (1-255) with currency name and table |
| `convert(amount, from, to, date)` | Conversion through PLN, rounded to 2 places |
| `getLastGoldPrices(n)`, `getGoldPrice(date)`, `getGoldPriceOrPrevious(date)` | Gold prices |

`PLN` is always `1`. Codes are case-insensitive.

## Configuration (all optional)

```yaml
kamarros:
  nbp:
    enabled: true            # false = do not register the bean
    base-url: https://api.nbp.pl/api
    table: A                 # A (main currencies) or B (others)
    timeout: 10s
    max-lookback-days: 10    # 1-90, used by the "or previous" methods
    cache-enabled: true
```

Define your own `NbpClient` bean to replace the default one.

Without Spring: `NbpClient.builder().table("A").build()`.

## Build and release

```bash
mvn verify                  # build + tests
mvn -Prelease clean deploy  # sources + javadoc + GPG signature + upload to Central Portal
```

Release steps (one-time setup in [RELEASE.md](RELEASE.md)): bump `<version>`, commit, tag `vX.Y.Z`, push the tag.

## License

Apache License 2.0, see [LICENSE](LICENSE).
