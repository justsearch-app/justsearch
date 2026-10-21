---
schema: test-intent.v1
task: F-excl
author-role: builder
author-session: fexcl-builder-20261021
---

# Test-intent entries

The exclusion scenarios and publication ordering are from the bounded F-excl assignment.
Independent acceptance is left to the verifier.

```json
{"entries":[
  {"items":["modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java"],
   "class":"New","repaired":true,
   "behaviour":"Every dense, chunk, sparse, NER, combined, late-chunking and BGE-M3 success, failure and fallback publication drops excluded work without changing its pending stages, retries, text or artifacts; unaffected items publish and removing the exclusion makes pending work runnable.",
   "sources":[
     {"kind":"owner-words","location":"task F-excl request X1","quote":"An exclusion saved after an enrichment pass read the policy and before it publishes means the pass publishes nothing for the excluded document."},
     {"kind":"owner-words","location":"task F-excl request X2","quote":"The same holds for every enrichment publication path, not only combined enrichment."},
     {"kind":"owner-words","location":"task F-excl request X3","quote":"Documents that are not excluded publish exactly as today."},
     {"kind":"owner-words","location":"task F-excl request X1","quote":"The document then goes through the normal exclusion handling, the same as a document excluded before the pass started."}],
   "evidence":"gates/test-intent/evidence/fexcl-publication.json",
   "newChecks":[
     "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#exclusionSavedDuringInferencePublishesNothing",
     "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#excludedInferenceFailureCannotSpendRetries",
     "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/ops/EnrichmentExclusionPublicationTest.java#excludedFallbackCannotPublish"]},
  {"items":["modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduExclusionPublicationFenceTest.java"],
   "class":"New","repaired":true,
   "behaviour":"A policy save begun after the VDU exclusion check waits for parent/chunk publication or exhausted-claim failure publication, then the saved policy excludes the document through the existing path.",
   "sources":[
     {"kind":"owner-words","location":"task F-excl request design rule","quote":"re-check the policy at publication, inside the same fence as the existing revision check, so a policy saved after the re-check is ordered after the publication."},
     {"kind":"owner-words","location":"task F-excl request X2","quote":"The same holds for every enrichment publication path, not only combined enrichment."}],
   "evidence":"gates/test-intent/evidence/fexcl-publication.json",
   "newChecks":["modules/worker-services/src/test/java/io/justsearch/indexerworker/services/VduExclusionPublicationFenceTest.java#policySaveWaitsForVduPublicationAfterItsExclusionCheck"]}
]}
```

## Acceptance records
