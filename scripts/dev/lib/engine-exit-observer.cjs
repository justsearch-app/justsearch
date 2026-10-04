'use strict';

// Install at spawn; defer supervision until its run/lease/state are initialized.
function observeEngineExit(child) {
  let exited = false, exitCode, handler;
  child.once('exit', code => {
    exited = true;
    exitCode = code;
    if (handler) handler(code);
  });
  return {
    get exited() { return exited; },
    activate(callback) {
      if (handler) throw new Error('Engine exit observer already activated');
      handler = callback;
      if (exited) handler(exitCode);
    },
  };
}
module.exports = { observeEngineExit };
