# Full test suite, Qodana, and formatting report

21 November 2026 (America/Chicago)

Results from bringing up the five-database Docker stack and running the verification suite on Linux against branch `test-report/viz-export-2026-11-21` at `0b490e7`. No application or test logic was modified; `ktfmtFormat` was run and left eight formatted files in the working tree.

## Summary

| Check | Outcome | Notes |
| --- | --- | --- |
| `./scripts/docker_test_databases.sh up` | Passed | All 6 containers healthy; SQL Server, Oracle, and Db2 seeded |
| `./gradlew check --continue` | Failed, then passed after fixes | Original run: 3 `ktfmtCheck` tasks and 2 unit test failures; all resolved, see Resolution |
| `compileRenderKotlin`, `testDockerDatabaseHarness`, `testAssertConveyorSite`, `:mcp:npmCliTest`, `verifyMcpCoverageRatchet` | Passed | Verified independently after unit test failures blocked `check` |
| `./gradlew integrationTest --rerun-tasks` | Passed | 32 tests ran and passed across all 5 engines; 5 SSL tests skipped |
| `./gradlew qodanaScan` | Passed | `QDJVM` (`recommended` profile), 0 findings |
| `./gradlew ktfmtFormat` + `./gradlew ktfmtCheck` | Passed | Formatted 8 Kotlin files; `ktfmtCheck` passed across all modules |

## Test databases

`./scripts/docker_test_databases.sh up` exited `0` with all containers healthy:

- `safedb-test-mysql-connectivity`
- `safedb-test-mysql`
- `safedb-test-postgres`
- `safedb-test-mssql` (seeded by `safedb-test-mssql-init`)
- `safedb-test-oracle`
- `safedb-test-db2`

## Unit tests and `check` gates

`./gradlew check --continue` exited `1` before `ktfmtFormat` was run. Three formatting tasks failed initially (`:ktfmtCheckMain`, `:ktfmtCheckTest`, `:shared:ktfmtCheckMain`) and were later fixed and re-verified with `ktfmtCheck`. Two unit test failures were observed (fixed afterwards; see Resolution):

| Suite | Total | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| Desktop (`:test`) | 416 | 415 | 1 | 0 | 0 |
| Shared (`:shared:test`) | 763 | 762 | 1 | 0 | 0 |
| MCP (`:mcp:test`) | 253 | 253 | 0 | 0 | 0 |

### Observed unit test failures (original run)

1. **`com.safedb.ui.VisualizationChartTest.contrastingBarLabelColorMeetsWcagAaAcrossAllThemePalettes()`** (`src/test/kotlin/com/safedb/ui/VisualizationChartTest.kt:432`)
   - Failure: `org.opentest4j.AssertionFailedError: Expected >= 4.5 contrast for ControlBlue (dark=false) bar=Color(0.7176471, 0.41568628, 0.0, 1.0, sRGB IEC61966-2.1) label=Color(0.05882353, 0.09019608, 0.16470589, 1.0, sRGB IEC61966-2.1), got 4.3144217`
2. **`com.safedb.explore.ExploreAggregateTest.averageRoundsToEightDecimalsOnBothSurfaces()`** (`shared/src/test/kotlin/com/safedb/explore/ExploreAggregateTest.kt:121`)
   - Failure: `org.opentest4j.AssertionFailedError: expected: <23.33333333> but was: <23.3333>`

### Other `check` tasks

- Passed (`./gradlew compileRenderKotlin testDockerDatabaseHarness testAssertConveyorSite :mcp:npmCliTest verifyMcpCoverageRatchet`): all 5 tasks exited `0`.
- Not verified in the original run: `verifyUnitTestDiscovery` and root `koverVerify` (`verifyCoverageRatchet` for desktop and `:shared`) did not complete because `:test` and `:shared:test` failed. They ran in the post-fix `check`.

## Integration tests

`./gradlew integrationTest --rerun-tasks` exited `0` against the live Docker stack without `SAFEDB_TEST_*` overrides.

