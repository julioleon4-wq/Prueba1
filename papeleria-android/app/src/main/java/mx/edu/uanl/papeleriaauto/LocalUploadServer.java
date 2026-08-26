package mx.edu.uanl.papeleriaauto;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class LocalUploadServer {
    public interface Listener { void onPdfReceived(File file, String originalName); }

    private final File uploadDir;
    private final int port;
    private final Listener listener;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private volatile String token = newTokenValue();
    private ServerSocket serverSocket;
    private static final long MAX_BYTES = 25L * 1024L * 1024L;

    public LocalUploadServer(File cacheDir, int port, Listener listener) {
        this.uploadDir = new File(cacheDir, "remote_uploads");
        this.uploadDir.mkdirs();
        this.port = port;
        this.listener = listener;
        cleanupOldFiles();
    }

    public int getPort() { return port; }
    public synchronized String newSession() { token = newTokenValue(); return token; }
    public String getToken() { return token; }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;
        pool.execute(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(30000);
                    pool.execute(() -> handle(socket));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        });
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    private static String newTokenValue() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void cleanupOldFiles() {
        long cutoff = System.currentTimeMillis() - 60L * 60L * 1000L;
        File[] files = uploadDir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile() && f.lastModified() < cutoff) try { f.delete(); } catch(Exception ignored) {}
    }

    private void handle(Socket socket) {
        try (Socket s = socket; InputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] first = requestLine.split(" ");
            if (first.length < 2) { sendText(out, 400, "Solicitud inválida"); return; }
            String method = first[0].toUpperCase(Locale.ROOT);
            String path = first[1].split("\\?", 2)[0];
            Map<String,String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0,i).trim().toLowerCase(Locale.ROOT), line.substring(i+1).trim());
            }

            String expected = "/upload/" + token;
            if (!path.equals(expected)) { sendText(out, 404, "Este QR ya venció o ya se usó. Genera uno nuevo en el kiosco."); return; }
            if ("GET".equals(method)) { sendHtml(out, uploadPage()); return; }
            if (!"POST".equals(method)) { sendText(out, 405, "Método no permitido"); return; }

            String ct = headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
            if (!ct.startsWith("application/pdf")) { sendJson(out, 415, "{\"ok\":false,\"error\":\"Ese archivo no es PDF. Elige un PDF y vuelve a intentar.\"}"); return; }
            long length;
            try { length = Long.parseLong(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
            if (length <= 0) { sendJson(out, 400, "{\"ok\":false,\"error\":\"El archivo está vacío.\"}"); return; }
            if (length > MAX_BYTES) { sendJson(out, 413, "{\"ok\":false,\"error\":\"Tu PDF pesa más de 25 MB.\"}"); return; }

            String encoded = headers.getOrDefault("x-file-name", "documento.pdf");
            String original;
            try { original = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()); } catch(Exception e) { original = "documento.pdf"; }
            original = sanitize(original);
            if (!original.toLowerCase(Locale.ROOT).endsWith(".pdf")) original += ".pdf";

            File target = new File(uploadDir, System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0,8) + ".pdf");
            try (OutputStream fileOut = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buf = new byte[64 * 1024];
                long remain = length;
                while (remain > 0) {
                    int n = in.read(buf, 0, (int)Math.min(buf.length, remain));
                    if (n < 0) throw new EOFException("Carga incompleta");
                    fileOut.write(buf, 0, n);
                    remain -= n;
                }
            }

            byte[] magic = new byte[5];
            try (InputStream fin = new FileInputStream(target)) { if (fin.read(magic) != 5) throw new IOException("PDF inválido"); }
            if (!"%PDF-".equals(new String(magic, StandardCharsets.US_ASCII))) {
                target.delete();
                sendJson(out, 400, "{\"ok\":false,\"error\":\"El archivo no parece ser un PDF válido.\"}");
                return;
            }

            String usedToken = token;
            token = newTokenValue();
            if (listener != null) listener.onPdfReceived(target, original);
            sendJson(out, 200, "{\"ok\":true,\"message\":\"Archivo recibido\",\"session\":\"" + usedToken + "\"}");
        } catch(Exception ignored) { }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1, cur;
        while ((cur = in.read()) != -1) {
            if (prev == '\r' && cur == '\n') {
                byte[] a = b.toByteArray();
                int len = a.length > 0 && a[a.length-1] == '\r' ? a.length-1 : a.length;
                return new String(a, 0, len, StandardCharsets.ISO_8859_1);
            }
            b.write(cur); prev = cur;
            if (b.size() > 16384) throw new IOException("Header demasiado grande");
        }
        return b.size() == 0 ? null : b.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static String sanitize(String s) {
        s = s.replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_").trim();
        if (s.length() > 100) s = s.substring(0,100);
        return s.isEmpty() ? "documento.pdf" : s;
    }

    private static void sendHtml(OutputStream out, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, 200, "text/html; charset=utf-8", b.length); out.write(b); out.flush();
    }
    private static void sendText(OutputStream out, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, status, "text/plain; charset=utf-8", b.length); out.write(b); out.flush();
    }
    private static void sendJson(OutputStream out, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, status, "application/json; charset=utf-8", b.length); out.write(b); out.flush();
    }
    private static void writeHeaders(OutputStream out, int status, String type, int len) throws IOException {
        String reason = status==200?"OK":status==400?"Bad Request":status==404?"Not Found":status==405?"Method Not Allowed":status==413?"Payload Too Large":status==415?"Unsupported Media Type":"Error";
        String h = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + len + "\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String uploadPage() {
        return """
<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#0f4fd1"><title>Subir PDF</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f2efe8;color:#171717;font-family:Arial,Helvetica,sans-serif;padding:20px}main{max-width:560px;margin:4vh auto;background:#fff;border:1px solid #ddd5c8;border-radius:24px;padding:24px;box-shadow:0 22px 55px #00000014;overflow:hidden;position:relative}main:after{content:'C';position:absolute;right:-20px;bottom:-70px;font-size:240px;font-weight:900;color:#111;opacity:.025;pointer-events:none}.top{font-size:10px;font-weight:900;letter-spacing:.13em;text-transform:uppercase;color:#0f4fd1}.status{display:inline-block;margin-top:8px;border:1px solid #d5d0c8;border-radius:99px;padding:7px 9px;font-size:9px;font-weight:900;text-transform:uppercase}h1{font-size:34px;line-height:1.02;letter-spacing:-.035em;margin:20px 0 9px}.lead{color:#5d5953;line-height:1.5;font-size:14px}.picker{margin:24px 0 14px;border:1px dashed #aaa39a;border-radius:16px;background:#faf8f3;padding:20px}.picker label{display:block;font-weight:900;margin-bottom:12px}.meta{font-size:11px;color:#77716a;margin-top:10px}.send{width:100%;border:0;border-radius:13px;background:#0f4fd1;color:#fff;padding:16px;font-size:15px;font-weight:900}.send:disabled{opacity:.5}.msg{margin-top:14px;padding:12px;border-left:4px solid #b6b4ae;background:#f7f6f2;line-height:1.4;font-size:12px}.ok{border-color:#1f7a4c}.bad{border-color:#b12b2b;color:#8d1e1e}.progress{height:8px;border-radius:99px;background:#e7e2d9;overflow:hidden;margin-top:14px;display:none}.progress i{display:block;height:100%;width:0;background:#0f4fd1;transition:width .08s}.pct{text-align:right;font-size:10px;color:#666;margin-top:5px}.foot{margin-top:18px;font-size:10px;color:#777;line-height:1.45}</style></head>
<body><main><div class="top">COBRA / PAPELERÍA</div><span class="status">Sesión privada</span><h1>Manda tu PDF en corto.</h1><p class="lead">Elige el archivo y súbelo. Cuando llegue, sigue en la tablet para revisar páginas, color y copias.</p><div class="picker"><label for="f">Tu documento</label><input id="f" type="file" accept="application/pdf,.pdf"><div class="meta">Solo PDF · máximo 25 MB</div></div><button class="send" id="b" onclick="sendFile()">MANDAR AL KIOSCO</button><div id="prog" class="progress"><i id="bar"></i></div><div id="pct" class="pct"></div><div id="m" class="msg">Listo para recibir tu archivo.</div><div class="foot">No cierres esta página hasta que diga que ya quedó. Si se corta la red, puedes volver a intentar mientras el QR siga activo.</div></main>
<script>
function fail(t){let m=document.getElementById('m');m.className='msg bad';m.textContent=t;document.getElementById('b').disabled=false}
function sendFile(){const f=document.getElementById('f').files[0],m=document.getElementById('m'),b=document.getElementById('b'),p=document.getElementById('prog'),bar=document.getElementById('bar'),pct=document.getElementById('pct');if(!f){fail('Primero elige un PDF.');return}if(f.size>25*1024*1024){fail('Ese PDF pesa más de 25 MB.');return}if(!/pdf$/i.test(f.name)&&f.type!=='application/pdf'){fail('Ese archivo no parece PDF.');return}b.disabled=true;p.style.display='block';m.className='msg';m.textContent='Subiendo… no cierres esta página.';let x=new XMLHttpRequest();x.open('POST',location.pathname,true);x.setRequestHeader('Content-Type','application/pdf');x.setRequestHeader('X-File-Name',encodeURIComponent(f.name));x.upload.onprogress=e=>{if(e.lengthComputable){let n=Math.round(e.loaded*100/e.total);bar.style.width=n+'%';pct.textContent=n+'%'}};x.onload=()=>{let r={};try{r=JSON.parse(x.responseText||'{}')}catch{}if(x.status>=200&&x.status<300&&r.ok){bar.style.width='100%';pct.textContent='100%';m.className='msg ok';m.innerHTML='<b>Ya quedó.</b><br>Regresa a la tablet para revisar tu impresión.';b.style.display='none';document.getElementById('f').disabled=true}else fail(r.error||'No se pudo enviar. Intenta otra vez.')};x.onerror=()=>fail('Se cortó la conexión. Revisa la red e intenta otra vez.');x.ontimeout=()=>fail('La subida tardó demasiado. Intenta otra vez.');x.timeout=45000;x.send(f)}
</script></body></html>
""";
    }
}
