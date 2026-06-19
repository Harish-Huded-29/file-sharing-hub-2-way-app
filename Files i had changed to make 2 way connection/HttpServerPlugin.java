package com.bluetoothshare.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;


import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@CapacitorPlugin(name = "HttpServer")
public class HttpServerPlugin extends Plugin {

    private static final String TAG = "HttpServerPlugin";
    private ServerThread serverThread;
    private static Map<String, FileInfo> fileMap = new HashMap<>();
    private Context context;
    
    // File info class
    static class FileInfo {
        String path;
        String name;
        long size;
        String mimeType;

        FileInfo(String path, String name, long size, String mimeType) {
            this.path = path;
            this.name = name;
            this.size = size;
            this.mimeType = mimeType;
        }
    }

    // Context setter for use from MainActivity
    public void setContext(Context ctx) {
        this.context = ctx;
    }


    // Synchronous start server method
    public String startServerSync(int port) {
        try {
            if (serverThread != null && serverThread.isAlive()) {
                String ip = getIpAddressSync();
                return "{\"success\":true,\"port\":" + port + ",\"ip\":\"" + ip + "\"}";
            }

            fileMap.clear();
            Context ctx = context != null ? context : getContext();
            serverThread = new ServerThread(port, ctx);
            serverThread.start();
            
            Thread.sleep(500);
            
            String ip = getIpAddressSync();
            Log.d(TAG, "✅ Server started: " + ip + ":" + port);
            
            return "{\"success\":true,\"port\":" + port + ",\"ip\":\"" + ip + "\"}";
        } catch (Exception e) {
            Log.e(TAG, "Start server error", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // Synchronous stop server method
    public void stopServerSync() {
        if (serverThread != null) {
            serverThread.stopServer();
            serverThread = null;
            fileMap.clear();
            
            Log.d(TAG, "Server stopped");
        }
    }

    // Synchronous add file method
    public String addFileSync(String path, String virtualPath, String name, long size, String mimeType) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return "{\"success\":false,\"error\":\"File not found: " + path + "\"}";
            }

            fileMap.put(virtualPath, new FileInfo(path, name, size, mimeType));
            Log.d(TAG, "✅ File added: " + name);
            return "{\"success\":true}";
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String getIpAddressSync() {
    try {
        // Try WiFi first
        Context ctx = context != null ? context : getContext();
        WifiManager wifiManager = (WifiManager) ctx.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            
            if (ipAddress != 0) {
                String ip = String.format(Locale.US, "%d.%d.%d.%d",
                        (ipAddress & 0xff),
                        (ipAddress >> 8 & 0xff),
                        (ipAddress >> 16 & 0xff),
                        (ipAddress >> 24 & 0xff));
                
                Log.d(TAG, "WiFi IP: " + ip);
                return ip;
            }
        }
        
        // Fallback: scan all network interfaces
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            
            if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                continue;
            }
            
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                
                if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                    String ip = address.getHostAddress();
                    
                    // Return first valid private IP
                    if (ip.startsWith("192.168.") || 
                        ip.startsWith("10.") ||
                        ip.startsWith("172.")) {
                        Log.d(TAG, "Network interface IP: " + ip);
                        return ip;
                    }
                }
            }
        }
        
        Log.w(TAG, "No local IP found");
        return "0.0.0.0";
        
    } catch (Exception e) {
        Log.e(TAG, "Error getting IP", e);
        return "0.0.0.0";
    }
}

    // Capacitor plugin methods (optional - we use sync methods above)
    @PluginMethod
    public void startServer(PluginCall call) {
        Integer port = call.getInt("port", 8080);
        String result = startServerSync(port);
        
        try {
            JSObject ret = new JSObject(result);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to start server: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopServer(PluginCall call) {
        stopServerSync();
        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void getIpAddress(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("ip", getIpAddressSync());
        call.resolve(ret);
    }

    @PluginMethod
    public void addFile(PluginCall call) {
        String path = call.getString("path");
        String virtualPath = call.getString("virtualPath");
        String name = call.getString("name");
        Long size = call.getLong("size", 0L);
        String mimeType = call.getString("mimeType", "application/octet-stream");
        
        String result = addFileSync(path, virtualPath, name, size, mimeType);
        
        try {
            JSObject ret = new JSObject(result);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to add file: " + e.getMessage());
        }
    }

    // Server thread
    private static class ServerThread extends Thread {
        private ServerSocket serverSocket;
        private boolean running = false;
        private int port;
        private Context context;

        ServerThread(int port, Context context) {
            this.port = port;
            this.context = context;
        }

        @Override
public void run() {
    try {
        // Get local IP address
        InetAddress bindAddress = getLocalInetAddress();
        
        if (bindAddress != null) {
            // Bind to specific local address (works without internet)
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindAddress, port));
            Log.d(TAG, "🌐 HTTP Server bound to: " + bindAddress.getHostAddress() + ":" + port);
        } else {
            // Fallback to binding all interfaces
            serverSocket = new ServerSocket(port, 50, null);
            Log.d(TAG, "🌐 HTTP Server listening on 0.0.0.0:" + port);
        }
        
        running = true;

        while (running) {
            try {
                Socket client = serverSocket.accept();
                Log.d(TAG, "📱 Client connected: " + client.getInetAddress());
                new ClientHandler(client).start();
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "Accept error", e);
                }
            }
        }
    } catch (IOException e) {
        Log.e(TAG, "Server error", e);
    } finally {
        Log.d(TAG, "Server thread stopped");
    }
}

