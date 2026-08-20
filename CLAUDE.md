# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Spring Boot 4.1.0 (Java 21) service that creates Stripe Checkout Sessions and persists orders in Postgres. A Thymeleaf form posts product details to a REST endpoint, which returns a hosted Stripe checkout URL the browser redirects to. A webhook endpoint receives Stripe's server-to-server confirmation and updates order status. Duplicate payments on retry are prevented via a two-layer idempotency scheme (DB unique key + Stripe Idempotency-Key). See `docs/HUONG-DAN-STRIPE-SPRING-BOOT.md` and `docs/STRIPE-TINH-NANG-NANG-CAO.md` for detailed Vietnamese walkthroughs.

## Commands

Uses the Maven wrapper (`./mvnw`); no global Maven install required. **Postgres must be running** (the app and `@SpringBootTest` load a JPA datasource + run Flyway on startup).

```bash
docker compose up -d            # Start Postgres (compose.yaml) before running the app
./mvnw spring-boot:run          # Run the app (port 8080)
./mvnw clean package            # Build the executable jar into target/
./mvnw test                     # Run all tests (needs Postgres up)
./mvnw test -Dtest=StripePaymentApplicationTests#methodName   # Run a single test
java -jar target/stripe-payment-0.0.1-SNAPSHOT.jar            # Run the built jar
```

## Architecture

Standard layered Spring MVC flow, all under package `com.example.stripe_payment` (note the underscore — `com.example.stripe-payment` is an invalid package name, see HELP.md):

- `controller/WebController` — `@Controller` serving Thymeleaf pages: GET `/` (checkout form), `/success`, `/cancel`.
- `controller/ProductCheckoutController` — REST layer under `/product/v1`: `POST /checkout` (one-time payment), `POST /subscribe` (recurring subscription), `POST /refund` (full/partial refund by `sessionId` or `paymentIntentId`), `POST /coupon` (create a coupon + optional promotion code). Checkout/subscribe accept `ProductRequest` (optional `orderId` → metadata + idempotency key, `couponId`/`allowPromotionCodes` → discounts, `interval` → subscription period); refund accepts `RefundRequest`; coupon accepts `CouponRequest` (`percentOff` XOR `amountOff`+`currency`, `duration`, optional `promotionCode`).
- `controller/StripeWebhookController` — `POST /webhook`. Receives Stripe events **server-to-server** (the trustworthy confirmation, unlike the browser `/success` redirect). Verifies the `Stripe-Signature` header against `stripe.webhookSecret` on the **raw body** via `Webhook.constructEvent`, then delegates to `OrderService.processWebhookEvent`.
- `controller/OrderController` — `GET /orders/{ref}` looks up a persisted order by `order_ref` (404 via `OrderNotFoundException` if missing).
- `service/StripeService` — pure Stripe SDK wrapper (no DB): `createCheckoutSession` (builds `SessionCreateParams`, applies discounts, passes the idempotency key as Stripe `Idempotency-Key`), `refund`, `createCoupon`. Sets static `Stripe.apiKey` in `@PostConstruct`.
- `service/OrderService` — orchestration + persistence. `checkout` implements two-layer idempotency: look up order by `idempotency_key`; if present return the existing session (`duplicate=true`); else insert a `PENDING` order (unique constraint guards races via caught `DataIntegrityViolationException`), call Stripe, save the session. `processWebhookEvent` is `@Transactional`: dedupe by `event_id` in `processed_webhook_events`, then update order status (`checkout.session.completed`→PAID + store payment_intent, `charge.refunded`→REFUNDED).
- `exception/` — `GlobalExceptionHandler` (`@RestControllerAdvice`) maps exceptions to `ProblemDetail` (RFC 9457): `StripeOperationException` (wraps `StripeException`, preserving code/status) → status from Stripe's HTTP code (e.g. `resource_missing`→400, card decline→402, auth→500); `IllegalArgumentException`/`IllegalStateException`/`MissingRequestHeaderException`→400; `OrderNotFoundException`→404; fallback→500. Services throw `StripeOperationException` (never a bare `RuntimeException`) so the type survives to the handler.
- `entity/` — `OrderEntity` (table `orders`), `ProcessedEventEntity` (table `processed_webhook_events`), `OrderStatus` enum (PENDING/PAID/FAILED/REFUNDED). `@PrePersist`/`@PreUpdate` manage timestamps.
- `repository/` — Spring Data `OrderRepository` (`findByIdempotencyKey`/`findByOrderRef`/`findByStripeSessionId`/`findByStripePaymentIntentId`), `ProcessedEventRepository`.
- `dto/ProductRequest` — `amount`/`quantity` (Long), `name`, `currency`, plus optional `orderId` (→ `order_ref`), `couponId`, `allowPromotionCodes`, `interval`. `StripeResponse` adds `orderRef`, `orderStatus`, `duplicate`. `OrderResponse.from(entity)` maps the entity for `GET /orders/{ref}`.
- `resources/db/migration/V1__init.sql` — Flyway schema (`orders`, `processed_webhook_events`). Schema is Flyway-owned; Hibernate runs in `validate` mode.
- `resources/templates/` — `index.html` generates an `Idempotency-Key` (UUID) client-side and sends it as an HTTP header; `success.html`, `cancel.html`.

## Configuration

`src/main/resources/application.yaml` reads secrets and datasource from the environment: `stripe.secretKey=${STRIPE_SECRET_KEY}`, `stripe.webhookSecret=${STRIPE_WEBHOOK_SECRET}`, `app.base-url`, and `spring.datasource.*` (`DB_URL`/`DB_USER`/`DB_PASSWORD`, defaulting to the local Postgres in `compose.yaml`). `spring.jpa.hibernate.ddl-auto=validate` (Flyway owns DDL); `spring.flyway.enabled=true`. Set `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` (the latter from `stripe listen`) before running.

## Conventions

- Lombok throughout (`@Data`, `@Builder`, `@Getter/@Setter`, `@RequiredArgsConstructor`); constructor injection on final fields.
- `StripeService` stays Stripe-only; DB/idempotency/webhook-state logic lives in `OrderService`. Keep that split.
- Idempotency is two-layer and deliberate: the same key guards both the `orders` unique constraint and the Stripe `Idempotency-Key`. Webhook processing is deduped by `event_id`.
