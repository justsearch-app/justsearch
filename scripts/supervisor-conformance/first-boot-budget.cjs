'use strict';

// Preloaded only into the conformance runner for a case with a short first-boot budget.
// startDeadlines captures both limits before onSpawned. Restore the normal limits there,
// so a successor that arrives before the first readiness does not inherit the fixture fault.
const supervisor = require('../dev/lib/engine-supervisor.cjs');
const fs = require('node:fs');
const firstBootMs = Number(process.env.JUSTSEARCH_CONFORMANCE_FIRST_BOOT_TIMEOUT_MS);
if (process.env.JUSTSEARCH_SUPERVISOR_HARNESS !== '1'
    || !Number.isSafeInteger(firstBootMs) || firstBootMs <= 0) {
  throw new Error('first-boot budget requires a positive timeout in the conformance harness');
}
const createSupervisionLoop = supervisor.createSupervisionLoop;
supervisor.createSupervisionLoop = (options) => {
  const captured = {};
  const timeouts = new Proxy({ initialPortMs: firstBootMs, initialReadyMs: firstBootMs }, {
    get(limits, key) {
      captured[key] = limits[key];
      return limits[key];
    },
  });
  return createSupervisionLoop({
    ...options,
    timeouts,
    hooks: {
      ...options.hooks,
      onSpawned: async (record) => {
        if (record.n === 1) {
          // The adapter asserts this receipt: a hook moved ahead of deadline capture must fail
          // the case, even though the product catches and logs exceptions thrown by hooks.
          fs.writeFileSync(process.env.JUSTSEARCH_CONFORMANCE_FIRST_BOOT_BUDGET_PATH,
            JSON.stringify(captured));
        }
        Object.assign(timeouts, options.timeouts);
        await options.hooks?.onSpawned?.(record);
      },
    },
  });
};
