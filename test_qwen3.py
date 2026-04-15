import requests
import json

url = "http://localhost:11434/api/chat"

tools = [{
    "type": "function",
    "function": {
        "name": "test_tool",
        "description": "测试工具",
        "parameters": {
            "type": "object",
            "properties": {
                "msg": {"type": "string"}
            },
            "required": ["msg"]
        }
    }
}]

messages = [{"role": "user", "content": "调用测试工具"}]

payload = {
    "model": "qwen3:8b-q8_0",
    "messages": messages,
    "tools": tools,
    "stream": False
}

print("测试 qwen3:8b-q8_0 的 Tool Calling...")
try:
    resp = requests.post(url, json=payload, timeout=60)
    result = resp.json()
    msg = result.get("message", {})
    tc = msg.get("tool_calls", [])
    
    print(f"Tool Calls数量: {len(tc)}")
    print(f"Content: {msg.get('content', '')[:200]}")
    
    if tc:
        print("✅ 支持Tool Calling!")
        print(f"调用的工具: {tc[0]['function']['name']}")
    else:
        print("❌ 不支持或未调用Tool")
except Exception as e:
    print(f"错误: {e}")
