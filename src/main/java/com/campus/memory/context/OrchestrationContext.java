package com.campus.memory.context;

import com.campus.memory.dto.TraceInfo;
import com.campus.memory.dto.RelevantFile;
import java.util.*;

/**
 * 智能编排执行上下文 (ThreadLocal)
 * 用于在 Agent 工具调用链中共享数据
 */
public class OrchestrationContext {
    private static final ThreadLocal<List<RelevantFile>> TOOL_FILES = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<String>> TOOL_MEMORIES = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<TraceInfo.TraceInfoBuilder> TRACE_BUILDER = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<String> USER_ROLE = ThreadLocal.withInitial(() -> "student");
    private static final ThreadLocal<String> SESSION_ID = ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<Set<String>> INVOKED_TOOLS = ThreadLocal.withInitial(LinkedHashSet::new);

    public static void clear() {
        TOOL_FILES.get().clear();
        TOOL_MEMORIES.get().clear();
        INVOKED_TOOLS.get().clear();
        TRACE_BUILDER.remove();
        USER_ROLE.remove();
        SESSION_ID.remove();
    }

    public static void remove() {
        TOOL_FILES.remove();
        TOOL_MEMORIES.remove();
        TRACE_BUILDER.remove();
        USER_ROLE.remove();
        SESSION_ID.remove();
        INVOKED_TOOLS.remove();
    }

    // Getters and Setters
    public static List<RelevantFile> getToolFiles() { return TOOL_FILES.get(); }
    public static List<String> getToolMemories() { return TOOL_MEMORIES.get(); }
    public static TraceInfo.TraceInfoBuilder getTraceBuilder() { return TRACE_BUILDER.get(); }
    public static void setTraceBuilder(TraceInfo.TraceInfoBuilder builder) { TRACE_BUILDER.set(builder); }
    public static String getUserRole() { return USER_ROLE.get(); }
    public static void setUserRole(String role) { USER_ROLE.set(role); }
    public static String getSessionId() { return SESSION_ID.get(); }
    public static void setSessionId(String id) { SESSION_ID.set(id); }
    
    public static void recordToolInvoke(String toolName) {
        INVOKED_TOOLS.get().add(toolName);
    }
    
    public static Set<String> getInvokedTools() {
        return INVOKED_TOOLS.get();
    }
}
