# Unchanged assertion proof for Adaptation scopes

Compared exact assertion-call text at c00a0cb24 with the final candidate. No expected value, message, condition or ordering inside these calls changed. Changed frame/admission encodings are listed in the changeset; these are the specifically scoped adaptations, not the Obsolete window/rail oracles.

## StatusStateProducersTest.java#indexingJobCountsWithinTheSameStatesDoNotAdvanceTheCounter

2 identical assertion calls; SHA256 39a34b8564d6ec5f35bbd4b8a8283993eb3c01b653c72f25ed0205862405b471. Original execution receipt: queue-scale-original-base.json. Final candidate receipt: queue-scale-current.json.

```java
assertEquals(before, statusCounter(),
            "more or fewer queued jobs in the same states is a measurement, not a state change");
assertEquals(before + 1, statusCounter(), "a job state appearing is a state change");
```

## RemoteIndexingJobsBridgeTest.java#statusCounterTracksJobsAndNotReadOnlyReattachment

3 identical assertion calls; SHA256 52a9e9afcdbfdf830a67bd43de23e85f275b5a1d862f8eb107fa644f58319b62. Original execution receipt: queue-scale-original-base.json. Final candidate receipt: queue-scale-current.json.

```java
assertEquals(before + 1, statusCounter(), "worker queue publication must invalidate status");
assertEquals(before + 1, statusCounter(), "same snapshot at a new cursor must not invalidate");
assertEquals(before + 2, statusCounter());
```

## QueueProjectionCommitTest.java#subscriberClaimSurvivesEnqueueAndCompletionBookkeeping

4 identical assertion calls; SHA256 b6138b3b620dede68fcc1597df0e4bb2b060c2b93177f4b659ae5f31fa09e437. Original execution receipt: queue-scale-original-base.json. Final candidate receipt: queue-scale-current.json.

```java
assertTrue(queue.markClaimDone(first, success(), null),
          "outer enqueue must finish invalidating old claims before subscriber acquires a new one");
assertTrue(queue.markClaimDone(claimed.get(), success(), null));
assertTrue(queue.markClaimDone(claimed.get(), success(), null),
          "outer finishClaim must not erase a replacement claim created by its subscriber");
assertFalse(queue.markClaimDone(first, success(), null));
```
