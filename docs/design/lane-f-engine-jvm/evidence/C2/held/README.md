# Held root replay implementation

R9 holds the unactivated root-plan and ingest-child implementation from compiled
sources. The retirement patch is a complete source/test diff against41d0ab86d.
Reading its removed lines recovers the previously implemented behavior and tests.
`git apply --reverse --check` validates reconstruction against the retirement tree;
this is an archival check, not authorization to activate that former behavior.

C2-3 must first implement public-input digest comparison separately from persisted
prepared payload, looking up the key before preparation. A changed public input is
OPERATION_KEY_REUSED; changed generation/excludes with matching public input must
replay the stored plan. C2-8/C2-10 then adapt and connect the held root/child code to
actual producers, survival admission, accepted scan keys and committed-unit outcomes.
Do not blindly apply the patch: it also restores the superseded test-only activation
assumptions and the identity_json/payload coupling that R9 explicitly rejects.

Current generic preparation, UTF-8 identity bounds, root registration/snapshot
atomicity, generation capture and ordinary runner regressions remain compiled.
The new unactivated-replay refusal regression replaces the old assumption that a
prepared replay schema could be accepted before its keyed replay owner existed.
No failing test was suppressed to obtain a green run; retired feature tests remain
reviewable here and are reactivated only with their owning implementation.
