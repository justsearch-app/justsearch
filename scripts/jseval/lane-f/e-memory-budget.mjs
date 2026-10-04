/** Consumer accounting projection. Launch flags and RSS alone cannot establish this budget. */
export function componentBudget(accounting, arm) {
  const checks = [], reasons = [];
  const positive = x => Number.isFinite(x) && x >= 0;
  if (!accounting?.processes?.length) return { check: undefined, reason: 'Consumer private-byte accounting not collected (including host ORT)' };
  const required = arm === 'main' ? ['head', 'worker'] : arm === 'branch' ? ['engine'] : accounting.requiredRoles ?? [];
  for (const role of required) if (!accounting.processes.some(p => p.role === role)) { checks.push(undefined); reasons.push(`${role}: missing component process`); }
  for (const p of accounting.processes) {
    if (!['head', 'worker', 'engine'].includes(p.role)) continue; // extraction/llama are outside this component
    for (const name of ['heap', 'metaspace', 'direct', 'hostOrt', 'nativeOther']) {
      const c = p.consumers?.[name];
      const measurable = positive(c?.privateBytes) && positive(c?.budgetBytes);
      checks.push(measurable ? c.privateBytes <= c.budgetBytes : undefined);
      if (!measurable) reasons.push(`${p.role}/${name}: missing consumer accounting/budget`);
    }
    const consumers = Object.values(p.consumers ?? {});
    const complete = p.complete === true && positive(p.privateBytes) && positive(p.luceneMmapBytes)
      && consumers.every(c => positive(c.privateBytes) && positive(c.budgetBytes))
      && consumers.reduce((n, c) => n + c.privateBytes, 0) >= p.privateBytes;
    checks.push(complete ? true : undefined);
    if (!complete) reasons.push(`${p.role}: unattributed private bytes or missing separate Lucene mmap accounting`);
  }
  return { check: checks.includes(false) ? false : checks.length && checks.every(c => c === true) ? true : undefined,
    reason: reasons.join('; ') || 'Per-consumer private bytes within declared budgets; page cache and children accounted separately' };
}
