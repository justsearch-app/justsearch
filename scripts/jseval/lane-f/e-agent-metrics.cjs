// Projection of retained admission-loop wire results; not an acquisition instrument.
const terminalComplete = c => !c.streamed || c.terminal?.doneCount === 1
  && c.terminal?.errorCount === 0 && c.terminal?.eof === true;
const isAdmitted = c => !c.windowBoundary && !c.error && c.status >= 200 && c.status < 300
  && Number.isFinite(c.durationMs) && c.durationMs >= 0 && terminalComplete(c);
function agentMetrics(calls) {
  const admitted = calls.filter(isAdmitted);
  const agentTerminalErrors = {};
  for (const c of calls.filter(c => !terminalComplete(c))) {
    const code = c.terminal?.errorCode ?? 'INVALID_TERMINAL';
    agentTerminalErrors[code] = (agentTerminalErrors[code] ?? 0) + 1;
  }
  return { agentOffered: calls.length, agentAdmitted: admitted.length, agentTerminalErrors,
    agentP95: admitted.length ? admitted.map(c => c.durationMs).sort((a, b) => a - b)[Math.ceil(admitted.length * .95) - 1] : undefined };
}
exports.terminalComplete = terminalComplete;
exports.isAdmitted = isAdmitted;
exports.agentMetrics = agentMetrics;
