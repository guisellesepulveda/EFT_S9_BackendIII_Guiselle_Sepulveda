#!/usr/bin/env python3
"""Mock server de un BFF a partir de su contrato OpenAPI (docs/openapi/bff-*.json).

Permite que el equipo de frontend avance en paralelo, sin backend: responde, para cada operación del contrato, el primer código 2xx
con un cuerpo generado desde los `example` del esquema (o valores neutros por tipo).

    python3 scripts/openapi_mock.py docs/openapi/bff-mobile.json 4010
    curl -i localhost:4010/mobile/home -H 'Authorization: Bearer cualquier-cosa'

Solo biblioteca estándar. No valida tokens ni cuerpos: es un mock de contrato, no de seguridad.
"""
import json
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def resolve(spec, schema):
    while schema and "$ref" in schema:
        node = spec
        for part in schema["$ref"].lstrip("#/").split("/"):
            node = node[part]
        schema = node
    return schema or {}


def example(spec, schema, depth=0):
    schema = resolve(spec, schema)
    if depth > 6:
        return None
    if "example" in schema:
        return schema["example"]
    kind = schema.get("type")
    if kind == "object" or "properties" in schema:
        return {k: example(spec, v, depth + 1) for k, v in schema.get("properties", {}).items()}
    if kind == "array":
        return [example(spec, schema.get("items", {}), depth + 1)]
    fmt = schema.get("format")
    if kind == "string":
        return {"uuid": "3f2b8c1e-5a47-4c35-9d0e-1b2a3c4d5e6f", "date-time": "2026-10-10T12:00:00Z", "date": "2026-10-10"}.get(fmt, "texto")
    if kind == "integer":
        return 1
    if kind == "number":
        return 1000.5
    if kind == "boolean":
        return True
    return None


def compile_paths(spec):
    routes = []
    for path, ops in spec["paths"].items():
        regex = re.compile("^" + re.sub(r"\{[^/]+\}", "[^/]+", path) + "$")
        for method, op in ops.items():
            codes = sorted(c for c in op.get("responses", {}) if c.startswith("2")) or ["200"]
            resp = op["responses"].get(codes[0], {})
            content = resp.get("content", {})
            schema = next(iter(content.values()), {}).get("schema") if content else None
            body = example(spec, schema) if schema else None
            routes.append((method.upper(), regex, int(codes[0]), body))
    return routes


def make_handler(routes):
    class Handler(BaseHTTPRequestHandler):
        def handle_any(self):
            path = self.path.split("?")[0]
            length = int(self.headers.get("Content-Length") or 0)
            if length:
                self.rfile.read(length)
            for method, regex, status, body in routes:
                if method == self.command and regex.match(path):
                    payload = b"" if body is None else json.dumps(body, ensure_ascii=False).encode()
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("X-Mock", "true")
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                    return
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

        do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_any

        def log_message(self, fmt, *args):
            sys.stderr.write("[mock] %s\n" % (fmt % args))

    return Handler


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    spec_path = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 4010
    spec = load(spec_path)
    routes = compile_paths(spec)
    print(f"Mock de {spec['info'].get('title', spec_path)}: {len(routes)} operaciones en http://localhost:{port}")
    ThreadingHTTPServer(("127.0.0.1", port), make_handler(routes)).serve_forever()
