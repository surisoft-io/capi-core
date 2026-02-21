package io.surisoft.capi.schema;

public class JsonRpcResponse {
    private String jsonrpc = "2.0";
    private Object result;
    private JsonRpcError error;
    private Object id;

    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(id);
        response.setResult(result);
        return response;
    }

    public static JsonRpcResponse error(Object id, int code, String message) {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(id);
        JsonRpcError err = new JsonRpcError();
        err.setCode(code);
        err.setMessage(message);
        response.setError(err);
        return response;
    }

    public static JsonRpcResponse error(Object id, int code, String message, Object data) {
        JsonRpcResponse response = new JsonRpcResponse();
        response.setId(id);
        JsonRpcError err = new JsonRpcError();
        err.setCode(code);
        err.setMessage(message);
        err.setData(data);
        response.setError(err);
        return response;
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public JsonRpcError getError() {
        return error;
    }

    public void setError(JsonRpcError error) {
        this.error = error;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public static class JsonRpcError {
        private int code;
        private String message;
        private Object data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }
    }
}
