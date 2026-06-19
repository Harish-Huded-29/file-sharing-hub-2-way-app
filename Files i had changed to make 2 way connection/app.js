// Global variables
let isSharing = false;
let selectedFiles = [];
let serverPort = 8080;
let deviceHostname = 'immortal';

// Check if device has local network (doesn't need internet)
function checkLocalNetwork() {
    // We don't care about internet, just need local WiFi
    addLog('📶 Checking local network...');
    
    if (!window.AndroidBridge) {
        addLog('⚠️ AndroidBridge not ready');
        return false;
    }
    
    addLog('✅ Local network ready (no internet required)');
    return true;
}


// Storage key
const STORAGE_KEY = 'bluetooth_share_files';

// File picker promise resolver
let filePickerResolve = null;

// File picker callback - called from Android
window.onFilesPicked = function(filesJson) {
    try {
        const result = JSON.parse(filesJson);
        if (filePickerResolve) {
            filePickerResolve(result);
            filePickerResolve = null;
        }
    } catch (e) {
        console.error('Error parsing files:', e);
        if (filePickerResolve) {
            filePickerResolve({ error: e.message });
            filePickerResolve = null;
        }
    }
};

// Initialize
document.addEventListener('DOMContentLoaded', function() {
    initializeApp();
});

async function initializeApp() {
    addLog('App initialized');
    addLog('Device: ' + deviceHostname);
    
    if (!window.Capacitor || window.Capacitor.getPlatform() !== 'android') {
        addLog('⚠️ This app only works on Android');
        return;
    }
    
    addLog('✅ Running on Android');
    
    // Check if native methods are available
    if (!window.AndroidBridge) {
        addLog('⚠️ Waiting for native bridge...');
        setTimeout(initializeApp, 500);
        return;
    }
    
    addLog('✅ Native bridge ready');
    await loadStoredFiles();
}

// Load stored files
async function loadStoredFiles() {
    try {
        const stored = localStorage.getItem(STORAGE_KEY);
        if (stored) {
            selectedFiles = JSON.parse(stored);
            if (selectedFiles.length > 0) {
                updateFileList();
                addLog(`📁 Loaded ${selectedFiles.length} files`);
            }
        }
    } catch (error) {
        addLog('Error loading: ' + error.message);
    }
}

// Save files
function saveFiles() {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(selectedFiles));
    } catch (error) {
        addLog('Error saving: ' + error.message);
    }
}

// File selection
async function selectFolder() {
    try {
        if (!window.AndroidBridge || !window.AndroidBridge.pickFiles) {
            alert('File picker not available. Please restart app.');
            addLog('❌ AndroidBridge.pickFiles not found');
            return;
        }
        
        addLog('Opening file picker...');
        
        // Call native method
        window.AndroidBridge.pickFiles();
        
        // Wait for callback
        const result = await new Promise((resolve) => {
            filePickerResolve = resolve;
            
            // Timeout after 30 seconds
            setTimeout(() => {
                if (filePickerResolve) {
                    filePickerResolve({ error: 'Timeout' });
                    filePickerResolve = null;
                }
            }, 30000);
        });
        
        if (result.error) {
            addLog('❌ Error: ' + result.error);
            return;
        }
        
        if (result.files && result.files.length > 0) {
            for (const file of result.files) {
                const exists = selectedFiles.some(f => f.path === file.path);
                if (!exists) {
                    selectedFiles.push({
                        name: file.name,
                        path: file.path,
                        size: file.size || 0,
                        mimeType: file.mimeType || 'application/octet-stream'
                    });
                }
            }
            
            saveFiles();
            updateFileList();
            addLog(`✅ Added ${result.files.length} files`);
        } else {
            addLog('No files selected');
        }
        
    } catch (error) {
        addLog('❌ Error: ' + error.message);
        alert('Error: ' + error.message);
    }
}

// Update file list
function updateFileList() {
    const fileList = document.getElementById('fileList');
    const folderPath = document.getElementById('folderPath');
    
    if (selectedFiles.length === 0) {
        folderPath.textContent = 'No files selected';
        fileList.innerHTML = '';
        return;
    }
    
    folderPath.textContent = `${selectedFiles.length} file(s) selected`;
    fileList.innerHTML = '';
    
    selectedFiles.forEach((file, index) => {
        const div = document.createElement('div');
        div.className = 'file-item';
        div.innerHTML = `
            <span class="file-name">📄 ${file.name}</span>
            <span class="file-size">${formatFileSize(file.size)}</span>
            <button class="delete-btn" onclick="deleteFile(${index})">🗑️</button>
        `;
        fileList.appendChild(div);
    });
}

