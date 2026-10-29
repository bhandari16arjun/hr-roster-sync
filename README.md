# HR Roster Sync — idempotent, out-of-order, self-healing

MoveInSync Backend Case Study 2026 · **Case Study 5** · Java 21 + Spring Boot 3.4 + PostgreSQL

Corporate clients push employee changes (joiners, transfers, shift changes, exits) from their HR
system in near real time, and send a full nightly export of up to 200,000 employees. Updates arrive
twice, out of order, with bad data, and sometimes cut short. This service keeps MoveInSync's
employee records correct anyway.

| Hard rule from the brief | Where it is enforced | Proven by |
|---|---|---|
| Apply per employee in version order; stale ignored; duplicate is a no-op | `EmployeeMerger` (pure function) + row lock in `EmployeeSyncService` | `EmployeeMergerTest`, `outOfOrderUpdateIsIgnoredAsStale`, `concurrentOutOfOrderDeliveryConvergesToHighestVersion` |
| Replaying the same feed twice changes nothing | idempotency key `(tenant, employeeId, version)` | `replayingTheSameFeedTwiceChangesNothing`, `reRunningTheSameExportChangesNothing` |
| One bad record never fails a batch; per-record error report | `RecordValidator` + per-item results | `oneBadRecordNeverFailsTheBatch`, `invalidRowsAreReportedAndNeverDeactivateAnyone` |
| HR never overwrites transport-owned fields | `HrRecord` has no transport fields; separate SQL per owner | `hrUpdateNeverOverwritesTransportOwnedFields`, `hrEventCannotSetTransportFieldsAndGetsAWarning` |
| Diff 200k rows without loading both into memory | 64 hash buckets spilled to disk, compared one at a time | `SnapshotDiffBenchmarkTest` (2.2 s, ≤93 MB heap, `-Xmx384m`) |
| Safety brake: ≥30% missing → stop, deactivate nobody, operator approves | `SafetyBrake` (configurable) + `AWAITING_APPROVAL` state | `truncatedExportTripsTheSafetyBrakeAndDeactivatesNobody`, `operatorApprovalAppliesTheBrakedRun` |
| DB update and change event can never disagree | transactional outbox | `OutboxCrashTest` (3 crash points) + live `scripts/crash-demo.sh` |
| Leaver's future bookings cancelled, seat freed | `BookingCancellationHandler` (deduped consumer) | `normalNightAppliesChangesAndDeactivatesLeavers`, crash tests |
| No personal data in logs or errors | IDs-only logging, redacted `toString`, codes-only error reports | `personalDataNeverAppearsInLogs`, `errorsAreStructuredAndNeverEchoTheInput` |
| Tenant isolation | tenant comes from the credential; every SQL has `WHERE tenant_id = ?` | `anotherTenantCannotReadOrEditOurEmployee`, `anotherTenantCannotSeeOurReconciliationRuns` |

Design note (architecture, sequence diagram, failure-mode table): **[docs/DESIGN.md](docs/DESIGN.md)**

---

## Run it

**One command (Docker):**

```bash
docker compose up --build
# app: http://localhost:8080   health: http://localhost:8080/actuator/health
```

