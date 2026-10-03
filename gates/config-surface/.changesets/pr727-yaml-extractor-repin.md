---
classification: declared-growth
tempdoc: 936
---

Repin yaml_keys from 102 to the freshly generated measurement of 109, alongside
this declaration. The gate consumes yamlKeyCount from
scripts/docs/generate-runtime-config-matrix.mjs: it counts unique YAML
contributions parsed from ResolvedConfigBuilder.java, rather than counting
literal lines in an application.yaml resource.

The PR-base pin at ac1c93bf3 was 112. Commit cfa4a78b8 removed the two infra
health gRPC YAML contributions and config/application.yaml entries, moving the
pin 112 -> 110. Commit d87a0e60c removed the search pipeline profile and six
obsolete worker contributions, moving it 110 -> 103; it also removed obsolete
shipped YAML entries. Commit ecfa96797 removed workers.indexer.enabled, moving
it 103 -> 102. These ten removals explain the 112 -> 102 pin history.

Later commit 43e2f3d25 extended parseYamlContributions to include
putYamlIntFromNode and putYamlIntClampedFromNode. That correction exposed seven
existing contributions previously omitted from the metric; it did not add
seven new settings. The pin was not advanced with the extractor correction.
The complete current extractor measures 109, while the previous extractor
measured 102. This declaration licenses restoring those seven entries to the
count and advances the live pin to exactly 109.

CI resolves the diff base from the merge-base of HEAD and the target branch
(GITHUB_BASE_REF), with HEAD~1 only as a fallback. Thus its PR-wide comparison
sees the original 112 pin and earlier declared-growth changesets, but coverage
cannot license a live measurement above 102. Local report artifacts can be
stale: regenerate the matrix before running the gate. This change satisfies
the current measurement and retains a covering declaration in the PR diff.
