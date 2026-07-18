import sys
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

class WebhookHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        try:
            # Get the content length and read the JSON payload injected by Agent Manager
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length).decode('utf-8')
            
            # Print physical proof the webhook triggered!
            print("\n" + "="*50)
            print("🚀 PYTHON EXTENSION WEBHOOK TRIGGERED!")
            print(f"📥 Received Paylod: {post_data}")
            
            # The Agent Manager sends: {"workflowId":"...", "runId":"...", "input":"..."}
            data = json.loads(post_data)
            input_text = data.get("input", "")
            
            # Execute our "Proprietary Data Science Logic" 
            # (In this mock, we just reverse the text and shout it)
            processed_output = f"[Processed natively in Python 🐍] {input_text.upper()[::-1]}"
            print(f"📤 Returning Output: {processed_output}")
            print("="*50 + "\n")
            
            # Respond to the Java Virtual Thread!
            self.send_response(200)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self.end_headers()
            self.wfile.write(processed_output.encode('utf-8'))
            
        except Exception as e:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(str(e).encode('utf-8'))

def run(port=8000):
    server_address = ('', port)
    httpd = HTTPServer(server_address, WebhookHandler)
    print(f"🐍 Python Webhook Extension active. Listening on port {port}...")
    print(f"Waiting for Agent Manager Virtual Thread payloads...")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    httpd.server_close()

if __name__ == '__main__':
    run()
