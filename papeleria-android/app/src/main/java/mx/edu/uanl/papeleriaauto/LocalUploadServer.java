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
                    socket.setSoTimeout(15000);
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
            if (!path.equals(expected)) { sendText(out, 404, "Sesión expirada. Vuelve a escanear el QR del kiosco."); return; }

            if ("GET".equals(method)) {
                sendHtml(out, uploadPage());
                return;
            }
            if (!"POST".equals(method)) { sendText(out, 405, "Método no permitido"); return; }

            String ct = headers.getOrDefault("content-type", "").toLowerCase(Locale.ROOT);
            if (!ct.startsWith("application/pdf")) { sendJson(out, 415, "{\"ok\":false,\"error\":\"Solo se aceptan PDF\"}"); return; }
            long length;
            try { length = Long.parseLong(headers.getOrDefault("content-length", "-1")); } catch (Exception e) { length = -1; }
            if (length <= 0 || length > MAX_BYTES) { sendJson(out, 413, "{\"ok\":false,\"error\":\"PDF vacío o mayor a 25 MB\"}"); return; }

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
                target.delete(); sendJson(out, 400, "{\"ok\":false,\"error\":\"El archivo no parece un PDF válido\"}"); return;
            }

            String usedToken = token;
            token = newTokenValue();
            if (listener != null) listener.onPdfReceived(target, original);
            sendJson(out, 200, "{\"ok\":true,\"message\":\"PDF enviado al kiosco\",\"session\":\"" + usedToken + "\"}");
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
        String h = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + len + "\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String uploadPage() {
        return "<!doctype html><html lang='es'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Enviar a Papelería</title><style>"+
        "*{box-sizing:border-box}body{margin:0;background:#07111f;color:#f5f9ff;font-family:system-ui;padding:22px}.box{max-width:560px;margin:8vh auto;background:#0d1b2d;border:1px solid #24415f;border-radius:24px;padding:24px}.logo{width:54px;height:54px;background:linear-gradient(135deg,#42d392,#4ca3ff);color:#07111f;border-radius:16px;display:grid;place-items:center;font-weight:900;font-size:25px}h1{font-size:26px}.muted{color:#9fb2c8}.drop{margin:22px 0;border:1px dashed #557390;border-radius:18px;padding:26px;text-align:center}input{width:100%}button{width:100%;border:0;border-radius:14px;padding:15px;font-size:17px;font-weight:800;background:#42d392;color:#05211c}.msg{margin-top:16px;padding:13px;border-radius:12px;background:#10243b}.ok{color:#42d392}.bad{color:#ff7777}</style></head><body><div class='box'><div class='logo'>P</div><h1>Enviar PDF al kiosco</h1><p class='muted'>Selecciona tu archivo. Se enviará únicamente a la tablet que tienes enfrente.</p><div class='drop'><input id='f' type='file' accept='application/pdf,.pdf'></div><button id='b' onclick='send()'>ENVIAR PDF</button><div id='m' class='msg'>Máximo 25 MB · Solo PDF</div></div><script>async function send(){const f=document.getElementById('f').files[0],m=document.getElementById('m'),b=document.getElementById('b');if(!f){m.className='msg bad';m.textContent='Selecciona un PDF.';return}if(f.size>25*1024*1024){m.className='msg bad';m.textContent='El archivo supera 25 MB.';return}b.disabled=true;m.className='msg';m.textContent='Enviando…';try{const r=await fetch(location.pathname,{method:'POST',headers:{'Content-Type':'application/pdf','X-File-Name':encodeURIComponent(f.name)},body:f});const x=await r.json();if(!r.ok)throw new Error(x.error||'No se pudo enviar');m.className='msg ok';m.innerHTML='✅ PDF recibido por el kiosco.<br><b>Ya puedes continuar en la tablet.</b>';b.style.display='none';document.getElementById('f').disabled=true}catch(e){m.className='msg bad';m.textContent=e.message;b.disabled=false}}</script></body></html>";
    }
}
