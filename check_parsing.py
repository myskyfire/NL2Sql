# -*- coding: utf-8 -*-
with open('training_test_cases.txt', 'r', encoding='utf-8') as f:
    lines = [l.strip() for l in f.readlines() if l.strip() and not l.startswith('#')]
    
print(f'Total non-comment lines: {len(lines)}')

failed = []
for i, l in enumerate(lines):
    parts = l.split('|')
    if len(parts) < 4:
        failed.append((i+1, l[:100]))

print(f'Failed to parse (< 4 parts): {len(failed)}')
if failed:
    print('\nFirst 10 failed lines:')
    for ln, content in failed[:10]:
        print(f'  Line {ln}: {content}')
        print(f'    Parts count: {len(content.split("|"))}')
else:
    print('✅ All lines parsed successfully!')
