# Branch Bug Review

Review target: `cursor/dcecde63` against `main` (merge base `2f2bec24ead15b2a8528ec7174ccc3f430b31e18`).

## Findings

### 1. High — Lifetime schema caching can bypass mandatory risk blocking

**File/line:** `shared/src/main/kotlin/com/safedb/service/SafeDbServiceImpl.kt:220`

**Trigger:** Load a schema while a table is classified as `Small`, allow the table to grow externally, then run a query under Standard or Flexible with a current plan showing a high-row table scan and usable optimizer cost.

**Incorrect behavior:** The stale cached metadata produces a 4-point Elevated scan signal without `mandatoryBlockWhenGateEnabled`, so execution proceeds. Fresh `Large` metadata would make the plan finding mandatory and block enabled risk gates. The same issue can affect join-expansion decisions after uniqueness changes.

**Suggested fix:** Do not use a lifetime schema cache for safety evaluation. Re-introspect before risk evaluation, or enforce a freshness/revision policy that prevents stale catalog metadata from determining mandatory plan blocks.

**Regression test:** Cache a Small/unique schema, switch the fake adapter to current Large/non-unique metadata plus a high scan/join plan, and assert the gate blocks before execution.

### 2. Medium — Extracted pivot comparator changes worksheet sorting

**Files/lines:** `shared/src/main/kotlin/com/safedb/explore/PivotSupport.kt:90`; affected calls include `shared/src/main/kotlin/com/safedb/explore/ApplyWorksheet.kt:210` and `:243`.

**Trigger:** Sort worksheet text values such as `apple` and `Banana` ascending, with or without grouping.

**Incorrect behavior:** Sorting changes from the previous case-insensitive order (`apple`, `Banana`) to case-sensitive order (`Banana`, `apple`). `NULL` and empty text also compare equal instead of using the worksheet comparator’s explicit null ordering, which can change order-dependent calculations.

**Why the change allows it:** Making `compareCells(ResultCell, ResultCell)` `internal` creates a more-specific overload than the worksheet’s private nullable comparator.

**Suggested fix:** Rename the extracted helper to `comparePivotCells` and update only pivot call sites.

**Regression test:** Add mixed-case and `NULL` versus empty-string worksheet sort cases.

### 3. Medium — Right-side Builder controls overlap the canvas

**File/line:** `src/main/kotlin/com/safedb/ui/BuilderScreen.kt:575` (related branch at `:548`).

**Trigger:** Build a query with at least one filter, enough groups/sorts to fill the 208dp options card, and enough joins to fill the 88dp join list.

**Incorrect behavior:** Only the filter panel contributes to `queryControlsHeightPx`. The canvas inset bottoms out at 232dp while the right control column can extend to about 310dp, obscuring and intercepting tables and join lines. The checked-in collapsed Builder preview reproduces this overlap.

**Suggested fix:** Measure both control siblings and use their maximum height for `contentTopInset` without restoring a full-width pointer-blocking overlay.

**Regression test:** Render a full Builder scene with one filter, capped options, and capped joins; assert the first table row begins below both overlays and remains interactive.

### 4. Medium — Join hit-testing can intercept scrollbar gestures

**Files/lines:** `src/main/kotlin/com/safedb/ui/Canvas.kt:419-445`; scrollbar children at `:702-712`.

**Trigger:** Pan a routed join segment beneath the horizontal or vertical scrollbar, then drag the scrollbar.

**Incorrect behavior:** The parent Initial-pass handler captures and consumes the gesture; the scrollbar cannot move, and releasing can add or remove the underlying join.

**Suggested fix:** Keep join input below scrollbar overlays or explicitly exclude scrollbar bounds and non-primary pointer input before capturing the gesture.

**Regression test:** Place a routed line under each scrollbar, drag the thumb/track, and assert scrolling changes while joins remain unchanged.

### 5. Medium — A successful connection save can be reported as failed

**Files/lines:** `src/main/kotlin/com/safedb/viewmodel/ConnectionsViewModel.kt:60-68`; `src/main/kotlin/com/safedb/ui/ConnectionForm.kt:130-142`.

**Trigger:** `createConnection` or `updateConnection` succeeds, but the immediately following `listConnections()` call fails.

**Incorrect behavior:** The form reports a save error and stays open even though persistence completed. Retrying a create can reuse the same generated ID and return “Connection already exists.”

**Suggested fix:** Treat mutation success independently from list refresh; refresh after acknowledging the save and expose refresh failures as list-load errors.

**Regression test:** Use a fake service where mutation succeeds and list refresh fails; assert the save completion is acknowledged once and no retry is needed.

### 6. Incomplete fix — MSSQL SSL host change does not affect the documented harness

**Files/lines:** `shared/src/integrationTest/kotlin/com/safedb/integration/SslCompatIntegrationTest.kt:334`; `scripts/verify_ssl_compat.sh:33`.

**Trigger:** Run `scripts/verify_ssl_compat.sh`.

**Incorrect behavior:** The script exports `SAFEDB_TEST_MSSQL_SSL_HOST=127.0.0.1`, bypassing the new `localhost` fallback. `hostNameInCertificate` becomes `127.0.0.1`, while the property test expects `localhost`, so the documented suite fails before live SSL verification.

**Suggested fix:** Align the script’s MSSQL default with the certificate identity, or derive the assertion from the configured host.

**Regression test:** Run the property test with the same MSSQL host environment exported by the script.

## Verification

- Fresh `./gradlew check koverXmlReport koverVerify --rerun-tasks --no-build-cache`: passed; 211 desktop tests, 345 shared tests, 84.54% desktop coverage, 79.92% shared coverage.
- `./gradlew renderPreview --rerun-tasks`: passed; all 36 previews rendered.
- Focused SSL reproduction with `SAFEDB_TEST_MSSQL_SSL_HOST=127.0.0.1`: failed at the expected `localhost` assertion, confirming finding 6.
