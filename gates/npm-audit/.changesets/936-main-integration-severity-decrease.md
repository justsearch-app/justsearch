---
classification: severity-decrease
tempdoc: 936
---
The lane F integration of main (#721/#722) imports two advisory identities that
the lane's current lockfiles no longer contain. The current GitHub advisory
producer reports zero advisories for runtime-client and no high/critical
advisories for ui-web; the existing Knip/smol-toml 1.7.2 correction remains.
The npm-audit gate's shrink-only rebalance removes both high identities from
the baseline. No dependency or severity policy is weakened.

Evidence: docs/design/lane-f-engine-jvm/evidence/C1/resume-2026-09-12.md.
