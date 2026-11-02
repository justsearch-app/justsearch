/** Owned WebView2 test session: execute requests through the existing shell credential command.
 * Only a response leaves the webview; never return or record the window credential in the harness.
 */
export async function requestThroughWindow(debugPort, endpoint, body, timeoutMs = 30_000) {
  const response = await fetch(`http://127.0.0.1:${debugPort}/json/list`, {
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!response.ok) throw new Error(`WebView2 debugger unavailable: HTTP ${response.status}`);
  const pages = await response.json();
  const page = pages.find(page => page.type === 'page'
    && /^(?:http:\/\/tauri\.localhost|tauri:\/\/localhost)(?:\/|$)/.test(page.url));
  if (!page?.webSocketDebuggerUrl) throw new Error('Owned JustSearch webview not ready');
  const expression = `(async () => {
    const credential = await window.__TAURI_INTERNALS__.invoke('session_token');
    if (!credential?.windowCredential) throw new Error('Window credential not ready');
    const response = await fetch(credential.origin + ${JSON.stringify(endpoint)}, {
      method: 'POST', redirect: 'error',
      headers: {'Content-Type': 'application/json', 'X-JustSearch-Session': credential.token,
        'X-JustSearch-Window': credential.windowCredential},
      body: ${JSON.stringify(JSON.stringify(body))}
    });
    const text = await response.text();
    let body = null; try { body = JSON.parse(text); } catch {}
    return {status: response.status, text, body};
  })()`;
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(page.webSocketDebuggerUrl);
    let finished = false;
    const finish = (error, value) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      socket.close();
      if (error) reject(error); else resolve(value);
    };
    const timer = setTimeout(() => finish(new Error('Owned webview request timed out')), timeoutMs);
    socket.addEventListener('open', () => socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate',
      params: { expression, awaitPromise: true, returnByValue: true } })));
    socket.addEventListener('error', () => finish(new Error('Owned webview debugger disconnected')));
    socket.addEventListener('close', () => finish(new Error('Owned webview debugger closed')));
    socket.addEventListener('message', event => {
      const message = JSON.parse(String(event.data));
      if (message.id !== 1) return;
      if (message.error || message.result?.exceptionDetails) {
        finish(new Error('Owned webview request failed')); // No evaluated objects in logs.
      } else finish(null, message.result?.result?.value);
    });
  });
}