// Helper method to get local IP without needing internet
private InetAddress getLocalInetAddress() {
    try {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            
            // Skip loopback and inactive interfaces
            if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                continue;
            }
            
            Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress address = addresses.nextElement();
                
                // Get IPv4 address that's not loopback
                if (!address.isLoopbackAddress() && address.getAddress().length == 4) {
                    String hostAddress = address.getHostAddress();
                    
                    // Prefer 192.168.x.x or 10.x.x.x (private networks)
                    if (hostAddress.startsWith("192.168.") || 
                        hostAddress.startsWith("10.") ||
                        hostAddress.startsWith("172.")) {
                        Log.d(TAG, "Found local address: " + hostAddress);
                        return address;
                    }
                }
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "Error finding local address", e);
    }
    return null;
}

        void stopServer() {
            running = false;
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing server", e);
            }
        }
    }

    // Client handler thread
    private static class ClientHandler extends Thread {
        private Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
public void run() {
    BufferedReader in = null;
    OutputStream out = null;
    
    try {
        in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        out = socket.getOutputStream();

        String requestLine = in.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            socket.close();
            return;
        }

        Log.d(TAG, "📨 Request: " + requestLine);

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            send400(out);
            socket.close();
            return;
        }

        String method = parts[0];
        String path = parts[1];
        
        // Decode URL-encoded path
        try {
            path = java.net.URLDecoder.decode(path, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Error decoding path", e);
        }

        // Read headers (important for file serving)
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            Log.d(TAG, "Header: " + line);
        }

        // Route requests
        if (path.equals("/") || path.equals("/index.html")) {
            sendFileList(out);
        } else if (fileMap.containsKey(path)) {
            Log.d(TAG, "📤 Serving file: " + path);
            sendFile(out, fileMap.get(path));
        } else {
            Log.d(TAG, "❌ File not found: " + path);
            Log.d(TAG, "Available paths: " + fileMap.keySet());
            send404(out);
        }

    } catch (Exception e) {
        Log.e(TAG, "❌ Handler error", e);
    } finally {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing resources", e);
        }
    }
}

        private void sendFileList(OutputStream out) throws IOException {
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html><html><head>");
    html.append("<meta charset='UTF-8'>");
    html.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
    html.append("<title>Free Signal Hub</title>");
    html.append("<style>");
    
    // CSS
    html.append("*{margin:0;padding:0;box-sizing:border-box}");
    html.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:linear-gradient(135deg,#667eea 0%,#764ba2 100%);min-height:100vh}");
    html.append(".header{background:rgba(102,126,234,0.95);padding:20px;color:white;display:flex;align-items:center;justify-content:space-between;box-shadow:0 4px 20px rgba(0,0,0,0.2)}");
    html.append(".header-left{display:flex;align-items:center;gap:15px}");
    html.append(".logo{font-size:32px}");
    html.append(".title h1{font-size:24px;font-weight:600;margin-bottom:5px}");
    html.append(".title .subtitle{font-size:14px;opacity:0.9}");
    html.append(".status-badge{background:rgba(255,255,255,0.2);padding:8px 16px;border-radius:20px;font-size:14px}");
    html.append(".container{display:flex;height:calc(100vh - 80px);background:white}");
    html.append(".sidebar{width:360px;background:#2d2d2d;color:white;padding:20px;overflow-y:auto}");
    html.append(".sidebar-header{font-size:12px;text-transform:uppercase;color:#888;margin-bottom:15px;font-weight:600}");
    html.append(".file-item{background:#3a3a3a;padding:15px;margin-bottom:10px;border-radius:8px;cursor:pointer;transition:all 0.3s}");
    html.append(".file-item:hover{background:#4a4a4a;transform:translateX(5px)}");
    html.append(".file-item.active{background:#667eea}");
    html.append(".file-name{font-size:14px;font-weight:500;margin-bottom:5px}");
    html.append(".file-size{font-size:12px;color:#aaa}");
    html.append(".preview-area{flex:1;padding:30px;overflow-y:auto}");
    html.append(".preview-content{max-width:900px;margin:0 auto}");
    html.append(".preview-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;padding-bottom:15px;border-bottom:2px solid #eee}");
    html.append(".preview-title{font-size:20px;font-weight:600}");
    html.append(".preview-actions{display:flex;gap:10px}");
    html.append(".btn{padding:10px 20px;border:none;border-radius:6px;cursor:pointer;font-size:14px;font-weight:600;text-decoration:none;display:inline-block}");
    html.append(".btn-primary{background:#667eea;color:white}");
    html.append(".btn-primary:hover{background:#5568d3}");
    html.append(".preview-frame{width:100%;height:600px;border:1px solid #ddd;border-radius:8px}");
    html.append(".image-preview{max-width:100%;border-radius:8px}");
    html.append(".file-info{background:#f0f0f0;padding:20px;border-radius:8px;margin-top:20px}");
    html.append(".file-info-row{display:flex;justify-content:space-between;padding:10px 0;border-bottom:1px solid #ddd}");
    html.append(".file-info-label{font-weight:600;color:#666}");
    html.append("</style>");
    html.append("</head><body>");
    
    // Header
    html.append("<div class='header'>");
    html.append("<div class='header-left'>");
    html.append("<div class='logo'>🔗</div>");
    html.append("<div class='title'><h1>Free Signal Hub</h1>");
    html.append("<div class='subtitle'>By IMMORTAL</div></div>");
    html.append("</div>");
    html.append("<div class='status-badge'>🟢 Connected</div>");
    html.append("</div>");
    
    // Container
    html.append("<div class='container'>");
    html.append("<div class='sidebar'>");
    html.append("<div class='sidebar-header'>AVAILABLE FILES</div>");
    
    if (fileMap.isEmpty()) {
        html.append("<div style='color:#888;padding:20px;text-align:center'>No files</div>");
    } else {
        for (Map.Entry<String, FileInfo> entry : fileMap.entrySet()) {
            FileInfo file = entry.getValue();
            String icon = getFileIcon(file.name);
            html.append("<div class='file-item' onclick='preview(\"");
            html.append(escapeJs(entry.getKey())).append("\",\"");
            html.append(escapeJs(file.name)).append("\",\"");
            html.append(file.mimeType).append("\",");
            html.append(file.size).append(")'>");
            html.append("<div class='file-name'>").append(icon).append(" ").append(escapeHtml(file.name)).append("</div>");
            html.append("<div class='file-size'>").append(formatSize(file.size)).append("</div>");
            html.append("</div>");
        }
    }
    
    html.append("</div>");
    html.append("<div class='preview-area' id='preview'>Select a file</div>");
    html.append("</div>");
    
    // JavaScript
    html.append("<script>");
    html.append("function preview(path,name,mime,size){");
    html.append("document.querySelectorAll('.file-item').forEach(e=>e.classList.remove('active'));");
    html.append("event.currentTarget.classList.add('active');");
    html.append("let h='<div class=\"preview-content\">';");
    html.append("h+='<div class=\"preview-header\">';");
    html.append("h+='<div class=\"preview-title\">'+name+'</div>';");
    html.append("h+='<div class=\"preview-actions\">';");
    html.append("h+='<a href=\"'+path+'\" download class=\"btn btn-primary\">Download</a>';");
    html.append("h+='</div></div>';");
    html.append("if(mime.startsWith('image/')){");
    html.append("h+='<img src=\"'+path+'\" class=\"image-preview\">';");
    html.append("}else if(mime.startsWith('video/')){");
    html.append("h+='<video controls style=\"width:100%;max-width:800px\"><source src=\"'+path+'\"></video>';");
    html.append("}else if(mime==='application/pdf'){");
    html.append("h+='<iframe src=\"'+path+'\" class=\"preview-frame\"></iframe>';");
    html.append("}else{");
    html.append("h+='<div style=\"text-align:center;padding:40px\"><h3>Preview not available</h3><p>Click Download to view</p></div>';");
    html.append("}");
    html.append("h+='<div class=\"file-info\">';");
    html.append("h+='<div class=\"file-info-row\"><span class=\"file-info-label\">Name:</span><span>'+name+'</span></div>';");
    html.append("h+='<div class=\"file-info-row\"><span class=\"file-info-label\">Size:</span><span>'+formatSize(size)+'</span></div>';");
    html.append("h+='<div class=\"file-info-row\"><span class=\"file-info-label\">Type:</span><span>'+mime+'</span></div>';");
    html.append("h+='</div></div>';");
    html.append("document.getElementById('preview').innerHTML=h;");
    html.append("}");
    html.append("function formatSize(b){");
    html.append("if(b<1024)return b+' B';");
    html.append("let k=1024,s=['B','KB','MB','GB'];");
    html.append("let i=Math.floor(Math.log(b)/Math.log(k));");
    html.append("return (b/Math.pow(k,i)).toFixed(2)+' '+s[i];");
    html.append("}");
    html.append("</script>");
    html.append("</body></html>");

    String response = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=UTF-8\r\n" +
            "Content-Length: " + html.length() + "\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n" +
            html.toString();

    out.write(response.getBytes(StandardCharsets.UTF_8));
    out.flush();
}

// Helper method for escaping JavaScript strings
private String escapeJs(String text) {
    return text.replace("\\", "\\\\")
               .replace("\"", "\\\"")
               .replace("'", "\\'")
               .replace("\n", "\\n")
               .replace("\r", "\\r");
}

// Helper methods
private String getFileIcon(String filename) {
    String lower = filename.toLowerCase();
    if (lower.endsWith(".pdf")) return "📕";
    if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "📘";
    if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return "📗";
    if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return "📙";
    if (lower.endsWith(".zip") || lower.endsWith(".rar")) return "📦";
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) return "🖼️";
    if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mkv")) return "🎬";
    if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac")) return "🎵";
    if (lower.endsWith(".txt")) return "📝";
    if (lower.endsWith(".java") || lower.endsWith(".py") || lower.endsWith(".js") || lower.endsWith(".html") || lower.endsWith(".css")) return "💻";
    return "📄";
}

