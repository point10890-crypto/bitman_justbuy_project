"""Read-only feasibility probe. No strategy promotion; rules fixed before results."""
from pathlib import Path
import argparse
import hashlib
import json
import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--captures', type=Path, required=True)
parser.add_argument('--bars', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
OUT = args.output
OUT.mkdir(parents=True, exist_ok=True)
records = pd.read_csv(args.captures, dtype={'stock_code': str})
records = records[records['mode'].isin(['BREAKOUT','REVERSAL_EDGE','FLOW_LEADER','CATALYST_BURST','JONGGA_V2'])].copy()
records['at'] = pd.to_datetime(records.created_at, utc=True).dt.tz_convert('Asia/Seoul')
records['day'] = pd.to_datetime(records.analysis_date)
records = records.sort_values('at').drop_duplicates(['mode','day','stock_code'], keep='first')
bars = pd.read_pickle(args.bars)
bars = bars[bars.final].drop_duplicates(['code','date']).sort_values(['code','date'])
by_code = {str(code): g.set_index('date') for code,g in bars.groupby('code')}
out=[]
excluded={}
def skip(reason): excluded[reason]=excluded.get(reason,0)+1
for r in records.itertuples():
    g=by_code.get(r.stock_code)
    if g is None or r.day not in g.index: skip('no_final_bar'); continue
    i=g.index.get_loc(r.day)
    if i<120: skip('history_short'); continue
    if r.at.date()!=r.day.date(): skip('capture_date_mismatch'); continue
    minute=r.at.hour*60+r.at.minute
    if not 540<=minute<=930: skip('outside_regular_session'); continue
    px=float(r.price_at_analysis)
    b=g.iloc[i]; prev=g.iloc[i-1]; hist=g.iloc[:i]
    if not np.isfinite(px) or not b.low<=px<=b.high: skip('price_outside_daily_range'); continue
    c=hist.close
    if (c.iloc[-120:].pct_change().abs()>.30).any(): skip('corporate_action_context'); continue
    ma5,ma20,ma60,ma120=[c.iloc[-n:].mean() for n in (5,20,60,120)]
    high20=hist.high.iloc[-20:].max(); high250=hist.high.iloc[-250:].max() if len(hist)>=250 else np.nan
    change=px/prev.close-1
    aligned=ma5>ma20>ma60
    dry=hist.volume.iloc[-5:].mean()<hist.volume.iloc[-20:].mean()
    checks={
        'BREAKOUT': bool(.03<=change<=.15 and px>prev.high and px>b.open),
        'REVERSAL_EDGE': bool(aligned and prev.low<=ma20*1.02 and prev.close>=ma20*.98 and px>b.open and px>prev.close and dry),
        'FLOW_LEADER': bool(px>ma60>ma120 and px>=.90*high250),
        # Theme membership/history cannot be inferred from an industry label.
        'CATALYST_BURST': None,
        # Only these parts can be tested without the captured intraday high/volume.
        'JONGGA_V2': bool(.05<=change<=.15 and px>ma20),
    }
    h={'BREAKOUT':0,'REVERSAL_EDGE':5,'FLOW_LEADER':20,'CATALYST_BURST':0,'JONGGA_V2':1}[r.mode]
    if i+h>=len(g): skip('right_censored'); continue
    future=g.iloc[i+1:i+h+1]
    if h and (g.close.iloc[i:i+h+1].pct_change().abs()>.30).any(): skip('corporate_action_outcome'); continue
    terminal=(g.close.iloc[i+h]/px-1)*100
    peak=(future.high.max()/px-1)*100 if h else np.nan
    gap=(g.open.iloc[i+1]/px-1)*100 if r.mode=='JONGGA_V2' else np.nan
    out.append(dict(mode=r.mode,day=str(r.day.date()),code=r.stock_code,at=r.at.isoformat(),price=px,
        passed=checks[r.mode],terminal=terminal,peak=peak,gap=gap,change=change*100,
        ma_aligned=aligned,dry=dry,near_high=px/high250,open_above=px>b.open))
d=pd.DataFrame(out)
d.to_csv(OUT/'intent_probe_rows.csv',index=False)
summary={'exclusions':excluded,'rules':'fixed original intent predicates; technical layer only; no PIT material/financial/flow data','sections':{}}
for mode,g in d.groupby('mode'):
    result={}
    for label,part in [('all',g),('development',g[g.day<'2026-08-01']),('validation',g[g.day>='2026-08-01'])]:
        keep=part[part.passed.eq(True)]
        days=keep.day.unique(); paired=part[part.day.isin(days)]
        result[label]={'baseline_n':len(part),'selected_n':len(keep),'baseline_days':part.day.nunique(),'selected_days':len(days)}
        for metric in ['terminal','peak','gap']:
            before=paired.groupby('day')[metric].mean(); after=keep.groupby('day')[metric].mean()
            result[label][metric]={'baseline_same_days':None if before.empty else round(before.mean(),3),
                'selected':None if after.empty else round(after.mean(),3),
                'delta':None if after.empty else round((after-before).mean(),3)}
    summary['sections'][mode]=result
# These four ablations were inspected on development data after the strict rules.
# All are disclosed, including failures; no grid search or automated promotion.
summary['ablations'] = {}
for mode, rule in [('REVERSAL_EDGE', 'dry_volume'), ('REVERSAL_EDGE', 'aligned_trend'),
                   ('FLOW_LEADER', 'near_52w_high'), ('FLOW_LEADER', 'positive_intraday')]:
    result = {}
    for label, part in [('development', d[d.day < '2026-08-01']),
                        ('validation', d[d.day >= '2026-08-01'])]:
        g = part[part['mode'] == mode]
        mask = {'dry_volume': g.dry, 'aligned_trend': g.ma_aligned,
                'near_52w_high': g.near_high >= .90, 'positive_intraday': g.open_above}[rule]
        keep = g[mask]
        paired = g[g.day.isin(keep.day)]
        result[label] = {'baseline_n': len(g), 'selected_n': len(keep),
                         'baseline_days': g.day.nunique(), 'selected_days': keep.day.nunique()}
        for metric in ['peak', 'terminal']:
            before = paired.groupby('day')[metric].mean()
            after = keep.groupby('day')[metric].mean()
            result[label][metric] = {'baseline_same_days': before.mean(),
                                    'selected': after.mean(), 'delta': (after-before).mean()}
    summary['ablations'][mode + ':' + rule] = result
summary['input_sha256'] = {name: hashlib.sha256(path.read_bytes()).hexdigest()
                          for name, path in [('captures', args.captures), ('bars', args.bars)]}
summary['publication_eligible'] = False
summary['limitations'] = [
    'Historical captured candidates only; not whole-market discovery or current-policy replay.',
    'Record creation time is a proxy, not proven first market capture time.',
    'No point-in-time actual turnover, material, financial or flow history in this comparison.',
    'No intraday post-capture high: BREAKOUT and CATALYST_BURST peak is unknown.',
    'Swing/leader peaks cover subsequent sessions, excluding the capture session.',
    'Daily final low/high used only for retrospective data-quality exclusion.',
    'Corporate action exclusion is a heuristic; no adjustment-factor reconciliation.',
    'Daily equal weights on days with selected candidates; coverage must be read alongside returns.',
    'Gross returns; overlapping observations; no statistical significance or predictive guarantee.',
    'Chronological validation, not untouched prospective data; earlier research used 2026 data.',
]
def clean(value):
    if isinstance(value, dict): return {k: clean(v) for k,v in value.items()}
    if isinstance(value, list): return [clean(v) for v in value]
    if isinstance(value, (float, np.floating)): return round(float(value), 6) if np.isfinite(value) else None
    if isinstance(value, np.integer): return int(value)
    return value
summary = clean(summary)
(OUT/'intent_probe_summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False))