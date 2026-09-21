# Existing reindex alias: recorded ownership correction

Live1992 at87195eac9 proves that POST /api/indexing/reindex still invokes the direct
IndexingService and flush path. The Library already invokes core.reindex through
prepared operation dispatch. Keep the alias, but route it to that same catalog,
preparation, consent, admission and record owner; remove the controller bypass.

The existing query parameter force remains authoritative (Boolean.parseBoolean,
default false). Accept the same optional flat JSON control fields as the ingestion
alias: idempotencyKey, preparationNonce and confirmationToken. They remain outside
public arguments. The response becomes the existing OperationInvocationResponse,
including the durable key/row, typed consent failures and retained-key conflicts;
do not retain a separate status-only success envelope or a second flush effect.
Matched-route admission must resolve core.reindex before reserving its direct slot.

Verification: real Javalin/filter/catalog/dispatcher/admission/SQLite front tests for
incremental and forced query forms, exact query/control forwarding, same-key retry
and changed-force conflict. Verify the actual ResourceApiModule route registration,
remove the old IndexingRoutes mapping and handler, and check that missing recovery
composition cannot fall through to the old service. Focused UI/controller tests,
PMD/format, relevant governance/docs checks, then corrected live alias proof. Keep
the already-passed full1974 suite as evidence for its revision; repeat integrated
checks at the next coherent boundary. This adds no producer/recovery authority and
no generic dev-MCP allowlist expansion. Both-store and installed recovery stay open.

## Verification on 2026-09-21

Base e39b408b3 plus this alias diff. Focused2004 ran64 cases with one failure:
the new running-retry fixture admitted the original durable effect into its sole
slot, so the retry received the designed ingress429 before dispatch. The separate
first-accept cases still use capacity1. The retry-only fixture now has capacity2
so it can reach the retained-row contract; it asserts only the original durable
owner remains after each HTTP response. No production admission limit changed.

2005: UI DeclaredSurvivalFrontIntegrationTest, OperationsControllerTest and
LegacyEndpointGuardTest execute64 cases/5 suites, zero failures/errors/skips.
The same Gradle run passes system-tests:compileIntegrationTestJava, UI pmdMain,
pmdTest and spotlessJavaCheck. Full command/output and copied XML/counts are
in tmp/2005.txt, tmp/2005-xml and tmp/2005-counts.json. The two existing system
HTTP happy-path tests now check standard receipt identity; this run compiles them,
it does not execute their external-server scenarios. The front tests prove exact
force query/control forwarding, cap1 durable acceptance, running/terminal retry,
changed-force409 and final slot release. LegacyEndpointGuardTest proves the
indexing route cohort cannot supply the removed direct-service fallback.

Live2008 rebuilds this diff and starts owned run2bfbcdf2-053e-4b19-88fd-78a0c7b1d1ca,
API56216, preserved tmp/lane-f-resume-live1982 corpus. Actual production alias2010
returns parent12 key01a0c147-464f-7915-ac36-024ce620c9cf. Read-only2013 proves parent12
REINDEX and child13 INGEST both DURABLE/COMPLETE with one attempt; walk revision9
is sealed and acknowledged, five unchanged files skipped. Retained-key alias2014
returns original row12 and exact parent/child/walk contents remain unchanged.
This proves ResourceApiModule's production route dispatch through recorded ownership.
2012 forced query is refused by the dev tool's path allowlist before HTTP; no
forced live claim follows (query force is covered by the real HTTP fixture).
No new model query was needed for this alias-only correction; live1995 remains
its separately versioned standard-query evidence. Stop2015 closes ports and
quick_health2016 reports ABSENT, no foreign runs, no inference orphan.

Docs regeneration and canonical-links2009 pass; operation-surface2011 passes
with zero findings. Raw artifacts remain in the worktree tmp directory through
lane acceptance plus30 days and must be exported before worktree release.
Both-store/process-death recovery obligations remain open.