**Without Docker** (needs only JDK 21; starts an embedded PostgreSQL):

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.main-class=com.moveinsync.rostersync.LocalDevApplication
```

**Demo the main flows** (curl + jq), then the **live crash** (needs the docker compose setup, which restarts the app):

```bash
./scripts/demo.sh
./scripts/crash-demo.sh
```

**Tests and benchmarks:**

```bash
./mvnw test                 # 64 unit + integration tests (real embedded PostgreSQL, no Docker needed)
./mvnw -Pbenchmark test     # 200k diff + latency benchmarks, JVM capped at 384 MB; results in target/benchmark-results.md
```

> Embedded PostgreSQL refuses to run as `root`; run the tests as a normal user.

### Demo users (HTTP Basic)

| User | Password | Role | Tenant |
|---|---|---|---|
| `acme-hr` | `acme-hr-pass` | HR_SYSTEM | acme |
| `acme-transport` | `acme-transport-pass` | TRANSPORT_ADMIN | acme |
| `acme-ops` | `acme-ops-pass` | OPERATOR | acme |
| `acme-e1001` | `acme-e1001-pass` | EMPLOYEE (E1001) | acme |
| `globex-hr` / `globex-transport` / `globex-ops` | `<user>-pass` | … | globex |

Passwords are BCrypt-hashed at startup and can be overridden with env vars (`ACME_HR_PASSWORD`, …).
Seeded offices: acme `BLR-01`, `BLR-02`, `HYD-01`; globex `PUN-01`, `GGN-01`.

---

## API

| Method & path | Role | What it does |
|---|---|---|
| `POST /api/v1/hr/events` | HR_SYSTEM | Batch of ≤1,000 `CREATE`/`UPDATE`/`DEACTIVATE` events. Always 200 with a per-record report. |
| `POST /api/v1/hr/snapshots` (multipart `file`) | HR_SYSTEM | Nightly CSV export → reconciliation run report. |
| `GET /api/v1/reconciliations[/{id}]` | OPERATOR, HR_SYSTEM | Run status and counts. |
| `GET /api/v1/reconciliations/{id}/errors` · `/missing` | OPERATOR, HR_SYSTEM | Per-record error report (codes only) · sample of missing employee IDs. |
| `POST /api/v1/reconciliations/{id}/approve` · `/reject` | OPERATOR | Decide a braked run. |
| `GET /api/v1/employees/{id}` · `/{id}/events` | TRANSPORT_ADMIN, OPERATOR | Employee record · change events emitted for them. |
| `PUT /api/v1/employees/{id}/transport` | TRANSPORT_ADMIN | Set pickup point / special assistance (transport-owned). |
| `GET /api/v1/me` | EMPLOYEE | Own record only. |
| `POST /api/v1/bookings` · `GET /api/v1/bookings?employeeId=` | TRANSPORT_ADMIN | Minimal bookings, to show deactivation side effects. |
| `GET/POST /api/v1/offices` | TRANSPORT_ADMIN, OPERATOR (GET also HR) | Office codes used for validation (cached). |
| `GET /api/v1/ops/outbox` · `/outbox/dead` · `POST /outbox/dead/replay` | OPERATOR | Outbox lag, dead letters, replay. |
| `POST /api/v1/ops/faults` | OPERATOR | **Demo only** (`FAULT_INJECTION_ENABLED=true`): arm a one-shot JVM crash. |
| `GET /actuator/health` · `/actuator/prometheus` | public · OPERATOR | Health · metrics. |

Example:

```bash
curl -u acme-hr:acme-hr-pass -H 'Content-Type: application/json' localhost:8080/api/v1/hr/events -d '{"events":[
  {"type":"CREATE","employeeId":"E1001","version":1,"name":"Priya Sharma","email":"priya@acme.com",
   "phone":"+919800000001","managerId":"M1","officeCode":"BLR-01","shiftCode":"MORNING"}]}'
```

Nightly export format (`#TRAILER` line optional — if present and the row count differs, the brake trips):

```csv
employeeId,version,name,email,phone,managerId,officeCode,shiftCode
E1001,8,Priya Sharma,priya@acme.com,+919800000001,M2,BLR-02,EVENING
#TRAILER,1
```

Errors are RFC 7807 problem documents with a stable `code` and the `requestId`, never the input:

```json
{ "status": 404, "title": "Not Found", "detail": "employee not found", "code": "NOT_FOUND", "requestId": "4f0c…" }
```

---

## Assumptions

1. **HR sends the full HR record with every CREATE/UPDATE** (state transfer, not a delta), with a
   per-employee version that only goes up. This is what makes "highest version wins" correct for
   out-of-order delivery. CREATE and UPDATE are treated the same ("state at version N").
