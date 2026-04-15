# 设置控制台代码页为UTF-8
chcp 65001 | Out-Null

# 启动应用
cd "E:\work\idea workspace\NLP2Sql\nlp2sql-web"
java "-Dfile.encoding=UTF-8" -jar target\nlp2sql-web-1.0.0.jar
