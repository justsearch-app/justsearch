# Response to independent review 3 (2026-09-10), and the freeze

Review 3 is `review-3-findings.md`. Six findings against sixteen and sixteen; all at named
transitions; none touching the direction. That met the convergence signal announced in the
round-3 brief, so v4 closes the six and freezes the contract.

| # | sev | finding | v4 answer | where |
|---|---|---|---|---|
| F1 | High | an unbound launch cannot be declared absent from a registry scan and a memory reading | the PID rule: PID and start time persisted into the intent immediately after `pb.start()`; `RELEASED` only when that process is confirmed dead, or, with no PID recorded, a process scan finds no matching executable started after the intent; registry miss and zero delta are corroboration only; the self-test launcher follows the same protocol; entitlements reconstructed before admission reopens | 5.3 `BOUND`; H1 ledger traces |
| F2 | High | unshared adoption and draining can create two live device authorities | adoption is `attach` under the drain lock (validate `SERVING`, establish lease, cancel idle deadline atomically, or `HOST_DRAINING`); replacement only after the predecessor's confirmed exit or identity-verified termination by the sole-owner Engine; a draining host with leases is never alive beside a successor that opens admission | 7.2; decision 16; H2 rows |
| F3 | High | one fairness cursor lets foreground selections reset aged-lane service | three persistent cursors, one per selection class, each advanced only by its own class; FIFO within lanes; the unseatable marked wait stays pending with aging suspended, reported | 5.2 item 3; H1 scheduler traces |
| F4 | High | automatic sealing contradicts C1 | sealing is the parent's explicit close; release when `sealed AND every accepted child has exited`; a child is retained before its send can become ambiguous; work id is a cancellation group, not the task-group lifetime | 4.4 Engine retention; decision 10 |
| F5 | High | the watermark neither cancels all future submissions nor bounds fence history | cancelled-work tombstone refusing every later submission for that work id; running work stays accepted and terminates normally; tombstones retired at seal or client boot change, bounded by the number of unsealed work ids, which C1 admission bounds | 4.4 cancellation; decision 10 |
| F6 | Medium | the equation double-charges resident tickets | charge = `residentBytes + unmaterialisedEntitlementBytes` per ticket; materialisation moves bytes between the two without changing the sum; partition invariant `resident + sessionRunEntitlement + seatEntitlement = entitlement`; lifecycle state is never a charge | 5.3 admission; decision 5 |

Verified-sound items from all three rounds are unchanged. The reviewer's version note (the
bundle carried v3, not v2) is honoured by keeping `design-v3.md` beside v4; v2 exists only as
quoted in `review-2-findings.md`, which is recorded as a lineage gap.

**Freeze.** No further review round is scheduled. Remaining obligations are implementation-
time proofs named in section 11 and carried by the stage checklists H0 to H2, plus the open
list in section 13 for H2b.
