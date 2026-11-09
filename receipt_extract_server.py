#!/usr/bin/env python3
"""
سرور واسطهٔ خواندن فیش (برای بخش «🤖 خواندنِ خودکارِ فیش» در برنامه)

اجرا:
  export ANTHROPIC_API_KEY="sk-ant-..."
  export SERVER_TOKEN="یک-رمز-دلخواه"        # همان «رمز سرور» در تنظیمات برنامه
  python3 receipt_extract_server.py           # پیش‌فرض پورت 8790

در برنامه: آدرس = http://IP-کامپیوتر:8790/extract  و  رمز = همان SERVER_TOKEN
فقط کتابخانه‌های استاندارد پایتون لازم است.
"""
import os, json, re, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

API_KEY = os.environ.get("ANTHROPIC_API_KEY", "")
TOKEN = os.environ.get("SERVER_TOKEN", "")
MODEL = os.environ.get("MODEL", "claude-sonnet-5-5")
PORT = int(os.environ.get("PORT", "8790"))

PROMPT = """این تصویر احتمالاً فیش/رسید پرداخت بانکی ایرانی است (کارت‌به‌کارت، پایا، ساتنا، پل، اپ بانکی یا خودپرداز).
فقط یک JSON معتبر برگردان، بدون هیچ متن یا ```:
{
 "isReceipt": true/false,
 "amount": "مبلغ فقط با ارقام انگلیسی و بدون جداکننده",
 "unit": "rial" یا "toman" یا "unknown"  (واحدی که روی فیش نوشته؛ اگر معلوم نیست unknown),
 "date": "تاریخ شمسی همان‌طور که روی فیش است مثل 1405/08/17",
 "time": "ساعت اگر هست",
 "ref": "شماره پیگیری / مرجع / رهگیری (فقط ارقام انگلیسی)",
 "payer": "نام پرداخت‌کننده اگر هست",
 "destination": "نام یا شماره/کارت مقصد اگر هست",
 "bank": "نام بانک/اپ",
 "confidence": عددی بین 0 تا 1,
 "notes": "هر ابهام مهم، مثلاً تصویر تار، چند مبلغ، فیش ناموفق"
}
قواعد: ارقام فارسی را به انگلیسی تبدیل کن. مبلغ را حدس نزن؛ اگر خوانا نیست amount را خالی بگذار و confidence را پایین بده.
اگر تراکنش «ناموفق» یا «در انتظار» است حتماً در notes بنویس. اگر عکس فیش نیست isReceipt=false."""

def ask_model(b64, mime):
    body = {
        "model": MODEL, "max_tokens": 700,
        "messages": [{"role": "user", "content": [
            {"type": "image", "source": {"type": "base64", "media_type": mime or "image/jpeg", "data": b64}},
            {"type": "text", "text": PROMPT}]}],
    }
    req = urllib.request.Request(
        "https://api.anthropic.com/v1/messages", data=json.dumps(body).encode(),
        headers={"content-type": "application/json", "x-api-key": API_KEY, "anthropic-version": "2023-06-01"})
    with urllib.request.urlopen(req, timeout=55) as r:
        data = json.loads(r.read().decode())
    text = "".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text")
    text = re.sub(r"^```(?:json)?|```$", "", text.strip(), flags=re.M).strip()
    m = re.search(r"\{.*\}", text, flags=re.S)
    return json.loads(m.group(0) if m else text)

class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Token")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers(); self.wfile.write(raw)
    def _auth(self):
        return (not TOKEN) or self.headers.get("X-Token", "") == TOKEN
    def do_OPTIONS(self): self._send(204, {})
    def do_GET(self):
        if not self._auth(): return self._send(401, {"error": "bad token"})
        if self.path.startswith("/health"): return self._send(200, {"ok": True, "model": MODEL})
        self._send(404, {"error": "not found"})
    def do_POST(self):
        if not self._auth(): return self._send(401, {"error": "bad token"})
        if not self.path.startswith("/extract"): return self._send(404, {"error": "not found"})
        if not API_KEY: return self._send(500, {"error": "ANTHROPIC_API_KEY تنظیم نشده"})
        try:
            n = int(self.headers.get("Content-Length", "0"))
            j = json.loads(self.rfile.read(n).decode())
            self._send(200, ask_model(j.get("image", ""), j.get("mime")))
        except urllib.error.HTTPError as e:
            self._send(502, {"error": "API %s" % e.code})
        except Exception as e:
            self._send(500, {"error": str(e)[:200]})
    def log_message(self, *a): pass

if __name__ == "__main__":
    if not TOKEN: print("⚠️ SERVER_TOKEN خالی است؛ هر کسی در شبکه می‌تواند از سرور استفاده کند.")
    print("listening on 0.0.0.0:%d  model=%s" % (PORT, MODEL))
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
