# C1 installed dev launch: initialize the owned log directory

Clean run205 wrote the Engine application log to `build/headless-data/logs/engine.log` instead of
its configured data directory. Logback consumes `justsearch.data.dir` before HeadlessApp mirrors
`JUSTSEARCH_DATA_DIR`; the dev runner supplied only the latter. Packaged startup and the isolated
Java fixture already pass the system property before Java entry.

`buildHeadJavaOpts` now receives the dev runner's existing resolved `dataDir` and passes it as
`-Djustsearch.data.dir`. This is a projection of the launch authority, not a new setting. The owned
path follows operator JAVA_OPTS so a stale inherited property cannot divert logs to another run.
Space-containing paths use the same quoting convention as the existing heap-dump path.

Adverse223 fails the new path assertion with original code. Restored225 passes the exact shared
launch-flag test, including spaces and a conflicting inherited data-dir flag. Logs:
`tmp/c1-dev-log-path-before-223.txt`, `tmp/c1-dev-log-path-restored-225.txt`.
The next real installed run must confirm the application log exists in its owned data directory.
This fixes the dev-run evidence path; it does not explain the hosted ledger failure because
`IsolatedBackendFixture` already passed the property at JVM entry.