2. **Idempotency key = (tenant, employeeId, version).** Same version + same data → `DUPLICATE`;
   same version + different data → `CONFLICT` (reported, not applied: the source broke its own versioning).
3. **The nightly export lists active employees only**, each row carrying the same version numbers as
   the API. An active employee absent from the export is a leaver — unless the brake trips.
4. Deactivating via reconciliation does not bump the version (it has none). It is applied only if the
   employee's version is still the one seen at compare time, so a newer API update always wins.
5. Transport-owned fields are last-write-wins among transport admins (one human UI per office).
6. Times are stored in UTC; "future bookings" means trip date ≥ today (UTC).

## Benchmarks

Measured in a cloud container: **2 vCPU Intel Xeon @ 2.10 GHz, 7 GB RAM, Java 21, embedded PostgreSQL 14.15
on the same 2 vCPUs** (database and JVM compete for CPU, so these are conservative). JVM capped at `-Xmx384m`.

| Scenario | Result |
|---|---|
| Nightly diff, 200,000-employee store vs 199,000-row export (2,000 changed, 1,000 new, 2,000 left) | **2.2 s end to end** (compare phase 0.6 s) — target < 60 s |
| Peak heap during that run | **≤ 93 MB** (bounded: one bucket ≈ 1/64 of the file in memory) |
| Truncated export (120,000 of 199,000) | brake tripped in **1.4 s**, 79,000 missing reported, nobody deactivated |
| Real-time apply, 8 concurrent writers, single-event transactions | **≈ 4,500 events/s, p50 1.27 ms, p99 7.6 ms** |
| First-ever load of 200,000 new employees (insert + 200,000 outbox events) | 63 s (≈ 3,150 rows/s) |
| Batched creates, 100 batches × 500 | ≈ 1,100 events/s, p50 429 ms / p99 1,053 ms per batch |

The batched and first-load numbers are bound by PostgreSQL write I/O on the shared 2-vCPU box (the
JVM spends its time waiting on the socket); see "where it stops scaling" in the design note.
Raw output of the last run: `target/benchmark-results.md` after `./mvnw -Pbenchmark test`.

## Trade-offs (short version — details in the design note)

- **Consistency over availability for writes:** every change takes a row lock and commits with its
  outbox row. If PostgreSQL is down, ingest fails fast (HR retries — idempotency makes that safe)
  rather than accepting data we could lose.
- **Events are at-least-once, effects are exactly-once:** the relay may publish an event twice after
  a crash; consumers dedup by `eventId`. True exactly-once delivery is not possible across a crash.
- **The brake stops the whole run**, including valid joiners in the same file: a broken file is not trusted at all.
- **Synchronous reconciliation endpoint:** simple and fine for 200k rows (seconds); a very large
  tenant would move it to a background job with the same phases.
- **In-process event bus** instead of Kafka: the outbox is the hard part and is real; the publisher is
  one interface (`ChangeEventPublisher`) away from Kafka/SNS. Left out deliberately to keep the
  one-command setup small.
- **HTTP Basic + BCrypt** instead of OAuth2/JWT: stateless and adequate behind TLS for a machine
  HR integration; an OIDC resource server would replace it in production.

## Project layout

```
domain/          pure rules, no Spring/DB: EmployeeMerger, SafetyBrake, RecordValidator, HrRecord…
sync/            real-time ingest: HrController, HrIngestService, EmployeeSyncService (locks + outbox)
reconciliation/  nightly diff: SpillFiles, BucketComparator, ReconciliationService, approval API
outbox/          OutboxRepository, OutboxRelay (retry/backoff/dead-letter), publisher + handler interfaces
booking/         bookings + BookingCancellationHandler (deactivation side effect, deduped)
employee/        repository (JDBC), transport-owned field service, office cache
security/        roles, tenant-bound users, URL rules
common/, ops/    error handling, request ids, fault injection, operator endpoints
```