| Suite | Class | Tests | Passed | Failed | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| `:shared:integrationTest` | `Db2AdapterIntegrationTest` | 3 | 3 | 0 | 0 |
| `:shared:integrationTest` | `MssqlAdapterIntegrationTest` | 3 | 3 | 0 | 0 |
| `:shared:integrationTest` | `MySqlAdapterIntegrationTest` | 4 | 4 | 0 | 0 |
| `:shared:integrationTest` | `MySqlQuerySafetyIntegrationTest` | 1 | 1 | 0 | 0 |
| `:shared:integrationTest` | `OracleAdapterIntegrationTest` | 3 | 3 | 0 | 0 |
| `:shared:integrationTest` | `PostgresAdapterIntegrationTest` | 2 | 2 | 0 | 0 |
| `:shared:integrationTest` | `SafeDbServiceDb2IntegrationTest` | 1 | 1 | 0 | 0 |
| `:shared:integrationTest` | `SafeDbServiceMySqlIntegrationTest` | 1 | 1 | 0 | 0 |
| `:shared:integrationTest` | `SafeDbServicePostgresIntegrationTest` | 1 | 1 | 0 | 0 |
| `:shared:integrationTest` | `SslCompatIntegrationTest` | 5 | 0 | 0 | 5 |
| `:mcp:integrationTest` | `McpMySqlIntegrationTest` | 2 | 2 | 0 | 0 |
| `:mcp:integrationTest` | `McpPackagedDialectsIntegrationTest` | 5 | 5 | 0 | 0 |
| `:mcp:integrationTest` | `McpPackagedStdioIntegrationTest` | 4 | 4 | 0 | 0 |
| `:mcp:integrationTest` | `McpPostgresIntegrationTest` | 2 | 2 | 0 | 0 |

### Integration coverage gap

- All 5 tests in `SslCompatIntegrationTest` skipped because `SAFEDB_TEST_REQUIRE_SSL` and `SAFEDB_TEST_SSL_LAUNCH_PROFILE` are not set in a bare `./gradlew integrationTest` invocation. Live TLS compatibility requires running `scripts/verify_ssl_compat.sh` (or `scripts/docker_test_databases.sh verify`), which was not executed in the original pass (run afterwards; see Resolution).

## Static analysis (Qodana)

`./gradlew qodanaScan` exited `0` (`108.064s`) using `qodana.yaml` (`QDJVM`, profile `recommended`):

- SARIF outputs (`build/qodana/results/qodana.sarif.json` and `qodana-short.sarif.json`): `exitCode: 0`, `executionSuccessful: True`, `0` tool execution notifications, `0` findings.

## Formatting (`ktfmt`)

`./gradlew ktfmtFormat` exited `0` and reformatted 8 files in the working tree:

- `shared/src/main/kotlin/com/safedb/explore/ApplyVisualization.kt`
- `src/main/kotlin/com/safedb/App.kt`
- `src/main/kotlin/com/safedb/export/HtmlReportWriter.kt`
- `src/main/kotlin/com/safedb/ui/RecipeFileChooser.kt`
- `src/main/kotlin/com/safedb/ui/VisualizationChart.kt`
- `src/test/kotlin/com/safedb/export/HtmlReportWriterTest.kt`
- `src/test/kotlin/com/safedb/ui/VisualizationChartTest.kt`
- `src/test/kotlin/com/safedb/viewmodel/ExploreViewModelTest.kt`

`./gradlew ktfmtCheck` then exited `0` across all modules (`14 actionable tasks: 3 executed, 11 up-to-date`).

## Resolution

The two unit test failures above were fixed afterwards. Each fix is one line:

| Failure | Fix |
| --- | --- |
| `VisualizationChartTest.contrastingBarLabelColorMeetsWcagAaAcrossAllThemePalettes` | In `VisualizationSeriesPalette` (`src/main/kotlin/com/safedb/ui/theme/Color.kt`), the third series color changed from `0xFFB76A00` to `0xFFA85F00`. The old color reached only 4.14:1 with a white label and 4.31:1 with the dark label. It was the only failing color across all palettes in light and dark modes. |
| `ExploreAggregateTest.averageRoundsToEightDecimalsOnBothSurfaces` | In `formatVisualizationNumber` (`shared/src/main/kotlin/com/safedb/explore/ApplyVisualization.kt`), the `Auto` pattern changed from `"#,##0.####"` to `"#,##0.########"`, so chart labels keep the 8-decimal contract of the pivot surface. |

Re-verification after the fixes:

- `./gradlew check --continue` exited `0`. Unit test results: desktop 416 tests, shared 763, MCP 253, with 0 failures, 0 errors, and 0 skipped in each. This run includes `verifyUnitTestDiscovery` and the coverage ratchets that were blocked before. A first attempt inside the sandbox failed in 5 UI tests with `NoClassDefFoundError: Could not initialize class org.jetbrains.skia.Surface` (an environment problem unrelated to the fixes). It was re-run outside the sandbox.
- `./gradlew qodanaScan` exited `0` with `0 problem detected`.
- `scripts/verify_ssl_compat.sh --rerun-tasks --no-build-cache` (with `SAFEDB_SSL_ROOT=.docker/safedb-ssl`) exited `0`. `SslCompatIntegrationTest`: 5 tests, 5 passed, 0 skipped. This closes the "Integration coverage gap" above.
- Follow-up: the HTML export dark bar label color in `HtmlReportAssets.kt` (`.bar-label-dark`) was `#1c1f26`, which differed from the in-app `0xFF0F172A`. It is now `#0f172a`. The remaining `#1c1f26` values in that file are the report text color, not bar labels.
