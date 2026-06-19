package com.filesharinghub.app;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "HttpServer")
public class HttpServerPlugin extends Plugin {
    
    private static final String TAG = "HttpServerPlugin";
    private static ServerThread serverThread;
    private Context context;
    private static final Map<String, FileInfo> fileMap = new ConcurrentHashMap<>();
    private static volatile boolean twoWayTransferDisabled = false;
    
    // File information holder
    static class FileInfo {
        final String filePath;
        final String fileName;
        final long fileSize;
        final String mimeType;
        
        FileInfo(String path, String name, long size, String mime) {
            this.filePath = path;
            this.fileName = name;
            this.fileSize = size;
            this.mimeType = mime;
        }
    }
    
    // Set context from MainActivity
    public void setContext(Context ctx) {
        this.context = ctx;
        Log.d(TAG, "✅ Context set");
    }
    
    public void setTwoWayTransferDisabled(boolean disabled) {
        twoWayTransferDisabled = disabled;
        Log.d(TAG, "🔒 Two-way transfer disabled status changed to: " + disabled);
    }
    
    // Start server (called from MainActivity)
    public String startServerSync(int port) {
        try {
            if (serverThread != null && serverThread.isAlive()) {
                return "{\"success\":false,\"error\":\"Server already running\"}";
            }
            
            // Clean up any residual cached uploads from a previous run to free memory
            clearUploadedFiles(this.context);
            
            String ipAddress = getLocalIpAddress();
            if (ipAddress == null) {
                return "{\"success\":false,\"error\":\"No local network connection detected. Please connect to a Wi-Fi network or turn on your mobile hotspot, and ensure the other device is connected to the same network. A shared local network is required to establish a connection; without it, there is no medium to transfer files. (Since no medium is found to transfer, the server cannot start)\"}";
            }
            
            ServerSocket socket = null;
            // Prevent using system/preassigned ports (0 - 1023)
            int activePort = port;
            if (activePort < 1024) {
                activePort = 8080;
            }
            
            while (activePort <= 65535) {
                try {
                    socket = new ServerSocket(activePort);
                    break;
                } catch (IOException e) {
                    activePort++;
                }
            }
            
            if (socket == null) {
                return "{\"success\":false,\"error\":\"No available port found\"}";
            }
            
            serverThread = new ServerThread(socket, context);
            serverThread.start();
            
            Log.d(TAG, "✅ Server started at " + ipAddress + ":" + activePort);
            
            return "{\"success\":true,\"ip\":\"" + ipAddress + "\",\"port\":" + activePort + "}";
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Server start error", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // Stop server
    public void stopServerSync() {
        stopServerSyncStatic();
        clearUploadedFiles(this.context);
    }
    
    public static void stopServerSyncStatic() {
        try {
            if (serverThread != null) {
                serverThread.stopServer();
                serverThread = null;
            }
            fileMap.clear();
            twoWayTransferDisabled = false;
            Log.d(TAG, "✅ Server stopped statically");
        } catch (Exception e) {
            Log.e(TAG, "❌ Static stop error", e);
        }
    }

    // Helper method to clear all files inside context.getCacheDir() / uploaded_files
    public static void clearUploadedFiles(Context ctx) {
        try {
            if (ctx != null) {
                File uploadDir = new File(ctx.getCacheDir(), "uploaded_files");
                if (uploadDir.exists() && uploadDir.isDirectory()) {
                    File[] files = uploadDir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.delete()) {
                                Log.d(TAG, "🗑️ Deleted cached upload file: " + file.getName());
                            } else {
                                Log.w(TAG, "⚠️ Failed to delete cached upload file: " + file.getName());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing uploaded files: " + e.getMessage());
        }
    }
    

    
    // Add file to be served
    public String addFileSync(String path, String virtualPath, String name, long size, String mimeType) {
        try {
            if (path.startsWith("content://")) {
                // Verify we can open the stream
                try (InputStream is = context.getContentResolver().openInputStream(android.net.Uri.parse(path))) {
                    if (is == null) {
                        return "{\"success\":false,\"error\":\"Cannot open content URI\"}";
                    }
                }
            } else {
                File file = new File(path);
                if (!file.exists()) {
                    return "{\"success\":false,\"error\":\"File not found\"}";
                }
            }
            
            fileMap.put(virtualPath, new FileInfo(path, name, size, mimeType));
            Log.d(TAG, "✅ File registered: " + name);
            return "{\"success\":true}";
            
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // Remove file from server dynamically
    public String removeFileSync(String virtualPath) {
        try {
            if (fileMap.containsKey(virtualPath)) {
                fileMap.remove(virtualPath);
                Log.d(TAG, "🗑️ File dynamically unregistered: " + virtualPath);
                return "{\"success\":true}";
            }
            return "{\"success\":false,\"error\":\"File not found\"}";
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // Get local IP address
    private String getLocalIpAddress() {
        // 1. Try ConnectivityManager (Modern Android standard, permission-free active link check)
        try {
            if (context != null) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork != null) {
                        android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
                        if (capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                                && !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                                && !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            Log.d(TAG, "ConnectivityManager: Active network is CELLULAR only, skipping to interface enumeration.");
                        } else {
                            android.net.LinkProperties linkProperties = cm.getLinkProperties(activeNetwork);
                            if (linkProperties != null) {
                                for (android.net.LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                                    InetAddress address = linkAddress.getAddress();
                                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                                        String ip = address.getHostAddress();
                                        Log.d(TAG, "📶 ConnectivityManager IP resolved: " + ip);
                                        return ip;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ConnectivityManager IP resolution failed: " + e.getMessage());
        }

        // 2. Try WifiManager fallback (for older Wi-Fi client mode)
        try {
            if (context != null) {
                android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null && wifiManager.isWifiEnabled()) {
                    int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
                    if (ipAddress != 0) {
                        String ip = String.format(Locale.US, "%d.%d.%d.%d",
                            (ipAddress & 0xff),
                            (ipAddress >> 8 & 0xff),
                            (ipAddress >> 16 & 0xff),
                            (ipAddress >> 24 & 0xff)
                        );
                        Log.d(TAG, "📶 WifiManager IP resolved: " + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "WifiManager IP resolution failed: " + e.getMessage());
        }

        // 3. Fall back to interface enumeration (prioritizing wlan/ap/hotspot interfaces)
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            
            // Prioritize Wi-Fi and Hotspot interfaces first
            for (NetworkInterface intf : interfaces) {
                String name = intf.getName().toLowerCase();
                if (name.contains("rmnet") || name.contains("pdp") || name.contains("ccmni") || name.contains("epdg") || name.contains("dummy")) {
                    continue;
                }
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap") || name.startsWith("p2p") || name.startsWith("eth") || name.startsWith("rndis")) {
                    for (InetAddress inetAddress : Collections.list(intf.getInetAddresses())) {
                        if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                            String ip = inetAddress.getHostAddress();
                            Log.d(TAG, "📶 Interface " + intf.getName() + " IP resolved: " + ip);
                            return ip;
                        }
                    }
                }
            }
            
            // Generic fallback to any non-loopback, non-cellular IPv4
            for (NetworkInterface intf : interfaces) {
                String name = intf.getName().toLowerCase();
                if (name.contains("rmnet") || name.contains("pdp") || name.contains("ccmni") || name.contains("epdg") || name.contains("dummy")) {
                    continue;
                }
                for (InetAddress inetAddress : Collections.list(intf.getInetAddresses())) {
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        String ip = inetAddress.getHostAddress();
                        Log.d(TAG, "📶 Fallback Interface " + intf.getName() + " IP resolved: " + ip);
                        return ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "IP address error", e);
        }
        return null;
    }



    // ================== SERVER THREAD ==================
    private static class ServerThread extends Thread {
        private final int port;
        private final Context context;
        private final ServerSocket serverSocket;
        private volatile boolean running = true;
        
        ServerThread(ServerSocket socket, Context context) {
            this.serverSocket = socket;
            this.port = socket.getLocalPort();
            this.context = context;
        }
        
        @Override
        public void run() {
            try {
                Log.d(TAG, "🚀 Server listening on port " + port);
                
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        new RequestHandler(clientSocket, context).start();
                    } catch (IOException e) {
                        if (running) {
                            Log.e(TAG, "Accept error", e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ Server error", e);
            }
        }
        
        void stopServer() {
            running = false;
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Stop error", e);
            }
        }
    }
    
    // ================== REQUEST HANDLER ==================
    private static class RequestHandler extends Thread {
        private final Socket socket;
        private final Context context;
        
        RequestHandler(Socket socket, Context context) {
            this.socket = socket;
            this.context = context;
        }
        
        @Override
        public void run() {
            BufferedReader in = null;
            OutputStream out = null;
            
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = socket.getOutputStream();
                
                // Read request line
                String requestLine = in.readLine();
                if (requestLine == null) return;
                
                Log.d(TAG, "📨 Request: " + requestLine);
                
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;
                
                String method = parts[0];
                String path = parts[1];
                
                // Route the request
                if (path.equals("/") || path.equals("/index.html")) {
                    sendHomePage(out);
                } else if (path.equals("/icon.png")) {
                    serveIcon(out);
                } else if (path.equals("/send")) {
                    sendSendPage(out);
                } else if (path.equals("/receive")) {
                    sendReceivePage(out);
                } else if (path.equals("/api/peers")) {
                    sendPeersApi(out);
                } else if (path.startsWith("/api/upload")) {
                    handleUpload(in, out);
                } else if (path.startsWith("/download/")) {
                    handleDownload(path, out);
                } else if (fileMap.containsKey(path)) {
                    serveFile(path, out);
                } else {
                    send404(out);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Request error", e);
            } finally {
                closeQuietly(in);
                closeQuietly(out);
                closeQuietly(socket);
            }
        }

        private void serveIcon(OutputStream out) {
            try {
                InputStream is = context.getAssets().open("public/icon.png");
                PrintWriter writer = new PrintWriter(out);
                writer.print("HTTP/1.1 200 OK\r\n");
                writer.print("Content-Type: image/png\r\n");
                writer.print("Connection: close\r\n");
                writer.print("\r\n");
                writer.flush();
                
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                is.close();
                out.flush();
            } catch (Exception e) {
                Log.e(TAG, "Error serving icon", e);
                send404(out);
            }
        }

        // ================== UPLOAD HANDLER ==================
        private void handleUpload(BufferedReader in, OutputStream out) {
            try {
                Log.d(TAG, "📤 Upload request received");
                
                if (twoWayTransferDisabled) {
                    sendJsonError(out, "Two-way transfer has been disabled by the server host.");
                    return;
                }
                
                // Read headers
                int contentLength = 0;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.startsWith("Content-Length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
                
                if (contentLength == 0) {
                    sendJsonError(out, "No content");
                    return;
                }
                
                Log.d(TAG, "Content-Length: " + contentLength);
                
                // Read body
                char[] buffer = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = in.read(buffer, totalRead, contentLength - totalRead);
                    if (read == -1) break;
                    totalRead += read;
                }
                
                String body = new String(buffer, 0, totalRead);
                
                // Parse JSON
                JSONObject json = new JSONObject(body);
                String filename = json.getString("filename");
                String base64Data = json.getString("data");
                
                Log.d(TAG, "Uploading: " + filename);
                
                // Decode Base64
                byte[] fileBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
                
                // Save file
                File uploadDir = new File(context.getCacheDir(), "uploaded_files");
                uploadDir.mkdirs();
                
                File file = new File(uploadDir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(fileBytes);
                fos.close();
                
                // Register file
                String virtualPath = "/" + filename;
                fileMap.put(virtualPath, new FileInfo(
                    file.getAbsolutePath(),
                    filename,
                    file.length(),
                    "application/octet-stream"
                ));
                
                Log.d(TAG, "✅ Upload complete: " + filename + " (" + file.length() + " bytes)");
                
                // Send success response
                String response = "{\"success\":true,\"filename\":\"" + filename + "\"}";
                sendJsonResponse(out, response);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Upload error", e);
                sendJsonError(out, e.getMessage());
            }
        }
        
        // ================== DOWNLOAD HANDLER ==================
        private void handleDownload(String path, OutputStream out) {
            try {
                // Parse: /download/host/filename
                String[] parts = path.split("/");
                if (parts.length < 4) {
                    send404(out);
                    return;
                }
                
                String filename = URLDecoder.decode(parts[3], "UTF-8");
                String virtualPath = "/" + filename;
                
                FileInfo fileInfo = fileMap.get(virtualPath);
                if (fileInfo == null) {
                    Log.e(TAG, "❌ File not found: " + virtualPath);
                    send404(out);
                    return;
                }
                
                serveFile(virtualPath, out);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Download error", e);
                send404(out);
            }
        }
        
        // ================== FILE SERVING ==================
        private void serveFile(String virtualPath, OutputStream out) {
            try {
                FileInfo fileInfo = fileMap.get(virtualPath);
                if (fileInfo == null) {
                    send404(out);
                    return;
                }
                
                InputStream fis = null;
                long fileLength = fileInfo.fileSize;
                
                if (fileInfo.filePath.startsWith("content://")) {
                    fis = context.getContentResolver().openInputStream(android.net.Uri.parse(fileInfo.filePath));
                } else {
                    File file = new File(fileInfo.filePath);
                    if (!file.exists()) {
                        Log.e(TAG, "❌ Physical file not found: " + fileInfo.filePath);
                        send404(out);
                        return;
                    }
                    fileLength = file.length();
                    fis = new FileInputStream(file);
                }
                
                if (fis == null) {
                    send404(out);
                    return;
                }
                
                // Send headers
                PrintWriter writer = new PrintWriter(out);
                writer.print("HTTP/1.1 200 OK\r\n");
                writer.print("Content-Type: " + fileInfo.mimeType + "\r\n");
                writer.print("Content-Length: " + fileLength + "\r\n");
                writer.print("Content-Disposition: attachment; filename=\"" + fileInfo.fileName + "\"\r\n");
                writer.print("Connection: close\r\n");
                writer.print("\r\n");
                writer.flush();
                
                // Send file
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                fis.close();
                out.flush();
                
                Log.d(TAG, "✅ Served: " + fileInfo.fileName);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Serve error", e);
            }
        }

        // ================== HOME PAGE ==================
        private void sendHomePage(OutputStream out) throws IOException {
            String html = "<!DOCTYPE html><html><head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>File Sharing Hub</title>" +
                "<link href='https://fonts.googleapis.com/css2?family=Outfit:wght@600;700;800&family=Inter:wght@400;500;600&display=swap' rel='stylesheet'>" +
                "<style>" +
                "body{font-family:'Inter',sans-serif;background:radial-gradient(circle at top right,#1d1e2e,#0e0f19);color:#fff;min-height:100vh;display:flex;align-items:center;justify-content:center;margin:0;padding:20px;box-sizing:border-box}" +
                ".card{background:rgba(30,31,48,0.6);border:1px solid rgba(255,255,255,0.08);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);padding:40px;border-radius:20px;text-align:center;max-width:400px;width:100%;box-shadow:0 8px 32px 0 rgba(0,0,0,0.37)}" +
                "h1{margin:0 0 5px 0;font-family:'Outfit',sans-serif;font-size:32px;font-weight:800;background:linear-gradient(135deg,#ffffff 40%,#a78bfa 100%);-webkit-background-clip:text;-webkit-text-fill-color:transparent;text-shadow:0 4px 20px rgba(167,139,250,0.25);letter-spacing:-0.5px;}" +
                ".developer-credit{font-size:13px;color:#a0aec0;margin-bottom:15px}" +
                ".developer-name{color:#a78bfa;font-weight:700;text-shadow:0 0 8px rgba(167,139,250,0.3)}" +
                ".btn{display:block;background:linear-gradient(135deg,#9f7aea 0%,#667eea 100%);color:white;padding:15px;border-radius:12px;text-decoration:none;margin:12px 0;font-size:16px;font-weight:600;transition:all 0.2s;text-align:center}" +
                ".btn:hover{transform:translateY(-2px);box-shadow:0 4px 15px rgba(102,126,234,0.4)}" +
                ".btn:active{transform:translateY(0)}" +
                ".btn-help {display:inline-flex;align-items:center;gap:8px;background:rgba(255,255,255,0.05);border:1px solid rgba(255,255,255,0.1);color:#cbd5e0;padding:10px 20px;border-radius:30px;font-size:13px;font-weight:600;cursor:pointer;margin-top:0;margin-bottom:15px;transition:all 0.2s ease;}" +
                ".btn-help:hover {background:rgba(255, 255, 255, 0.1);color:#fff;border-color:rgba(255,255,255,0.2);}" +
                ".modal {position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(14,15,25,0.85);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);display:flex;align-items:center;justify-content:center;z-index:1000;opacity:0;pointer-events:none;transition:opacity 0.3s ease;padding:20px;}" +
                ".modal.show {opacity:1;pointer-events:auto;}" +
                ".modal-content {background:#1e1f30;border:1px solid rgba(255,255,255,0.1);border-radius:20px;width:100%;max-width:500px;padding:30px;box-shadow:0 20px 40px rgba(0,0,0,0.5);transform:translateY(20px);transition:transform 0.3s ease;text-align:left;}" +
                ".modal.show .modal-content {transform:translateY(0);}" +
                ".modal-header {display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;border-bottom:1px solid rgba(255,255,255,0.08);padding-bottom:15px;}" +
                ".modal-header h2 {font-family:'Outfit',sans-serif;font-size:20px;margin:0;color:#a78bfa;}" +
                ".modal-close-icon {background:none;border:none;color:#a0aec0;font-size:24px;cursor:pointer;line-height:1;}" +
                ".modal-close-icon:hover {color:#fff;}" +
                ".modal-body {font-size:14px;line-height:1.6;color:#cbd5e0;}" +
                ".modal-body p {margin-bottom:12px;}" +
                ".modal-footer {margin-top:25px;display:flex;justify-content:flex-end;}" +
                ".btn-modal-close {background:linear-gradient(135deg,#9f7aea 0%,#667eea 100%);color:white;padding:10px 24px;border:none;border-radius:8px;font-weight:600;cursor:pointer;font-size:14px;}" +
                ".guide-steps {display:flex;flex-direction:column;gap:15px;margin-top:10px;}" +
                ".guide-step {display:flex;gap:15px;align-items:flex-start;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.05);border-radius:12px;padding:12px;transition:all 0.2s ease;}" +
                ".guide-step:hover {background:rgba(255,255,255,0.05);border-color:rgba(167,139,250,0.2);transform:translateY(-1px);}" +
                ".guide-step.border-highlight {border:1px dashed rgba(239,68,68,0.4);background:rgba(239,68,68,0.03);}" +
                ".guide-step.border-highlight:hover {border-color:rgba(239,68,68,0.6);background:rgba(239,68,68,0.05);}" +
                ".step-num {width:28px;height:28px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:13px;flex-shrink:0;color:#fff;box-shadow:0 2px 8px rgba(0,0,0,0.2);}" +
                ".bg-purple {background:linear-gradient(135deg,#a78bfa 0%,#7c3aed 100%);}" +
                ".bg-red {background:linear-gradient(135deg,#ef4444 0%,#dc2626 100%);}" +
                ".step-content {flex:1;}" +
                ".step-content h4 {margin:0 0 4px 0;font-family:'Outfit',sans-serif;font-size:14px;font-weight:600;color:#fff;}" +
                ".step-content p {margin:0;font-size:12px;line-height:1.5;color:#cbd5e0;}" +
                ".highlight-cyan {color:#38bdf8;font-weight:600;}" +
                ".highlight-purple {color:#c084fc;font-weight:600;}" +
                ".highlight-green {color:#34d399;font-weight:600;}" +
                ".highlight-red {color:#fca5a5;font-weight:600;}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<h1>File Sharing Hub</h1>" +
                "<div class='developer-credit'>By <span class='developer-name'>Immortal</span></div>" +
                "<button onclick='openHelpModal()' class='btn-help'>❓ How to Use</button>" +
                "<a href='/send' id='sendBtn' class='btn' onclick='handleSendClick(event)'>Send Files</a>" +
                "<a href='/receive' class='btn'>Receive Files</a>" +
                "</div>" +
                "<div id='helpModal' class='modal'>" +
                "<div class='modal-content'>" +
                "<div class='modal-header'>" +
                "<h2>❓ How to Use (User)</h2>" +
                "<button class='modal-close-icon' onclick='closeHelpModal()'>&times;</button>" +
                "</div>" +
                "<div class='modal-body'>" +
                "<div class='guide-steps'>" +
                "<div class='guide-step'>" +
                "<span class='step-num bg-purple'>1</span>" +
                "<div class='step-content'>" +
                "<h4>Download Shared Files</h4>" +
                "<p>Click <span class='highlight-green'>Receive Files</span> to view and download files shared by the host or other clients.</p>" +
                "</div>" +
                "</div>" +
                "<div class='guide-step'>" +
                "<span class='step-num bg-purple'>2</span>" +
                "<div class='step-content'>" +
                "<h4>Upload Files</h4>" +
                "<p>Click <span class='highlight-purple'>Send Files</span> to share files from your device. Note: the host can disable this block at any time.</p>" +
                "</div>" +
                "</div>" +
                "<div class='guide-step'>" +
                "<span class='step-num bg-purple'>3</span>" +
                "<div class='step-content'>" +
                "<h4>Real-time Monitoring</h4>" +
                "<p>All shared files are <span class='highlight-cyan'>monitored and can be removed</span> by the server host in real-time.</p>" +
                "</div>" +
                "</div>" +
                "<div class='guide-step border-highlight'>" +
                "<span class='step-num bg-red'>🔒</span>" +
                "<div class='step-content'>" +
                "<h4>Offline Security & Deletion</h4>" +
                "<p>The network is completely offline. Once the host stops the server, <span class='highlight-red'>all uploaded files are immediately deleted</span> from the host's storage to protect privacy and clear cache.</p>" +
                "</div>" +
                "</div>" +
                "</div>" +
                "</div>" +
                "<div class='modal-footer'>" +
                "<button class='btn-modal-close' onclick='closeHelpModal()'>Got it</button>" +
                "</div>" +
                "</div>" +
                "</div>" +
                "<div id='disabledModal' class='modal'>" +
                "<div class='modal-content'>" +
                "<div class='modal-header'>" +
                "<h2>⚠️ Two-way Transfer Disabled</h2>" +
                "<button class='modal-close-icon' onclick='closeDisabledModal()'>&times;</button>" +
                "</div>" +
                "<div class='modal-body'>" +
                "<p>The server host has disabled 2-way transfer. You can only receive the files by navigating to the receive files button.</p>" +
                "<p style='margin-top:12px;color:#a78bfa;font-weight:600;'>Please contact the server host to turn on the two-way transfer if needed.</p>" +
                "</div>" +
                "<div class='modal-footer'>" +
                "<button class='btn-modal-close' onclick='closeDisabledModal()'>Close</button>" +
                "</div>" +
                "</div>" +
                "</div>" +
                "<script>" +
                "let isTwoWayDisabled=false;" +
                "function openHelpModal(){document.getElementById('helpModal').classList.add('show');}" +
                "function closeHelpModal(){document.getElementById('helpModal').classList.remove('show');}" +
                "function showDisabledModal(){document.getElementById('disabledModal').classList.add('show');}" +
                "function closeDisabledModal(){document.getElementById('disabledModal').classList.remove('show');}" +
                "function handleSendClick(e){" +
                "if(isTwoWayDisabled){" +
                "e.preventDefault();" +
                "showDisabledModal();" +
                "}" +
                "}" +
                "async function checkStatus(){" +
                "try{" +
                "const res=await fetch('/api/peers');" +
                "const data=await res.json();" +
                "isTwoWayDisabled=data.twoWayTransferDisabled||false;" +
                "}catch(e){console.error(e);}" +
                "}" +
                "checkStatus();" +
                "setInterval(checkStatus,2000);" +
                "document.addEventListener('click',function(e){" +
                "const hm=document.getElementById('helpModal');" +
                "const dm=document.getElementById('disabledModal');" +
                "if(e.target===hm)closeHelpModal();" +
                "if(e.target===dm)closeDisabledModal();" +
                "});" +
                "</script>" +
                "</body></html>";

            sendHtmlResponse(out, html);
        }
        
        // ================== SEND PAGE ==================
        private void sendSendPage(OutputStream out) throws IOException {
            String html = "<!DOCTYPE html><html><head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>Send Files</title>" +
                "<link href='https://fonts.googleapis.com/css2?family=Outfit:wght@600;700&family=Inter:wght@400;500;600&display=swap' rel='stylesheet'>" +
                "<style>" +
                "body{font-family:'Inter',sans-serif;background:radial-gradient(circle at top right,#1d1e2e,#0e0f19);color:#fff;min-height:100vh;padding:20px;margin:0;box-sizing:border-box}" +
                ".card{background:rgba(30,31,48,0.6);border:1px solid rgba(255,255,255,0.08);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);padding:30px;border-radius:20px;max-width:600px;margin:0 auto;box-shadow:0 8px 32px 0 rgba(0,0,0,0.37)}" +
                ".header{display:flex;align-items:center;gap:15px;margin-bottom:25px}" +
                ".app-icon{width:45px;height:45px;border-radius:10px;box-shadow:0 4px 15px rgba(159,122,234,0.3)}" +
                "h1{margin:0;font-family:'Outfit',sans-serif;font-size:24px;font-weight:700;flex:1}" +
                ".upload-zone{border:2px dashed rgba(255,255,255,0.2);border-radius:12px;padding:50px 20px;text-align:center;cursor:pointer;margin:20px 0;transition:all 0.2s}" +
                ".upload-zone:hover{border-color:#9f7aea;background:rgba(159,122,234,0.05)}" +
                ".file-list{margin:20px 0}" +
                ".file-item{background:rgba(255,255,255,0.05);padding:12px 18px;border-radius:8px;margin:8px 0;display:flex;justify-content:space-between;align-items:center;border:1px solid rgba(255,255,255,0.03)}" +
                ".btn{background:linear-gradient(135deg,#9f7aea 0%,#667eea 100%);color:white;padding:12px 24px;border:none;border-radius:10px;cursor:pointer;font-size:16px;font-weight:600;width:100%;transition:all 0.2s;text-align:center;text-decoration:none;display:block;box-sizing:border-box}" +
                ".btn:hover{transform:translateY(-1px);box-shadow:0 4px 15px rgba(102,126,234,0.4)}" +
                ".btn:active{transform:translateY(0)}" +
                ".btn:disabled{opacity:0.5;cursor:not-allowed;transform:none;box-shadow:none}" +
                ".status{padding:12px;border-radius:8px;margin-top:15px;display:none;font-weight:600}" +
                ".status.show{display:block}" +
                ".status.success{background:rgba(40,167,69,0.2);color:#2fa84d;border:1px solid rgba(40,167,69,0.3)}" +
                ".back{color:#a78bfa;text-decoration:none;display:inline-flex;align-items:center;gap:5px;font-weight:600;margin-bottom:15px;transition:color 0.2s}" +
                ".back:hover{color:#c084fc}" +
                ".modal {position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(14,15,25,0.85);backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);display:flex;align-items:center;justify-content:center;z-index:1000;opacity:0;pointer-events:none;transition:opacity 0.3s ease;padding:20px;}" +
                ".modal.show {opacity:1;pointer-events:auto;}" +
                ".modal-content {background:#1e1f30;border:1px solid rgba(255,255,255,0.1);border-radius:20px;width:100%;max-width:500px;padding:30px;box-shadow:0 20px 40px rgba(0,0,0,0.5);transform:translateY(20px);transition:transform 0.3s ease;}" +
                ".modal.show .modal-content {transform:translateY(0);}" +
                ".modal-header {display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;border-bottom:1px solid rgba(255,255,255,0.08);padding-bottom:15px;}" +
                ".modal-header h2 {font-family:'Outfit',sans-serif;font-size:20px;margin:0;color:#a78bfa;text-align:left;}" +
                ".modal-close-icon {background:none;border:none;color:#a0aec0;font-size:24px;cursor:pointer;line-height:1;}" +
                ".modal-close-icon:hover {color:#fff;}" +
                ".modal-body {font-size:14px;line-height:1.6;color:#cbd5e0;text-align:left;}" +
                ".modal-body p {margin-bottom:12px;}" +
                ".modal-footer {margin-top:25px;display:flex;justify-content:flex-end;}" +
                ".btn-modal-close {background:linear-gradient(135deg,#9f7aea 0%,#667eea 100%);color:white;padding:10px 24px;border:none;border-radius:8px;font-weight:600;cursor:pointer;font-size:14px;}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<a href='/' class='back'>← Back</a>" +
                "<div class='header'>" +
                "<h1>Send Files</h1>" +
                "</div>" +
                "<input type='file' id='input' multiple style='display:none'>" +
                "<div class='upload-zone' id='uploadZone' onclick='handleZoneClick()'>" +
                "<div style='font-size:40px;margin-bottom:10px'>📁</div>" +
                "<div style='font-weight:500'>Click to select files</div>" +
                "</div>" +
                "<div class='file-list' id='list'></div>" +
                "<button class='btn' id='uploadBtn' style='display:none' onclick='upload()'>Upload Files</button>" +
                "<div class='status' id='status'></div>" +
                "</div>" +
                "<div id='disabledModal' class='modal'>" +
                "<div class='modal-content'>" +
                "<div class='modal-header'>" +
                "<h2>⚠️ Two-way Transfer Disabled</h2>" +
                "<button class='modal-close-icon' onclick='closeDisabledModal()'>&times;</button>" +
                "</div>" +
                "<div class='modal-body'>" +
                "<p>The server host has disabled 2-way transfer. You can only receive the files by navigating to the receive files button.</p>" +
                "<p style='margin-top:12px;color:#a78bfa;font-weight:600;'>Please contact the server host to turn on the two-way transfer if needed.</p>" +
                "</div>" +
                "<div class='modal-footer' style='gap:10px;display:flex;justify-content:flex-end;width:100%;'>" +
                "<a href='/receive' class='btn' style='margin:0;padding:10px 20px;font-size:14px;width:auto;background:linear-gradient(135deg,#10b981 0%,#059669 100%);'>Go to Receive</a>" +
                "<button class='btn-modal-close' onclick='closeDisabledModal()'>Close</button>" +
                "</div>" +
                "</div>" +
                "</div>" +
                "<script>" +
                "let files=[];" +
                "let isTwoWayDisabled=false;" +
                "document.getElementById('input').onchange=(e)=>{" +
                "if(isTwoWayDisabled){" +
                "showDisabledModal();" +
                "return;" +
                "}" +
                "files=Array.from(e.target.files);" +
                "updateList();" +
                "};" +
                "function handleZoneClick(){" +
                "if(isTwoWayDisabled){" +
                "showDisabledModal();" +
                "}else{" +
                "document.getElementById('input').click();" +
                "}" +
                "}" +
                "function updateList(){" +
                "const list=document.getElementById('list');" +
                "const btn=document.getElementById('uploadBtn');" +
                "if(files.length===0){list.innerHTML='';btn.style.display='none';return;}" +
                "list.innerHTML=files.map(f=>`<div class='file-item'><span>${f.name}</span><span>${formatSize(f.size)}</span></div>`).join('');" +
                "btn.style.display='block';" +
                "}" +
                "async function upload(){" +
                "if(isTwoWayDisabled){" +
                "showDisabledModal();" +
                "return;" +
                "}" +
                "const btn=document.getElementById('uploadBtn');" +
                "const status=document.getElementById('status');" +
                "btn.disabled=true;" +
                "status.className='status show';" +
                "status.textContent='Uploading...';" +
                "let success=0;" +
                "for(const file of files){" +
                "try{" +
                "const base64=await new Promise((resolve)=>{" +
                "const reader=new FileReader();" +
                "reader.onload=()=>resolve(reader.result.split(',')[1]);" +
                "reader.readAsDataURL(file);" +
                "});" +
                "const res=await fetch('/api/upload',{" +
                "method:'POST'," +
                "headers:{'Content-Type':'application/json'}," +
                "body:JSON.stringify({filename:file.name,data:base64})" +
                "});" +
                "const result=await res.json();" +
                "if(result.success)success++;" +
                "else{" +
                "if(result.error && result.error.includes('disabled')){" +
                "isTwoWayDisabled=true;" +
                "showDisabledModal();" +
                "break;" +
                "}" +
                "throw new Error(result.error);" +
                "}" +
                "}catch(e){" +
                "status.textContent='⚠️ '+(e.message||'Upload failed');" +
                "btn.disabled=false;" +
                "return;" +
                "}" +
                "}" +
                "status.className='status show success';" +
                "status.textContent='✅ Uploaded '+success+' file(s)!';" +
                "setTimeout(()=>{files=[];updateList();status.className='status';},3000);" +
                "btn.disabled=false;" +
                "}" +
                "function formatSize(b){" +
                "if(b<1024)return b+' B';" +
                "const k=1024,s=['KB','MB','GB'];" +
                "const i=Math.floor(Math.log(b)/Math.log(k));" +
                "return(b/Math.pow(k,i)).toFixed(1)+' '+s[i-1];" +
                "}" +
                "function showDisabledModal(){document.getElementById('disabledModal').classList.add('show');}" +
                "function closeDisabledModal(){document.getElementById('disabledModal').classList.remove('show');}" +
                "async function checkStatus(){" +
                "try{" +
                "const res=await fetch('/api/peers');" +
                "const data=await res.json();" +
                "isTwoWayDisabled=data.twoWayTransferDisabled||false;" +
                "const uploadBtn=document.getElementById('uploadBtn');" +
                "const zone=document.getElementById('uploadZone');" +
                "if(isTwoWayDisabled){" +
                "if(uploadBtn)uploadBtn.disabled=true;" +
                "if(zone)zone.style.opacity='0.5';" +
                "showDisabledModal();" +
                "}else{" +
                "if(uploadBtn)uploadBtn.disabled=false;" +
                "if(zone)zone.style.opacity='1';" +
                "closeDisabledModal();" +
                "}" +
                "}catch(e){console.error(e);}" +
                "}" +
                "checkStatus();" +
                "setInterval(checkStatus,2000);" +
                "document.addEventListener('click',function(e){" +
                "const dm=document.getElementById('disabledModal');" +
                "if(e.target===dm)closeDisabledModal();" +
                "});" +
                "</script></body></html>";
            
            sendHtmlResponse(out, html);
        }

        // ================== RECEIVE PAGE ==================
        private void sendReceivePage(OutputStream out) throws IOException {
            String html = "<!DOCTYPE html><html><head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>Receive Files</title>" +
                "<link href='https://fonts.googleapis.com/css2?family=Outfit:wght@600;700&family=Inter:wght@400;500;600&display=swap' rel='stylesheet'>" +
                "<style>" +
                "body{font-family:'Inter',sans-serif;background:radial-gradient(circle at top right,#1d1e2e,#0e0f19);color:#fff;min-height:100vh;padding:20px;margin:0;box-sizing:border-box}" +
                ".card{background:rgba(30,31,48,0.6);border:1px solid rgba(255,255,255,0.08);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);padding:30px;border-radius:20px;max-width:800px;margin:0 auto;box-shadow:0 8px 32px 0 rgba(0,0,0,0.37)}" +
                ".header{display:flex;align-items:center;gap:15px;margin-bottom:25px}" +
                ".app-icon{width:45px;height:45px;border-radius:10px;box-shadow:0 4px 15px rgba(159,122,234,0.3)}" +
                "h1{margin:0;font-family:'Outfit',sans-serif;font-size:24px;font-weight:700;flex:1}" +
                ".file-list{display:grid;gap:12px;margin-top:20px}" +
                ".file-item{background:rgba(255,255,255,0.05);padding:15px 20px;border-radius:8px;display:flex;justify-content:space-between;align-items:center;border:1px solid rgba(255,255,255,0.03)}" +
                ".download-btn{background:linear-gradient(135deg,#9f7aea 0%,#667eea 100%);color:white;padding:8px 18px;border-radius:8px;text-decoration:none;font-weight:600;transition:all 0.2s;font-size:14px;display:inline-block}" +
                ".download-btn:hover{transform:translateY(-1px);box-shadow:0 4px 12px rgba(102,126,234,0.3)}" +
                ".download-btn:active{transform:translateY(0)}" +
                ".empty{text-align:center;padding:45px 20px;color:#a0aec0;font-size:15px}" +
                ".back{color:#a78bfa;text-decoration:none;display:inline-flex;align-items:center;gap:5px;font-weight:600;margin-bottom:15px;transition:color 0.2s}" +
                ".back:hover{color:#c084fc}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<a href='/' class='back'>← Back</a>" +
                "<div class='header'>" +
                "<h1>Receive Files</h1>" +
                "</div>" +
                "<div id='files'>Loading...</div>" +
                "</div>" +
                "<script>" +
                "async function loadFiles(){" +
                "try{" +
                "const res=await fetch('/api/peers');" +
                "const data=await res.json();" +
                "const container=document.getElementById('files');" +
                "if(!data.files || data.files.length===0){" +
                "container.innerHTML='<div class=\"empty\">📭 No files shared yet</div>';" +
                "return;" +
                "}" +
                "let html='<div class=\"file-list\">';" +
                "data.files.forEach(f=>{" +
                "const url='/download/host/'+encodeURIComponent(f.name);" +
                "html+=`<div class='file-item'>" +
                "<div><div style='font-weight:600'>📄 ${f.name}</div>" +
                "<div style='font-size:12px;color:#666'>${formatSize(f.size)}</div></div>" +
                "<a href='${url}' class='download-btn' download='${f.name}'>Download</a>" +
                "</div>`;" +
                "});" +
                "html+='</div>';" +
                "container.innerHTML=html;" +
                "}catch(e){" +
                "document.getElementById('files').innerHTML='<div class=\"empty\">❌ Error loading files</div>';" +
                "}" +
                "}" +
                "function formatSize(b){" +
                "if(b<1024)return b+' B';" +
                "const k=1024,s=['KB','MB','GB'];" +
                "const i=Math.floor(Math.log(b)/Math.log(k));" +
                "return(b/Math.pow(k,i)).toFixed(1)+' '+s[i-1];" +
                "}" +
                "loadFiles();" +
                "setInterval(loadFiles,5000);" +
                "</script></body></html>";
            
            sendHtmlResponse(out, html);
        }
        
        // ================== PEERS API ==================
        private void sendPeersApi(OutputStream out) throws IOException {
            try {
                JSONArray filesArray = new JSONArray();
                
                for (Map.Entry<String, FileInfo> entry : fileMap.entrySet()) {
                    FileInfo info = entry.getValue();
                    JSONObject fileObj = new JSONObject();
                    fileObj.put("name", info.fileName);
                    fileObj.put("size", info.fileSize);
                    fileObj.put("path", entry.getKey());
                    filesArray.put(fileObj);
                }
                
                JSONObject response = new JSONObject();
                response.put("files", filesArray);
                response.put("twoWayTransferDisabled", twoWayTransferDisabled);
                
                sendJsonResponse(out, response.toString());
                
            } catch (Exception e) {
                sendJsonError(out, e.getMessage());
            }
        }
        
        // ================== HELPER METHODS ==================
        private void sendHtmlResponse(OutputStream out, String html) throws IOException {
            String response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: " + html.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n\r\n" +
                html;
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        
        private void sendJsonResponse(OutputStream out, String json) throws IOException {
            String response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n" +
                json;
            out.write(response.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        
        private void sendJsonError(OutputStream out, String error) {
            try {
                String json = "{\"success\":false,\"error\":\"" + error.replace("\"", "'") + "\"}";
                sendJsonResponse(out, json);
            } catch (IOException e) {
                Log.e(TAG, "Error sending error response", e);
            }
        }
        
        private void send404(OutputStream out) {
            try {
                String response = "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Connection: close\r\n\r\n" +
                    "404 Not Found";
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                Log.e(TAG, "Error sending 404", e);
            }
        }
        
        private void closeQuietly(Closeable closeable) {
            if (closeable != null) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        
        private void closeQuietly(Socket socket) {
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
}