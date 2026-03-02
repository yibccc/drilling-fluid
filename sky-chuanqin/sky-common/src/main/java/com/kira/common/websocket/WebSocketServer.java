package com.kira.common.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket服务端
 * 支持按wellId分组推送消息
 */
@Component
@ServerEndpoint("/ws/{sid}")
@Slf4j
public class WebSocketServer {

    // 存储 sid -> Session 的映射
    private static Map<String, Session> sessionMap = new ConcurrentHashMap<>();

    // 井号分组映射：wellId -> Session集合
    private static Map<String, Set<Session>> wellSessions = new ConcurrentHashMap<>();

    // Session到井号的映射：sessionId -> wellId
    private static Map<String, String> sessionWellMap = new ConcurrentHashMap<>();

    // 存储 sid -> wellId 的订阅关系（兼容旧代码）
    private static Map<String, String> sidWellIdMap = new ConcurrentHashMap<>();

    /**
     * 连接建立时调用
     * sid格式：wellId_clientType_timestamp
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        log.info("客户端：{} 建立连接", sid);
        sessionMap.put(sid, session);

        // 尝试从sid中解析wellId
        String wellId = parseWellIdFromSid(sid);
        if (wellId != null && !wellId.isEmpty()) {
            // 使用新的数据结构
            wellSessions.computeIfAbsent(wellId, k -> ConcurrentHashMap.newKeySet()).add(session);
            sessionWellMap.put(session.getId(), wellId);

            // 兼容旧代码
            sidWellIdMap.put(sid, wellId);
            log.info("客户端：{} 自动订阅井：{}", sid, wellId);
        }
    }

    /**
     * 收到客户端消息时调用
     * 支持动态订阅：{"type":"subscribe","wellId":"WELL001"}
     */
    @OnMessage
    public void onMessage(String message, @PathParam("sid") String sid) {
        log.info("收到来自客户端：{} 的消息：{}", sid, message);

        // 解析订阅消息
        if (message.contains("\"type\":\"subscribe\"") && message.contains("\"wellId\"")) {
            try {
                String wellId = extractWellId(message);
                if (wellId != null && !wellId.isEmpty()) {
                    // 从旧订阅中移除
                    String oldWellId = sidWellIdMap.get(sid);
                    if (oldWellId != null) {
                        Session session = sessionMap.get(sid);
                        if (session != null) {
                            Set<Session> oldSessions = wellSessions.get(oldWellId);
                            if (oldSessions != null) {
                                oldSessions.remove(session);
                                if (oldSessions.isEmpty()) {
                                    wellSessions.remove(oldWellId);
                                }
                            }
                        }
                    }

                    // 添加到新订阅
                    sidWellIdMap.put(sid, wellId);
                    Session session = sessionMap.get(sid);
                    if (session != null) {
                        wellSessions.computeIfAbsent(wellId, k -> ConcurrentHashMap.newKeySet()).add(session);
                        sessionWellMap.put(session.getId(), wellId);
                    }

                    log.info("客户端：{} 订阅井：{}", sid, wellId);
                    sendToClient(sid, "{\"type\":\"subscribed\",\"wellId\":\"" + wellId + "\"}");
                }
            } catch (Exception e) {
                log.error("处理订阅消息失败", e);
            }
        }
    }

    /**
     * 连接关闭时调用
     */
    @OnClose
    public void onClose(Session session, @PathParam("sid") String sid) {
        log.info("连接断开: {}", sid);
        sessionMap.remove(sid);
        sidWellIdMap.remove(sid);

        // 从新数据结构中移除
        String wellId = sessionWellMap.remove(session.getId());
        if (wellId != null) {
            Set<Session> sessions = wellSessions.get(wellId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    wellSessions.remove(wellId);
                }
            }
        }
    }

    /**
     * 发生错误时调用
     */
    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket发生错误：{}", error.getMessage(), error);
    }

    /**
     * 发送消息给指定客户端
     */
    public void sendToClient(String sid, String message) {
        Session session = sessionMap.get(sid);
        if (session != null && session.isOpen()) {
            sendToSession(session, message);
        }
    }

    /**
     * 向指定井的订阅客户端发送消息
     *
     * @param wellId  井号
     * @param message 消息内容
     */
    public void sendToWell(String wellId, String message) {
        if (wellId == null || wellId.isEmpty()) {
            log.warn("井号为空，使用广播模式");
            sendToAllClient(message);
            return;
        }

        Set<Session> sessions = wellSessions.get(wellId);
        if (sessions == null || sessions.isEmpty()) {
            log.warn("井 {} 没有订阅客户端", wellId);
            return;
        }

        // 遍历订阅该井的客户端
        int successCount = 0;
        for (Session session : sessions) {
            if (session.isOpen()) {
                sendToSession(session, message);
                successCount++;
            }
        }

        log.info("向井 {} 的 {} 个客户端推送消息成功", wellId, successCount);
    }

    /**
     * 发送消息给指定Session
     */
    private void sendToSession(Session session, String message) {
        try {
            session.getBasicRemote().sendText(message);
        } catch (Exception e) {
            log.error("发送消息失败", e);
        }
    }

    /**
     * 广播消息给所有客户端
     */
    public void sendToAllClient(String message) {
        Collection<Session> sessions = sessionMap.values();
        for (Session session : sessions) {
            sendToSession(session, message);
        }
    }

    /**
     * 从sid中解析wellId
     */
    private String parseWellIdFromSid(String sid) {
        if (sid != null && sid.contains("_")) {
            return sid.split("_")[0];
        }
        return null;
    }

    /**
     * 从消息中提取wellId
     */
    private String extractWellId(String message) {
        try {
            int start = message.indexOf("\"wellId\":\"") + 10;
            int end = message.indexOf("\"", start);
            return message.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取当前在线人数
     */
    public static int getOnlineCount() {
        return sessionMap.size();
    }

    /**
     * 获取订阅指定井的客户端数量
     */
    public static int getWellSubscriberCount(String wellId) {
        return (int) sidWellIdMap.values().stream()
                .filter(id -> id.equals(wellId))
                .count();
    }
}