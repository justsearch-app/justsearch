import fs from 'node:fs';
import path from 'node:path';
export function configuredFixture(root) {
  fs.mkdirSync(root, { recursive: true });
  for (const name of ['gte-multilingual-base', 'splade', 'ner', 'reranker', 'citation-scorer']) {
    const dir = path.join(root, 'onnx', name); fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'model.onnx'), `${name} weights`);
    fs.writeFileSync(path.join(dir, 'tokenizer.json'), '{}');
    if (name === 'splade') fs.writeFileSync(path.join(dir, 'vocab.txt'), 'vocabulary');
  }
  const chat = path.join(root, 'chat.gguf'); fs.writeFileSync(chat, 'chat weights');
  return { keys: [{ key: 'justsearch.models.dir', value: root }, { key: 'justsearch.llm.model_path', value: chat }] };
}
