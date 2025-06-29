package mcp;

/**
 * Exception class for MCP-specific errors with JSON-RPC error codes
 */
public class MCPException extends Exception {
    private final int code;
    private final Object data;
    
    public MCPException(int code, String message) {
        super(message);
        this.code = code;
        this.data = null;
    }
    
    public MCPException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }
    
    public int getCode() {
        return code;
    }
    
    public Object getData() {
        return data;
    }
    
    // Standard JSON-RPC error codes
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    
    // MCP-specific error codes
    public static final int UNAUTHORIZED = -32000;
    public static final int FORBIDDEN = -32001;
    public static final int RESOURCE_NOT_FOUND = -32002;
    public static final int TOOL_ERROR = -32003;
}