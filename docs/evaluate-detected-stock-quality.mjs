import { readFileSync, writeFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

// Only public stock snapshots are loaded. This tool does not open the production user DB or call APIs.
const directory=dirname(fileURLToPath(import.meta.url));
const snapshots=JSON.parse(readFileSync(resolve(directory,'DETECTED_STOCK_QUALITY_SNAPSHOT_2026_10_12.json'),'utf8'));
const performance=JSON.parse(readFileSync(resolve(directory,'DETECTED_STOCK_LEGACY_PERFORMANCE_2026_10_12.json'),'utf8'));
const labels={BREAKOUT:'단타',REVERSAL_EDGE:'스윙',FLOW_LEADER:'주도주',CATALYST_BURST:'테마주'};
const database=new DatabaseSync(':memory:');
database.exec('CREATE TABLE stock_picks (mode TEXT, stock TEXT, code TEXT, current REAL, target REAL, stop REAL, quoteSource TEXT, quoteCheckedAt TEXT); CREATE TABLE legacy_performance (mode TEXT, samples INTEGER, winRate REAL, avgReturnPct REAL, basis TEXT);');
const price=value=>{
  if(!/^(?:\d+|\d{1,3}(?:,\d{3})+)$/.test(String(value)))throw new Error('Invalid snapshot price');
  const parsed=Number(String(value).replaceAll(',','')); if(!Number.isFinite(parsed)||parsed<=0)throw new Error('Invalid snapshot price'); return parsed;
};
const insert=database.prepare('INSERT INTO stock_picks VALUES (?,?,?,?,?,?,?,?)');
for(const snapshot of snapshots)for(const pick of snapshot.stockPicks){
  if(!/^\d{6}$/.test(pick.code))throw new Error('Invalid snapshot stock code');
  insert.run(labels[snapshot.mode]??snapshot.mode,pick.name,pick.code,price(pick.currentPrice),price(pick.targetPrice),price(pick.stopLoss),pick.quoteSource,pick.quoteCheckedAt);
}
const insertPerformance=database.prepare('INSERT INTO legacy_performance VALUES (?,?,?,?,?)');
for(const row of performance.rows)insertPerformance.run(row.mode,row.samples,row.winRate,row.avgReturnPct,row.basis);
const queries={
  picks:'SELECT mode, stock, code, current, target, stop, quoteSource, quoteCheckedAt, CASE WHEN target > current AND current > stop THEN ROUND((target-current)/(current-stop),2) ELSE NULL END AS rewardRisk FROM main.stock_picks ORDER BY rewardRisk ASC, mode, code',
  frequency:'SELECT stock, code, COUNT(*) AS occurrences FROM main.stock_picks GROUP BY code, stock ORDER BY occurrences DESC, code',
  legacy:'SELECT mode, samples, winRate, avgReturnPct, basis FROM main.legacy_performance ORDER BY avgReturnPct ASC',
};
const datasets=Object.fromEntries(Object.entries(queries).map(([id,sql])=>[id,database.prepare(sql).all()]));
const summary=database.prepare('SELECT COUNT(*) AS slots,COUNT(DISTINCT code) AS distinctStocks,SUM(CASE WHEN target>current AND current>stop AND target-current<current-stop THEN 1 ELSE 0 END) AS rewardRiskBelowOne FROM main.stock_picks').get();
writeFileSync(resolve(directory,'DETECTED_STOCK_QUALITY_CALCULATIONS_2026_10_12.json'),JSON.stringify({queries,datasets,summary},null,2));
database.close();
process.stdout.write(JSON.stringify(summary));
