# XXL-Job多井支持与WebSocket分组推送优化设计

## 一、背景

当前系统存在以下问题需要优化：

1. **单井配置限制**：Redis只能存储单个井号，无法同时监控多口井
2. **WebSocket推送低效**：使用`sendToAllClient()`广播，O(n)遍历所有客户端

## 二、设计目标

1. 支持同时监控多口井，可通过配置动态增删
2. WebSocket预警按井号精准推送，降低时间复杂度
3. 保持向后兼容，平滑迁移

## 三、Redis多井配置设计

### 3.1 数据结构

```redis
# 活跃井列表（Set）
SADD well:active SHB001 SHB002 SHB003

# 操作命令
SMEMBERS well:active     # 获取所有活跃井
SADD well:active SHB004  # 添加井
SREM well:active SHB002  # 移除井
```

### 3.2 井配置服务

```java
@Service
public class WellConfigService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String ACTIVE_WELLS_KEY = "well:active";

    public void addWell(String wellId) {
        redisTemplate.opsForSet().add(ACTIVE_WELLS_KEY, wellId);
    }

    public void removeWell(String wellId) {
        redisTemplate.opsForSet().remove(ACTIVE_WELLS_KEY, wellId);
    }

    public Set<String> getActiveWells() {
        return redisTemplate.opsForSet().members(ACTIVE_WELLS_KEY);
    }

    public boolean isWellActive(String wellId) {
        return Boolean.TRUE.equals(
            redisTemplate.opsForSet().isMember(ACTIVE_WELLS_KEY, wellId)
        );
    }
}
```

### 3.3 定时任务改造

```java
@XxlJob("caPollutionDetectionJob")
public void caPollutionDetection() {
    Set<String> activeWells = wellConfigService.getActiveWells();

    if (activeWells == null || activeWells.isEmpty()) {
        log.warn("没有活跃的井需要检测");
        return;
    }

    for (String wellId : activeWells) {
        try {
            String location = getWellLocation(wellId);
            detectPollutionForWell(wellId, location);
        } catch (Exception e) {
            log.error("井{}检测失败", wellId, e);
        }
    }
}
```

## 四、WebSocket分组推送设计

### 4.1 数据结构改造

```java
public class WebSocketServer {

    // 改造前：单层Map，O(n)
    // private Map<String, Session> sessionMap;

    // 改造后：井号分组，O(k)
    private Map<String, Set<Session>> wellSessions = new ConcurrentHashMap<>();
    private Map<String, String> sessionWellMap = new ConcurrentHashMap<>();
}
```

### 4.2 核心方法

```java
// 按井号推送（O(k)复杂度）
public void sendToWell(String wellId, String message) {
    Set<Session> sessions = wellSessions.get(wellId);
    if (sessions != null && !sessions.isEmpty()) {
        sessions.forEach(session -> sendToSession(session, message));
    }
}

// 连接时自动订阅
@OnOpen
public void onOpen(Session session, @PathParam("sid") String sid) {
    String wellId = parseWellIdFromSid(sid);
    wellSessions.computeIfAbsent(wellId, k -> ConcurrentHashMap.newKeySet()).add(session);
    sessionWellMap.put(session.getId(), wellId);
}

// 关闭时清理
@OnClose
public void onClose(Session session) {
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
```

### 4.3 预警推送改造

```java
// 改造前
webSocketServer.sendToAllClient(jsonMessage);

// 改造后
webSocketServer.sendToWell(wellId, jsonMessage);
```

## 五、文件改造清单

| 文件 | 改造类型 | 说明 |
|------|----------|------|
| `WebSocketServer.java` | 修改 | 添加井号分组数据结构和sendToWell()方法 |
| `PollutionDetectionTest.java` | 修改 | 改造为循环处理多井，预警使用sendToWell() |
| `WellConfigService.java` | 新增 | 井配置管理服务 |
| `WellController.java` | 新增 | 井配置管理REST接口 |

## 六、迁移步骤

### 阶段1：新增服务（零风险）
- 新增 WellConfigService.java
- 新增 WellController.java
- 单元测试验证

### 阶段2：WebSocket改造（灰度）
- 添加 wellSessions 数据结构
- 保留 sendToAllClient() 兼容
- 新增 sendToWell() 方法
- 测试验证

### 阶段3：定时任务改造
- 修改 PollutionDetectionTest.java
- 改用 getActiveWells() 循环
- 预警改用 sendToWell() 推送
- 单井失败隔离测试

### 阶段4：清理
- 移除旧的 sendToAllClient() 调用
- 清理单井配置代码
- 更新文档

## 七、性能预期

| 指标 | 改造前 | 改造后 | 提升 |
|------|--------|--------|------|
| 1000连接推送耗时 | ~50ms | ~3ms | 16x |
| 单井推送遍历次数 | 1000 | 50 | 20x |
| 内存占用 | 单Map | 双Map | +20% |

## 八、风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| Redis故障 | 无法获取井列表 | 定时任务返回warn日志，不影响其他功能 |
| WebSocket内存增加 | 约20% | 可接受，连接数有限 |
| 迁移期间兼容性 | 旧客户端连接 | 保留sendToAllClient()，逐步迁移 |
