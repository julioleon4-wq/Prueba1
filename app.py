import argparse
import http.server
import socket
import socketserver
import threading
import webbrowser
from pathlib import Path


class SilentHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        return


def find_open_port(default_port: int) -> int:
    for port in range(default_port, default_port + 100):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind(("", port))
                return sock.getsockname()[1]
            except OSError:
                continue
    raise RuntimeError("No se encontró un puerto libre.")


def start_server(port: int, directory: Path) -> socketserver.TCPServer:
    handler = lambda *args, **kwargs: SilentHandler(*args, directory=str(directory), **kwargs)
    httpd = socketserver.TCPServer(("", port), handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    return httpd


def main() -> None:
    parser = argparse.ArgumentParser(description="Lanzador del selector de asientos")
    parser.add_argument("--port", type=int, default=8000, help="Puerto del servidor local")
    args = parser.parse_args()

    root = Path(__file__).parent
    port = find_open_port(args.port)
    server = start_server(port, root)

    url = f"http://127.0.0.1:{port}/index.html"
    webbrowser.open(url)
    print(f"Servidor activo en {url} (Ctrl+C para salir)")

    try:
        while True:
            threading.Event().wait(1)
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()


if __name__ == "__main__":
    main()
