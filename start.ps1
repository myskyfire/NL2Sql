# 设置控制台代码页为UTF-8
chcp 65001 | Out-Null

# 配置JDK 21路径
$env:JAVA_HOME = "D:\Program Files\Java\jdk-21.0.6"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 启动应用
cd "D:\WorkSpace\idea workspace\NL2Sql\nl2sql-web"
& "$env:JAVA_HOME\bin\java" `
  "-Dfile.encoding=UTF-8" `
  "-Dhttp.proxyHost=127.0.0.1" `
  "-Dhttp.proxyPort=7890" `
  "-Dhttps.proxyHost=127.0.0.1" `
  "-Dhttps.proxyPort=7890" `
  -jar target\nl2sql-web-1.0.0.jar
