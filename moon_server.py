import json, os, re, subprocess, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
import requests

KEY = os.environ.get("OPENROUTER_API_KEY")
MODEL = os.environ.get("MOON_MODEL", "openrouter/auto")
PORT = 8765
http = requests.Session()

APPS = {"youtube": "com.google.android.youtube", "whatsapp": "com.whatsapp",
        "chrome": "com.android.chrome", "instagram": "com.instagram.android",
        "maps": "com.google.android.apps.maps", "facebook": "com.facebook.katana",
        "telegram": "org.telegram.messenger", "gmail": "com.google.android.gm",
        "settings": "com.android.settings", "spotify": "com.spotify.music"}

SYSTEM = """You are Moon, a voice assistant on the user's Android phone.
Reply ONLY with one JSON object: {"say": "answer in max 15 words", "action": "none", "arg": ""}
Actions: open_url (arg=https URL), search (arg=query), open_app (arg=package name), torch_on, torch_off, none.
Call the user boss."""
history = []

def quick(text):
    t = text.lower().strip().rstrip(".?!")
    m = re.match(r"(?:please )?(?:open|launch|start) (.+)", t)
    if m:
        name = re.sub(r"^the |\s*app$", "", m.group(1).strip())
        if name in APPS:
            return {"say": "Opening " + name, "action": "open_app", "arg": APPS[name]}
    if "torch" in t or "flashlight" in t:
        on = "off" not in t
        return {"say": "Torch on" if on else "Torch off",
                "action": "torch_on" if on else "torch_off", "arg": ""}
    m = re.match(r"(?:search|google) (?:for )?(.+)", t)
    if m:
        return {"say": "Searching", "action": "search", "arg": m.group(1)}
    return None

def ask_ai(text):
    history.append({"role": "user", "content": text})
    r = http.post("https://openrouter.ai/api/v1/chat/completions",
        headers={"Authorization": "Bearer " + KEY},
        json={"model": MODEL, "max_tokens": 120,
              "messages": [{"role": "system", "content": SYSTEM}] + history[-6:]},
        timeout=30)
    if r.status_code != 200: print(r.text)
    r.raise_for_status()
    raw = r.json()["choices"][0]["message"]["content"]
    m = re.search(r"\{.*\}", raw, re.S)
    try:
        data = json.loads(m.group(0)) if m else {"say": raw, "action": "none", "arg": ""}
    except json.JSONDecodeError:
        data = {"say": raw, "action": "none", "arg": ""}
    history.append({"role": "assistant", "content": json.dumps(data)})
    return data

class H(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            text = json.loads(self.rfile.read(n))["text"]
            print("You:", text)
            data = quick(text) or ask_ai(text)
            print("Moon:", data)
            subprocess.Popen(["am", "broadcast", "-a", "moon.COMMAND", "--es", "text", text,
                "--es", "action", str(data.get("action")), "--es", "arg", str(data.get("arg"))],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except Exception as e:
            print("Error:", e)
            data = {"say": "Sorry boss, the AI had a problem.", "action": "none", "arg": ""}
        body = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass

if not KEY: sys.exit("Set OPENROUTER_API_KEY first")
print("Moon server running on 127.0.0.1:%d" % PORT)
HTTPServer(("127.0.0.1", PORT), H).serve_forever()
