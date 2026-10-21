"""List public/protected API members present in OLD jar but missing in NEW jar (binary-compat check)."""
import subprocess, sys, zipfile, re
old_jar, new_jar = sys.argv[1], sys.argv[2]
def classes(jar):
    with zipfile.ZipFile(jar) as z:
        return sorted(n[:-6].replace('/', '.') for n in z.namelist() if n.endswith('.class') and '$$' not in n)
def members(jar, names):
    out = {}
    for i in range(0, len(names), 200):
        chunk = names[i:i+200]
        txt = subprocess.run(['javap', '-protected', '-cp', jar] + chunk, capture_output=True, text=True).stdout
        cur = None
        for line in txt.splitlines():
            m = re.match(r'^(?:public |protected |abstract |final |sealed |non-sealed |static )*(?:class|interface|enum|@interface|record) ([\w.$]+)', line)
            if m and line.rstrip().endswith('{'):
                cur = m.group(1).split('<')[0]; out.setdefault(cur, set()); out[cur].add('__type__ ' + line.strip()); continue
            if cur and line.startswith('  ') and line.strip().endswith(';'):
                out[cur].add(re.sub(r'\s+', ' ', line.strip()))
    return out
old_classes = classes(old_jar); new_classes = set(classes(new_jar))
oldm = members(old_jar, old_classes); newm = members(new_jar, [c for c in old_classes if c in new_classes])
removed_classes = [c for c in old_classes if c not in new_classes]
problems = 0
for c in removed_classes:
    if c in oldm: print('REMOVED CLASS', c); problems += 1
for c, ms in oldm.items():
    if c not in newm: continue
    for m in sorted(ms - newm[c]):
        if m.startswith('__type__'):
            print('TYPE DECL CHANGED', c, '|', m[9:], '->', [x[9:] for x in newm[c] if x.startswith('__type__')]); problems += 1
        else:
            print('MISSING MEMBER', c, '|', m); problems += 1
print(f'checked {len(oldm)} public types; {problems} incompatibilities')
