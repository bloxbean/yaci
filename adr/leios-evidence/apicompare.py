"""List the external API (public/protected types and their public/protected members) present in OLD jar but missing
in NEW jar (binary-compat check).

A type counts as external when it is public or protected and so is every type that encloses it. Package-private and
private types, and anonymous or local classes, are not API: they may change freely.

Usage: apicompare.py OLD.jar NEW.jar [CLASSPATH]. Exits 1 on any incompatibility, 2 on a tool error or a class whose
listing is missing, so a CI step or a reviewer cannot read a failed run as a pass (ADR 0016 Verification 5)."""
import re
import subprocess
import sys
import zipfile

old_jar, new_jar = sys.argv[1], sys.argv[2]
extra_cp = sys.argv[3] if len(sys.argv) > 3 else ''
tool_errors = 0

HEADER = re.compile(r'^((?:public |protected |private |abstract |final |sealed |non-sealed |static |strictfp )*)'
                    r'(?:class|interface|enum|@interface|record) ([\w.$]+)')


def classes(jar):
    with zipfile.ZipFile(jar) as z:
        return sorted(n[:-6].replace('/', '.') for n in z.namelist()
                      if n.endswith('.class') and not n.endswith(('module-info.class', 'package-info.class')))


def listings(jar, names):
    """Each class's header modifiers and its public/protected members, as javap prints them."""
    global tool_errors
    headers, members = {}, {}
    for i in range(0, len(names), 200):
        chunk = names[i:i + 200]
        cp = jar + (':' + extra_cp if extra_cp else '')
        run = subprocess.run(['javap', '-protected', '-cp', cp] + chunk, capture_output=True, text=True)
        if run.returncode != 0:
            print('TOOL ERROR javap exit', run.returncode, run.stderr.strip()[:500])
            tool_errors += 1
        cur = None
        for line in run.stdout.splitlines():
            m = HEADER.match(line)
            if m and line.rstrip().endswith('{'):
                cur = m.group(2).split('<')[0]
                headers[cur] = m.group(1).split()
                members.setdefault(cur, set()).add('__type__ ' + re.sub(r'\s+', ' ', line.strip()))
                continue
            if cur and line.startswith('  ') and line.strip().endswith(';'):
                members[cur].add(re.sub(r'\s+', ' ', line.strip()))
    return headers, members


def external(name, headers):
    """Public or protected, and so is every enclosing type (Outer$Inner)."""
    parts = name.split('$')
    for i in range(1, len(parts) + 1):
        enclosing = '$'.join(parts[:i])
        modifiers = headers.get(enclosing)
        if modifiers is None or not ({'public', 'protected'} & set(modifiers)):
            return False
    return True


def check_coverage(jar, names, listed):
    # every class javap can describe must have been parsed; a class the regex missed is a tool error, not a pass
    global tool_errors
    for c in names:
        if c not in listed:
            print('MISSING LISTING', jar, c)
            tool_errors += 1


old_classes = classes(old_jar)
new_classes = classes(new_jar)
old_headers, old_members = listings(old_jar, old_classes)
new_headers, new_members = listings(new_jar, new_classes)
check_coverage(old_jar, old_classes, old_headers)
check_coverage(new_jar, new_classes, new_headers)

old_api = [c for c in old_classes if external(c, old_headers)]
problems = 0
for c in old_api:
    if c not in new_headers:
        print('REMOVED CLASS', c)
        problems += 1
        continue
    if not external(c, new_headers):
        print('NO LONGER ACCESSIBLE', c, '|', ' '.join(new_headers[c]))
        problems += 1
        continue
    for m in sorted(old_members[c] - new_members[c]):
        if m.startswith('__type__'):
            print('TYPE DECL CHANGED', c, '|', m[9:], '->',
                  [x[9:] for x in new_members[c] if x.startswith('__type__')])
        else:
            print('MISSING MEMBER', c, '|', m)
        problems += 1
print(f'checked {len(old_api)} external types of {len(old_classes)} classes; '
      f'{problems} incompatibilities; {tool_errors} tool errors')
sys.exit(2 if tool_errors else (1 if problems else 0))
