"""Launch bridge seam only; these checks do not substitute for the live Engine proof."""
from contextlib import ExitStack
from types import SimpleNamespace
from unittest.mock import Mock
import time

from jseval.quality import frozen_index as frozen
from .test_frozen_index import source
from .conftest import pinned_env


def test_frozen_handle_supplies_internal_launch_intent_only_at_wait(source, monkeypatch):
    collection, config, root, baseline, record = source
    launch = Mock(return_value=SimpleNamespace(proc=Mock()))
    monkeypatch.setattr(frozen.backend, 'start_backend', launch)
    monkeypatch.setattr(frozen, 'confirm', lambda *args: SimpleNamespace(ok=True))
    with ExitStack() as stack:
        handle = frozen.FrozenHandle(collection, config, root, root/'data', root,
                                     record, {}, pinned_env(config), {}, stack, root/'run.jsonl')
        launch.assert_not_called()
        assert handle.wait(prepared_only=True, deadline=time.monotonic()+30) is handle
        request = launch.call_args.kwargs
        assert request['env_overrides']['JUSTSEARCH_FROZEN_EVAL'] == 'true'
        assert request['prepared_only'] and not request['clean']
        assert request['index_cache_mode'] == 'off'
        handle._backend = None  # the mock seam owns no process lifetime
