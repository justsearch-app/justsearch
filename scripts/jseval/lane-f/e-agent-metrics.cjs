// Projection of retained admission-loop wire results; not an acquisition instrument.
const terminalComplete = c => !c.streamed || c.terminal?.doneCount === 1
  && c.terminal?.errorCount === 0 && c.terminal?.eof === true;
// Cancellation cannot erase any terminal or HTTP violation already observed.
const boundaryCensored = c => c.windowBoundary === true && (c.cancellationCause === 'fixed-window-end' || !c.streamed && c.cancellationCause === 'mode-block-end')
  && ['TRANSPORT_FAILURE', 'WINDOW_BOUNDARY_CANCELLED'].includes(c.error)
  && (c.status == null || c.status >= 200 && c.status < 300)
  && (!c.streamed || c.terminal?.errorCount === 0 && c.terminal?.doneCount <= 1 && c.terminal?.eof === false);
const isAdmitted = c => !c.windowBoundary && !c.error && c.status >= 200 && c.status < 300
  && Number.isFinite(c.durationMs) && c.durationMs >= 0 && terminalComplete(c);
function agentMetrics(calls) {
  const admitted = calls.filter(isAdmitted);
  const agentTerminalErrors = {};
  for (const c of calls.filter(c => !terminalComplete(c) && !boundaryCensored(c))) {
    const code = c.terminal?.errorCode ?? 'INVALID_TERMINAL';
    agentTerminalErrors[code] = (agentTerminalErrors[code] ?? 0) + 1;
  }
  return { agentOffered: calls.length, agentAdmitted: admitted.length, agentTerminalErrors,
    agentP95: admitted.length ? admitted.map(c => c.durationMs).sort((a, b) => a - b)[Math.ceil(admitted.length * .95) - 1] : undefined };
}
exports.terminalComplete = terminalComplete;
exports.isAdmitted = isAdmitted;
exports.agentMetrics = agentMetrics;
exports.boundaryCensored = boundaryCensored;
exports.wireOutcomes = calls => {
  const outcomes = {};
  for (const c of calls) {
    const outcome = boundaryCensored(c) ? 'boundary-censored' : !terminalComplete(c)
      ? `terminal:${c.terminal?.errorCode ?? 'INVALID_TERMINAL'}` : c.error ? `error:${c.error}` : `http:${c.status ?? 'missing'}`;
    outcomes[outcome] = (outcomes[outcome] ?? 0) + 1;
  }
  return outcomes;
};
