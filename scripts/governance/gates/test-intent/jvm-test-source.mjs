/**
 * Conservative structural proof for the new-test exemption, not a general JVM parser. Only a
 * packaged, single named class is recognised. Unsupported lexical forms and declarations stay
 * gated; comments and ordinary literals cannot hide a declaration or initializer from the scan.
 */
function tokensOf(text, kotlin) {
  text = text.replace(/\r\n?/g, '\n');
  // Java expands Unicode escapes before lexing (even in comments). Kotlin templates and raw
  // strings need their own recursive lexer; backtick and non-ASCII identifiers are also uncertain.
  if (/\\u/.test(text)) return null;
  const tokens = [];
  for (let i = 0; i < text.length;) {
    const ch = text[i];
    if (/\s/.test(ch)) { i++; continue; }
    if (text.startsWith('//', i)) {
      const end = text.indexOf('\n', i + 2);
      i = end < 0 ? text.length : end + 1;
      continue;
    }
    if (text.startsWith('/*', i)) {
      let depth = 1;
      i += 2;
      while (i < text.length && depth) {
        if (kotlin && text.startsWith('/*', i)) { depth++; i += 2; }
        else if (text.startsWith('*/', i)) { depth--; i += 2; }
        else i++;
      }
      if (depth) return null;
      continue;
    }
    if (text.startsWith('"""', i)) return null;
    if (ch === '"' || ch === "'") {
      const quote = ch;
      let closed = false;
      for (i++; i < text.length; i++) {
        if (text[i] === '\n' || text[i] === '\r') return null;
        if (kotlin && quote === '"' && text[i] === '$') return null;
        if (text[i] === '\\') { i++; continue; }
        if (text[i] === quote) { closed = true; i++; break; }
      }
      if (!closed) return null;
      tokens.push('<literal>');
      continue;
    }
    const identifier = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(text.slice(i));
    if (identifier) { tokens.push(identifier[0]); i += identifier[0].length; continue; }
    if (!/[0-9{}()[\];,.@:=<>?!+*/%&|^~\-]/.test(ch)) return null;
    tokens.push(ch);
    i++;
  }
  return tokens;
}

const IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/;

/** Prove the package, sole top-level class and absence of explicit static initialization. */
export function isIsolatedJvmTestSource(rel, text) {
  const match = /^modules\/[^/]+\/src\/[^/]+\/(java|kotlin)\/(.+)\.(java|kt)$/.exec(rel);
  if (!match || typeof text !== 'string') return false;
  const kotlin = match[1] === 'kotlin';
  if (match[3] !== (kotlin ? 'kt' : 'java')) return false;
  const parts = match[2].split('/');
  const name = parts.pop();
  if (parts[0] !== 'io' || parts[1] !== 'justsearch') return false;
  if (parts.length === 0 || ![name, ...parts].every((p) => IDENTIFIER.test(p))) return false;
  const tokens = tokensOf(text, kotlin);
  if (!tokens) return false;

  // Pair every delimiter before trusting any top-level boundary.
  const ends = new Map();
  const stack = [];
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (['{', '(', '['].includes(t)) stack.push(i);
    else if (['}', ')', ']'].includes(t)) {
      const start = stack.pop();
      if (start === undefined || tokens[start] !== ({ '}': '{', ')': '(', ']': '[' })[t]) return false;
      ends.set(start, i);
    }
    if ((!kotlin && t === 'static' && tokens[i + 1] === '{')
      // Reject instance init too: this deliberately recognises a smaller safe Kotlin subset.
      || (kotlin && t === 'init' && tokens[i + 1] === '{')) return false;
  }
  if (stack.length) return false;

  let i = 0;
  const qualifiedName = () => {
    if (!IDENTIFIER.test(tokens[i] ?? '')) return null;
    const out = [tokens[i++]];
    while (tokens[i] === '.' && IDENTIFIER.test(tokens[i + 1] ?? '')) {
      i++;
      out.push(tokens[i++]);
    }
    return out.join('.');
  };
  if (tokens[i++] !== 'package' || qualifiedName() !== parts.join('.')) return false;
  if (tokens[i] === ';') i++;
  else if (!kotlin) return false;
  while (tokens[i] === 'import') {
    i++;
    if (!kotlin && tokens[i] === 'static') i++;
    if (!qualifiedName()) return false;
    if (tokens[i] === '.' && tokens[i + 1] === '*') i += 2;
    if (kotlin && tokens[i] === 'as') {
      i++;
      if (!IDENTIFIER.test(tokens[i++] ?? '')) return false;
    }
    if (tokens[i] === ';') i++;
    else if (!kotlin) return false;
  }
  // Annotations and a small, explicit set of class modifiers can precede the sole declaration.
  const modifiers = kotlin ? ['public', 'internal', 'private', 'open', 'final', 'abstract', 'data', 'sealed']
    : ['public', 'final', 'abstract', 'strictfp'];
  while (tokens[i] === '@' || modifiers.includes(tokens[i])) {
    if (tokens[i] !== '@') { i++; continue; }
    i++;
    if (!qualifiedName()) return false;
    if (tokens[i] === '(') i = ends.get(i) + 1;
  }
  if (tokens[i++] !== 'class' || tokens[i++] !== name) return false;
  // The class header may contain constructor/annotation arguments, but no second declaration.
  for (; i < tokens.length && tokens[i] !== '{'; i++) {
    if ([';', '}', 'class', 'interface', 'object', 'fun', 'val', 'var', '='].includes(tokens[i])) return false;
    if (tokens[i] === '(' || tokens[i] === '[') i = ends.get(i);
  }
  if (tokens[i] !== '{') return false;
  i = ends.get(i) + 1;
  // Java permits an empty top-level declaration after the class.
  if (!kotlin) while (tokens[i] === ';') i++;
  return i === tokens.length;
}

/**
 * Conservative collision check across arbitrary JVM sources, including ones the structural
 * scanner cannot parse. Match the package with optional trivia/backticks and any mention of the
 * simple type name, rather than trusting filenames or trying to parse every Java/Kotlin form.
 * References, comments and literals can over-report collisions; uncertainty keeps the file gated.
 */
export function mayDeclareJvmType(text, qualifiedName) {
  if (typeof text !== 'string') return true;
  text = text.replace(/\r\n?/g, '\n');
  // Java expands eligible Unicode escapes before tokenizing. Checking both forms also covers
  // ineligible escapes and Kotlin strings without having to decide their lexical context.
  const decoded = text.replace(/\\u+([0-9a-f]{4})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
    .replace(/\r\n?/g, '\n');
  const parts = qualifiedName.split('.');
  const name = parts.pop().replace(/[$]/g, '\\$&');
  const trivia = '(?:\\s|/\\*[\\s\\S]*\\*/|//[^\\r\\n]*(?:\\r?\\n|$))*';
  const pkg = parts.map((p) => '`?' + p.replace(/[$]/g, '\\$&') + '`?').join(`${trivia}\\.${trivia}`);
  const packagePattern = new RegExp(`\\bpackage${trivia}${pkg}(?![A-Za-z0-9_$])`, 'i');
  const namePattern = new RegExp(`(?<![A-Za-z0-9_$])${name}(?![A-Za-z0-9_$])`, 'i');
  return [text, decoded].some((source) => packagePattern.test(source) && namePattern.test(source));
}
