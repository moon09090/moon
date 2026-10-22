"""Moon brain: runs in Termux. The Moon Android app sends it your spoken text."""
import json, os, re, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
import requests

KEY = os.environ.get("OPENROUTER_API_KEY")
MODEL = os.environ.get("MOON_MODEL", "anthropic/claude-3.5-sonnet")
PORT = 8765

SYSTEM = """You are Moon, a voice assistant on the user's Android phone. The user speaks to you.
Reply ONLY with one JSON object and nothing else:
{"say": "short spoken reply, max 2 sentences", "action": "none", "arg": ""}
Allowed actions:
- open_url: arg = full https URL
- search: arg = search query (opens Google)
- open_app: arg = Android package name (YouTube com.google.android.youtube, WhatsApp com.whatsapp, Chrome com.android.chrome, Instagram com.instagram.android, Maps com.google.android.apps.maps)
- torch_on / torch_off
- none: just answer
Call the user "boss"."""

history = []


def ask_ai(text):
    history.append({"role": "user", "content": text})
    msgs = [{"role": "system", "content": SYSTEM}] + history[-8:]
    r = requests.post(
        "https://openrouter.ai/api/v1/chat/completions",
        headers={"Authorization": f"Bearer {KEY}"},
        json={"model": MODEL, "messages": msgs},
        timeout=40,
    )
    r.raise_for_status()
    raw = r.json()["choices"][0]["message"]["content"]
    m = re.search(r"\{.*\}", raw, re.S)
    try:
        data = json.loads(m.group(0)) if m else {"say": raw, "action": "none", "arg": ""}
    except json.JSONDecodeError:
        data = {"say": raw, "action": "none", "arg": ""}
    history.append({"role": "assistant", "content": json.dumps(data)})
    return data


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            text = json.loads(self.rfile.read(n))["text"]
            print("You:", text)
            data = ask_ai(text)
            print("Moon:", data)
        except Exception as e:
            print("Error:", e)
            data = {"say": "Sorry boss, the AI had a problem.", "action": "none", "arg": ""}
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


if not KEY:
    sys.exit('Set your key first: export OPENROUTER_API_KEY="your_key"')
print(f"Moon server running on 127.0.0.1:{PORT}")
HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
