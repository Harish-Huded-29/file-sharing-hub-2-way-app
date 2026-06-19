// Global variables
let isSharing = false;
let selectedFiles = [];
let serverPort = 8080;
let deviceHostname = 'immortal';
let pollInterval = null;
let activeServerPort = 8080;
let isTwoWayDisabled = false;

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
        const result = typeof filesJson === 'string' ? JSON.parse(filesJson) : filesJson;
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
    
    // Initialize UI controls
    const toggle = document.getElementById('disableTwoWayToggle');
    if (toggle) {
        toggle.checked = false;
    }
    isTwoWayDisabled = false;
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
            let newlyRegistered = 0;
            for (const file of result.files) {
                const exists = selectedFiles.some(f => f.path === file.path);
                if (!exists) {
                    selectedFiles.push({
                        name: file.name,
                        path: file.path,
                        size: file.size || 0,
                        mimeType: file.mimeType || 'application/octet-stream'
                    });
                    
                    // If the server is running, dynamically register the new file
                    if (isSharing && window.AndroidBridge && window.AndroidBridge.addFile) {
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
                                newlyRegistered++;
                            }
                        } catch (err) {
                            console.error('Dynamic register failed:', err);
                        }
                    }
                }
            }
            
            saveFiles();
            updateFileList();
            addLog(`✅ Added ${result.files.length} files`);
            if (isSharing && newlyRegistered > 0) {
                addLog(`📡 Dynamically registered ${newlyRegistered} files with the running server`);
                syncFilesWithServer();
            }
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
        const file = selectedFiles[index];
        selectedFiles.splice(index, 1);
        saveFiles();
        updateFileList();
        addLog(`🗑️ Deleted: ${fileName}`);
        
        // If the server is running, dynamically remove it from the server mapping
        if (isSharing && window.AndroidBridge && window.AndroidBridge.removeFile) {
            try {
                window.AndroidBridge.removeFile('/' + file.name);
                syncFilesWithServer();
            } catch (err) {
                console.error('Dynamic unregister failed:', err);
            }
        }
        
        // Auto-enable two-way transfer if no files left to share
        if (isTwoWayDisabled && selectedFiles.length === 0) {
            isTwoWayDisabled = false;
            const toggle = document.getElementById('disableTwoWayToggle');
            if (toggle) toggle.checked = false;
            if (window.AndroidBridge && window.AndroidBridge.setTwoWayTransferDisabled) {
                window.AndroidBridge.setTwoWayTransferDisabled(false);
            }
            addLog("🔓 Two-way transfer auto-enabled because no files are left to share.");
        }
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
        
        // Push current Two-Way Disabled state to native server
        if (window.AndroidBridge && window.AndroidBridge.setTwoWayTransferDisabled) {
            window.AndroidBridge.setTwoWayTransferDisabled(isTwoWayDisabled);
        }
        
        const ipAddress = result.ip;
        const actualPort = result.port || serverPort;
        activeServerPort = actualPort;
        addLog('✅ Server started at ' + ipAddress + ':' + actualPort);
        
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
        
        const wifiUrl = `http://${ipAddress}:${actualPort}`;

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
        startPolling();
        
    } catch (error) {
        addLog('❌ Error: ' + error.message);
        alert('Failed: ' + error.message);
        stopSharing();
    }
}

// Stop sharing
async function stopSharing() {
    try {
        stopPolling();
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
        
        // Clear selected files when server stops
        selectedFiles = [];
        saveFiles();
        updateFileList();
        
        // Reset Two-way transfer disabled status on stop sharing
        isTwoWayDisabled = false;
        const toggle = document.getElementById('disableTwoWayToggle');
        if (toggle) {
            toggle.checked = false;
        }
        if (window.AndroidBridge && window.AndroidBridge.setTwoWayTransferDisabled) {
            window.AndroidBridge.setTwoWayTransferDisabled(false);
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
function copyUrl(elementId) {
    const id = elementId || 'wifiUrl';
    const url = document.getElementById(id).textContent;
    
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

// Open URL
function openUrl(elementId) {
    const id = elementId || 'wifiUrl';
    const url = document.getElementById(id).textContent;
    
    if (window.AndroidBridge && window.AndroidBridge.openBrowser) {
        window.AndroidBridge.openBrowser(url);
    } else {
        window.open(url, '_blank');
    }
}

// Add log
function addLog(message) {
    const time = new Date().toLocaleTimeString();
    console.log(`[${time}] ${message}`);
}

// Polling and synchronization for server files (auto-refresh)
function startPolling() {
    if (pollInterval) clearInterval(pollInterval);
    pollInterval = setInterval(syncFilesWithServer, 3000);
}

function stopPolling() {
    if (pollInterval) {
        clearInterval(pollInterval);
        pollInterval = null;
    }
}

async function syncFilesWithServer() {
    if (!isSharing) return;
    try {
        const res = await fetch(`http://127.0.0.1:${activeServerPort}/api/peers`);
        const data = await res.json();
        if (data && data.files) {
            selectedFiles = data.files.map(f => ({
                name: f.name,
                path: f.path,
                size: f.size
            }));
            updateFileList();
            
            // Auto-enable two-way transfer if no files left to share
            if (isTwoWayDisabled && selectedFiles.length === 0) {
                isTwoWayDisabled = false;
                const toggle = document.getElementById('disableTwoWayToggle');
                if (toggle) toggle.checked = false;
                if (window.AndroidBridge && window.AndroidBridge.setTwoWayTransferDisabled) {
                    window.AndroidBridge.setTwoWayTransferDisabled(false);
                }
                addLog("🔓 Two-way transfer auto-enabled because no files are left to share.");
            }
        }
    } catch (e) {
        console.error('Error syncing files with server:', e);
    }
}

// Toggle Two-Way Transfer
function toggleTwoWayTransfer() {
    const toggle = document.getElementById('disableTwoWayToggle');
    if (toggle) {
        if (toggle.checked && selectedFiles.length === 0) {
            alert("Please upload at least one file before turning that on, as the server is of no use if no one can upload data and there is no data to receive.");
            toggle.checked = false;
            isTwoWayDisabled = false;
            return;
        }
        isTwoWayDisabled = toggle.checked;
        addLog(`🔒 Two-way transfer disabled status: ${isTwoWayDisabled}`);
        
        if (window.AndroidBridge && window.AndroidBridge.setTwoWayTransferDisabled) {
            window.AndroidBridge.setTwoWayTransferDisabled(isTwoWayDisabled);
        }
    }
}

// Help Modal controls
function openHelpModal() {
    const modal = document.getElementById('helpModal');
    if (modal) {
        modal.classList.add('show');
    }
}

function closeHelpModal() {
    const modal = document.getElementById('helpModal');
    if (modal) {
        modal.classList.remove('show');
    }
}

// Close modal when clicking outside of it
document.addEventListener('click', function(event) {
    const modal = document.getElementById('helpModal');
    if (modal && event.target === modal) {
        closeHelpModal();
    }
});