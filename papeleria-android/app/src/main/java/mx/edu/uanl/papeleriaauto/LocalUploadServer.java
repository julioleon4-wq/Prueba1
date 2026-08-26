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
                    socket.setSoTimeout(20000);
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
            if (!path.equals(expected)) { sendText(out, 404, "Esta sesión ya no está disponible. Genera un nuevo QR en el kiosco."); return; }

            if ("GET".equals(method)) { sendHtml(out, uploadPage()); return; }
            if (!"POST".equals(method)) { sendText(out, 405, "Método no permitido"); return; }

            String ct = headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
            if (!ct.startsWith("application/pdf")) { sendJson(out, 415, "{\"ok\":false,\"error\":\"Solo se aceptan archivos PDF\"}"); return; }
            long length;
            try { length = Long.parseLong(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
            if (length <= 0 || length > MAX_BYTES) { sendJson(out, 413, "{\"ok\":false,\"error\":\"El PDF está vacío o supera 25 MB\"}"); return; }

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
                sendJson(out, 400, "{\"ok\":false,\"error\":\"El archivo no es un PDF válido\"}");
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
        return "<!doctype html><html lang='es'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'><meta name='theme-color' content='#121212'><title>Enviar documento</title><style>"+
        "*{box-sizing:border-box}body{margin:0;background:#f3f2ed;color:#121212;font-family:Arial,Helvetica,sans-serif;padding:24px}main{max-width:560px;margin:5vh auto;background:#fff;border:1px solid #d8d7d2;padding:28px}header{display:flex;justify-content:space-between;align-items:center;border-bottom:3px solid #121212;padding-bottom:18px;margin-bottom:26px}.mark{font-weight:900;letter-spacing:.06em}.step{font-size:12px;color:#666}h1{font-size:30px;line-height:1.05;margin:0 0 10px}.lead{color:#5b5b57;line-height:1.45}.picker{margin:28px 0;border:2px dashed #a9a8a3;background:#faf9f6;padding:24px}.picker label{display:block;font-weight:800;margin-bottom:12px}input{width:100%}.meta{font-size:12px;color:#72716d;margin-top:10px}.send{width:100%;border:0;background:#1155cc;color:white;padding:16px;font-size:16px;font-weight:800;letter-spacing:.02em}.send:disabled{opacity:.45}.msg{margin-top:18px;padding:14px;border-left:4px solid #b6b4ae;background:#f7f6f2;line-height:1.4}.ok{border-color:#1f7a4c}.bad{border-color:#b12b2b;color:#8d1e1e}.foot{margin-top:24px;font-size:11px;color:#777}</style></head><body><main><header><div class='mark'>PAPELERÍA / AUTOSERVICIO</div><div class='step'>ENVÍO DE ARCHIVO</div></header><h1>Envía tu PDF al kiosco</h1><p class='lead'>El archivo irá únicamente a la tablet que tienes enfrente. Al terminar la sesión se elimina del kiosco.</p><div class='picker'><label for='f'>Seleccionar documento</label><input id='f' type='file' accept='application/pdf,.pdf'><div class='meta'>PDF · máximo 25 MB</div></div><button class='send' id='b' onclick='send()'>ENVIAR AL KIOSCO</button><div id='m' class='msg'>Esperando archivo.</div><div class='foot'>No cierres esta página hasta ver la confirmación.</div></main><script>async function send(){const f=document.getElementById('f').files[0],m=document.getElementById('m'),b=document.getElementById('b');if(!f){m.className='msg bad';m.textContent='Selecciona un PDF.';return}if(f.size>25*1024*1024){m.className='msg bad';m.textContent='El archivo supera 25 MB.';return}b.disabled=true;m.className='msg';m.textContent='Enviando documento…';try{const r=await fetch(location.pathname,{method:'POST',headers:{'Content-Type':'application/pdf','X-File-Name':encodeURIComponent(f.name)},body:f});const x=await r.json();if(!r.ok)throw new Error(x.error||'No se pudo enviar');m.className='msg ok';m.innerHTML='<b>Documento recibido.</b><br>Continúa en la pantalla del kiosco.';b.style.display='none';document.getElementById('f').disabled=true}catch(e){m.className='msg bad';m.textContent=e.message;b.disabled=false}}</script></body></html>";
    }
}
