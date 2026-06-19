package com.filesharinghub.app;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    
    private static final String TAG = "MainActivity";
    private static final int FILE_PICKER_REQUEST = 1001;
    private HttpServerPlugin serverPlugin;
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.d(TAG, "✅ MainActivity started");
        
        // Initialize plugin
        serverPlugin = new HttpServerPlugin();
        serverPlugin.setContext(this);
        
        // Add JavaScript bridge
        try {
            WebView webView = getBridge().getWebView();
            if (webView != null) {
                webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
                Log.d(TAG, "✅ Bridge added");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Bridge error", e);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (serverPlugin != null) {
                serverPlugin.stopServerSync();
            }
            Intent serviceIntent = new Intent(this, ForegroundService.class);
            stopService(serviceIntent);
        } catch (Exception e) {
            // Ignore
        }
        super.onDestroy();
    }
    
    public class AndroidBridge {
        
        @JavascriptInterface
        public String startServer(int port) {
            try {
                return serverPlugin.startServerSync(port);
            } catch (Exception e) {
                Log.e(TAG, "❌ startServer error", e);
                return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }
        
        @JavascriptInterface
        public String stopServer() {
            try {
                serverPlugin.stopServerSync();
                return "{\"success\":true}";
            } catch (Exception e) {
                Log.e(TAG, "❌ stopServer error", e);
                return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }
        
        @JavascriptInterface
        public void startForegroundService() {
            try {
                Intent serviceIntent = new Intent(MainActivity.this, ForegroundService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    MainActivity.this.startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Log.d(TAG, "✅ Foreground service started");
            } catch (Exception e) {
                Log.e(TAG, "❌ startForegroundService error", e);
            }
        }

        @JavascriptInterface
        public void stopForegroundService() {
            try {
                Intent serviceIntent = new Intent(MainActivity.this, ForegroundService.class);
                stopService(serviceIntent);
                Log.d(TAG, "✅ Foreground service stopped");
            } catch (Exception e) {
                Log.e(TAG, "❌ stopForegroundService error", e);
            }
        }
        
        @JavascriptInterface
        public String addFile(String path, String virtualPath, String name, long size, String mimeType) {
            try {
                return serverPlugin.addFileSync(path, virtualPath, name, size, mimeType);
            } catch (Exception e) {
                Log.e(TAG, "❌ addFile error", e);
                return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public String addFile(String path, String name, long size, String mimeType) {
            return addFile(path, "/" + name, name, size, mimeType);
        }
        
        @JavascriptInterface
        public String removeFile(String virtualPath) {
            try {
                return serverPlugin.removeFileSync(virtualPath);
            } catch (Exception e) {
                Log.e(TAG, "❌ removeFile error", e);
                return "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public void setTwoWayTransferDisabled(boolean disabled) {
            try {
                serverPlugin.setTwoWayTransferDisabled(disabled);
            } catch (Exception e) {
                Log.e(TAG, "❌ setTwoWayTransferDisabled error", e);
            }
        }
        
        @JavascriptInterface
        public void openBrowser(String url) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Log.d(TAG, "Opened system browser: " + url);
            } catch (Exception e) {
                Log.e(TAG, "❌ Error opening browser: " + e.getMessage());
            }
        }
        
        @JavascriptInterface
        public void pickFiles() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    
                    startActivityForResult(intent, FILE_PICKER_REQUEST);
                    Log.d(TAG, "✅ File picker opened");
                } catch (Exception e) {
                    Log.e(TAG, "❌ File picker error", e);
                    notifyFilePicked("{\"error\":\"" + e.getMessage() + "\"}");
                }
            });
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        try {
            if (requestCode == FILE_PICKER_REQUEST) {
                if (resultCode == RESULT_OK && data != null) {
                    JSONArray filesArray = new JSONArray();
                    
                    if (data.getClipData() != null) {
                        // Multiple files
                        int count = data.getClipData().getItemCount();
                        Log.d(TAG, "Selected " + count + " files");
                        for (int i = 0; i < count; i++) {
                            Uri uri = data.getClipData().getItemAt(i).getUri();
                            try {
                                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Exception e) {
                                // Ignore
                            }
                            JSONObject fileObj = getFileInfo(uri);
                            if (fileObj != null) {
                                filesArray.put(fileObj);
                            }
                        }
                    } else if (data.getData() != null) {
                        // Single file
                        Uri uri = data.getData();
                        Log.d(TAG, "Selected 1 file");
                        try {
                            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception e) {
                            // Ignore
                        }
                        JSONObject fileObj = getFileInfo(uri);
                        if (fileObj != null) {
                            filesArray.put(fileObj);
                        }
                    }
                    
                    JSONObject result = new JSONObject();
                    result.put("files", filesArray);
                    notifyFilePicked(result.toString());
                    
                } else {
                    Log.d(TAG, "File picker cancelled");
                    notifyFilePicked("{\"files\":[]}");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ onActivityResult error", e);
            notifyFilePicked("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    private JSONObject getFileInfo(Uri uri) {
        try {
            String fileName = getFileName(uri);
            long fileSize = getFileSize(uri);
            String mimeType = getContentResolver().getType(uri);
            
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }
            
            JSONObject fileObj = new JSONObject();
            fileObj.put("name", fileName);
            fileObj.put("size", fileSize);
            fileObj.put("mimeType", mimeType);
            fileObj.put("path", uri.toString());
            
            Log.d(TAG, "File: " + fileName + " (" + formatSize(fileSize) + ")");
            return fileObj;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ getFileInfo error", e);
            return null;
        }
    }
    
    private String getFileName(Uri uri) {
        String result = "file";
        try {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
                cursor.close();
            }
            if (result == null || result.equals("file")) {
                result = uri.getLastPathSegment();
                if (result == null) result = "file";
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ getFileName error", e);
            result = uri.getLastPathSegment();
            if (result == null) result = "file";
        }
        return result;
    }
    
    private long getFileSize(Uri uri) {
        try {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (sizeIndex >= 0) {
                        long size = cursor.getLong(sizeIndex);
                        cursor.close();
                        return size;
                    }
                }
                cursor.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "⚠️ getFileSize error", e);
        }
        return 0;
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private void notifyFilePicked(String json) {
        try {
            runOnUiThread(() -> {
                try {
                    WebView webView = getBridge().getWebView();
                    if (webView != null) {
                        String js = "javascript:if(window.onFilesPicked){window.onFilesPicked(" + json + ")}";
                        webView.evaluateJavascript(js, null);
                        Log.d(TAG, "✅ File picker result sent");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ notifyFilePicked inner error", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "❌ notifyFilePicked error", e);
        }
    }
}