package com.kira.common.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.websocket.Session;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.Mockito.*;

/**
 * WebSocket井号分组推送测试
 */
class WebSocketServerTest {

    private WebSocketServer webSocketServer;

    @BeforeEach
    void setUp() {
        webSocketServer = new WebSocketServer();
    }

    @AfterEach
    void tearDown() {
        // 清理静态数据 - 由于WebSocketServer使用静态变量，我们需要重置它们
        // 实际项目中可能需要提供reset方法或使用测试专用的配置
    }

    @Test
    void testSendToWell_withValidWellId() {
        // 准备模拟Session
        Session session1 = mockSession("session1", "SHB001_client1_001");
        Session session2 = mockSession("session2", "SHB001_client2_002");
        Session session3 = mockSession("session3", "SHB002_client1_003");

        // 模拟连接
        webSocketServer.onOpen(session1, "SHB001_client1_001");
        webSocketServer.onOpen(session2, "SHB001_client2_002");
        webSocketServer.onOpen(session3, "SHB002_client1_003");

        // 执行推送
        String message = "{\"type\":\"ALERT\",\"wellId\":\"SHB001\"}";
        webSocketServer.sendToWell("SHB001", message);

        // 验证：SHB001的两个客户端收到消息
        verify(session1, atLeastOnce()).getBasicRemote();
        verify(session2, atLeastOnce()).getBasicRemote();
        // SHB002的客户端不应该收到
        verify(session3, never()).getBasicRemote();

        // 清理
        webSocketServer.onClose(session1, "SHB001_client1_001");
        webSocketServer.onClose(session2, "SHB001_client2_002");
        webSocketServer.onClose(session3, "SHB002_client1_003");
    }

    @Test
    void testSendToWell_withNoSubscribers() {
        // 没有订阅的井
        String message = "test";
        webSocketServer.sendToWell("SHB999", message);

        // 不应该抛出异常
    }

    @Test
    void testOnClose_cleanupWellSessions() {
        Session session1 = mockSession("session1", "SHB001_client1_001");
        Session session2 = mockSession("session2", "SHB001_client2_002");

        webSocketServer.onOpen(session1, "SHB001_client1_001");
        webSocketServer.onOpen(session2, "SHB001_client2_002");

        // 关闭一个连接
        webSocketServer.onClose(session1, "SHB001_client1_001");

        // SHB001应该还有一个连接
        String message = "test";
        webSocketServer.sendToWell("SHB001", message);

        // session2应该收到消息
        verify(session2, atLeastOnce()).getBasicRemote();

        // 清理
        webSocketServer.onClose(session2, "SHB001_client2_002");
    }

    @Test
    void testSendToAllClient() {
        Session session1 = mockSession("session1", "SHB001_client1_001");
        Session session2 = mockSession("session2", "SHB002_client1_002");

        webSocketServer.onOpen(session1, "SHB001_client1_001");
        webSocketServer.onOpen(session2, "SHB002_client1_002");

        String message = "broadcast message";
        webSocketServer.sendToAllClient(message);

        // 所有客户端都应该收到消息
        verify(session1, atLeastOnce()).getBasicRemote();
        verify(session2, atLeastOnce()).getBasicRemote();

        // 清理
        webSocketServer.onClose(session1, "SHB001_client1_001");
        webSocketServer.onClose(session2, "SHB002_client1_002");
    }

    @Test
    void testOnOpen_autoSubscribeByWellId() {
        Session session = mockSession("session1", "SHB001_client1_001");

        webSocketServer.onOpen(session, "SHB001_client1_001");

        // 验证会话被添加到sessionMap
        webSocketServer.sendToWell("SHB001", "test");

        // 应该收到消息
        verify(session, atLeastOnce()).getBasicRemote();

        // 清理
        webSocketServer.onClose(session, "SHB001_client1_001");
    }

    /**
     * 创建模拟Session对象
     */
    private Session mockSession(String sessionId, String sid) {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
