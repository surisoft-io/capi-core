package io.surisoft.capi.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonRpcRequest {
    private String jsonrpc;
    private String method;
    private Object params;
    private Object id;
    /**
     * Reserved metadata envelope. From protocol revision 2026-07-28 this is where the client's
     * protocol version, capabilities and identity travel, replacing the {@code initialize}
     * handshake. Previously discarded by {@code @JsonIgnoreProperties}.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("_meta")
    private java.util.Map<String, Object> meta;

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Object getParams() {
        return params;
    }

    public void setParams(Object params) {
        this.params = params;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public java.util.Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(java.util.Map<String, Object> meta) {
        this.meta = meta;
    }
}
