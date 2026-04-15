package com.nl2sql.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class LogContextUtilTest {
    
    @AfterEach
    void tearDown() {
        // 清理MDC，防止影响其他测试
        MDC.clear();
    }
    
    @Test
    void testInitRequestId() {
        String requestId = LogContextUtil.initRequestId();
        
        assertNotNull(requestId);
        assertEquals(32, requestId.length(), "requestId应该是32位UUID");
        assertEquals(requestId, MDC.get(LogContextUtil.REQUEST_ID));
    }
    
    @Test
    void testSetUserContext() {
        Long userId = 123L;
        String username = "testuser";
        
        LogContextUtil.setUserContext(userId, username);
        
        assertEquals("123", MDC.get(LogContextUtil.USER_ID));
        assertEquals("testuser", MDC.get(LogContextUtil.USERNAME));
    }
    
    @Test
    void testSetUserContextWithNull() {
        LogContextUtil.setUserContext(null, null);
        
        assertNull(MDC.get(LogContextUtil.USER_ID));
        assertNull(MDC.get(LogContextUtil.USERNAME));
    }
    
    @Test
    void testSetSessionId() {
        String sessionId = "session-abc-123";
        
        LogContextUtil.setSessionId(sessionId);
        
        assertEquals(sessionId, MDC.get(LogContextUtil.SESSION_ID));
    }
    
    @Test
    void testSetSessionIdWithNull() {
        LogContextUtil.setSessionId(null);
        
        assertNull(MDC.get(LogContextUtil.SESSION_ID));
    }
    
    @Test
    void testSetSqlContext() {
        String sql = "SELECT * FROM users WHERE id = 1";
        Long executionTime = 100L;
        
        LogContextUtil.setSqlContext(sql, executionTime);
        
        assertEquals(sql, MDC.get(LogContextUtil.SQL_TEXT));
        assertEquals("100", MDC.get(LogContextUtil.EXECUTION_TIME));
    }
    
    @Test
    void testSetSqlContextTruncation() {
        // 生成超过1000字符的SQL
        StringBuilder longSql = new StringBuilder("SELECT * FROM users WHERE ");
        for (int i = 0; i < 100; i++) {
            longSql.append("id").append(i).append("=").append(i).append(" AND ");
        }
        
        LogContextUtil.setSqlContext(longSql.toString(), 50L);
        
        String truncatedSql = MDC.get(LogContextUtil.SQL_TEXT);
        assertNotNull(truncatedSql);
        assertTrue(truncatedSql.length() <= 1003, "SQL应该被截断（1000 + '...'）");
        assertTrue(truncatedSql.endsWith("..."), "截断的SQL应该以...结尾");
    }
    
    @Test
    void testClear() {
        LogContextUtil.initRequestId();
        LogContextUtil.setUserContext(1L, "user");
        LogContextUtil.setSessionId("session");
        
        LogContextUtil.clear();
        
        assertNull(MDC.get(LogContextUtil.REQUEST_ID));
        assertNull(MDC.get(LogContextUtil.USER_ID));
        assertNull(MDC.get(LogContextUtil.USERNAME));
        assertNull(MDC.get(LogContextUtil.SESSION_ID));
    }
    
    @Test
    void testRemove() {
        LogContextUtil.initRequestId();
        LogContextUtil.setUserContext(1L, "user");
        
        LogContextUtil.remove(LogContextUtil.REQUEST_ID);
        
        assertNull(MDC.get(LogContextUtil.REQUEST_ID));
        assertNotNull(MDC.get(LogContextUtil.USER_ID), "其他字段应该保留");
    }
    
    @Test
    void testMultipleRequestsIsolation() {
        // 模拟第一个请求
        String requestId1 = LogContextUtil.initRequestId();
        LogContextUtil.setUserContext(1L, "user1");
        
        assertEquals(requestId1, MDC.get(LogContextUtil.REQUEST_ID));
        assertEquals("user1", MDC.get(LogContextUtil.USERNAME));
        
        // 清除后模拟第二个请求
        LogContextUtil.clear();
        
        String requestId2 = LogContextUtil.initRequestId();
        LogContextUtil.setUserContext(2L, "user2");
        
        assertNotEquals(requestId1, requestId2, "不同请求应该有不同requestId");
        assertEquals("user2", MDC.get(LogContextUtil.USERNAME));
        assertNotEquals(requestId1, MDC.get(LogContextUtil.REQUEST_ID));
    }
}
