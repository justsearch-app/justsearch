# VDU buffer and control-error checkpoint694

September12,2026; production revision41a74500ba72762a18ae2221bf74a581b5221505,
Windows/Java25. Production and test source stayed fixed throughout this run.
Later9d7183dd4/e872a36d4 synchronize the generated and shared skill paragraphs only.

```text
gradlew.bat build pmdAll -PtestParallelism=1 --max-workers=4 --console=plain
```

PASS in22m32s:9,993 cases/1,637 suites, zero failures/errors,35 skips. Eleven of38
test tasks execute;27 are UP-TO-DATE. Build reports43 executed tasks and323 up-to-date.
XML and counts were copied before the stress selection changed module result files.
Focused and negative evidence remains in switch-buffer-version.md and vdu-control-errors.md.

Required broad stress695 stops at app-agent because that module has no matching
`*Stress*` test. It reports a selection failure, not a failed test. Source inventory
locates the two named stress classes in adapters-lucene and ort-common. Stress696:

```text
gradlew.bat :modules:adapters-lucene:test --tests '*Stress*'
  :modules:ort-common:test --tests '*Stress*' -PincludeStress=true
  -PtestParallelism=1 --max-workers=4 --console=plain
```

PASS in8s. adapters-lucene is UP-TO-DATE from695; ort-common is FROM-CACHE with
unchanged inputs. This is reuse, not a new stress execution for either suite in696.
No tag, selector rule or assertion was weakened to bypass empty modules.

[Hosted CI34708670410](https://github.com/justsearch-app/justsearch/actions/runs/34708670410)
is successful at e872a36d42b49a844950c495cf5ece0e4c9cff7a. This records observed
workflow status, not a new per-case hosted artifact audit. Intermediate c950e8c72,
41a74500b and9d7183dd4 runs were cancelled by newer pushes, not treated as passing.

Retained local evidence: tmp/c2-2-buffer-control-full-694.txt, sibling -xml/ and
-counts.json; tmp/c2-2-buffer-control-stress-{695,696}.txt and696 XML/counts.
Capture utilities are tmp/c2-2-capture-buffer-control-full-694.py and
tmp/c2-2-capture-generation.py. Keep through lane acceptance plus30 days and export
before worktree release. These checks do not close generation-transition safety,
the shared offline owner, actual-model/installed proof or C2-2 as a whole.
