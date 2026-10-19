'use strict';

const fs = require('node:fs');

// The MCP launcher needs stdout's first JSON line, but its pipes disappear with the session.
// Keep a durable copy before writing to either pipe; a lost reader must not end supervision.
function preserveSupervisorOutput(logPath, streams = [process.stdout, process.stderr]) {
  for (const stream of streams) {
    const write = stream.write;
    let closed = false;
    stream.on('error', error => {
      if (!['EPIPE', 'ERR_STREAM_DESTROYED', 'ERR_STREAM_WRITE_AFTER_END'].includes(error.code)) throw error;
      closed = true;
    });
    stream.write = function (chunk, encoding, callback) {
      const cb = typeof encoding === 'function' ? encoding : callback;
      fs.appendFileSync(logPath, chunk, typeof encoding === 'string' ? encoding : undefined);
      if (closed || stream.destroyed || stream.writableEnded) {
        if (typeof cb === 'function') process.nextTick(cb);
        return true;
      }
      return write.call(this, chunk, encoding, callback);
    };
  }
}

module.exports = { preserveSupervisorOutput };
