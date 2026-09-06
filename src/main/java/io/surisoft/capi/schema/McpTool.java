package io.surisoft.capi.schema;

public class McpTool {
    private String name;
    private String description;
    private String inputSchema;
    /**
     * JSON Schema for the tool's result, serialised. Optional — when present CAPI also emits
     * {@code structuredContent} alongside {@code content} on a successful call (protocol
     * revision 2025-06-18 onwards).
     */
    private String outputSchema;
    private String serviceId;
    private boolean streaming;
    private String category;
    private int timeout;
    private boolean mcpServer;
    private String httpMethod;
    private String httpPathTemplate;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(String inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public boolean isMcpServer() {
        return mcpServer;
    }

    public void setMcpServer(boolean mcpServer) {
        this.mcpServer = mcpServer;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getHttpPathTemplate() {
        return httpPathTemplate;
    }

    public void setHttpPathTemplate(String httpPathTemplate) {
        this.httpPathTemplate = httpPathTemplate;
    }

    public boolean isOpenApiPromoted() {
        return httpPathTemplate != null;
    }

    public String getOutputSchema() {
        return outputSchema;
    }

    public void setOutputSchema(String outputSchema) {
        this.outputSchema = outputSchema;
    }
}
