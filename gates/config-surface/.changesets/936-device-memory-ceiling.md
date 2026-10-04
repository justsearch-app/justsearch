---
classification: declared-growth
tempdoc: 936
---

D1-14 adds `justsearch.gpu.device_memory_ceiling_mb` as an optional restart-required
operator cap. The Engine clamps both sampled total and free device memory to this value
when choosing beside or in-place encoder composition; zero deliberately forces the
floor path for lifecycle verification. The one new env/sysprop pair and apply-scope row
move their ratchet pins from 236 to 237 and from 277 to 278 in this commit.
