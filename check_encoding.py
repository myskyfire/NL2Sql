with open('training_test_cases.txt', 'rb') as f:
    data = f.read()
    
# 尝试UTF-8解码
try:
    text = data.decode('utf-8')
    print('UTF-8 decoding: SUCCESS')
    print(f'File size: {len(data)} bytes')
    print(f'Total lines: {len(text.splitlines())}')
except UnicodeDecodeError as e:
    print(f'UTF-8 decoding: FAILED at position {e.start}')
    print(f'Problem bytes: {data[e.start:e.end]}')
    print(f'Context: {data[max(0,e.start-50):e.end+50]}')
