@echo off
chcp 65001 >nul

REM 设置控制台编码为UTF-8
set PYTHONIOENCODING=utf-8

REM 创建logs目录（如果不存在）
if not exist "logs" (
    mkdir logs
    echo ✓ 创建logs目录
)

echo ========================================
echo   NLP2SQL Enterprise Agent 启动脚本
echo ========================================
echo
echo 控制台编码: UTF-8
echo 日志目录: logs/
echo   - app.log (JSON格式)
echo   - app-text.log (文本格式)
echo
echo 代理设置: 127.0.0.1:7890
echo

REM 启动应用（配置HTTP代理）
"D:\Program Files\Java\jdk-21.0.6\bin\java" -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Djava.net.useSystemProxies=true -jar target\nlp2sql-web-1.0.0.jar
