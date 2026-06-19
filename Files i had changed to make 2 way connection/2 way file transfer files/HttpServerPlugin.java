package com.filesharinghub.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
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
    private ServerThread serverThread;
    private Context context;
    private static final Map<String, FileInfo> fileMap = new ConcurrentHashMap<>();
    
    // mDNS variables
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    
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
    
    // Start server (called from MainActivity)
    public String startServerSync(int port) {
        try {
            if (serverThread != null && serverThread.isAlive()) {
                return "{\"success\":false,\"error\":\"Server already running\"}";
            }
            
            String ipAddress = getLocalIpAddress();
            if (ipAddress == null) {
                return "{\"success\":false,\"error\":\"No network connection\"}";
            }
            
            serverThread = new ServerThread(port, context);
            serverThread.start();
            
            // Register mDNS service
            registerMdnsService(port);
            
            Log.d(TAG, "✅ Server started at " + ipAddress + ":" + port);
            Log.d(TAG, "📡 Access via: http://immortal.local:" + port);
            
            return "{\"success\":true,\"ip\":\"" + ipAddress + "\",\"port\":" + port + ",\"mdns\":\"immortal.local\"}";
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Server start error", e);
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // Stop server
    public void stopServerSync() {
        try {
            unregisterMdnsService();
            
            if (serverThread != null) {
                serverThread.stopServer();
                serverThread = null;
            }
            fileMap.clear();
            Log.d(TAG, "✅ Server stopped");
        } catch (Exception e) {
            Log.e(TAG, "❌ Stop error", e);
        }
    }
    
    // Register mDNS service (immortal.local)
    private void registerMdnsService(int port) {
        if (context == null) {
            Log.e(TAG, "❌ Context not set, cannot register mDNS");
            return;
        }
        
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName("immortal");
            serviceInfo.setServiceType("_http._tcp.");
            serviceInfo.setPort(port);
            
            registrationListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo info) {
                    Log.d(TAG, "✅ mDNS registered: http://immortal.local:" + port);
                }
                
                @Override
                public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.e(TAG, "❌ mDNS registration failed: " + errorCode);
                }
                
                @Override
                public void onServiceUnregistered(NsdServiceInfo info) {
                    Log.d(TAG, "🔌 mDNS unregistered");
                }
                
                @Override
                public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.e(TAG, "❌ mDNS unregistration failed: " + errorCode);
                }
            };
            
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
            Log.d(TAG, "📡 Registering mDNS service as 'immortal.local'...");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ mDNS registration error", e);
        }
    }
    
    // Unregister mDNS service
    private void unregisterMdnsService() {
        try {
            if (nsdManager != null && registrationListener != null) {
                nsdManager.unregisterService(registrationListener);
                Log.d(TAG, "🔌 Unregistering mDNS service...");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ mDNS unregister error", e);
        }
    }
    
    // Add file to be served
    public String addFileSync(String path, String virtualPath, String name, long size, String mimeType) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return "{\"success\":false,\"error\":\"File not found\"}";
            }
            
            fileMap.put(virtualPath, new FileInfo(path, name, size, mimeType));
            Log.d(TAG, "✅ File registered: " + name);
            return "{\"success\":true}";
            
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
        }
    }
    
    // Get local IP address
    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); 
                 en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); 
                     enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
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
        private ServerSocket serverSocket;
        private volatile boolean running = true;
        
        ServerThread(int port, Context context) {
            this.port = port;
            this.context = context;
        }
        
        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(port);
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
            } catch (IOException e) {
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

        // ================== UPLOAD HANDLER ==================
        private void handleUpload(BufferedReader in, OutputStream out) {
            try {
                Log.d(TAG, "📤 Upload request received");
                
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
                
                File file = new File(fileInfo.filePath);
                if (!file.exists()) {
                    Log.e(TAG, "❌ Physical file not found: " + fileInfo.filePath);
                    send404(out);
                    return;
                }
                
                // Send headers
                PrintWriter writer = new PrintWriter(out);
                writer.print("HTTP/1.1 200 OK\r\n");
                writer.print("Content-Type: " + fileInfo.mimeType + "\r\n");
                writer.print("Content-Length: " + file.length() + "\r\n");
                writer.print("Content-Disposition: attachment; filename=\"" + fileInfo.fileName + "\"\r\n");
                writer.print("Connection: close\r\n");
                writer.print("\r\n");
                writer.flush();
                
                // Send file
                FileInputStream fis = new FileInputStream(file);
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
                "<style>" +
                "body{font-family:system-ui;background:linear-gradient(135deg,#667eea,#764ba2);min-height:100vh;display:flex;align-items:center;justify-content:center;margin:0}" +
                ".card{background:white;padding:40px;border-radius:12px;text-align:center;max-width:400px}" +
                "h1{margin:0 0 30px 0;color:#333}" +
                ".btn{display:block;background:#667eea;color:white;padding:15px;border-radius:8px;text-decoration:none;margin:10px 0;font-size:16px}" +
                ".btn:hover{background:#5568d3}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<h1>📁 File Sharing Hub</h1>" +
                "<a href='/send' class='btn'>📤 Send Files</a>" +
                "<a href='/receive' class='btn'>📥 Receive Files</a>" +
                "</div></body></html>";
            
            sendHtmlResponse(out, html);
        }
        
        // ================== SEND PAGE ==================
        private void sendSendPage(OutputStream out) throws IOException {
            String html = "<!DOCTYPE html><html><head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>Send Files</title>" +
                "<style>" +
                "body{font-family:system-ui;background:linear-gradient(135deg,#667eea,#764ba2);min-height:100vh;padding:20px;margin:0}" +
                ".card{background:white;padding:30px;border-radius:12px;max-width:600px;margin:0 auto}" +
                "h1{margin:0 0 20px 0;color:#333}" +
                ".upload-zone{border:2px dashed #ccc;border-radius:8px;padding:60px 20px;text-align:center;cursor:pointer;margin:20px 0}" +
                ".upload-zone:hover{border-color:#667eea;background:#f8f9ff}" +
                ".file-list{margin:20px 0}" +
                ".file-item{background:#f5f5f5;padding:12px;border-radius:6px;margin:8px 0;display:flex;justify-content:space-between;align-items:center}" +
                ".btn{background:#667eea;color:white;padding:12px 24px;border:none;border-radius:6px;cursor:pointer;font-size:16px;width:100%}" +
                ".btn:disabled{opacity:0.5}" +
                ".status{padding:12px;border-radius:6px;margin-top:15px;display:none}" +
                ".status.show{display:block}" +
                ".status.success{background:#d4edda;color:#155724}" +
                ".back{color:#667eea;text-decoration:none;display:inline-block;margin-bottom:20px}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<a href='/' class='back'>← Back</a>" +
                "<h1>📤 Send Files</h1>" +
                "<input type='file' id='input' multiple style='display:none'>" +
                "<div class='upload-zone' onclick='document.getElementById(\"input\").click()'>" +
                "<div style='font-size:48px'>📁</div>" +
                "<div>Click to select files</div>" +
                "</div>" +
                "<div class='file-list' id='list'></div>" +
                "<button class='btn' id='uploadBtn' style='display:none' onclick='upload()'>Upload Files</button>" +
                "<div class='status' id='status'></div>" +
                "</div>" +
                "<script>" +
                "let files=[];" +
                "document.getElementById('input').onchange=(e)=>{" +
                "files=Array.from(e.target.files);" +
                "updateList();" +
                "};" +
                "function updateList(){" +
                "const list=document.getElementById('list');" +
                "const btn=document.getElementById('uploadBtn');" +
                "if(files.length===0){list.innerHTML='';btn.style.display='none';return;}" +
                "list.innerHTML=files.map(f=>`<div class='file-item'><span>${f.name}</span><span>${formatSize(f.size)}</span></div>`).join('');" +
                "btn.style.display='block';" +
                "}" +
                "async function upload(){" +
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
                "}catch(e){console.error(e);}" +
                "}" +
                "if(success===files.length){" +
                "status.className='status show success';" +
                "status.textContent='✅ Uploaded '+success+' file(s)!';" +
                "setTimeout(()=>{files=[];updateList();status.className='status';},3000);" +
                "}else{" +
                "status.textContent='⚠️ Uploaded '+success+'/'+files.length+' files';" +
                "}" +
                "btn.disabled=false;" +
                "}" +
                "function formatSize(b){" +
                "if(b<1024)return b+' B';" +
                "const k=1024,s=['KB','MB','GB'];" +
                "const i=Math.floor(Math.log(b)/Math.log(k));" +
                "return(b/Math.pow(k,i)).toFixed(1)+' '+s[i-1];" +
                "}" +
                "</script></body></html>";
            
            sendHtmlResponse(out, html);
        }

        // ================== RECEIVE PAGE ==================
        private void sendReceivePage(OutputStream out) throws IOException {
            String html = "<!DOCTYPE html><html><head>" +
                "<meta charset='UTF-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>Receive Files</title>" +
                "<style>" +
                "body{font-family:system-ui;background:linear-gradient(135deg,#667eea,#764ba2);min-height:100vh;padding:20px;margin:0}" +
                ".card{background:white;padding:30px;border-radius:12px;max-width:800px;margin:0 auto}" +
                "h1{margin:0 0 20px 0;color:#333}" +
                ".file-list{display:grid;gap:12px;margin-top:20px}" +
                ".file-item{background:#f5f5f5;padding:15px;border-radius:6px;display:flex;justify-content:space-between;align-items:center}" +
                ".download-btn{background:#667eea;color:white;padding:8px 16px;border-radius:6px;text-decoration:none}" +
                ".empty{text-align:center;padding:40px;color:#666}" +
                ".back{color:#667eea;text-decoration:none;display:inline-block;margin-bottom:20px}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<a href='/' class='back'>← Back</a>" +
                "<h1>📥 Receive Files</h1>" +
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
    } // End of RequestHandler
    
} // End of HttpServerPlugin