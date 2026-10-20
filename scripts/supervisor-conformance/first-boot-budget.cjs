'use strict';

// Preloaded only into the conformance runner for a case with a short first-boot budget.
// startDeadlines captures both limits before onSpawned. Restore the normal limits there,
// so a successor that arrives before the first readiness does not inherit the fixture fault.
const supervisor = require('../dev/lib/engine-supervisor.cjs');
const firstBootMs = Number(process.env.JUSTSEARCH_CONFORMANCE_FIRST_BOOT_TIMEOUT_MS);
if (process.env.JUSTSEARCH_SUPERVISOR_HARNESS !== '1'
    || !Number.isSafeInteger(firstBootMs) || firstBootMs <= 0) {
  throw new Error('first-boot budget requires a positive timeout in the conformance harness');
}
const createSupervisionLoop = supervisor.createSupervisionLoop;
supervisor.createSupervisionLoop = (options) => {
  const timeouts = { initialPortMs: firstBootMs, initialReadyMs: firstBootMs };
  return createSupervisionLoop({
    ...options,
    timeouts,
    hooks: {
      ...options.hooks,
      onSpawned: async (record) => {
        Object.assign(timeouts, options.timeouts);
        await options.hooks?.onSpawned?.(record);
      },
    },
  });
};
