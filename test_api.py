import requests
import json

API_URL = "http://localhost:8080/api/nl2sql/chat"
TOKEN = "test-token-123456"

response = requests.post(
    API_URL,
    json={'message': '用户总数', 'datasourceId': 1},
    headers={'Authorization': f'Bearer {TOKEN}'},
    timeout=30
)

print(f"Status Code: {response.status_code}")
print(f"\nResponse:")
print(json.dumps(response.json(), indent=2, ensure_ascii=False))
