"""Show saved retrieval bodies and parent relationships without rerunning search."""
import json
import re
from pathlib import Path

import requests

target = Path('C:/Users/CraftOldW/.codex/visualizations/2026/09/08/01a0813d-dd87-7903-941c-fd009795c49f/os-chunk-review.html')
report = json.loads(Path('eval/rag/os-human-28-results.json').read_text(encoding='utf-8'))
chunks, questions = {}, []


def add(hit):
    cid = hit['chunkId']
    p = hit['provenance']
    entry = {'text': hit['content'], 'title': p['documentTitle'],
             'loc': json.loads(p['sourceLocation']), 'parent': hit.get('parentChunkId')}
    if cid in chunks:
        if chunks[cid]['text'] != entry['text']:
            raise ValueError(f'Saved content differs for {cid}')
        entry['parent'] = entry['parent'] or chunks[cid]['parent']
    chunks[cid] = entry
    return cid


for q in report['questions']:
    folder = Path('output/os-pilot-10') if 'original 10' in q['batch'] else Path('output/os-remaining-18')
    modes = {}
    for mode in ['BM25', 'VECTOR', 'RRF', 'PARENT']:
        raw = json.loads((folder / f'{q["id"]}-{mode}.json').read_text(encoding='utf-8'))['response']
        ranked = [add(h) for h in raw['rankedChildren']]
        modes[mode] = {'ranked': ranked, 'hits': [add(h) for h in raw['hits']], 'tokens': raw['contextTokens']}
    source = (Path('output/course-text-inventory/text') / (q['documentPath'] + '.txt')).read_text(encoding='utf-8')
    parts = re.split(r'--- 第 (\d+) 页 ---', source)
    pages = [{'page': int(parts[i]), 'text': parts[i+1].strip()} for i in range(1, len(parts), 2) if int(parts[i]) in q['userPages']]
    questions.append({'id': q['id'], 'query': q['query'], 'modes': modes, 'pages': pages,
                      'sourceTitle': Path(q['documentPath']).name, 'note': q['note'], 'batch': q['batch']})

# Saved responses do not include the parents skipped by the context budget.
# Read their existing indexed bodies separately and label this provenance in UI.
missing = sorted({c['parent'] for c in chunks.values() if c['parent'] and c['parent'] not in chunks})
s = requests.Session()
s.trust_env = False
r = s.post('http://127.0.0.1:9200/eval-qwen37-1024-structured-v2-c800-p2400/_search',
           json={'size': 300, '_source': ['chunk_id', 'content', 'document_title', 'source_location'],
                 'query': {'bool': {'filter': [{'term': {'knowledge_base_id': '2098021244222922753'}},
                                              {'terms': {'chunk_id': missing}}]}}}, timeout=30)
r.raise_for_status()
for h in r.json()['hits']['hits']:
    x = h['_source']
    chunks[x['chunk_id']] = {'text': x['content'], 'title': x['document_title'],
                            'loc': json.loads(x['source_location']), 'parent': None, 'indexSupplement': True}
if any(cid not in chunks for cid in missing):
    raise ValueError('Some skipped parent bodies are absent from the existing index')

data = json.dumps({'questions': questions, 'chunks': chunks}, ensure_ascii=False, separators=(',', ':')).replace('<', '\\u003c')
template = Path('scripts/os-chunk-review-template.html').read_text(encoding='utf-8')
html = template.replace('"__REVIEW_DATA__"', data)
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(html, encoding='utf-8')
print(json.dumps({'path': str(target), 'bytes': target.stat().st_size, 'questions': len(questions),
                  'distinctChunks': len(chunks), 'skippedParentsReadFromIndex': len(missing)}))