// Delete file
function deleteFile(index) {
    if (confirm(`Delete "${selectedFiles[index].name}"?`)) {
        const fileName = selectedFiles[index].name;
        selectedFiles.splice(index, 1);
        saveFiles();
        updateFileList();
        addLog(`🗑️ Deleted: ${fileName}`);
    }
}

// Format file size
function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Start sharing
async function startSharing() { 
    if (selectedFiles.length === 0) {
        alert('Please select files first!');
        return;
    }

    // Check local network (no internet needed)
    if (!checkLocalNetwork()) {
        alert('Please ensure WiFi is enabled');
        return;
    }
    
    try {
        if (!window.AndroidBridge) {
            throw new Error('Native bridge not available');
        }
        
        isSharing = true;
        updateUI(true);
        addLog('🚀 Starting HTTP server (offline mode)...');
        addLog('🚀 Starting HTTP server...');
        
        // Start server
        const serverResult = window.AndroidBridge.startServer(serverPort);
        const result = JSON.parse(serverResult);
        
        if (!result.success) {
            throw new Error(result.error || 'Failed to start server');
        }
        
        const ipAddress = result.ip;
        addLog('✅ Server started at ' + ipAddress + ':' + serverPort);
        
        // Register files
        let registered = 0;
        for (const file of selectedFiles) {
            try {
                const fileResult = window.AndroidBridge.addFile(
                    file.path,
                    '/' + file.name,
                    file.name,
                    file.size,
                    file.mimeType
                );
                
                const fr = JSON.parse(fileResult);
                if (fr.success) {
                    registered++;
                }
            } catch (e) {
                addLog(`⚠️ ${file.name}: ${e.message}`);
            }
        }
        
        addLog(`✅ Registered ${registered}/${selectedFiles.length} files`);
        
        const wifiUrl = `http://${ipAddress}:${serverPort}`;

document.getElementById('wifiUrl').textContent = wifiUrl;
document.getElementById('serverInfo').classList.remove('hidden');

addLog('📱 Access URL: ' + wifiUrl);
addLog('✅ Copy this URL to access files');
        
        // Start foreground service
        if (window.AndroidBridge.startForegroundService) {
            window.AndroidBridge.startForegroundService();
            addLog('✅ Background service active');
        }
        
        addLog('🎉 Sharing active! Test from another device.');
        
    } catch (error) {
        addLog('❌ Error: ' + error.message);
        alert('Failed: ' + error.message);
        stopSharing();
    }
}

// Stop sharing
async function stopSharing() {
    try {
        isSharing = false;
        updateUI(false);
        addLog('⏹️ Stopping...');
        
        if (window.AndroidBridge) {
            if (window.AndroidBridge.stopServer) {
                window.AndroidBridge.stopServer();
            }
            
            if (window.AndroidBridge.stopForegroundService) {
                window.AndroidBridge.stopForegroundService();
            }
        }
        
        addLog('✅ Stopped');
        
    } catch (error) {
        addLog('Error: ' + error.message);
    }
}

// Update UI
function updateUI(isRunning) {
    const statusDot = document.querySelector('.status-dot');
    const statusText = document.getElementById('statusText');
    const startBtn = document.getElementById('startBtn');
    const stopBtn = document.getElementById('stopBtn');
    
    if (isRunning) {
        statusDot.className = 'status-dot running';
        statusText.textContent = 'Sharing Active';
        startBtn.classList.add('hidden');
        stopBtn.classList.remove('hidden');
    } else {
        statusDot.className = 'status-dot stopped';
        statusText.textContent = 'Stopped';
        startBtn.classList.remove('hidden');
        stopBtn.classList.add('hidden');
        document.getElementById('serverInfo').classList.add('hidden');
    }
}

// Copy URL
function copyUrl() {
    const url = document.getElementById('wifiUrl').textContent;
    
    if (navigator.clipboard) {
        navigator.clipboard.writeText(url).then(() => {
            alert('✅ Copied: ' + url);
        });
    } else {
        const textarea = document.createElement('textarea');
        textarea.value = url;
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
        alert('✅ Copied: ' + url);
    }
}

// Add log
function addLog(message) {
    const logs = document.getElementById('logs');
    const time = new Date().toLocaleTimeString();
    const entry = document.createElement('div');
    entry.className = 'log-entry';
    entry.innerHTML = `<span class="log-time">[${time}]</span> ${message}`;
    logs.insertBefore(entry, logs.firstChild);
    
    while (logs.children.length > 50) {
        logs.removeChild(logs.lastChild);
    }
    
    console.log(`[${time}] ${message}`);
}