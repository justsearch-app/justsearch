# C1 initial Engine memory budget

The C1 candidate sets `-XX:MaxDirectMemorySize=256m` in both launchers and their exact-set
test. This is a starting allocation limit, not a measured maximum or a stage-E verdict.
The limit prevents the default direct-buffer allowance from growing with the heap. E must
check real bulk indexing, concurrent search and the model workload before choosing the
final collector and heap. An allocation failure remains visible and must be investigated.

| Consumer | C1 setting or accounting source | What the line bounds |
| --- | --- | --- |
| Java heap | Packaged `-Xmx2g`; development uses its explicit run configuration | Java heap only; the development default is not a 2 GiB claim |
| Metaspace | `-XX:MetaspaceSize=128m` at both launchers | Initial collection threshold, **not** a maximum; no `MaxMetaspaceSize` cap exists |
| Direct byte buffers | `-XX:MaxDirectMemorySize=256m` at both launchers | JVM-accounted direct buffers; not every native allocation |
| ORT host allocations | No host-memory cap in `ModelSessionPolicy` | Unbounded today; measure process private bytes during E |
| ORT device arenas | Per-role `gpuMemMb` via `ModelSessionPolicy.Gpu.arenaCapBytes` | Device allocation only; it cannot stand in for the host line |
| Lucene mappings | File-backed mapped index pages | Account separately as page cache/mapped working set |
| Extraction children | Existing child JVM flags and pool count | Outside the Engine; include every child in the machine sum |
| Generative server | Existing llama-server configuration and slot count | Outside the Engine; include its host/device usage in the machine sum |

On Windows the process metric is commit charge/private bytes, with working set reported
separately. Heap plus direct-buffer limits are not a total process-memory ceiling: metaspace,
thread stacks, ORT and other native allocations remain additional consumers. The stage-E
machine envelope adds Engine, llama-server, extraction children and the accounted page cache.

Initial direct allowance rationale: 256 MiB is one eighth of the packaged heap and leaves
bounded buffer headroom without inheriting another 2 GiB allowance. This is a declared first
cut, chosen before measurements, to be confirmed or corrected by the live gate. It does not
claim measured sufficiency for supported hardware.

Verification so far: `node scripts/dev/test-dev-runner-head-java-opts.mjs` passes with exact
flag sets in both emitters. The repository compile and Rust library tests (77/77) pass.
These are source/launch-contract checks; the live workload and stage-E memory proof remain required.
