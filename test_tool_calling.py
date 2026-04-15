import requests
import json

def test_tool_calling(model_name):
    """测试模型是否支持Tool Calling"""
    
    url = "http://localhost:11434/api/chat"
    
    # 定义工具
    tools = [
        {
            "type": "function",
            "function": {
                "name": "get_current_weather",
                "description": "获取指定城市的天气",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "location": {
                            "type": "string",
                            "description": "城市名称，例如：北京"
                        }
                    },
                    "required": ["location"]
                }
            }
        }
    ]
    
    # 测试消息
    messages = [
        {
            "role": "user",
            "content": "北京的天气怎么样？"
        }
    ]
    
    payload = {
        "model": model_name,
        "messages": messages,
        "tools": tools,
        "stream": False
    }
    
    print(f"\n{'='*60}")
    print(f"测试模型: {model_name}")
    print(f"{'='*60}")
    
    try:
        response = requests.post(url, json=payload, timeout=60)
        result = response.json()
        
        # 检查是否有tool_calls
        message = result.get("message", {})
        tool_calls = message.get("tool_calls", [])
        content = message.get("content", "")
        
        print(f"\n响应内容:")
        print(f"  Content长度: {len(content)}")
        print(f"  Tool Calls数量: {len(tool_calls)}")
        
        if tool_calls:
            print(f"\n✅ 支持Tool Calling!")
            print(f"  调用的工具: {tool_calls[0]['function']['name']}")
            print(f"  参数: {json.dumps(tool_calls[0]['function']['arguments'], ensure_ascii=False)}")
            return True
        else:
            print(f"\n❌ 不支持Tool Calling或未调用工具")
            if content:
                print(f"  返回文本: {content[:100]}...")
            return False
            
    except Exception as e:
        print(f"\n❌ 测试失败: {e}")
        return False


if __name__ == "__main__":
    models_to_test = [
        "qwen2.5:7b-instruct-q4_K_M",
        "qwen2.5-coder:7b-instruct-q4_0"
    ]
    
    results = {}
    
    for model in models_to_test:
        supported = test_tool_calling(model)
        results[model] = supported
    
    print(f"\n\n{'='*60}")
    print("测试结果汇总:")
    print(f"{'='*60}")
    for model, supported in results.items():
        status = "✅ 支持" if supported else "❌ 不支持"
        print(f"{status} - {model}")
