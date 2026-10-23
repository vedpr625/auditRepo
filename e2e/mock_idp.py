#!/usr/bin/env python3
"""Minimal OIDC provider for local testing (NOT Keycloak). Signs RS256 tokens shaped like Keycloak's.
The signed-in user is read from user.json on every /authorize, so a test can switch users."""
import json, time, uuid, base64, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, urlencode
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

PORT = 9000; REALM = "test"
ISS = f"http://127.0.0.1:{PORT}/realms/{REALM}"
TTL = int(sys.argv[1]) if len(sys.argv) > 1 else 300
import os, html
DEMO = os.environ.get("DEMO") == "1"
if os.path.exists("key.pem"):
    pem = open("key.pem", "rb").read()
    key = serialization.load_pem_private_key(pem, password=None)
else:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    open("key.pem", "wb").write(pem)
pub = key.public_key().public_numbers()
b64 = lambda n: base64.urlsafe_b64encode(n.to_bytes((n.bit_length()+7)//8, "big")).rstrip(b"=").decode()
JWKS = {"keys": [{"kty": "RSA", "kid": "k1", "use": "sig", "alg": "RS256", "n": b64(pub.n), "e": b64(pub.e)}]}
codes, refresh = {}, {}
log = open("idp.log", "a")

DEMO_USERS = {
  "priya":    ({"sub":"d-priya","name":"priya.admin","roles":["AUDIT_ADMIN"]}, "Audit admin", "Sees every record, marks reviews, manages sending systems and settings."),
  "nimal":    ({"sub":"d-nimal","name":"nimal.ministry","roles":["MINISTRY_AUDITOR"]}, "Ministry auditor", "Sees every record. Read only: cannot mark reviews or open Admin."),
  "kumari":   ({"sub":"d-kumari","name":"kumari.facility","roles":["FACILITY_AUDITOR"],"extra":{"facility_id":"Device/hhims-colombo-01"}}, "Facility auditor", "Sees only records from one facility (Colombo hospital system)."),
  "samantha": ({"sub":"d-samantha","name":"samantha.privacy","roles":["PRIVACY_OFFICER"]}, "Privacy officer", "Sees every record, including break-glass reasons. Read only."),
  "erin":     ({"sub":"d-erin","name":"erin.noaudit","roles":["SOME_OTHER_ROLE"]}, "No audit role", "Signed in but has no audit role: should see nothing."),
}

def picker(q):
    hidden = "".join(f'<input type="hidden" name="{html.escape(k)}" value="{html.escape(v)}">' for k, v in q.items())
    cards = "".join(f'<button name="demo_user" value="{k}"><b>{html.escape(t)}</b><span>{html.escape(u["name"])}</span><small>{html.escape(d)}</small></button>' for k, (u, t, d) in DEMO_USERS.items())
    return ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Demo sign-in</title>"
      "<style>body{font:16px system-ui,sans-serif;background:#f5f4ef;color:#1d2a2a;margin:0;padding:24px}main{max-width:560px;margin:auto}"
      "h1{font-size:22px}p{color:#4a5757}.warn{background:#fdf0cf;color:#6b4300;padding:10px 12px;border-radius:8px;font-size:14px}"
      "form{display:grid;gap:10px;margin-top:16px}button{text-align:left;padding:14px;border:1px solid #c9ccc4;border-radius:10px;background:#fff;font:inherit;cursor:pointer;display:grid;gap:2px}"
      "button:hover,button:focus{border-color:#0b6b6b;outline:2px solid #0b6b6b}button span{font-family:ui-monospace,monospace;font-size:13px;color:#4a5757}button small{color:#4a5757}</style>"
      "<main><h1>Demo sign-in</h1><div class=warn>Demonstration only. These are made-up people and made-up records. This is not Keycloak and has no passwords.</div>"
      f"<p>Pick who you want to be:</p><form method=get action=''>{hidden}{cards}</form></main>").encode()

def user():
    return json.load(open("user.json"))

def ttl():
    try: return int(open("ttl.txt").read().strip())
    except Exception: return TTL

def tokens(u, client_id, nonce=None):
    TTLV = ttl()
    now = int(time.time())
    access = {"exp": now+TTLV, "iat": now, "jti": str(uuid.uuid4()), "iss": ISS, "aud": ["chester-audit-repository", "account"],
              "sub": u["sub"], "typ": "Bearer", "azp": client_id, "realm_access": {"roles": u["roles"]},
              "scope": "openid profile", "preferred_username": u["name"]}
    idt = {"exp": now+TTLV, "iat": now, "iss": ISS, "aud": client_id, "sub": u["sub"], "typ": "ID", "azp": client_id,
           "preferred_username": u["name"]}
    if nonce: idt["nonce"] = nonce
    for k, v in u.get("extra", {}).items(): access[k] = v
    sign = lambda c: jwt.encode(c, pem, algorithm="RS256", headers={"kid": "k1"})
    rt = str(uuid.uuid4()); refresh[rt] = (u, client_id)
    return {"access_token": sign(access), "id_token": sign(idt), "refresh_token": rt, "token_type": "Bearer",
            "expires_in": TTLV, "refresh_expires_in": 1800, "scope": "openid profile"}

class H(BaseHTTPRequestHandler):
    def log_message(self, f, *a): log.write((f % a) + "\n"); log.flush()
    def send(self, code, body, ctype="application/json", headers=None):
        b = body if isinstance(body, bytes) else json.dumps(body).encode()
        self.send_response(code); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(b)))
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}; p = u.path
        if p.endswith("/.well-known/openid-configuration"):
            return self.send(200, {"issuer": ISS, "authorization_endpoint": ISS+"/protocol/openid-connect/auth",
                "token_endpoint": ISS+"/protocol/openid-connect/token", "jwks_uri": ISS+"/protocol/openid-connect/certs",
                "userinfo_endpoint": ISS+"/protocol/openid-connect/userinfo", "end_session_endpoint": ISS+"/protocol/openid-connect/logout",
                "response_types_supported": ["code"], "subject_types_supported": ["public"],
                "id_token_signing_alg_values_supported": ["RS256"], "grant_types_supported": ["authorization_code", "refresh_token"]})
        if p.endswith("/certs") or p == "/jwks": return self.send(200, JWKS)
        if p.endswith("/auth"):
            if DEMO and q.get("demo_user") not in DEMO_USERS:
                return self.send(200, picker(q), "text/html; charset=utf-8")
            code = str(uuid.uuid4()); codes[code] = (DEMO_USERS[q["demo_user"]][0] if DEMO else user(), q.get("client_id"), q.get("nonce"))
            return self.send(302, b"", headers={"Location": q["redirect_uri"] + "?" + urlencode({"code": code, "state": q.get("state", "")})})
        if p.endswith("/userinfo"):
            tok = self.headers.get("Authorization", "")[7:]
            try:
                c = jwt.decode(tok, options={"verify_signature": False})
                return self.send(200, {"sub": c["sub"], "preferred_username": c.get("preferred_username")})
            except Exception:
                return self.send(401, {"error": "invalid_token"})
        if p.endswith("/logout"):
            return self.send(302, b"", headers={"Location": q.get("post_logout_redirect_uri", "/")})
        self.send(404, {"error": "not found"})
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); f = {k: v[0] for k, v in parse_qs(self.rfile.read(n).decode()).items()}
        auth = self.headers.get("Authorization", "")
        log.write("GRANT " + str(f.get("grant_type")) + "\n"); log.flush()
        cid = f.get("client_id") or (base64.b64decode(auth[6:]).decode().split(":")[0] if auth.startswith("Basic ") else None)
        if f.get("grant_type") == "authorization_code" and f.get("code") in codes:
            u, client, nonce = codes.pop(f["code"]); return self.send(200, tokens(u, cid or client, nonce))
        if f.get("grant_type") == "client_credentials":
            u = {"sub": "svc-" + (cid or "x"), "name": "service-account-" + (cid or "x"), "roles": ["AUDIT_ADMIN"] if "reader" in (cid or "") else ["AUDIT_WRITER"]}
            return self.send(200, tokens(u, cid))
        if f.get("grant_type") == "refresh_token" and f.get("refresh_token") in refresh:
            u, client = refresh.pop(f["refresh_token"]); return self.send(200, tokens(user() if False else u, cid or client))
        self.send(400, {"error": "invalid_grant"})

ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()
