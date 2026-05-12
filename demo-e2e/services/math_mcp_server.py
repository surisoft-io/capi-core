"""
Mock MCP Server backend for CAPI MCP Gateway demo.

This is a real MCP Server — it speaks JSON-RPC 2.0 and implements the MCP
protocol (initialize, tools/list, tools/call). CAPI discovers its tools
automatically via tools/list and proxies tool calls to it.

Unlike the other demo services (plain REST), this one requires no mcp-tools
metadata in Consul — CAPI discovers the tool catalog directly from the server.
"""
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
import math
import uuid

SESSION_ID = str(uuid.uuid4())

TOOLS = [
    {
        "name": "calculate",
        "description": "Evaluate a mathematical expression. Supports +, -, *, /, **, sqrt, sin, cos, tan, log, pi, e. Example: 'sqrt(144) + 2**3'",
        "inputSchema": {
            "type": "object",
            "properties": {
                "expression": {
                    "type": "string",
                    "description": "Math expression to evaluate"
                }
            },
            "required": ["expression"]
        }
    },
    {
        "name": "convert",
        "description": "Convert between units. Supported: km/miles, kg/lbs, celsius/fahrenheit, liters/gallons.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "value": {
                    "type": "number",
                    "description": "Numeric value to convert"
                },
                "from_unit": {
                    "type": "string",
                    "description": "Source unit (km, miles, kg, lbs, celsius, fahrenheit, liters, gallons)"
                },
                "to_unit": {
                    "type": "string",
                    "description": "Target unit"
                }
            },
            "required": ["value", "from_unit", "to_unit"]
        }
    },
    {
        "name": "statistics",
        "description": "Calculate statistics (mean, median, min, max, stdev) for a list of numbers.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "numbers": {
                    "type": "array",
                    "items": {"type": "number"},
                    "description": "List of numbers to analyze"
                }
            },
            "required": ["numbers"]
        }
    }
]

CONVERSIONS = {
    ("km", "miles"): lambda v: v * 0.621371,
    ("miles", "km"): lambda v: v * 1.60934,
    ("kg", "lbs"): lambda v: v * 2.20462,
    ("lbs", "kg"): lambda v: v * 0.453592,
    ("celsius", "fahrenheit"): lambda v: v * 9/5 + 32,
    ("fahrenheit", "celsius"): lambda v: (v - 32) * 5/9,
    ("liters", "gallons"): lambda v: v * 0.264172,
    ("gallons", "liters"): lambda v: v * 3.78541,
}

SAFE_MATH = {
    "sqrt": math.sqrt, "sin": math.sin, "cos": math.cos, "tan": math.tan,
    "log": math.log, "log10": math.log10, "abs": abs, "round": round,
    "pi": math.pi, "e": math.e, "pow": pow,
}


def handle_calculate(arguments):
    expr = arguments.get("expression", "")
    try:
        result = eval(expr, {"__builtins__": {}}, SAFE_MATH)
        return {"content": [{"type": "text", "text": f"{expr} = {result}"}]}
    except Exception as e:
        return {"content": [{"type": "text", "text": f"Error evaluating '{expr}': {e}"}], "isError": True}


def handle_convert(arguments):
    value = arguments.get("value", 0)
    from_unit = arguments.get("from_unit", "").lower()
    to_unit = arguments.get("to_unit", "").lower()
    key = (from_unit, to_unit)
    if key in CONVERSIONS:
        result = CONVERSIONS[key](value)
        return {"content": [{"type": "text", "text": f"{value} {from_unit} = {result:.4f} {to_unit}"}]}
    elif from_unit == to_unit:
        return {"content": [{"type": "text", "text": f"{value} {from_unit} (no conversion needed)"}]}
    else:
        return {"content": [{"type": "text", "text": f"Unsupported conversion: {from_unit} -> {to_unit}"}], "isError": True}


