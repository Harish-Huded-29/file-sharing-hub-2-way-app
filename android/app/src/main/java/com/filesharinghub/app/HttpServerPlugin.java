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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@CapacitorPlugin(name = "HttpServer")
public class HttpServerPlugin extends Plugin {
    
    private static final String TAG = "HttpServerPlugin";
    private static ServerThread serverThread;
    private static LocalDnsResponder localDnsResponder;
    private Context context;
    private static final Map<String, FileInfo> fileMap = new ConcurrentHashMap<>();
    private static volatile boolean twoWayTransferDisabled = false;
    private static volatile String activeHostname = "filesharehub";
    
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
        return startServerSync(port, "filesharehub");
    }

    public String startServerSync(int port, String hostname) {
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
            
            String effectiveHostname = (hostname != null && !hostname.trim().isEmpty()) ? hostname.trim().toLowerCase(Locale.US) : "filesharehub";
            activeHostname = effectiveHostname;
            
            ServerSocket socket = null;
            // Prevent using system/preassigned ports (0 - 1023)
            int activePort = port;
            if (activePort < 1024) {
                activePort = 9090;
            }
            
            while (activePort <= 65535) {
                try {
                    socket = new ServerSocket(activePort, 250);
                    break;
                } catch (IOException e) {
                    activePort++;
                }
            }
            
            if (socket == null) {
                return "{\"success\":false,\"error\":\"No available port found\"}";
            }
            
            serverThread = new ServerThread(socket, context, effectiveHostname);
            serverThread.start();
            
            // Start Local Offline DNS Responder (mDNS + LLMNR + NsdManager)
            try {
                if (localDnsResponder == null) {
                    localDnsResponder = new LocalDnsResponder(this.context);
                }
                localDnsResponder.start(effectiveHostname, activePort, ipAddress);
            } catch (Exception dnsEx) {
                Log.w(TAG, "⚠️ Failed to initialize DNS responder: " + dnsEx.getMessage());
            }
            
            String dnsUrl = "http://" + effectiveHostname + ".local:" + activePort + "/";
            Log.d(TAG, "✅ Server started at " + ipAddress + ":" + activePort + " | DNS: " + dnsUrl);
            
            return "{\"success\":true,\"ip\":\"" + ipAddress + "\",\"port\":" + activePort + ",\"hostname\":\"" + effectiveHostname + "\",\"dnsUrl\":\"" + dnsUrl + "\"}";
            
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
            if (localDnsResponder != null) {
                localDnsResponder.stop();
                localDnsResponder = null;
            }
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



    // ================== HIGH-CONCURRENCY SERVER THREAD ==================
    private static class ServerThread extends Thread {
        private final int port;
        private final Context context;
        private final String hostname;
        private final ServerSocket serverSocket;
        private volatile boolean running = true;
        private final ThreadPoolExecutor threadPool;
        private final Semaphore downloadSemaphore = new Semaphore(24, true); // Fair queue for up to 24 simultaneous active file downloads
        private final Semaphore uploadSemaphore = new Semaphore(12, true);   // Fair queue for up to 12 simultaneous active file uploads
        
        ServerThread(ServerSocket socket, Context context, String hostname) {
            this.serverSocket = socket;
            this.port = socket.getLocalPort();
            this.context = context;
            this.hostname = hostname;
            
            // Queue capacity for 250 requests with 16 core threads and up to 64 peak worker threads
            BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(250);
            this.threadPool = new ThreadPoolExecutor(
                16,                         // core pool size (handles light requests instantly)
                64,                         // max pool size (handles bursts smoothly)
                30L, TimeUnit.SECONDS,     // idle thread keep-alive
                workQueue,
                new ThreadFactory() {
                    private final AtomicInteger threadId = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "HttpWorker-" + threadId.getAndIncrement());
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // Fallback gracefully under extreme spikes without crashing
            );
            this.threadPool.allowCoreThreadTimeOut(true);
        }
        
        @Override
        public void run() {
            try {
                Log.d(TAG, "🚀 High-concurrency server listening on port " + port + " (Backlog: 250, Workers: 16-64)");
                
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        clientSocket.setTcpNoDelay(true);
                        clientSocket.setSoTimeout(45000); // 45s socket timeout
                        clientSocket.setSendBufferSize(64 * 1024);
                        clientSocket.setReceiveBufferSize(64 * 1024);
                        
                        threadPool.execute(new RequestHandler(clientSocket, context, port, hostname, downloadSemaphore, uploadSemaphore));
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
            try {
                if (threadPool != null) {
                    threadPool.shutdownNow();
                }
            } catch (Exception ignored) {}
        }
    }
    
    // ================== REQUEST HANDLER ==================
    private static class RequestHandler implements Runnable {
        private final Socket socket;
        private final Context context;
        private final int port;
        private final String hostname;
        private final Semaphore downloadSemaphore;
        private final Semaphore uploadSemaphore;
        
        RequestHandler(Socket socket, Context context, int port, String hostname, Semaphore downloadSemaphore, Semaphore uploadSemaphore) {
            this.socket = socket;
            this.context = context;
            this.port = port;
            this.hostname = hostname;
            this.downloadSemaphore = downloadSemaphore;
            this.uploadSemaphore = uploadSemaphore;
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
                
                // Read all HTTP headers
                Map<String, String> headers = new HashMap<>();
                String headerLine;
                while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                    int colonIdx = headerLine.indexOf(':');
                    if (colonIdx > 0) {
                        String key = headerLine.substring(0, colonIdx).trim().toLowerCase(Locale.US);
                        String value = headerLine.substring(colonIdx + 1).trim();
                        headers.put(key, value);
                    }
                }
                
                // Validate Host header against activeHostname (reject outdated/previous hostnames)
                String hostHeader = headers.get("host");
                if (hostHeader != null && !isHostAllowed(hostHeader, activeHostname)) {
                    Log.w(TAG, "⛔ Dropping connection for invalid/outdated host: '" + hostHeader + "' (Active: '" + activeHostname + "')");
                    closeQuietly(in);
                    closeQuietly(out);
                    closeQuietly(socket);
                    return;
                }
                
                // Route the request
                if (path.contains("generate_204") || path.contains("gen_204") || path.equals("/ncsi.txt") || path.equals("/connecttest.txt")) {
                    sendRedirect(out, "/");
                } else if (path.equals("/") || path.equals("/index.html")) {
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
                    handleUpload(in, out, headers);
                } else if (path.startsWith("/view/")) {
                    handleView(path, out);
                } else if (path.startsWith("/download/")) {
                    handleDownload(path, out);
                } else if (fileMap.containsKey(path)) {
                    serveFile(path, out, true);
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

        private static boolean isHostAllowed(String hostHeader, String activeHostname) {
            if (hostHeader == null || hostHeader.trim().isEmpty()) return true;
            String host = hostHeader.trim();
            int colonIndex = host.indexOf(':');
            if (colonIndex != -1) {
                host = host.substring(0, colonIndex);
            }
            host = host.trim().toLowerCase(Locale.US);

            // Allow localhost and standard loopback
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
                return true;
            }

            // Allow any raw IPv4 or IPv6 address (direct IP access is always valid)
            if (host.matches("^(\\d{1,3}\\.){3}\\d{1,3}$") || host.contains(":")) {
                return true;
            }

            if (activeHostname == null || activeHostname.trim().isEmpty()) {
                return true;
            }
            String current = activeHostname.trim().toLowerCase(Locale.US);

            // Allow current active hostname and local suffixes
            if (host.equals(current)
                    || host.equals(current + ".local")
                    || host.equals(current + ".lan")
                    || host.equals(current + ".home")) {
                return true;
            }

            // Any other named host (like previous/old hostname) is rejected!
            return false;
        }

        private void sendRedirect(OutputStream out, String location) {
            try {
                PrintWriter writer = new PrintWriter(out);
                writer.print("HTTP/1.1 302 Found\r\n");
                writer.print("Location: " + location + "\r\n");
                writer.print("Content-Length: 0\r\n");
                writer.print("Connection: close\r\n\r\n");
                writer.flush();
            } catch (Exception ignored) {}
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
        private void handleUpload(BufferedReader in, OutputStream out, Map<String, String> headers) {
            boolean acquired = false;
            try {
                if (uploadSemaphore != null) {
                    uploadSemaphore.acquire();
                    acquired = true;
                }
                
                Log.d(TAG, "📤 Upload request received");
                
                if (twoWayTransferDisabled) {
                    sendJsonError(out, "Two-way transfer has been disabled by the server host.");
                    return;
                }
                
                // Read content-length from headers map
                int contentLength = 0;
                String clHeader = headers.get("content-length");
                if (clHeader != null) {
                    try {
                        contentLength = Integer.parseInt(clHeader.trim());
                    } catch (Exception ignored) {}
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
            } finally {
                if (acquired && uploadSemaphore != null) {
                    uploadSemaphore.release();
                }
            }
        }
        
        // ================== VIEW HANDLER ==================
        private void handleView(String path, OutputStream out) {
            try {
                // Parse: /view/host/filename
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
                
                serveFile(virtualPath, out, true);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ View error", e);
                send404(out);
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
                
                serveFile(virtualPath, out, false);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Download error", e);
                send404(out);
            }
        }

        private static String getEffectiveMimeType(String fileName, String fallback) {
            if (fileName == null) return fallback != null ? fallback : "application/octet-stream";
            String lower = fileName.toLowerCase(Locale.US);
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".txt") || lower.endsWith(".c") || lower.endsWith(".cpp") || lower.endsWith(".h") 
                    || lower.endsWith(".hpp") || lower.endsWith(".py") || lower.endsWith(".java") || lower.endsWith(".js") 
                    || lower.endsWith(".jsx") || lower.endsWith(".ts") || lower.endsWith(".tsx") || lower.endsWith(".html") 
                    || lower.endsWith(".htm") || lower.endsWith(".css") || lower.endsWith(".json") || lower.endsWith(".md") 
                    || lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".go") || lower.endsWith(".rs") 
                    || lower.endsWith(".php") || lower.endsWith(".kt") || lower.endsWith(".swift") || lower.endsWith(".sql") 
                    || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".xml") || lower.endsWith(".csv") 
                    || lower.endsWith(".log") || lower.endsWith(".env") || lower.endsWith(".ini") || lower.endsWith(".bat") 
                    || lower.endsWith(".ps1") || lower.endsWith(".cs") || lower.endsWith(".rb") || lower.endsWith(".dart")
                    || lower.endsWith(".gradle") || lower.endsWith(".properties")) {
                return "text/plain; charset=utf-8";
            }
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".mp4")) return "video/mp4";
            if (lower.endsWith(".webm")) return "video/webm";
            if (lower.endsWith(".mp3")) return "audio/mpeg";
            if (lower.endsWith(".wav")) return "audio/wav";
            if (lower.endsWith(".ogg")) return "audio/ogg";
            if (lower.endsWith(".doc")) return "application/msword";
            if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            return fallback != null && !fallback.isEmpty() ? fallback : "application/octet-stream";
        }
        
        // ================== FILE SERVING ==================
        private void serveFile(String virtualPath, OutputStream out, boolean inline) {
            boolean acquired = false;
            InputStream fis = null;
            try {
                FileInfo fileInfo = fileMap.get(virtualPath);
                if (fileInfo == null) {
                    send404(out);
                    return;
                }
                
                if (downloadSemaphore != null) {
                    downloadSemaphore.acquire();
                    acquired = true;
                }
                
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
                
                String mimeType = getEffectiveMimeType(fileInfo.fileName, fileInfo.mimeType);
                String disposition = inline ? "inline" : "attachment";
                
                // Send headers
                PrintWriter writer = new PrintWriter(out);
                writer.print("HTTP/1.1 200 OK\r\n");
                writer.print("Content-Type: " + mimeType + "\r\n");
                writer.print("Content-Length: " + fileLength + "\r\n");
                writer.print("Content-Disposition: " + disposition + "; filename=\"" + fileInfo.fileName + "\"\r\n");
                writer.print("Access-Control-Allow-Origin: *\r\n");
                writer.print("Connection: close\r\n");
                writer.print("\r\n");
                writer.flush();
                
                // Send file in high-throughput 32KB chunks
                byte[] buffer = new byte[32768];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                fis.close();
                fis = null;
                out.flush();
                
                Log.d(TAG, "✅ Served (" + disposition + "): " + fileInfo.fileName);
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Serve error", e);
            } finally {
                if (fis != null) {
                    try { fis.close(); } catch (Exception ignored) {}
                }
                if (acquired && downloadSemaphore != null) {
                    downloadSemaphore.release();
                }
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
                "<h4>Receive &amp; Preview Files</h4>" +
                "<p>Click <span class='highlight-green'>Receive Files</span> to view shared files in a split previewer. Opening <span class='highlight-purple'>.txt</span> or any <span class='highlight-cyan'>programming code files</span> (.py, .c, .cpp, .java, .js, .ts, .html, .css, etc.) enables the <span class='highlight-purple'>📋 Copy</span> button to copy all content with exact indentation.</p>" +
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
                "<style>" +
                "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;background:radial-gradient(circle at top right,#1d1e2e,#0e0f19);color:#fff;min-height:100vh;padding:20px;margin:0;box-sizing:border-box}" +
                ".card{background:rgba(30,31,48,0.6);border:1px solid rgba(255,255,255,0.08);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);padding:30px;border-radius:20px;max-width:600px;margin:0 auto;box-shadow:0 8px 32px 0 rgba(0,0,0,0.37)}" +
                ".header{display:flex;align-items:center;gap:15px;margin-bottom:25px}" +
                ".app-icon{width:45px;height:45px;border-radius:10px;box-shadow:0 4px 15px rgba(159,122,234,0.3)}" +
                "h1{margin:0;font-size:24px;font-weight:700;flex:1}" +
                ".upload-zone{border:2px dashed rgba(255,255,255,0.2);border-radius:14px;padding:45px 20px;text-align:center;cursor:pointer;margin:20px 0;transition:all 0.2s;background:rgba(255,255,255,0.02)}" +
                ".upload-zone:hover{border-color:#9f7aea;background:rgba(159,122,234,0.08)}" +
                ".upload-zone.dragover{border-color:#38bdf8;background:rgba(56,189,248,0.15);transform:scale(1.02)}" +
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
                ".modal-header h2 {font-size:20px;margin:0;color:#a78bfa;text-align:left;}" +
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
                "<div style='font-size:42px;margin-bottom:10px'>📁</div>" +
                "<div style='font-weight:600;font-size:16px;margin-bottom:4px;'>Click or Drag &amp; Drop files here</div>" +
                "<div style='font-size:12px;color:#a0aec0;'>Select files from your device to share</div>" +
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
                "const zone=document.getElementById('uploadZone');" +
                "['dragenter','dragover'].forEach(n=>{" +
                "zone.addEventListener(n,(e)=>{" +
                "e.preventDefault();e.stopPropagation();" +
                "if(!isTwoWayDisabled)zone.classList.add('dragover');" +
                "});" +
                "});" +
                "['dragleave','drop'].forEach(n=>{" +
                "zone.addEventListener(n,(e)=>{" +
                "e.preventDefault();e.stopPropagation();" +
                "zone.classList.remove('dragover');" +
                "});" +
                "});" +
                "zone.addEventListener('drop',(e)=>{" +
                "e.preventDefault();e.stopPropagation();" +
                "zone.classList.remove('dragover');" +
                "if(isTwoWayDisabled){showDisabledModal();return;}" +
                "if(e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length>0){" +
                "files=Array.from(e.dataTransfer.files);" +
                "updateList();" +
                "}" +
                "});" +
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
                "<link href='https://fonts.googleapis.com/css2?family=Outfit:wght@600;700;800&family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap' rel='stylesheet'>" +
                "<style>" +
                "*{box-sizing:border-box;margin:0;padding:0}" +
                "body{font-family:'Inter',-apple-system,sans-serif;background:radial-gradient(circle at top right,#1d1e2e,#0e0f19);color:#f1f5f9;min-height:100vh;padding:16px;margin:0}" +
                ".app-layout{display:flex;flex-direction:column;gap:16px;max-width:1600px;margin:0 auto;height:calc(100vh - 32px)}" +
                "@media(min-width:900px){" +
                ".app-layout{flex-direction:row;align-items:stretch}" +
                ".files-panel{flex:1;min-width:280px;max-width:340px;height:100%;display:flex;flex-direction:column}" +
                ".viewer-panel{flex:4;min-width:0;height:100%;display:flex!important;flex-direction:column}" +
                "}" +
                ".files-panel{background:rgba(30,31,48,0.65);border:1px solid rgba(255,255,255,0.08);backdrop-filter:blur(16px);-webkit-backdrop-filter:blur(16px);padding:20px;border-radius:20px;box-shadow:0 8px 32px 0 rgba(0,0,0,0.37);display:flex;flex-direction:column;min-height:0}" +
                ".panel-header{margin-bottom:16px;flex-shrink:0}" +
                ".back{color:#a78bfa;text-decoration:none;display:inline-flex;align-items:center;gap:5px;font-weight:600;font-size:13.5px;margin-bottom:10px;transition:color 0.2s}" +
                ".back:hover{color:#c084fc}" +
                ".title-row{display:flex;justify-content:space-between;align-items:center}" +
                "h1{font-family:'Outfit',sans-serif;font-size:22px;font-weight:800;background:linear-gradient(135deg,#ffffff 40%,#a78bfa 100%);-webkit-background-clip:text;-webkit-text-fill-color:transparent}" +
                ".file-count{background:rgba(167,139,250,0.15);color:#c084fc;border:1px solid rgba(167,139,250,0.3);padding:3px 10px;border-radius:20px;font-size:11.5px;font-weight:600}" +
                ".files-container{flex:1;min-height:0;overflow-y:auto;padding-right:4px}" +
                ".files-container::-webkit-scrollbar{width:5px}" +
                ".files-container::-webkit-scrollbar-track{background:rgba(255,255,255,0.02);border-radius:4px}" +
                ".files-container::-webkit-scrollbar-thumb{background:rgba(255,255,255,0.15);border-radius:4px}" +
                ".files-container::-webkit-scrollbar-thumb:hover{background:rgba(167,139,250,0.4)}" +
                ".file-list{display:flex;flex-direction:column;gap:8px}" +
                ".file-item{background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.06);border-radius:12px;padding:12px 14px;display:flex;align-items:center;gap:12px;cursor:pointer;transition:all 0.2s ease;user-select:none}" +
                ".file-item:hover{background:rgba(255,255,255,0.07);border-color:rgba(167,139,250,0.3);transform:translateX(2px)}" +
                ".file-item.active{background:rgba(167,139,250,0.16);border-color:#a78bfa;box-shadow:0 0 14px rgba(167,139,250,0.3)}" +
                ".item-icon{font-size:22px;line-height:1;flex-shrink:0}" +
                ".item-info{flex:1;min-width:0}" +
                ".item-name{font-size:13px;font-weight:600;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-bottom:2px}" +
                ".item-meta{display:flex;align-items:center;gap:8px;font-size:11px;color:#94a3b8}" +
                ".item-arrow{color:#64748b;font-size:16px;transition:transform 0.2s,color 0.2s;flex-shrink:0}" +
                ".file-item.active .item-arrow{color:#a78bfa;transform:translateX(2px)}" +
                ".card-type-tag{font-size:9px;text-transform:uppercase;font-weight:700;padding:1px 5px;border-radius:4px;letter-spacing:0.5px}" +
                ".tag-code{background:rgba(167,139,250,0.2);color:#c084fc}" +
                ".tag-pdf{background:rgba(239,68,68,0.2);color:#f87171}" +
                ".tag-doc{background:rgba(59,130,246,0.2);color:#60a5fa}" +
                ".tag-text{background:rgba(245,158,11,0.2);color:#fbbf24}" +
                ".tag-img{background:rgba(16,185,129,0.2);color:#34d399}" +
                ".tag-media{background:rgba(14,165,233,0.2);color:#38bdf8}" +
                ".tag-other{background:rgba(148,163,184,0.2);color:#94a3b8}" +
                ".viewer-panel{background:rgba(24,25,38,0.85);border:1px solid rgba(255,255,255,0.1);backdrop-filter:blur(20px);-webkit-backdrop-filter:blur(20px);border-radius:20px;padding:20px;box-shadow:0 12px 40px rgba(0,0,0,0.5);display:none;flex-direction:column;overflow:hidden;min-height:0}" +
                ".viewer-empty-state{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;text-align:center;padding:40px 20px;color:#94a3b8;margin:auto}" +
                ".empty-icon-wrap{width:72px;height:72px;border-radius:20px;background:rgba(167,139,250,0.08);border:1px solid rgba(167,139,250,0.15);display:flex;align-items:center;justify-content:center;font-size:36px;margin-bottom:18px;box-shadow:0 8px 24px rgba(0,0,0,0.2)}" +
                ".viewer-empty-state h3{font-size:20px;color:#f1f5f9;margin-bottom:8px;font-family:'Outfit',sans-serif;font-weight:700}" +
                ".viewer-empty-state p{font-size:14px;max-width:360px;line-height:1.6;color:#94a3b8}" +
                ".viewer-content-box{display:flex;flex-direction:column;height:100%;min-height:0;flex:1}" +
                ".viewer-header{display:flex;justify-content:space-between;align-items:center;padding-bottom:14px;border-bottom:1px solid rgba(255,255,255,0.08);margin-bottom:14px;flex-wrap:wrap;gap:12px;flex-shrink:0}" +
                ".viewer-file-info{display:flex;align-items:center;gap:12px;min-width:0;flex:1}" +
                ".viewer-file-icon{font-size:26px;line-height:1}" +
                ".viewer-file-titles{min-width:0}" +
                ".viewer-file-name{font-weight:700;font-size:16px;color:#fff;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:400px}" +
                ".viewer-file-meta{font-size:12px;color:#94a3b8;margin-top:2px}" +
                ".viewer-actions{display:flex;align-items:center;gap:8px}" +
                ".btn-action{padding:8px 16px;border-radius:10px;font-size:13px;font-weight:600;cursor:pointer;border:none;display:inline-flex;align-items:center;gap:6px;text-decoration:none;transition:all 0.2s}" +
                ".btn-copy{background:rgba(167,139,250,0.15);color:#c084fc;border:1px solid rgba(167,139,250,0.35)}" +
                ".btn-copy:hover{background:#7c3aed;color:#fff;border-color:#7c3aed;box-shadow:0 4px 14px rgba(124,58,237,0.4)}" +
                ".btn-download{background:linear-gradient(135deg,#10b981 0%,#059669 100%);color:#fff;box-shadow:0 2px 10px rgba(16,185,129,0.3)}" +
                ".btn-download:hover{transform:translateY(-1px);box-shadow:0 4px 15px rgba(16,185,129,0.45)}" +
                ".btn-close{background:rgba(255,255,255,0.08);color:#94a3b8;padding:8px 12px}" +
                ".btn-close:hover{background:rgba(239,68,68,0.2);color:#f87171}" +
                ".viewer-body{flex:1;min-height:0;overflow:auto;border-radius:12px;background:#0d0e17;border:1px solid rgba(255,255,255,0.06);position:relative;display:flex;flex-direction:column}" +
                "::selection{background:rgba(167,139,250,0.45);color:#fff}" +
                ".code-table{display:table;width:100%;font-family:'JetBrains Mono',Consolas,monospace;font-size:13px;line-height:1.6;tab-size:4;-moz-tab-size:4;border-collapse:collapse}" +
                ".code-row{display:table-row}" +
                ".code-row:hover{background:rgba(255,255,255,0.03)}" +
                ".line-number{display:table-cell;padding:0 14px 0 12px;text-align:right;color:#4b5563;user-select:none;-webkit-user-select:none;background:#090a10;border-right:1px solid rgba(255,255,255,0.06);font-size:12px;line-height:1.6;vertical-align:top;white-space:nowrap;width:1%}" +
                ".code-line{display:table-cell;padding:0 16px;color:#e2e8f0;white-space:pre!important;font-size:13px;line-height:1.6;user-select:text!important;-webkit-user-select:text!important;vertical-align:top;word-break:normal;word-wrap:normal}" +
                ".pdf-frame{width:100%;height:100%;min-height:500px;border:none;border-radius:12px;background:#fff}" +
                ".media-container{display:flex;align-items:center;justify-content:center;padding:20px;height:100%;min-height:350px}" +
                ".media-container img{max-width:100%;max-height:75vh;object-fit:contain;border-radius:8px;box-shadow:0 8px 24px rgba(0,0,0,0.4)}" +
                ".media-container video,.media-container audio{max-width:100%;border-radius:8px}" +
                ".unsupported-preview-box{display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;padding:50px 20px;height:100%;margin:auto}" +
                ".unsupported-icon-wrap{width:80px;height:80px;border-radius:20px;background:rgba(245,158,11,0.1);border:1px solid rgba(245,158,11,0.25);display:flex;align-items:center;justify-content:center;font-size:40px;margin-bottom:20px;box-shadow:0 8px 24px rgba(0,0,0,0.2)}" +
                ".unsupported-preview-box h3{font-size:20px;color:#f1f5f9;margin-bottom:8px;font-family:'Outfit',sans-serif;font-weight:700}" +
                ".unsupported-preview-box p{font-size:14px;color:#94a3b8;max-width:380px;margin-bottom:24px;line-height:1.6}" +
                ".btn-primary-large{background:linear-gradient(135deg,#9f7aea 0%,#667eea 100%);color:#fff;padding:13px 28px;border-radius:12px;text-decoration:none;font-weight:700;font-size:14px;box-shadow:0 4px 15px rgba(102,126,234,0.4);transition:all 0.2s;display:inline-flex;align-items:center;gap:8px}" +
                ".btn-primary-large:hover{transform:translateY(-2px);box-shadow:0 6px 20px rgba(102,126,234,0.6)}" +
                ".toast{position:fixed;bottom:24px;right:24px;background:#10b981;color:#fff;padding:12px 20px;border-radius:10px;font-size:14px;font-weight:600;box-shadow:0 8px 24px rgba(0,0,0,0.4);opacity:0;pointer-events:none;transform:translateY(10px);transition:all 0.3s ease;z-index:9999}" +
                ".toast.show{opacity:1;transform:translateY(0)}" +
                "@media(max-width:899px){" +
                ".app-layout{height:auto;min-height:calc(100vh - 32px)}" +
                ".files-panel{height:auto;min-height:400px}" +
                ".viewer-panel{position:fixed;top:0;left:0;right:0;bottom:0;z-index:1000;border-radius:0;padding:16px;height:100vh}" +
                ".viewer-panel.mobile-open{display:flex!important}" +
                "}" +
                "</style></head><body>" +
                "<div class='app-layout'>" +
                "<div class='files-panel'>" +
                "<div class='panel-header'>" +
                "<a href='/' class='back'>← Back</a>" +
                "<div class='title-row'>" +
                "<h1>Receive Files</h1>" +
                "<span class='file-count' id='fileCount'>0 files</span>" +
                "</div>" +
                "</div>" +
                "<div id='filesContainer' class='files-container'>" +
                "<div class='viewer-empty-state'><div class='empty-icon-wrap'>⏳</div><h3>Loading files...</h3></div>" +
                "</div>" +
                "</div>" +
                "<div class='viewer-panel' id='viewerPanel'>" +
                "<div class='viewer-empty-state' id='viewerEmptyState'>" +
                "<div class='empty-icon-wrap'>📁</div>" +
                "<h3>File Preview</h3>" +
                "<p>Select any file on the left to preview code, documents, PDF, or media here.</p>" +
                "</div>" +
                "<div class='viewer-content-box' id='viewerContentBox' style='display:none;'>" +
                "<div class='viewer-header'>" +
                "<div class='viewer-file-info'>" +
                "<span class='viewer-file-icon' id='viewerFileIcon'>📄</span>" +
                "<div class='viewer-file-titles'>" +
                "<div class='viewer-file-name' id='viewerFileName' title=''>Filename</div>" +
                "<div class='viewer-file-meta' id='viewerFileMeta'>0 KB</div>" +
                "</div>" +
                "</div>" +
                "<div class='viewer-actions'>" +
                "<button class='btn-action btn-copy' id='btnCopy' onclick='copyViewerContent()' title='Copy content preserving indentation'><span class='btn-icon'>📋</span> Copy</button>" +
                "<a href='#' class='btn-action btn-download' id='btnDownload' download='' title='Download this file'><span class='btn-icon'>⬇️</span> Download</a>" +
                "<button class='btn-action btn-close' id='btnClose' onclick='closeViewer()' title='Close Preview'>✕</button>" +
                "</div>" +
                "</div>" +
                "<div class='viewer-body' id='viewerBody'></div>" +
                "</div>" +
                "</div>" +
                "</div>" +
                "<div id='toast' class='toast'>📋 Copied to clipboard!</div>" +
                "<script>" +
                "let lastFilesJson='';" +
                "let currentRawContent='';" +
                "let activeFileName='';" +
                "function getFileCategory(filename){" +
                "if(!filename)return 'other';" +
                "const parts=filename.split('.');" +
                "if(parts.length<2)return 'other';" +
                "const ext=parts.pop().toLowerCase();" +
                "const codeExts=['c','cpp','h','hpp','py','java','js','jsx','ts','tsx','html','htm','css','json','md','sh','bash','go','rs','php','kt','swift','sql','yaml','yml','xml','cs','rb','dart','lua','r','csv','log','env','ini','bat','ps1','gradle','properties'];" +
                "if(codeExts.includes(ext))return 'code';" +
                "if(ext==='pdf')return 'pdf';" +
                "if(ext==='doc'||ext==='docx')return 'doc';" +
                "if(['txt','rtf','text'].includes(ext))return 'text';" +
                "if(['png','jpg','jpeg','gif','webp','svg','bmp','ico'].includes(ext))return 'image';" +
                "if(['mp4','webm','mp3','wav','ogg','m4a','mov','avi','mkv'].includes(ext))return 'media';" +
                "return 'other';" +
                "}" +
                "function getCategoryBadge(cat){" +
                "switch(cat){" +
                "case 'code':return '<span class=\"card-type-tag tag-code\">Code</span>';" +
                "case 'pdf':return '<span class=\"card-type-tag tag-pdf\">PDF</span>';" +
                "case 'doc':return '<span class=\"card-type-tag tag-doc\">DOC</span>';" +
                "case 'text':return '<span class=\"card-type-tag tag-text\">Text</span>';" +
                "case 'image':return '<span class=\"card-type-tag tag-img\">Image</span>';" +
                "case 'media':return '<span class=\"card-type-tag tag-media\">Media</span>';" +
                "default:return '<span class=\"card-type-tag tag-other\">File</span>';" +
                "}" +
                "}" +
                "function getCategoryIcon(cat){" +
                "switch(cat){" +
                "case 'code':return '💻';" +
                "case 'pdf':return '📕';" +
                "case 'doc':return '📘';" +
                "case 'text':return '📝';" +
                "case 'image':return '🖼️';" +
                "case 'media':return '🎬';" +
                "default:return '📦';" +
                "}" +
                "}" +
                "function isViewable(cat){" +
                "return ['code','pdf','doc','text','image','media'].includes(cat);" +
                "}" +
                "async function extractDocxText(url){" +
                "try{" +
                "const res=await fetch(url);" +
                "if(!res.ok)return null;" +
                "const buf=await res.arrayBuffer();" +
                "const bytes=new Uint8Array(buf);" +
                "const latin=new TextDecoder('latin1').decode(bytes);" +
                "const searchStr='word/document.xml';" +
                "const idx=latin.indexOf(searchStr);" +
                "if(idx===-1)return null;" +
                "const headerIdx=latin.lastIndexOf('PK\\x03\\x04',idx);" +
                "if(headerIdx===-1)return null;" +
                "const view=new DataView(buf,headerIdx);" +
                "const compMethod=view.getUint16(8,true);" +
                "const compSize=view.getUint32(18,true);" +
                "const fnLen=view.getUint16(26,true);" +
                "const extraLen=view.getUint16(28,true);" +
                "const dataStart=headerIdx+30+fnLen+extraLen;" +
                "const compressedData=bytes.subarray(dataStart,dataStart+compSize);" +
                "let xmlText='';" +
                "if(compMethod===8){" +
                "const ds=new DecompressionStream('deflate-raw');" +
                "const writer=ds.writable.getWriter();" +
                "writer.write(compressedData);" +
                "writer.close();" +
                "const decompressedBuf=await new Response(ds.readable).arrayBuffer();" +
                "xmlText=new TextDecoder('utf-8').decode(decompressedBuf);" +
                "}else{" +
                "xmlText=new TextDecoder('utf-8').decode(compressedData);" +
                "}" +
                "const parser=new DOMParser();" +
                "const xmlDoc=parser.parseFromString(xmlText,'text/xml');" +
                "const paragraphs=xmlDoc.getElementsByTagName('w:p');" +
                "let lines=[];" +
                "for(let p of paragraphs){" +
                "let pText='';" +
                "const allNodes=p.getElementsByTagName('*');" +
                "for(let node of allNodes){" +
                "if(node.tagName==='w:t')pText+=node.textContent;" +
                "else if(node.tagName==='w:tab')pText+='    ';" +
                "else if(node.tagName==='w:br')pText+='\\n';" +
                "}" +
                "lines.push(pText);" +
                "}" +
                "return lines.join('\\n');" +
                "}catch(e){console.error('Docx extract error:',e);return null;}" +
                "}" +
                "async function extractPdfText(url){" +
                "try{" +
                "const res=await fetch(url);" +
                "if(!res.ok)return '';" +
                "const buf=await res.arrayBuffer();" +
                "const bytes=new Uint8Array(buf);" +
                "const str=new TextDecoder('latin1').decode(bytes);" +
                "let text='';" +
                "const btRegex=/BT[\\s\\S]*?ET/g;" +
                "let match;" +
                "while((match=btRegex.exec(str))!==null){" +
                "const block=match[0];" +
                "const tjRegex=/\\((.*?)\\)\\s*T[jJ]/g;" +
                "let tjMatch;" +
                "let lineText='';" +
                "while((tjMatch=tjRegex.exec(block))!==null){" +
                "lineText+=tjMatch[1]+' ';" +
                "}" +
                "if(lineText.trim()){" +
                "text+=lineText.trim()+'\\n';" +
                "}" +
                "}" +
                "return text.trim();" +
                "}catch(e){return '';}" +
                "}" +
                "async function previewFile(fileName){" +
                "activeFileName=fileName;" +
                "highlightItem(fileName);" +
                "const cat=getFileCategory(fileName);" +
                "const viewUrl='/view/host/'+encodeURIComponent(fileName);" +
                "const downloadUrl='/download/host/'+encodeURIComponent(fileName);" +
                "document.getElementById('viewerEmptyState').style.display='none';" +
                "const contentBox=document.getElementById('viewerContentBox');" +
                "contentBox.style.display='flex';" +
                "const viewerPanel=document.getElementById('viewerPanel');" +
                "viewerPanel.classList.add('mobile-open');" +
                "document.getElementById('viewerFileIcon').textContent=getCategoryIcon(cat);" +
                "const nameEl=document.getElementById('viewerFileName');" +
                "nameEl.textContent=fileName;" +
                "nameEl.title=fileName;" +
                "const btnDownload=document.getElementById('btnDownload');" +
                "btnDownload.href=downloadUrl;" +
                "btnDownload.download=fileName;" +
                "const btnCopy=document.getElementById('btnCopy');" +
                "const viewerBody=document.getElementById('viewerBody');" +
                "currentRawContent='';" +
                "if(cat==='code'||cat==='text'){" +
                "btnCopy.style.display='inline-flex';" +
                "btnCopy.innerHTML='<span class=\"btn-icon\">📋</span> Copy';" +
                "viewerBody.innerHTML='<div style=\"padding:40px;text-align:center;color:#94a3b8;\">⏳ Loading content...</div>';" +
                "try{" +
                "const res=await fetch(viewUrl);" +
                "if(!res.ok)throw new Error('Failed to load file');" +
                "const text=await res.text();" +
                "currentRawContent=text;" +
                "renderCodeView(text);" +
                "}catch(e){" +
                "renderUnableToOpen(fileName,cat,downloadUrl);" +
                "}" +
                "}else if(cat==='doc'){" +
                "btnCopy.style.display='none';" +
                "viewerBody.innerHTML='<div style=\"padding:40px;text-align:center;color:#94a3b8;\">⏳ Extracting document text...</div>';" +
                "try{" +
                "const docxText=await extractDocxText(viewUrl);" +
                "if(docxText!==null&&docxText.length>0){" +
                "currentRawContent=docxText;" +
                "renderCodeView(docxText);" +
                "}else{" +
                "renderUnableToOpen(fileName,cat,downloadUrl);" +
                "}" +
                "}catch(e){" +
                "renderUnableToOpen(fileName,cat,downloadUrl);" +
                "}" +
                "}else if(cat==='pdf'){" +
                "btnCopy.style.display='none';" +
                "document.getElementById('viewerFileMeta').textContent='PDF Document';" +
                "viewerBody.innerHTML=`<iframe src='${viewUrl}#toolbar=1' class='pdf-frame'></iframe>`;" +
                "}else if(cat==='image'){" +
                "btnCopy.style.display='none';" +
                "document.getElementById('viewerFileMeta').textContent='Image Preview';" +
                "viewerBody.innerHTML=`<div class='media-container'><img src='${viewUrl}' alt='${escapeHtml(fileName)}' onerror=\"renderUnableToOpen('${escapeHtml(fileName)}','${cat}','${downloadUrl}')\"></div>`;" +
                "}else if(cat==='media'){" +
                "btnCopy.style.display='none';" +
                "document.getElementById('viewerFileMeta').textContent='Media Playback';" +
                "const isVideo=['mp4','webm','mov','avi','mkv'].includes(fileName.split('.').pop().toLowerCase());" +
                "if(isVideo){" +
                "viewerBody.innerHTML=`<div class='media-container'><video controls src='${viewUrl}' style='max-width:100%;max-height:75vh;' onerror=\"renderUnableToOpen('${escapeHtml(fileName)}','${cat}','${downloadUrl}')\"></video></div>`;" +
                "}else{" +
                "viewerBody.innerHTML=`<div class='media-container'><audio controls src='${viewUrl}' style='width:100%;' onerror=\"renderUnableToOpen('${escapeHtml(fileName)}','${cat}','${downloadUrl}')\"></audio></div>`;" +
                "}" +
                "}else{" +
                "renderUnableToOpen(fileName,cat,downloadUrl);" +
                "}" +
                "}" +
                "function renderCodeView(text){" +
                "const lines=text.split(/\\r?\\n/);" +
                "document.getElementById('viewerFileMeta').textContent=`${lines.length} lines • ${formatSize(new Blob([text]).size)}`;" +
                "let html='<div class=\"code-table\">';" +
                "for(let i=0;i<lines.length;i++){" +
                "const l=lines[i];" +
                "html+=`<div class=\"code-row\"><span class=\"line-number\">${i+1}</span><span class=\"code-line\">${escapeHtml(l)||'&nbsp;'}</span></div>`;" +
                "}" +
                "html+='</div>';" +
                "document.getElementById('viewerBody').innerHTML=html;" +
                "}" +
                "function renderUnableToOpen(fileName,cat,downloadUrl){" +
                "const btnCopy=document.getElementById('btnCopy');" +
                "if(btnCopy)btnCopy.style.display='none';" +
                "document.getElementById('viewerFileMeta').textContent='Preview unavailable';" +
                "const viewerBody=document.getElementById('viewerBody');" +
                "viewerBody.innerHTML=`<div class='unsupported-preview-box'><div class='unsupported-icon-wrap'>${getCategoryIcon(cat)}</div><h3>Unable to Open Preview</h3><p>This file format cannot be displayed directly in the browser preview. Please download the file to view it on your device.</p><a href='${downloadUrl}' class='btn-primary-large' download='${escapeHtml(fileName)}'>⬇️ Download File</a></div>`;" +
                "}" +
                "function copyViewerContent(){" +
                "const textToCopy=currentRawContent||(window.location.origin+'/download/host/'+encodeURIComponent(activeFileName));" +
                "if(!textToCopy)return;" +
                "const btn=document.getElementById('btnCopy');" +
                "const copySuccess=()=>{" +
                "btn.innerHTML='<span class=\"btn-icon\">✓</span> Copied!';" +
                "showToast('📋 Content copied with exact indentation!');" +
                "setTimeout(()=>{btn.innerHTML='<span class=\"btn-icon\">📋</span> Copy';},2500);" +
                "};" +
                "if(navigator.clipboard&&navigator.clipboard.writeText){" +
                "navigator.clipboard.writeText(textToCopy).then(copySuccess).catch(()=>{" +
                "fallbackCopy(textToCopy,copySuccess);" +
                "});" +
                "}else{" +
                "fallbackCopy(textToCopy,copySuccess);" +
                "}" +
                "}" +
                "function fallbackCopy(text,cb){" +
                "const ta=document.createElement('textarea');" +
                "ta.value=text;" +
                "ta.style.position='fixed';" +
                "ta.style.left='-9999px';" +
                "document.body.appendChild(ta);" +
                "ta.select();" +
                "try{document.execCommand('copy');if(cb)cb();}" +
                "catch(e){console.error('Fallback copy failed',e);}" +
                "document.body.removeChild(ta);" +
                "}" +
                "function closeViewer(){" +
                "activeFileName='';" +
                "highlightItem('');" +
                "document.getElementById('viewerContentBox').style.display='none';" +
                "document.getElementById('viewerEmptyState').style.display='flex';" +
                "document.getElementById('viewerPanel').classList.remove('mobile-open');" +
                "}" +
                "function highlightItem(fileName){" +
                "document.querySelectorAll('.file-item').forEach(c=>{" +
                "if(decodeURIComponent(c.getAttribute('data-filename')||'')===fileName){" +
                "c.classList.add('active');" +
                "}else{" +
                "c.classList.remove('active');" +
                "}" +
                "});" +
                "}" +
                "function handleItemClick(el){" +
                "const rawName=decodeURIComponent(el.getAttribute('data-filename'));" +
                "previewFile(rawName);" +
                "}" +
                "function showToast(msg){" +
                "const toast=document.getElementById('toast');" +
                "toast.textContent=msg;" +
                "toast.classList.add('show');" +
                "setTimeout(()=>toast.classList.remove('show'),3000);" +
                "}" +
                "function escapeHtml(str){" +
                "return(str||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#039;');" +
                "}" +
                "async function loadFiles(){" +
                "try{" +
                "const res=await fetch('/api/peers');" +
                "const data=await res.json();" +
                "const files=data.files||[];" +
                "const jsonStr=JSON.stringify(files);" +
                "if(jsonStr===lastFilesJson)return;" +
                "lastFilesJson=jsonStr;" +
                "document.getElementById('fileCount').textContent=files.length+(files.length===1?' file':' files');" +
                "const container=document.getElementById('filesContainer');" +
                "if(files.length===0){" +
                "container.innerHTML='<div class=\"viewer-empty-state\"><div class=\"empty-icon-wrap\">📭</div><h3>No files shared yet</h3><p>Files shared by host or other clients will appear here.</p></div>';" +
                "closeViewer();" +
                "return;" +
                "}" +
                "let html='<div class=\"file-list\">';" +
                "files.forEach(f=>{" +
                "const cat=getFileCategory(f.name);" +
                "const icon=getCategoryIcon(cat);" +
                "const badge=getCategoryBadge(cat);" +
                "const encName=encodeURIComponent(f.name);" +
                "const isActive=(f.name===activeFileName)?' active':'';" +
                "html+=`<div class='file-item${isActive}' data-filename='${encName}' onclick='handleItemClick(this)'>`+" +
                "`<span class='item-icon'>${icon}</span>`+" +
                "`<div class='item-info'>`+" +
                "`<div class='item-name' title='${escapeHtml(f.name)}'>${escapeHtml(f.name)}</div>`+" +
                "`<div class='item-meta'><span>${formatSize(f.size)}</span>${badge}</div>`+" +
                "`</div>`+" +
                "`<span class='item-arrow'>›</span>`+" +
                "`</div>`;" +
                "});" +
                "html+='</div>';" +
                "container.innerHTML=html;" +
                "}catch(e){" +
                "document.getElementById('filesContainer').innerHTML='<div class=\"viewer-empty-state\"><div class=\"empty-icon-wrap\">❌</div><h3>Error loading files</h3><p>Could not fetch file list from server.</p></div>';" +
                "}" +
                "}" +
                "function formatSize(b){" +
                "if(b<1024)return b+' B';" +
                "const k=1024,s=['KB','MB','GB'];" +
                "const i=Math.floor(Math.log(b)/Math.log(k));" +
                "return(b/Math.pow(k,i)).toFixed(1)+' '+s[i-1];" +
                "}" +
                "loadFiles();" +
                "setInterval(loadFiles,4000);" +
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