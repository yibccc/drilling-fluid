package com.kira.common.websocket;

import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket服务端
 * 支持按wellId分组推送消息
 */
@Component
@ServerEndpoint("/ws/{sid}")
public class
WebSocketServer {

    // 存储 sid -> Session 的映射
    private static Map<String, Session> sessionMap = new ConcurrentHashMap<>();

    // 存储 sid -> wellId 的订阅关系
    private static Map<String, String> sidWellIdMap = new ConcurrentHashMap<>();

    /**
     * 连接建立时调用
     * sid格式：wellId_clientType_timestamp
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("sid") String sid) {
        System.out.println("客户端：" + sid + " 建立连接");
        sessionMap.put(sid, session);

        // 尝试从sid中解析wellId
        String wellId = parseWellIdFromSid(sid);
        if (wellId != null && !wellId.isEmpty()) {
            sidWellIdMap.put(sid, wellId);
            System.out.println("客户端：" + sid + " 自动订阅井：" + wellId);
        }
    }

    /**
     * 收到客户端消息时调用
     * 支持动态订阅：{"type":"subscribe","wellId":"WELL001"}
     */
    @OnMessage
    public void onMessage(String message, @PathParam("sid") String sid) {
        System.out.println("收到来自客户端：" + sid + " 的消息：" + message);

        // 解析订阅消息
        if (message.contains("\"type\":\"subscribe\"") && message.contains("\"wellId\"")) {
            try {
                String wellId = extractWellId(message);
                if (wellId != null && !wellId.isEmpty()) {
                    sidWellIdMap.put(sid, wellId);
                    System.out.println("客户端：" + sid + " 订阅井：" + wellId);
                    sendToClient(sid, "{\"type\":\"subscribed\",\"wellId\":\"" + wellId + "\"}");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 连接关闭时调用
     */
    @OnClose
    public void onClose(@PathParam("sid") String sid) {
        System.out.println("连接断开: " + sid);
        sessionMap.remove(sid);
        sidWellIdMap.remove(sid);
    }

    /**
     * 发生错误时调用
     */
    @OnError
    public void onError(Session session, Throwable error) {
        System.out.println("WebSocket发生错误：" + error.getMessage());
        error.printStackTrace();
    }

    /**
     * 发送消息给指定客户端
     */
    public void sendToClient(String sid, String message) {
        Session session = sessionMap.get(sid);
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 发送消息给订阅了指定wellId的所有客户端
     */
    public void sendToWell(String wellId, String message) {
        if (wellId == null || wellId.isEmpty()) {
            return;
        }

        sidWellIdMap.entrySet().stream()
                .filter(entry -> wellId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .forEach(sid -> sendToClient(sid, message));
    }

    /**
     * 广播消息给所有客户端
     */
    public void sendToAllClient(String message) {
        Collection<Session> sessions = sessionMap.values();
        for (Session session : sessions) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (Exception e) {
                e.printStackTrace();
            }
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