---
schema: test-intent.v1
task: t-20261005-904643
author-role: builder
author-session: 35e861e7-ea02-44df-a223-17d1b43e0007
---

# Test-intent entries

Tempdoc 966 D6, first increment (scenario S11): `UnreferencedCodeTest`'s method rule stops exempting
non-public production methods that only tests call, and freezes the ones that exist today. The
classification of every formerly exempt method (callers searched across the whole repository's
production sources) is in `docs/tempdocs/966-evidence/d6-classification.md`: 84 methods frozen (78
test-only, 6 with no caller at all), 0 cross-module exemptions, 14 map entries that exempted nothing
dropped. The other two rules in the file (`no_unreferenced_package_private_classes`,
`no_unreferenced_package_private_fields`) are unchanged.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-launcher/src/test/java/io/justsearch/app/launcher/UnreferencedCodeTest.java",
        "modules/app-launcher/src/test/resources/archunit.properties"
      ],
      "class": "Structural rule kept",
      "rule": "UnreferencedCodeTest#no_unreferenced_non_public_methods: every private or package-private production method (io.justsearch, test bytecode not imported; lambdas, synthetic and bridge methods, Jackson-annotated methods and gRPC ImplBase overrides excluded as before) must be called or method-referenced by production code. Changed: (1) the name exemptions *ForTest*, *ForTesting, install* and reset* are removed; (2) the KNOWN_UNREFERENCED map (59 entries keyed by simple class name + method name) is replaced by CROSS_MODULE_PRODUCTION_CALLERS, keyed by fully qualified owner and signature, empty because no formerly exempt method has a production caller anywhere; (3) the rule is wrapped in FreezingArchRule with the violation text 'Method <fully qualified owner>.<name>(<parameter types>) is never referenced'; (4) a guard fails the test when the store lacks this rule's description or freeze.refreeze=true, unless store creation is enabled for the documented seeding run. archunit.properties adds the store configuration: freeze.store.default.path=archunit_store (overridden by an absolute path from build.gradle.kts), allowStoreCreation=false, allowStoreUpdate=true, and the seeding procedure.",
      "whySurvives": "The predicate is stricter, never weaker: every method the old rule reported is still reported (the remaining exclusions are the old ones minus the name predicates and the simple-name map), and methods the old rule exempted are now reported unless listed in the store. The 84 stored methods are exactly the violations the removed exemptions hid at the base (compared line by line after seeding), so nothing that passed before fails now and nothing new passes. Proven on the working tree: a new package-private SmokeDriver.probeD6ForTest() called only from a test passed the base rule (exempt by name) and fails the new one; deleting ConsentCapsuleService.liveGrantCount() with its test passes and drops that line from the store; a renamed rule description, freeze.refreeze=true and a missing store each fail. The test count of :modules:app-launcher:test is unchanged (103 before and after; the class still has 3 test methods).",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-904643",
          "scenario": "S11",
          "quote": "In app-launcher, non-public production methods reachable only from tests count as unreferenced: `KNOWN_UNREFERENCED` is split into named cross-module exemptions (production callers outside app-launcher's classpath) and test-only entries, which move to the frozen store; the name predicates (`*ForTest*`, `*ForTesting`, `install*`, `reset*`) are replaced by the methods they match today, classified the same way; violations are frozen by fully qualified owner and signature in a deliberately seeded store that shrinks automatically; an addition needs an entry and acceptance; refreezing in normal runs is impossible. Public methods and frontend exports are stated gaps.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-904643",
          "scenario": "P6",
          "quote": "Removing test-only production code together with its tests stays possible and needs no source beyond the removal itself.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    },
    {
      "items": [
        "modules/app-launcher/archunit_store/fbfdda09-fb1d-494e-a2a6-e593ce75d5a6",
        "modules/app-launcher/archunit_store/stored.rules"
      ],
      "class": "Structural rule kept",
      "rule": "The seeded FreezingArchRule store of UnreferencedCodeTest#no_unreferenced_non_public_methods. stored.rules maps the rule description 'Private/package-private methods should be referenced by other code (potential dead code)' to the violation file; the violation file lists 84 methods as 'Method <fully qualified owner>.<name>(<parameter types>) is never referenced'.",
      "whySurvives": "The store is the accepted set S11 names: the methods the removed exemptions (45 live KNOWN_UNREFERENCED entries and 39 name-predicate matches) hid at base 325dfa5da, classified one by one in docs/tempdocs/966-evidence/d6-classification.md (78 test-only, 6 with no caller in tests or production; none has a production caller outside app-launcher's classpath, so none stays a named exemption). It was seeded once, deliberately (allowStoreCreation=true for one run of UnreferencedCodeTest, then back to false), and its contents equal the enumerated violations exactly. It can only shrink in normal runs: allowStoreUpdate=true drops a stored method once it is removed or gains a production caller, and any other addition is a manual edit of these files, which this gate flags.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-904643",
          "scenario": "S11",
          "quote": "In app-launcher, non-public production methods reachable only from tests count as unreferenced: `KNOWN_UNREFERENCED` is split into named cross-module exemptions (production callers outside app-launcher's classpath) and test-only entries, which move to the frozen store; the name predicates (`*ForTest*`, `*ForTesting`, `install*`, `reset*`) are replaced by the methods they match today, classified the same way; violations are frozen by fully qualified owner and signature in a deliberately seeded store that shrinks automatically; an addition needs an entry and acceptance; refreezing in normal runs is impossible. Public methods and frontend exports are stated gaps.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261005-904643",
          "scenario": "P6",
          "quote": "Removing test-only production code together with its tests stays possible and needs no source beyond the removal itself.",
          "label": "agent-drafted, owner-adopted"
        }
      ]
    }
  ]
}
```

## Acceptance records
