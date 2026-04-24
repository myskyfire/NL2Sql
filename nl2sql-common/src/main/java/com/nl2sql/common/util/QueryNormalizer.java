package com.nl2sql.common.util;

/**
 * 查询文本归一化工具类
 * 
 * 用于NL2SQL查询缓存的文本归一化，去除可变实体（人名、地名、时间、金额等），保留查询结构
 * 业界标准参考：https://help.aliyun.com/zh/polardb/polardb-for-mysql/llm-based-nl2sql
 */
public class QueryNormalizer {
    
    /**
     * 归一化查询文本
     * 
     * @param query 原始查询文本
     * @return 归一化后的文本
     */
    public static String normalize(String query) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        
        String normalized = query;
        
        // 1. ✅ 先替换时间词，再替换人名，避免"昨天张三"被误匹配
        normalized = normalized.replaceAll("昨天|前天|今天|明天|后天", "{DATE_RELATIVE}");
        normalized = normalized.replaceAll("上周|本周|下周|上月|本月|下月|去年|今年|明年", "{DATE_RELATIVE}");
        normalized = normalized.replaceAll("最近\\d+天", "最近{NUM}天");
        normalized = normalized.replaceAll("过去\\d+天", "过去{NUM}天");
        normalized = normalized.replaceAll("近\\d+(天|周|月|年)", "近{NUM}{TIME_UNIT}");
        
        // 2. 人名替换：中文2-4字姓名 + 的/先生/女士等后缀
        normalized = normalized.replaceAll("[\\u4e00-\\u9fa5]{2,4}(?=的|先生|女士|同学|老师|经理|总)", "{PERSON}");
        
        // 3. 地名替换：省市县
        String[] provinces = {"北京", "上海", "天津", "重庆", "广东", "江苏", "浙江", "四川", "湖南", "湖北", 
                             "河南", "河北", "山东", "山西", "陕西", "安徽", "福建", "江西", "辽宁", "黑龙江", 
                             "吉林", "甘肃", "青海", "云南", "贵州", "海南", "台湾", "内蒙古", "广西", "宁夏", 
                             "新疆", "西藏"};
        for (String province : provinces) {
            normalized = normalized.replaceAll(province + "(省|市|自治区|地区|县)?", "{LOCATION}");
        }
        
        // 4. 时间替换：绝对日期
        normalized = normalized.replaceAll("\\d{4}年\\d{1,2}月?", "{DATE_ABSOLUTE}");
        normalized = normalized.replaceAll("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}", "{DATE_ABSOLUTE}");
        normalized = normalized.replaceAll("\\d{1,2}月\\d{1,2}[日号]", "{DATE_ABSOLUTE}");
        
        // 5. 金额替换
        normalized = normalized.replaceAll("\\d+[万千元亿]?元?", "{AMOUNT}");
        normalized = normalized.replaceAll("[￥$€£]\\d+([万千元亿])?", "{AMOUNT}");
        
        // 6. 数字ID替换
        normalized = normalized.replaceAll("ID[为是]?\\d+", "ID{NUM}");
        normalized = normalized.replaceAll("编号[为是]?\\w+", "编号{NUM}");
        normalized = normalized.replaceAll("订单号[为是]?\\w+", "订单号{NUM}");
        normalized = normalized.replaceAll("账号[为是]?\\w+", "账号{NUM}");
        
        // 7. 纯数字替换（保底）
        normalized = normalized.replaceAll("\\d+", "{NUM}");
        
        // 8. 去除多余空格
        normalized = normalized.trim().replaceAll("\\s+", " ");
        
        return normalized;
    }
}
