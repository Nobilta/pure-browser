#!/usr/bin/env python3
"""Read the debug APK's actual migrated schema; measure synthetic queries on host SQLite."""
import argparse, json, sqlite3, statistics, subprocess, tempfile, time
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
assert a.serial.startswith('emulator-')
adb=['adb','-s',a.serial];schema=[]
with tempfile.TemporaryDirectory(prefix='pure-query-schema-') as temp:
    for name in ['browser.db','browser.db-wal','browser.db-shm']:
        result=subprocess.run(adb+['exec-out','run-as','com.mybrowser.debug','cat','databases/'+name],capture_output=True)
        if result.returncode==0:Path(temp,name).write_bytes(result.stdout)
    source=sqlite3.connect(str(Path(temp,'browser.db')))
    schema=[row[0] for row in source.execute("SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY type DESC")]
    version=source.execute('PRAGMA user_version').fetchone()[0];source.close()
assert version==3,version
db=sqlite3.connect(':memory:')
for sql in schema:db.execute(sql)
for table,clock in [('bookmarks','created_at'),('history','visit_time')]:
    db.executemany(f'INSERT INTO {table}(title,url,host,{clock}) VALUES(?,?,?,?)',
        [(f'中文 Document {i}',f'https://site{i%5000}.example/item/{i}',f'site{i%5000}.example',i) for i in range(50000)])
db.commit();db.execute('ANALYZE')
queries={
 'bookmark_order':('SELECT * FROM bookmarks ORDER BY created_at DESC,id DESC LIMIT 50',[]),
 'folder_order':('SELECT * FROM bookmarks WHERE folder_id=? ORDER BY position,id LIMIT 50',[0]),
 'history_order':('SELECT * FROM history ORDER BY visit_time DESC,id DESC LIMIT 50',[]),
 'prefix_suggestions':("SELECT * FROM bookmarks WHERE host LIKE ? ESCAPE '!' OR title LIKE ? ESCAPE '!' OR url LIKE ? ESCAPE '!' ORDER BY created_at DESC,id DESC LIMIT 30",['site432.%','site432.%','site432.%']),
 'substring_fallback':("SELECT * FROM bookmarks WHERE title LIKE ? ESCAPE '!' OR url LIKE ? ESCAPE '!' ORDER BY created_at DESC,id DESC LIMIT 20",['%site432.%','%site432.%'])}
records={}
for name,(sql,params) in queries.items():
    plan=[r[3] for r in db.execute('EXPLAIN QUERY PLAN '+sql,params)];samples=[]
    for i in range(22):
        start=time.perf_counter_ns();rows=db.execute(sql,params).fetchall()
        if i>=2:samples.append((time.perf_counter_ns()-start)/1e6)
    records[name]={'plan':plan,'medianMs':statistics.median(samples),'rows':len(rows)}
assert any('idx_bookmark_order' in line for line in records['bookmark_order']['plan'])
assert any('idx_bookmark_folder' in line for line in records['folder_order']['plan'])
assert any('idx_history_time' in line for line in records['history_order']['plan'])
a.output.parent.mkdir(parents=True,exist_ok=True)
result={'schemaVersion':version,'hostSqliteVersion':sqlite3.sqlite_version,'syntheticRowsPerTable':50000,'scope':'Host SQLite, schema copied from the debug APK; no device query-time claim','queries':records}
a.output.write_text(json.dumps(result,indent=2));print(json.dumps(result,indent=2))
