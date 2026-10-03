"""Small specification experiments, NOT production/JMM/native verification.

Enumerate counterexamples to independent reference capture and exercise the
selected lifetime/closing/commit ordering rules. No repository state is mutated.
"""

import itertools
import json


def paired_capture():
    observed = set()
    schedules = 0
    for order in itertools.permutations(("read_config", "read_graph", "write_config", "write_graph")):
        if order.index("read_config") > order.index("read_graph"):
            continue
        if order.index("write_config") > order.index("write_graph"):
            continue
        schedules += 1
        config = graph = "A"
        read = {}
        for action in order:
            if action == "write_config":
                config = "B"
            elif action == "write_graph":
                graph = "B"
            elif action == "read_config":
                read["config"] = config
            else:
                read["graph"] = graph
        observed.add((read["config"], read["graph"]))
    assert ("A", "B") in observed and ("B", "A") in observed
    protected = set()
    for order in itertools.permutations(("capture", "publish")):
        config = graph = "A"
        for action in order:
            if action == "publish":
                config = graph = "B"
            else:
                protected.add((config, graph))
    assert protected == {("A", "A"), ("B", "B")}
    return {"unprotected_schedules": schedules, "unprotected_pairs": sorted(observed),
            "protected_pairs": sorted(protected)}


def lifetime():
    # The caller captured A; publication may change without invalidating its lease.
    held = 1
    retiring = True
    assert not (retiring and held == 0)
    omitted_retain = 0
    assert retiring and omitted_retain == 0  # negative control permits premature close
    held -= 1
    assert retiring and held == 0
    # A cursor may hold the predecessor after cutover: neither its permit nor
    # its generation slot is released merely because it is no longer serving.
    reader_closed, deleted = True, False
    assert not (reader_closed and deleted)
    deleted = True
    assert reader_closed and deleted
    return "capture requires retain; generation capacity requires witnessed deletion"


def shutdown():
    upgrade_frozen, closing = True, True
    upgrade_frozen = False
    legacy_admits = not upgrade_frozen
    selected_admits = not (upgrade_frozen or closing)
    assert legacy_admits and not selected_admits
    # Index success cannot override another stateful owner's refused close.
    index_closed, bodies_exited, process_closed = True, False, False
    assert index_closed and not (index_closed and bodies_exited and process_closed)
    return "old freeze release cannot reopen closing; index-only lock release is insufficient"


def commitment():
    outcomes = {}
    for order in itertools.permutations(("cancel", "commit_admission")):
        winner = order[0]
        committed = winner == "commit_admission"
        file_value = "B" if committed else "A"
        row = "COMPLETE" if committed else "CANCELLED"
        outcomes[" then ".join(order)] = [file_value, row]
        assert not (file_value == "B" and row != "COMPLETE")
    return outcomes


def commit_coverage():
    # A completed write followed by commit, then another completed write before
    # an unconstrained watermark sample: the sample falsely acknowledges write 2.
    completed = 1
    committed = completed
    completed = 2
    assert completed > committed
    unsafe_acknowledges_second = completed >= 2
    assert unsafe_acknowledges_second and committed < 2
    # The selected exclusive commit interval drains mutations and captures their
    # completed watermark before commit; later mutations cannot change that receipt.
    completed = 1
    captured = completed
    committed = captured
    published = captured
    completed = 2  # admitted only after the barrier releases
    assert published == committed == 1 and published < completed
    # Equal numeric sequence in another runtime is not coverage for this ticket.
    ticket = ("writer-A", 2)
    receipt = ("writer-B", 2)
    assert ticket[0] != receipt[0]
    return "exclusive commit captures completed mutations; later writes and other epochs are not covered"


print(json.dumps({
    "scope": "specification model only; no production code, native runtime or JVM memory-model proof",
    "paired_capture": paired_capture(),
    "lifetime": lifetime(),
    "shutdown": shutdown(),
    "commitment": commitment(),
    "commit_coverage": commit_coverage(),
}, indent=2))
