# Payment Gateway

An API that lets a merchant take a card payment through an acquiring bank, and retrieve a
previously made payment for reconciliation.

Java 26, Spring Boot 4.1, Gradle.

## Running it

Building and testing need nothing installed beyond a JDK — no database, no Docker:

```bash
./gradlew build
```

To run against the provided bank simulator:

```bash
docker-compose up -d      # the simulator, on 8080
./gradlew bootRun         # the gateway, on 8090
```

The gateway listens on **8090** because the simulator already owns 8080, which is Spring
Boot's default. The `imposters/` directory is unchanged from the template.

## The API

| | |
|---|---|
| `POST /payments` | Process a payment. 201 for both an authorization and a decline, 400 if the request is rejected before reaching the bank, 502 if the bank gives no verdict. |
| `GET /payments/{id}` | Retrieve a previously made payment. 404 if the id is unknown. |

Interactive documentation is generated from the code and served at
[`/swagger-ui.html`](http://localhost:8090/swagger-ui.html), with the raw specification at
`/v3/api-docs`.

A ready-made set of requests is in `bank.simulator.http`, for anyone using the IntelliJ HTTP
client. Otherwise:

```bash
curl -X POST localhost:8090/payments \
  -H 'Content-Type: application/json' \
  -d '{
    "card_number": "2222405343248877",
    "expiry_month": 4,
    "expiry_year": 2030,
    "currency": "GBP",
    "amount": 1050,
    "cvv": "123"
  }'
```

The simulator decides the outcome from the last digit of the card number: odd authorizes,
even declines, zero answers 503.

## Operations

Health and metrics run on **8091**, deliberately apart from the port merchants reach:

```bash
curl localhost:8091/actuator/health
curl localhost:8091/actuator/prometheus
```

---

The rest of this document covers the design decisions and the assumptions behind them, which
is what the exercise asks to be written down.

---

## Architecture

Four layers, with every dependency pointing inwards:

```
interfaces ──────┐
                 ├──> application ──> domain
infrastructure ──┘
```

- **`domain`** — the `Payment` aggregate, its status, and the repository port. No framework,
  no serialization annotations, no knowledge of HTTP or of the acquirer.
- **`application`** — the two use cases, the acquiring bank port, and the types that cross
  it. Owns the vocabulary the outer layers must speak.
- **`infrastructure`** — the HTTP adapter for the bank and the in-memory repository. The only
  place that knows the acquirer's wire format.
- **`interfaces`** — the REST controller, request/response DTOs, validation, and the
  exception handler.

Deleting `infrastructure` would leave `domain` and `application` compiling. Deleting
`application` would break `infrastructure`. That asymmetry is the point.

**Repository port in `domain`, acquiring bank port in `application`.** The two are not
equivalent. `PaymentRepository` returns the aggregate itself and expresses no storage
concept, so it belongs with the model. `AcquiringBankGateway` talks to an external system
with a contract of its own, so it belongs with the use case that needs it.

**Anti-corruption layer.** `AcquiringBankRequest` and `AcquiringBankResponse` — snake_case
keys, an `MM/yyyy` expiry string, a boolean plus a nullable code — never leave
`infrastructure`. `AcquiringBankClient` translates them into `ProcessPaymentCommand` and
`AuthorizationResult`. Swapping the acquirer changes that package and nothing else.

---

## Key decisions

### Two status vocabularies

`domain.payment.PaymentStatus` has two values: `AUTHORIZED` and `DECLINED`. A request that
fails validation never produces a `Payment`, so there is no persisted rejected state to
model.

`interfaces.payment.dto.PaymentResponseStatus` has three, adding `REJECTED`, and carries the
`@JsonValue` that pins the contract spelling (`"Authorized"`, capital A). Keeping the
serialization detail out of the domain follows the same rule applied to the acquirer's DTOs.

### Two error formats

| Situation | Status | Body |
|---|---|---|
| Validation failed, bank never called | 400 | `PaymentRejectedResponse` |
| Unknown payment id | 404 | `ProblemDetail` (RFC 9457) |
| Bank unreachable or erroring | 502 | `ProblemDetail` |

`PaymentRejectedResponse` exists because rejection has payment vocabulary to report: the
status `Rejected`, and which fields were wrong. The merchant must be able to tell "your
payload is broken" from "the bank said no" — the first is a bug to fix, the second is a
business outcome to record.

The other two failures have no domain concept to express, so they use the platform standard
rather than a bespoke envelope. `ProblemDetail` was not used for rejection because the
required `status: "Rejected"` field has no natural home in the RFC's structure.

Field names in validation errors are converted to the casing the merchant actually sent —
reporting `cardNumber` for a field called `card_number` sends them looking for something
that is not in their payload.

### 201 for both authorized and declined

A decline is still a created resource: it has an id, it is stored, and it can be retrieved
for reconciliation. Only a rejected request creates nothing, and that answers 400. The
`Location` header points at the retrieval endpoint.

### 502 Bad Gateway, not 503 Service Unavailable, when the bank fails

The gateway itself is healthy; an upstream dependency is not. A 503 would tell the merchant
to back off from us, when the right action is to retry the payment later.

### Authorize first, persist second

If the call to the bank throws, nothing is written. A request that never reached the bank
produced no payment for the merchant to retrieve. The reverse order would create records of
transactions that never happened, and a merchant reconciling against them would chase money
that was never taken.

### `AuthorizationResult` is a sealed interface

`Authorized(String authorizationCode)` and `Declined()`. The earlier boolean-plus-nullable
-code shape allowed two meaningless states — an approval without a reference, a refusal
carrying one. Modelling them out revealed a real case that had been invisible: a bank
answering `authorized: true` with no code produces a payment the merchant cannot reconcile,
and is now treated as a broken response rather than an approval.

Because the type is sealed, the `switch` in `PaymentService` compiles without a `default`.
Adding a third outcome breaks the build at every point that must handle it. That matters
because a third outcome is realistic: some issuers answer "referred", meaning the merchant
should call them rather than treat the payment as refused. Under the previous boolean, such
a verdict would have been mapped to a decline by default, costing the merchant a legitimate
sale.

### Card data

The full PAN and the CVV travel from the request to the bank and stop there. Only the last
four digits are stored or returned. Every record that carries a PAN overrides `toString`,
because the generated one would print it into any log line or stack trace that touches the
object; `SensitiveDataMaskingTest` pins this for all three carriers at once. Parse errors
answer with a generic message rather than echoing Jackson's, which quotes the offending
payload.

---

## Resilience

**Timeouts are the load-bearing part.** Two seconds to connect, five to read, both
configurable. Without them a socket can hang indefinitely, no failure is ever recorded, and
the circuit breaker never trips — every thread waits on a bank that will not answer until
the pool is empty.

**A circuit breaker, with no fallback.** It protects this application rather than the bank:
once open, a request fails in microseconds instead of holding a thread for a full read
timeout, which keeps the retrieval endpoint answering while the bank is down. There is no
fallback because no plausible answer can be invented for a payment.

**A 4xx does not count towards opening it.** A client error means the bank understood the
request and refused the payload — a defect on our side. Counting it as an outage would let
one malformed card open the circuit and block healthy payments for every other merchant.
This is the subtlest rule in the codebase and has a dedicated test.

**No retry, deliberately.** Authorization is not idempotent without an idempotency key,
which the simulator does not support. A read timeout is indistinguishable from a lost
response to a request that succeeded, so retrying it can authorize a cardholder twice. The
availability gained does not justify the risk of double-charging. With an idempotency key in
the acquirer's contract, retry on connection-level failures — never on read timeouts —
becomes safe and would be worth adding.

**No client-side rate limiter and no bulkhead.** A rate limiter would discard legitimate
payments against a limit the acquirer already enforces; the limit worth having in a gateway
is inbound, per merchant, which is a different concern. A bulkhead isolates one dependency
from another, and there is only one dependency. Both become worthwhile with a second
acquirer, BIN routing, or a fraud service.

---

## Assumptions

These are readings of the requirements where the requirements were silent.

- **Currencies are limited to GBP, USD and EUR**, satisfying "no more than 3 currency codes".
  Casing is normalised, so `gbp` is accepted — rejecting it would be a needless integration
  failure.
- **An amount of zero is rejected.** The requirements say "required" and "integer" and
  nothing about zero. Zero-value authorization is a real card-verification technique, so
  this is a decision rather than an obvious rule.
- **A card number containing spaces is rejected**, since the rule is "numeric characters
  only". Cards are printed in groups of four and many forms submit them that way, so
  stripping whitespace would be friendlier — but it would contradict the stated rule.
- **Surrounding whitespace is trimmed** from the card number and CVV before validation.
- **A card is valid through the last day of its expiry month**, so an expiry equal to the
  current month is accepted.
- **Declined payments are persisted** and retrievable, since the requirements describe
  retrieving "a previously made payment" and a decline is a business outcome to reconcile.
- **Payment ids are random UUIDs**, generated in the application layer. Two identical
  requests are two payments.
- **No authentication.** The requirements describe no merchant identity, so payments are not
  scoped to one and no credentials are checked.

---

## Testing

Sixteen files: twelve tests and four fixtures, running inside-out.

| Layer | What it pins |
|---|---|
| `ExpiryDateValidatorTest` | the "valid through the last day of the month" boundary, with a frozen clock |
| `PostPaymentRequestValidationTest` | the full validation table, plus one message per mistake |
| `AcquiringBankMapperTest` | zero-padded expiry, and an approval with no code being refused |
| `PaymentServiceTest` | nothing is persisted when the bank fails; only four digits are stored |
| `PaymentResponseStatusTest` | the exact contract spelling, both directions |
| `AcquiringBankClientTest` | the four simulator outcomes, plus an empty body |
| `AcquiringBankCircuitBreakerTest` | 5xx opens the circuit, 4xx never does, open means no call |
| `PaymentsControllerTest` | status codes, both error shapes, snake_case field names |
| `SensitiveDataMaskingTest` | no PAN or CVV in any `toString` |
| `PaymentGatewayE2ETest` | the full journey over real HTTP, and the read timeout |
| `ActuatorExposureTest` | operational endpoints stay off the merchant-facing port |
| `ArchitectureTest` | dependency direction, a framework-free domain, Jackson 3 only |

**WireMock, not the Mountebank container.** A test that only passes when a container happens
to be running is a test that fails for whoever clones the repository. The end-to-end test
starts a stub on a random port and rewrites `acquiring-bank.base-url` before the context
loads. It replicates the simulator's rule — odd authorises, even declines, zero fails — so
the card constants mean the same thing everywhere.

It is also the only place with real sockets. `MockRestServiceServer` replaces the request
factory, so timeouts and connection failures cannot happen there. The end-to-end test
shortens the read timeout to 300 ms and stubs a slow response: what matters is that a
timeout exists and becomes a 502, not that it is exactly five seconds.

**Three architecture rules, each one earned.** `ArchitectureTest` checks that dependencies
point inwards, that the domain touches no framework, and that nothing imports Jackson 2.
They are not there as convention: the second was broken during development — `PaymentStatus`
carried a `@JsonValue` — and the third guards a failure the compiler cannot catch, since
Jackson 2 can arrive transitively and the wrong `PropertyNamingStrategies` compiles cleanly
before silently failing to apply.

**Not tested, on purpose:** records and getters, `@Bean` methods, and the framework's own
path-variable conversion. Coverage is reported as a diagnostic, not chased as a target —
the two branches it flagged both turned out to be code that should not have existed in that
form, and were removed rather than covered.

---

## Observability

**What is here.** Actuator runs on its own port (8091) and exposes `health` and `metrics`
and nothing else. Separating the port keeps operational endpoints off the one merchants
reach; restricting the list means a later version widening its defaults cannot quietly open
something on a payment gateway.

The circuit breaker registry is bound to Micrometer through a `MeterBinder` bean, so circuit
state and call counts are published without any production class knowing metrics exist. That
binding is the reason the registry is a bean rather than a local variable.

Calls to the acquirer are timed by the framework, because the client is built from the
auto-configured `RestClient.Builder`. A histogram is enabled with explicit boundaries around
the five-second read timeout: counts and totals cannot answer the question that matters,
which is how close the slowest calls are to being cut off. A p99 of 4.8s means legitimate
authorizations are about to start failing, and that is visible here before any of them do.

Percentiles are computed at query time with `histogram_quantile`, not pre-computed in the
application. Client-side percentiles cannot be aggregated across instances, so the p99 read
from one replica of a scaled-out gateway would be arithmetically meaningless. The bucket
range is bounded to 5ms–10s as well: left open, Micrometer emits around seventy buckets per
tag combination reaching two hours, which is cardinality spent on latencies a five-second
timeout makes impossible.

The distribution is only readable in scrape format, so a Prometheus registry is included —
`/actuator/metrics` returns aggregates and would show none of it.

`ActuatorExposureTest` pins the separation. Which port serves which endpoint is a property
of this deployment rather than of the framework, and one line moved in `application.yml`
would put health and metrics in front of merchants with nothing else in the build noticing.

**Logging is deliberate rather than incidental**, and there is deliberately little of it.
Six statements in the whole service: the outcome of every payment at INFO, rejected requests
at DEBUG, the acquirer's status when it refuses or cannot be reached, and every circuit
state transition — the first thing anyone asks for during an incident.

The INFO line on each payment is an audit record rather than a debugging aid. Storage here
is a map that dies with the process, so it is the only durable evidence that a payment
happened, which is the difference between answering a merchant's dispute three days later
and not. Rejections sit at DEBUG because an invalid payload is the merchant's defect and not
an anomaly in this service; warning on each one would drown the log for a single clumsy
integration.

Nothing logs in the repository, the mappers, the validator, or the happy path of the
controller. Entry-and-exit logging is noise nobody reads, and more to the point every log
statement is a place a card number can escape — few statements, each one considered, is a
security posture as much as a tidiness one.

**Masking is an observability decision, not only a security one.** Deciding what must never
appear in a log is part of designing observability. Every record carrying a PAN overrides
`toString`, parse errors answer with a generic message instead of echoing the payload, and
the adapter logs the status code rather than the request object.

**What I would add next**, in order of value:

1. **A counter of outcomes** — authorized, declined, rejected, unavailable. Authorization
   rate is the number the business watches; a sudden drop means an issuer, an acquirer or a
   BIN range is failing, and it moves well before any merchant calls support.
2. **Distributed tracing, through `spring-boot-starter-opentelemetry`.** It pairs with the
   counter above: the metric says the authorization rate dropped, the traces say why.

The counter raises a question worth deciding rather than defaulting: injecting a
`MeterRegistry` into `PaymentService` puts an infrastructure library inside the use case,
which is the coupling the layering exists to prevent. The alternatives are a decorator
around the acquiring bank port, or an application event that infrastructure subscribes to.
The decorator is the smaller change; the event scales better if more consumers appear. It
was left out of this submission because the interesting part is the choice, not the wiring.

Tracing is deliberately the starter rather than a hand-written MDC filter. Micrometer
Tracing already puts `traceId` and `spanId` into the MDC, and because the acquiring bank
client is built from the auto-configured `RestClient.Builder`, trace context would propagate
to the bank with no change to any class here. Writing a correlation filter by hand would be
reimplementing, worse, something the platform already does — and it would stop at this
service's own logs rather than spanning the call to the acquirer.

Two caveats are worth stating rather than discovering later. Spring Boot sends logs over
OTLP but does not install log appenders by default, so correlation in the log line comes
free while shipping those logs does not. And sampling is a business decision here, not a
cost one: at ten percent, the traces lost are overwhelmingly for payments nobody
investigates, but so are nine in ten of the failures somebody will. Tail-based sampling at
the collector, or always sampling errors, is the answer — and the `traceId` stays in the log
either way, so correlation between lines survives a trace that was never exported.

The starter was left out of this submission because without a collector running it produces
nothing observable: a dependency that does nothing on a clean clone is worse than a
documented gap. The `payment id` would remain useful alongside it regardless — a `traceId`
correlates one request, while the payment id is what a merchant quotes when they raise a
ticket three days later.

---

## API documentation

OpenAPI is generated from the code and served at `/v3/api-docs`, with Swagger UI at
`/swagger-ui.html`. Merchants integrate against documentation rather than against source, so
a specification that can drift from the running service is worse than useful — the value of
generating it at runtime is precisely that it cannot.

Making that true took a decision. Field names are declared one by one with `@JsonProperty`
instead of through a `@JsonNaming` strategy. The strategy worked at runtime but was invisible
to the documentation generator: `PropertyNamingStrategies` moved to `tools.jackson.databind`
in Jackson 3, and swagger-core reads Jackson 2. `@JsonProperty` did not move — annotations
were deliberately left in `com.fasterxml.jackson.annotation` for exactly this kind of
cross-version compatibility — so the generator sees it.

The first attempt at this submission removed springdoc on the assumption that the mismatch
was unfixable. Checking rather than assuming showed otherwise, and per-field names are the
better design anyway: this is a contract we publish, and its wire names should not depend on
a global setting somebody can change elsewhere. It is the same reasoning already applied to
the acquirer's DTOs.

`@Schema` is applied selectively rather than everywhere: on fields a merchant could misread,
and on the two that would otherwise be given an invented example. `amount` says that GBP
10.50 is sent as 1050, because that is where a misreading costs real money. `card_number`
carries a chosen test card rather than a generated one, since an invented card number in
public documentation is a poor look. `expiry_month` is left alone — restating an obvious
name is noise that makes the rest easier to skip.

The status enum is narrowed per response: Authorized or Declined on a payment, Rejected on a
400. A client generated from this specification therefore never has to handle a value that
     cannot occur, which is the same distinction the two status enums make in the code. The cost
     is that the enum is inlined into each schema instead of shared by reference, since the three
     uses no longer have identical shapes.

Reviewing the generated output caught a real contradiction rather than a cosmetic one: the
rejection example showed `"status": "Authorized"`, a value that response can never carry.
Documentation that contradicts the contract is worse than none, and it was only visible by
reading what was actually produced.

Spring REST Docs would be the stronger option in a codebase that could afford it: it
generates from the tests, so documentation that contradicts behaviour breaks the build. The
trade-off is static HTML instead of an interactive UI, and considerably more machinery than
one annotation per field.

---

## Known limitations

- **A window between authorizing and persisting.** If the process dies after the bank
  approves and before the write, the money was taken and the gateway has no record of it.
  Closing this properly means writing the intent first and reconciling anything left pending
  — a larger design than these requirements call for.
- **No idempotency key**, which is what makes retry unsafe. It is the first thing to add for
  production.
- **Storage is a `ConcurrentHashMap`**, as the requirements permit. It is thread-safe but
  not durable, and holds everything in memory.
- **The circuit breaker's automatic open-to-half-open transition is not tested.** It depends
  on wall-clock time and Resilience4j takes no injectable clock; the recovery test triggers
  the transition by hand instead.
- **The simulator models a permanent failure as a transient one.** A card ending in zero
  always returns 503, so a run of such cards will open the circuit and briefly block valid
  payments. That is an artifact of the stub rather than a defect, but it is worth knowing
  before a demo.
- **Two Jackson versions are on the classpath.** `springdoc-openapi` brings Jackson 2 in
  transitively; Spring Boot 4 uses Jackson 3. They coexist by design, under different
  packages, but it is how the naming bug below happened and it is why `ArchitectureTest`
  forbids importing Jackson 2.
- **A malformed UUID in the path returns Spring's default 400 body**, a third error shape
  the exception handler does not cover. Unifying it would be a small change and was left out
  as beyond the requirements.