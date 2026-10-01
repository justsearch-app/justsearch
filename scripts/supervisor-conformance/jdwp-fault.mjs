/** Small external fault injector; never connects outside loopback or selects its own PID. */
import net from 'node:net';

export function loopbackEndpoint(host, port) {
  if (!['127.0.0.1', '::1'].includes(host) || !Number.isSafeInteger(port) || port < 1 || port > 65535) {
    throw new Error('JDWP requires an explicit loopback endpoint');
  }
  return { host, port };
}
const integer = value => { const b = Buffer.alloc(4); b.writeInt32BE(value); return b; };
export function packet(id, commandSet, command, data = Buffer.alloc(0)) {
  const b = Buffer.alloc(11 + data.length); b.writeUInt32BE(b.length); b.writeUInt32BE(id, 4);
  b[9] = commandSet; b[10] = command; data.copy(b, 11); return b;
}
export function readIds(data, size) {
  if (size < 1 || size > 8 || data.length < 4 || data.length !== 4 + data.readUInt32BE() * size) {
    throw new Error('Malformed JDWP object-id list');
  }
  return Array.from({ length: data.readUInt32BE() }, (_, i) => data.subarray(4 + i * size, 4 + (i + 1) * size));
}
function string(data) {
  if (data.length < 4 || data.readUInt32BE() > data.length - 4) throw new Error('Malformed JDWP string');
  return data.subarray(4, 4 + data.readUInt32BE()).toString('utf8');
}
export async function attachFault({ host = '127.0.0.1', port, kind, threadPattern, verify, record = () => {} }) {
  loopbackEndpoint(host, port);
  if (!['soft', 'hard'].includes(kind) || typeof verify !== 'function') throw new Error('Fault kind and ownership verifier required');
  await verify(); // caller binds port + process creation identity to its owned run
  const socket = net.createConnection({ host, port });
  const pending = new Map(); let sequence = 1, buffer = Buffer.alloc(0), handshaken = false, failed;
  const command = (set, cmd, data) => new Promise((resolve, reject) => {
    if (failed) { reject(failed); return; }
    const id = sequence++, timer = setTimeout(() => { pending.delete(id); reject(new Error('JDWP command timeout')); }, 5000);
    pending.set(id, { resolve, reject, timer }); socket.write(packet(id, set, cmd, data));
  });
  let onEvent = () => {};
  const handshake = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('JDWP handshake timeout')), 5000);
    socket.once('connect', () => socket.write('JDWP-Handshake'));
    socket.on('error', error => { clearTimeout(timer); reject(error); });
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      if (!handshaken) {
        if (buffer.length < 14) return;
        if (buffer.subarray(0, 14).toString() !== 'JDWP-Handshake') { clearTimeout(timer); reject(new Error('Invalid JDWP handshake')); socket.destroy(); return; }
        buffer = buffer.subarray(14); handshaken = true; clearTimeout(timer); resolve();
      }
      while (buffer.length >= 11) {
        const length = buffer.readUInt32BE();
        if (length < 11 || length > 16 * 1024 * 1024) { socket.destroy(new Error('Invalid JDWP packet size')); return; }
        if (buffer.length < length) return;
        const p = buffer.subarray(0, length); buffer = buffer.subarray(length);
        if (p[8] & 0x80) {
          const request = pending.get(p.readUInt32BE(4));
          if (!request) continue;
          clearTimeout(request.timer); pending.delete(p.readUInt32BE(4));
          if (p.readUInt16BE(9)) request.reject(new Error(`JDWP error ${p.readUInt16BE(9)}`));
          else request.resolve(p.subarray(11));
        } else onEvent(p);
      }
    });
  });
  socket.on('close', () => {
    failed = new Error('JDWP connection closed');
    for (const request of pending.values()) { clearTimeout(request.timer); request.reject(failed); }
    pending.clear();
  });
  try {
    await handshake;
    const sizes = await command(1, 7), objectSize = sizes.readInt32BE(8);
    if (objectSize < 1 || objectSize > 8) throw new Error('Unsupported JDWP object-id size');
    const suspended = [], matcher = new RegExp(threadPattern ?? (kind === 'soft' ? '^(qtp|Jetty|jetty|grpc-default-executor)' : '$^'));
    let asynchronousFault;
    const suspendMatching = async id => {
      const name = string(await command(11, 1, id));
      if (!matcher.test(name)) return;
      await verify();
      await command(11, 2, id);
      const evidence = { id: id.toString('hex'), name, at: new Date().toISOString() };
      suspended.push(evidence); record({ event: 'thread-suspended', ...evidence });
    };
    if (kind === 'soft') {
      // New API-pool threads must not undo the wedge. THREAD_START + SUSPEND_NONE.
      onEvent = p => {
        if (p[9] !== 64 || p[10] !== 100) return;
        const body = p.subarray(11);
        if (body.length < 5) return;
        let offset = 5;
        for (let i = 0; i < body.readUInt32BE(1); i++) {
          const event = body[offset]; offset += 5;
          if (event !== 6 || offset + objectSize > body.length) return;
          const id = body.subarray(offset, offset + objectSize); offset += objectSize;
          suspendMatching(id).catch(error => { asynchronousFault = error; record({ event: 'injection-error', reason: error.message }); });
        }
      };
      await command(15, 1, Buffer.concat([Buffer.from([6, 0]), integer(0)]));
      for (const id of readIds(await command(1, 4), objectSize)) await suspendMatching(id);
      if (!suspended.length) throw new Error('No live request threads matched; refusing to claim an API wedge');
    } else {
      await verify(); await command(1, 8); record({ event: 'vm-suspended', at: new Date().toISOString() });
    }
    return { suspended, healthy: () => !asynchronousFault && !failed,
      errors: () => asynchronousFault ? [asynchronousFault.message] : [],
      // Cleanup explicitly resumes the fault if the supervisor did not kill the target.
      async close() {
        try {
          if (!failed) {
            if (kind === 'hard') await command(1, 9);
            else for (const entry of suspended) await command(11, 3, Buffer.from(entry.id, 'hex'));
            await command(1, 6); // Dispose only during cleanup; it resumes debug suspensions.
          }
        } finally { socket.destroy(); }
      } };
  } catch (error) { socket.destroy(); throw error; }
}
