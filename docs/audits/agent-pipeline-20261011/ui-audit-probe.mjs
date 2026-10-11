/**
 * BitMan UI audit observations, captured 2026-10-11.
 * Reads actual repository source, transpiles it in memory, and uses isolated
 * VM mocks plus React SSR. No API, token, production storage, or log access.
 * These assertions preserve observed defects; after fixes they should fail.
 * Usage: node ui-audit-probe.mjs --repo-root <repository>
 * Repository discovery also supports BITMAN_AUDIT_REPO_ROOT, cwd, or ancestors
 * of this file, so this can later live under docs/audits inside the repository.
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

function findRepository() {
  const optionIndex = process.argv.indexOf('--repo-root');
  const explicit = optionIndex >= 0 ? process.argv[optionIndex + 1] : undefined;
  if (optionIndex >= 0 && !explicit) throw new Error('--repo-root requires a path');
  const candidates = [explicit, process.env.BITMAN_AUDIT_REPO_ROOT, process.cwd()];
  let directory = path.dirname(fileURLToPath(import.meta.url));
  while (true) {
    candidates.push(directory);
    const parent = path.dirname(directory);
    if (parent === directory) break;
    directory = parent;
  }
  for (const candidate of candidates.filter(Boolean)) {
    const root = path.resolve(candidate);
    if (fs.existsSync(path.join(root, 'frontend/src/pages/HomePage.tsx'))
      && fs.existsSync(path.join(root, 'frontend/src/hooks/useAnalysis.ts'))
      && fs.existsSync(path.join(root, 'frontend/package.json'))) return root;
  }
  throw new Error('Repository not found. Supply --repo-root or BITMAN_AUDIT_REPO_ROOT.');
}

const repositoryRoot = findRepository();
const requireFromFrontend = createRequire(path.join(repositoryRoot, 'frontend/package.json'));
const ts = requireFromFrontend('typescript');
const React = requireFromFrontend('react');
const jsxRuntime = requireFromFrontend('react/jsx-runtime');
const { renderToStaticMarkup } = requireFromFrontend('react-dom/server');
const sourceReads = new Map();

function moduleFromSource(relativePath, mocks, globals = {}) {
  const sourcePath = path.join(repositoryRoot, relativePath);
  const source = fs.readFileSync(sourcePath, 'utf8');
  sourceReads.set(relativePath, crypto.createHash('sha256').update(source).digest('hex'));
  const output = ts.transpileModule(source, {
    compilerOptions: {
      module: ts.ModuleKind.CommonJS,
      target: ts.ScriptTarget.ES2022,
      jsx: ts.JsxEmit.ReactJSX,
    },
  }).outputText;
  const module = { exports: {} };
  vm.runInNewContext(output, {
    module,
    exports: module.exports,
    require: name => {
      if (Object.hasOwn(mocks, name)) return mocks[name];
      throw new Error('Unmocked import: ' + name);
    },
    console,
    setTimeout,
    clearTimeout,
    ...globals,
  }, { filename: sourcePath });
  return module.exports;
}

function homeMocks(mainConditions, stateOverrides = {}) {
  let hookSlot = 0;
  return {
    react: {
      ...React,
      useEffect() {},
      useRef: value => ({ current: value }),
      useState: value => {
        const index = hookSlot++;
        return [Object.hasOwn(stateOverrides, index) ? stateOverrides[index] : value, () => {}];
      },
    },
    'react/jsx-runtime': jsxRuntime,
    'react-router-dom': {
      useNavigate: () => () => {},
      Link: props => React.createElement('a', { href: props.to }, props.children),
    },
    '../hooks/useAnalysis': {
      useAnalysis: () => ({ result: null, loading: false, error: null, analyze() {}, clear() {} }),
    },
    '../contexts/AuthContext': {
      getStoredToken: () => undefined,
      useAuth: () => ({ user: { role: 'ADMIN', subscription: 'pro' } }),
    },
    '../hooks/useMainConditions': {
      useMainConditions: () => ({ data: mainConditions, loading: false, error: null }),
    },
    '../api/conditionApi': {
      fetchConditionSection() { throw new Error('Network disabled'); },
    },
    '../components/app/AgentMascot': { AgentMascot: () => null },
    './HomePage.css': {},
  };
}

function observeMixedSnapshots() {
  const freshSection = {
    mode: 'BREAKOUT', sourceStatus: 'REALTIME_SCAN', asOf: '2026-04-29T03:00:00Z',
    signals: [{
      stockName: 'NEW_PICK_1200', stockCode: '000002', capturePrice: '200',
      highPrice: '210', currentPrice: '200', maxReturnPct: '+5%',
      capturedAt: '2026-04-29T03:00:00Z', riskFlags: [],
    }],
  };
  const home = moduleFromSource('frontend/src/pages/HomePage.tsx', homeMocks(
    { sections: { shortTerm: freshSection } },
    {
      3: { 'short-term': [{
        name: 'OLDER_PICK_0900', code: '000001', a: '100', b: '110',
        c: '+10%', captureTime: '09:00',
      }] },
      4: { 'short-term': '단타 최신 후보 반영' },
    },
  ));
  const markup = renderToStaticMarkup(home.default());
  const shortTerm = markup.split('<section id="short-term"')[1].split('</section>')[0];
  assert(shortTerm.includes('OLDER_PICK_0900'), 'Expected stored 09:00 endpoint rows to persist');
  assert(!shortTerm.includes('NEW_PICK_1200'), 'Expected 12:00 main rows to be hidden');
  assert(shortTerm.includes('12:00'), 'Expected newer main snapshot time on old endpoint rows');
  return { oldRowsShown: true, newRowsHidden: true, displayedSnapshotTime: '12:00' };
}

async function observeLateQuestionOverwrite() {
  const values = [];
  let slot = 0;
  const pending = {};
  const hook = moduleFromSource('frontend/src/hooks/useAnalysis.ts', {
    react: {
      useState: initial => {
        const index = slot++;
        values[index] = initial;
        return [initial, value => { values[index] = value; }];
      },
      useCallback: callback => callback,
    },
    '../api/analysisApi': {
      fetchPrecomputed: async () => null,
      fetchLiveAnalysis: query => new Promise(resolve => { pending[query] = resolve; }),
    },
    '../lib/analysisHistory': { addHistory() {} },
    '../lib/analysisCache': { getCached: () => null, setCache() {} },
    '../contexts/AuthContext': { getStoredToken: () => undefined },
  });
  const analysis = hook.useAnalysis();
  const makeResult = content => ({
    content, metadata: { agentsSucceeded: 1, agentsUsed: 1 },
    updatedAt: '2026-04-29T03:00:00Z', stockPicks: [], isFresh: true,
  });
  const first = analysis.analyze('QUESTION_A');
  analysis.clear();
  const second = analysis.analyze('QUESTION_B');
  pending.QUESTION_B(makeResult('ANSWER_B'));
  await second;
  assert.equal(values[0].content, 'ANSWER_B');
  pending.QUESTION_A(makeResult('ANSWER_A'));
  await first;
  assert.equal(values[0].content, 'ANSWER_A', 'Expected closed earlier request to overwrite later answer');
  return { secondAnswerFirst: 'ANSWER_B', finalAnswerAfterLateFirstCompletion: 'ANSWER_A' };
}

function observePerformanceLabels() {
  // This fixture preserves the frontend contract only. The separate backend
  // audit shows candidate target returns are supplied in these summary fields.
  const home = moduleFromSource('frontend/src/pages/HomePage.tsx', homeMocks({
    sections: {},
    trackRecord: { totalSignals: 1, avgMaxReturnPct: '+6.00%', winRate5d: '100.0%' },
  }));
  const markup = renderToStaticMarkup(home.default());
  assert(markup.includes('검증 신호 1건'));
  assert(markup.includes('평균 +6.00%'));
  assert(markup.includes('승률 100.0%'));
  return { renderedLabels: ['검증 신호 1건', '평균 +6.00%', '승률 100.0%'] };
}

async function observeStaleLiveCache() {
  let taskNow = Date.parse('2026-04-29T00:00:00Z');
  const storage = new Map();
  const cache = moduleFromSource('frontend/src/lib/analysisCache.ts', {}, {
    Date: { now: () => taskNow },
    localStorage: {
      getItem: key => storage.get(key) || null,
      setItem: (key, value) => storage.set(key, value),
      removeItem: key => storage.delete(key),
    },
  });
  const saved = {
    content: 'OLD_0900', updatedAt: '2026-04-29T00:00:00Z', isFresh: true,
    metadata: { agentsUsed: 1, agentsSucceeded: 1 },
  };
  cache.setCache('same query', '분석해줘', saved);
  taskNow += 2 * 60 * 60 * 1000;
  let calls = 0;
  const hook = moduleFromSource('frontend/src/hooks/useAnalysis.ts', {
    react: { useState: initial => [initial, () => {}], useCallback: callback => callback },
    '../api/analysisApi': {
      fetchPrecomputed: async () => null,
      fetchLiveAnalysis: async () => { calls++; throw new Error('Network disabled'); },
    },
    '../lib/analysisHistory': { addHistory() {} },
    '../lib/analysisCache': cache,
    '../contexts/AuthContext': { getStoredToken: () => undefined },
  });
  const value = await hook.useAnalysis().analyze('same query', '분석해줘');
  assert.equal(calls, 0);
  assert.equal(value.isFresh, true);
  assert.equal(value.isPrecomputed, false);
  assert.equal(value.content, 'OLD_0900');
  return { ageHours: 2, serverCalls: calls, content: value.content, isFresh: value.isFresh, isPrecomputed: value.isPrecomputed };
}

const observations = [
  ['mixed_endpoint_rows_and_main_time', observeMixedSnapshots],
  ['late_closed_question_overwrites_next_answer', observeLateQuestionOverwrite],
  ['candidate_style_summary_rendered_as_performance', observePerformanceLabels],
  ['two_hour_live_cache_keeps_fresh_flag', observeStaleLiveCache],
];
let failures = 0;
console.log('BitMan offline UI audit probe');
console.log('Repository: ' + repositoryRoot);
console.log('Scope: real source read/transpile/VM/SSR; mocked network and browser storage only.');
for (const [name, observe] of observations) {
  try {
    const evidence = await observe();
    console.log('OBSERVED ' + name + ': ' + JSON.stringify(evidence));
  } catch (error) {
    failures++;
    console.log('NOT_REPRODUCED ' + name + ': ' + (error?.stack || error));
  }
}
for (const [source, sha256] of sourceReads) console.log('SOURCE_SHA256 ' + source + ' ' + sha256);
console.log('Observed ' + (observations.length - failures) + '/' + observations.length + '; no repository files changed.');
process.exitCode = failures ? 1 : 0;