def handle_statistics(arguments):
    numbers = arguments.get("numbers", [])
    if not numbers:
        return {"content": [{"type": "text", "text": "No numbers provided"}], "isError": True}
    n = len(numbers)
    mean = sum(numbers) / n
    sorted_nums = sorted(numbers)
    median = sorted_nums[n // 2] if n % 2 else (sorted_nums[n // 2 - 1] + sorted_nums[n // 2]) / 2
    variance = sum((x - mean) ** 2 for x in numbers) / n
    stdev = math.sqrt(variance)
    result = {
        "count": n, "mean": round(mean, 4), "median": median,
        "min": min(numbers), "max": max(numbers),
        "stdev": round(stdev, 4), "sum": sum(numbers)
    }
    return {"content": [{"type": "text", "text": json.dumps(result, indent=2)}]}


TOOL_HANDLERS = {
    "calculate": handle_calculate,
    "convert": handle_convert,
    "statistics": handle_statistics,
}

# ---- Resources ---------------------------------------------------------------

RESOURCES = [
    {
        "uri": "math://reference/operators",
        "name": "Operator reference",
        "description": "Quick reference for supported math operators and functions.",
        "mimeType": "text/markdown",
    },
    {
        "uri": "math://reference/units",
        "name": "Unit conversion table",
        "description": "Supported source and target units for math_convert.",
        "mimeType": "text/markdown",
    },
]

RESOURCE_CONTENT = {
    "math://reference/operators": (
        "# Math operators\n\n"
        "- `+ - * /` arithmetic\n"
        "- `**` exponent\n"
        "- `sqrt(x)`, `sin(x)`, `cos(x)`, `tan(x)`, `log(x)`\n"
        "- constants: `pi`, `e`\n"
    ),
    "math://reference/units": (
        "# Supported units\n\n"
        "- length: `km`, `miles`\n"
        "- mass: `kg`, `lbs`\n"
        "- temperature: `celsius`, `fahrenheit`\n"
        "- volume: `liters`, `gallons`\n"
    ),
}

# ---- Prompts -----------------------------------------------------------------

PROMPTS = [
    {
        "name": "explain_calculation",
        "description": "Ask the model to explain how to compute a math expression step by step.",
        "arguments": [
            {"name": "expression", "description": "The expression to explain", "required": True}
        ],
    },
]

def render_prompt(name, arguments):
    if name == "explain_calculation":
        expression = (arguments or {}).get("expression", "<missing>")
        return {
            "messages": [
                {
                    "role": "user",
                    "content": {
                        "type": "text",
                        "text": f"Explain step by step how to compute the expression: {expression}",
                    },
                }
            ]
        }
    return None


class McpHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length)) if length > 0 else {}

        method = body.get("method")
        req_id = body.get("id")

        if method == "initialize":
            result = {
                "protocolVersion": "2025-03-26",
                "capabilities": {
                    "tools": {"listChanged": False},
                    "resources": {"listChanged": False, "subscribe": False},
                    "prompts": {"listChanged": False},
                },
                "serverInfo": {"name": "math-mcp-server", "version": "1.0.0"}
            }
            self.send_jsonrpc(req_id, result, headers={"Mcp-Session-Id": SESSION_ID})

        elif method == "notifications/initialized":
            # Notification, no response needed
            self.send_response(200)
            self.end_headers()

        elif method == "tools/list":
            self.send_jsonrpc(req_id, {"tools": TOOLS})

        elif method == "tools/call":
            params = body.get("params", {})
            tool_name = params.get("name")
            arguments = params.get("arguments", {})
            handler = TOOL_HANDLERS.get(tool_name)
            if handler:
                result = handler(arguments)
            else:
                result = {"content": [{"type": "text", "text": f"Unknown tool: {tool_name}"}], "isError": True}
            self.send_jsonrpc(req_id, result)

        elif method == "resources/list":
            self.send_jsonrpc(req_id, {"resources": RESOURCES})

        elif method == "resources/read":
            params = body.get("params", {})
            uri = params.get("uri")
            text = RESOURCE_CONTENT.get(uri)
            if text is None:
                self.send_error_jsonrpc(req_id, -32602, f"Resource not found: {uri}")
            else:
                self.send_jsonrpc(req_id, {
                    "contents": [{"uri": uri, "mimeType": "text/markdown", "text": text}]
                })

        elif method == "prompts/list":
            self.send_jsonrpc(req_id, {"prompts": PROMPTS})

        elif method == "prompts/get":
            params = body.get("params", {})
            name = params.get("name")
            arguments = params.get("arguments")
            rendered = render_prompt(name, arguments)
            if rendered is None:
                self.send_error_jsonrpc(req_id, -32602, f"Prompt not found: {name}")
            else:
                self.send_jsonrpc(req_id, rendered)

        elif method == "ping":
            self.send_jsonrpc(req_id, {})

        else:
            self.send_error_jsonrpc(req_id, -32601, f"Method not found: {method}")

    def send_jsonrpc(self, req_id, result, headers=None):
        response = {"jsonrpc": "2.0", "id": req_id, "result": result}
        payload = json.dumps(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        if headers:
            for k, v in headers.items():
                self.send_header(k, v)
        self.end_headers()
        self.wfile.write(payload)

    def send_error_jsonrpc(self, req_id, code, message):
        response = {"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}}
        payload = json.dumps(response).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        print(f"[math-mcp] {args[0]}")


if __name__ == "__main__":
    print("Math MCP Server starting on :8080")
    print(f"Tools: {', '.join(t['name'] for t in TOOLS)}")
    HTTPServer(("0.0.0.0", 8080), McpHandler).serve_forever()