private String escapeHtml(String text) {
    return text.replace("&", "&amp;")
               .replace("<", "&lt;")
               .replace(">", "&gt;")
               .replace("\"", "&quot;")
               .replace("'", "&#39;");
}

        private void sendFile(OutputStream out, FileInfo fileInfo) throws IOException {
    File file = new File(fileInfo.path);
    if (!file.exists()) {
        Log.e(TAG, "❌ File not found: " + fileInfo.path);
        send404(out);
        return;
    }

    try {
        // Send headers
        PrintWriter writer = new PrintWriter(out);
        writer.print("HTTP/1.1 200 OK\r\n");
        writer.print("Content-Type: " + fileInfo.mimeType + "\r\n");
        writer.print("Content-Length: " + file.length() + "\r\n");
        writer.print("Content-Disposition: inline; filename=\"" + fileInfo.name + "\"\r\n");
        writer.print("Accept-Ranges: bytes\r\n");
        writer.print("Cache-Control: no-cache\r\n");
        writer.print("Connection: close\r\n");
        writer.print("\r\n");
        writer.flush();

        // Send file content
        FileInputStream fis = new FileInputStream(file);
        byte[] buffer = new byte[8192];
        int bytesRead;
        long totalSent = 0;
        
        while ((bytesRead = fis.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            totalSent += bytesRead;
        }
        
        fis.close();
        out.flush();

        Log.d(TAG, "✅ Sent file: " + fileInfo.name + " (" + totalSent + " bytes)");
        
    } catch (Exception e) {
        Log.e(TAG, "❌ Error sending file: " + fileInfo.name, e);
        throw e;
    }
}

        private void send404(OutputStream out) throws IOException {
            String html = "<html><body style='font-family:Arial;text-align:center;padding:50px;background:#f5f5f5'>" +
                    "<div style='background:white;padding:40px;border-radius:15px;max-width:500px;margin:0 auto;box-shadow:0 4px 20px rgba(0,0,0,0.1)'>" +
                    "<h1 style='color:#dc3545;font-size:48px;margin-bottom:20px'>404</h1>" +
                    "<p style='color:#666;font-size:18px;margin-bottom:30px'>File not found</p>" +
                    "<a href='/' style='color:#667eea;text-decoration:none;font-size:16px;font-weight:bold'>← Back to file list</a>" +
                    "</div></body></html>";

            String response = "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: " + html.length() + "\r\n" +
                    "Connection: close\r\n\r\n" + html;

            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void send400(OutputStream out) throws IOException {
            String response = "HTTP/1.1 400 Bad Request\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private static String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            int exp = (int) (Math.log(bytes) / Math.log(1024));
            String pre = "KMGTPE".charAt(exp - 1) + "";
            return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), pre);
        }
    }
}