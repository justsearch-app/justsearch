# Inference observability diagnostic

`health_failure.py` is a historical live smoke check for the typed
`inference.health.failure_total{severity=restart_triggered, code=process_died}`
metric. It cold-starts a backend through `jseval.backend`, kills its managed
`llama-server` mid-flight, and reads the final metrics file. It assumes the
canonical checkout at `F:/JustSearch` and is not part of Lane F acceptance.

The former happy-path, startup-failure and config-failure scripts called the
retired direct inference reload routes and had stale settings payloads. They
were removed with the D1-4 route retirement. The supported inference refresh
path is an accepted `core.reconfigure` operation with a settings witness and
explicit refresh intent. The corresponding metric categories remain covered by
focused inference telemetry tests; Lane F installed-process evidence lives in
`docs/design/lane-f-engine-jvm/handoff.md`.
