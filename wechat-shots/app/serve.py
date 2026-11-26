# -*- coding: utf-8 -*-
"""Local static server for the WeChat chat-screenshot tool.

Run from the folder that contains app/ and input.mp4:
    python app/serve.py            (opens the browser)
    python app/serve.py --no-browser --port 8765

Serves ./app as the web root, ./input.mp4 at /sample/input.mp4.
Byte-range requests are supported so <video> seeking works.
All filesystem access is relative to the current directory on purpose:
os.getcwd() returns mojibake for this non-ASCII path.
"""
import argparse
import os
import socket
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def find_app_dir():
    for cand in (os.path.join('app', 'index.html'), 'index.html', 'probe.html'):
        if os.path.isfile(cand):
            return os.path.dirname(cand) or '.'
    return 'app'

APP_DIR = find_app_dir()
VIDEO = 'input.mp4'

MIME = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript; charset=utf-8',
    '.mjs': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.webp': 'image/webp',
    '.svg': 'image/svg+xml',
    '.ico': 'image/x-icon',
    '.mp4': 'video/mp4',
    '.m4v': 'video/mp4',
    '.mov': 'video/quicktime',
    '.webm': 'video/webm',
    '.txt': 'text/plain; charset=utf-8',
    '.zip': 'application/zip',
}

class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'WeChatShots/1.0'

    def log_message(self, fmt, *args):
        if not os.environ.get('WXS_QUIET'):
            sys.stderr.write('%s - %s\n' % (self.address_string(), fmt % args))

    def resolve(self, url_path):
        path = url_path.split('?', 1)[0].split('#', 1)[0]
        if path in ('', '/'):
            path = '/index.html'
        if path == '/sample/input.mp4':
            return VIDEO if os.path.isfile(VIDEO) else None
        rel = path.lstrip('/')
        full = os.path.normpath(os.path.join(APP_DIR, rel))
        base = os.path.normpath(APP_DIR)
        if base != '.' and not full.startswith(base):
            return None
        return full if os.path.isfile(full) else None

    def send_file(self, full):
        try:
            size = os.path.getsize(full)
        except OSError:
            self.send_error(404)
            return
        ext = os.path.splitext(full)[1].lower()
        ctype = MIME.get(ext, 'application/octet-stream')
        start, end = 0, size - 1
        status = 200
        rng = self.headers.get('Range')
        if rng and rng.startswith('bytes='):
            try:
                spec = rng.split('=', 1)[1].split(',', 1)[0].strip()
                a, _, b = spec.partition('-')
                if a == '':
                    start = max(0, size - int(b))
                else:
                    start = int(a)
                    if b:
                        end = min(int(b), size - 1)
                if start > end or start >= size:
                    self.send_response(416)
                    self.send_header('Content-Range', 'bytes ' + chr(42) + '/' + str(size))
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                status = 206
            except (ValueError, IndexError):
                start, end = 0, size - 1
                status = 200
        length = end - start + 1
        self.send_response(status)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(length))
        self.send_header('Accept-Ranges', 'bytes')
        if status == 206:
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, size))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        if self.command == 'HEAD':
            return
        try:
            with open(full, 'rb') as f:
                f.seek(start)
                remain = length
                while remain > 0:
                    chunk = f.read(min(262144, remain))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remain -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self):
        name = self.path.split('?', 1)[0].lstrip('/')
        prefix = '_save/'
        if not name.startswith(prefix):
            self.send_error(404, 'Not found')
            return
        fname = os.path.basename(name[len(prefix):])
        if not fname:
            self.send_error(400, 'bad name')
            return
        try:
            length = int(self.headers.get('Content-Length') or 0)
        except ValueError:
            length = 0
        body = self.rfile.read(length) if length > 0 else b''
        try:
            os.makedirs('_out', exist_ok=True)
            with open(os.path.join('_out', fname), 'wb') as f:
                f.write(body)
        except OSError as exc:
            self.send_error(500, str(exc))
            return
        msg = ('{"ok":true,"file":"%s","bytes":%d}' % (fname, len(body))).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(msg)))
        self.end_headers()
        self.wfile.write(msg)

    def do_GET(self):
        full = self.resolve(self.path)
        if not full:
            self.send_error(404, 'Not found')
            return
        self.send_file(full)

    do_HEAD = do_GET

def free_port(preferred):
    for p in range(preferred, preferred + 40):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                s.bind(('127.0.0.1', p))
                return p
            except OSError:
                continue
    return preferred

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--port', type=int, default=int(os.environ.get('WXS_PORT', '8765')))
    ap.add_argument('--no-browser', action='store_true')
    args = ap.parse_args()

    port = free_port(args.port)
    url = 'http://127.0.0.1:%d/' % port
    httpd = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    httpd.daemon_threads = True
    print('chat-screenshot tool')
    print('  web root : %s' % APP_DIR)
    print('  video    : %s' % (VIDEO if os.path.isfile(VIDEO) else '(missing)'))
    print('  open     : %s' % url)
    print('  stop     : Ctrl+C')
    sys.stdout.flush()
    if not args.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print('')
        print('bye')

if __name__ == '__main__':
    main()
