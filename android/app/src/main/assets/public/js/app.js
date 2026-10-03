// Global variables
let isSharing = false;
let selectedFiles = [];
let serverPort = 9090;
let deviceHostname = 'filesharehub';
let pollInterval = null;
let activeServerPort = 9090;
let isTwoWayDisabled = false;

// Storage keys
const STORAGE_KEY = 'bluetooth_share_files';
const HOSTNAME_STORAGE_KEY = 'custom_dns_hostname';
const SETUP_DONE_KEY = 'custom_dns_setup_done';

// Check if device has local network (doesn't need internet)
function checkLocalNetwork() {
    addLog('📶 Checking local network...');
    
    if (!window.AndroidBridge) {
        addLog('⚠️ AndroidBridge not ready');
        return false;
    }
    
    addLog('✅ Local network ready (no internet required)');
    return true;
}

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
    loadDeviceHostname();
    addLog('App initialized');
    
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
    loadDeviceHostname();
    addLog('DNS Hostname: ' + deviceHostname);
    await loadStoredFiles();
    
    // Initialize UI controls
    const toggle = document.getElementById('disableTwoWayToggle');
    if (toggle) {
        toggle.checked = false;
    }
    isTwoWayDisabled = false;

    // First time setup check (prompt on first launch only)
    const isFirstTime = !localStorage.getItem(SETUP_DONE_KEY);
    if (isFirstTime) {
        setTimeout(() => {
            openNameModal(true);
        }, 300);
    }
}

// Resolve device hostname dynamically
function loadDeviceHostname() {
    try {
        let stored = localStorage.getItem(HOSTNAME_STORAGE_KEY);
        if (stored && stored.trim()) {
            deviceHostname = stored.trim().replace(/[^a-zA-Z0-9-]/g, '').toLowerCase();
        } else {
            deviceHostname = 'filesharehub';
        }
    } catch (e) {
        deviceHostname = 'filesharehub';
    }
    const currentNameEl = document.getElementById('currentNameDisplay');
    if (currentNameEl) {
        currentNameEl.textContent = deviceHostname;
    }
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
        addLog('🚀 Starting HTTP & Offline DNS server...');
        
        // Start server with custom DNS hostname
        const serverResult = window.AndroidBridge.startServer(serverPort, deviceHostname);
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
        const effectiveHost = result.hostname || deviceHostname;
        const dnsUrl = `${effectiveHost}.local:${actualPort}`;
        const wifiUrl = `${ipAddress}:${actualPort}`;
        
        addLog('✅ Server started at ' + ipAddress + ':' + actualPort);
        addLog('🌐 Link Ready: ' + dnsUrl);
        
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
        
        // Update URL Displays
        const dnsUrlEl = document.getElementById('dnsUrl');
        if (dnsUrlEl) dnsUrlEl.textContent = dnsUrl;
        
        const wifiUrlEl = document.getElementById('wifiUrl');
        if (wifiUrlEl) wifiUrlEl.textContent = wifiUrl;
        
        document.getElementById('serverInfo').classList.remove('hidden');

        addLog('📱 Hostname URL: ' + dnsUrl);
        addLog('📍 Direct IP URL: ' + wifiUrl);
        
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
    const changeNameBtn = document.getElementById('changeNameBtn');
    
    if (isRunning) {
        statusDot.className = 'status-dot running';
        statusText.textContent = 'Sharing Active';
        startBtn.classList.add('hidden');
        stopBtn.classList.remove('hidden');
        if (changeNameBtn) changeNameBtn.classList.add('hidden');
    } else {
        statusDot.className = 'status-dot stopped';
        statusText.textContent = 'Stopped';
        startBtn.classList.remove('hidden');
        stopBtn.classList.add('hidden');
        if (changeNameBtn) changeNameBtn.classList.remove('hidden');
        document.getElementById('serverInfo').classList.add('hidden');
    }
}

// Copy URL
function copyUrl(elementId) {
    const id = elementId || 'wifiUrl';
    const text = document.getElementById(id).textContent.trim();
    const copyText = text.startsWith('http://') || text.startsWith('https://') ? text : `http://${text}`;
    
    if (navigator.clipboard) {
        navigator.clipboard.writeText(copyText).then(() => {
            alert('✅ Copied: ' + copyText);
        });
    } else {
        const textarea = document.createElement('textarea');
        textarea.value = copyText;
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
        alert('✅ Copied: ' + copyText);
    }
}

// Open URL
function openUrl(elementId) {
    const id = elementId || 'wifiUrl';
    const text = document.getElementById(id).textContent.trim();
    const url = text.startsWith('http://') || text.startsWith('https://') ? text : `http://${text}`;
    
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
            const newFiles = data.files.map(f => ({
                name: f.name,
                path: f.path,
                size: f.size
            }));
            
            const isChanged = newFiles.length !== selectedFiles.length || 
                              newFiles.some((f, idx) => !selectedFiles[idx] || f.name !== selectedFiles[idx].name || f.size !== selectedFiles[idx].size);
            
            if (isChanged) {
                selectedFiles = newFiles;
                updateFileList();
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
    } catch (e) {
        // silent
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

// Change User Name Modal controls (supports first-time setup and manual change)
function openNameModal(isFirstTime = false) {
    loadDeviceHostname();
    const input = document.getElementById('modalHostnameInput');
    
    const modalTitle = document.getElementById('modalTitle');
    const modalDesc = document.getElementById('modalDesc');
    const cancelBtn = document.getElementById('modalCancelBtn');
    const closeBtn = document.getElementById('modalCloseBtn');
    const saveBtn = document.getElementById('modalSaveBtn');
    const modalFooter = document.querySelector('#nameModal .modal-footer');
    
    if (isFirstTime) {
        if (input) {
            input.value = '';
            input.placeholder = 'Enter user name';
        }
        updateModalPreview('');
        if (modalTitle) modalTitle.textContent = '🚀 Welcome! Set User Name';
        if (modalDesc) modalDesc.textContent = 'Choose a custom username for your offline file sharing. Other devices will use this name to connect and download your files.';
        if (cancelBtn) cancelBtn.style.display = 'none';
        if (closeBtn) closeBtn.style.display = 'none';
        if (saveBtn) {
            saveBtn.textContent = '💾 Save & Continue';
            saveBtn.style.margin = '0 auto';
        }
        if (modalFooter) {
            modalFooter.style.justifyContent = 'center';
        }
    } else {
        if (input) {
            input.value = deviceHostname;
            input.placeholder = 'Enter user name';
        }
        updateModalPreview(deviceHostname);
        if (modalTitle) modalTitle.textContent = '✏️ Change User Name';
        if (modalDesc) modalDesc.textContent = 'Enter a custom username for your offline link. Peer devices will use this name to access your shared files.';
        if (cancelBtn) cancelBtn.style.display = 'inline-block';
        if (closeBtn) closeBtn.style.display = 'inline-block';
        if (saveBtn) {
            saveBtn.textContent = '💾 Save User Name';
            saveBtn.style.margin = '';
        }
        if (modalFooter) {
            modalFooter.style.justifyContent = 'flex-end';
        }
    }
    
    const modal = document.getElementById('nameModal');
    if (modal) {
        modal.classList.add('show');
    }
}

function closeNameModal() {
    const modal = document.getElementById('nameModal');
    if (modal) {
        modal.classList.remove('show');
    }
    if (!localStorage.getItem(SETUP_DONE_KEY)) {
        localStorage.setItem(SETUP_DONE_KEY, 'true');
    }
}

function updateModalPreview(val) {
    const clean = (val || '').replace(/[^a-zA-Z0-9-]/g, '').toLowerCase();
    const preview = document.getElementById('modalDnsPreview');
    if (preview) {
        preview.textContent = `${clean || 'name'}.local:${activeServerPort}`;
    }
}

function saveCustomHostname() {
    const input = document.getElementById('modalHostnameInput');
    let val = input ? input.value : '';
    let clean = val.replace(/[^a-zA-Z0-9-]/g, '').toLowerCase();
    if (!clean) {
        clean = 'filesharehub';
    }
    deviceHostname = clean;
    localStorage.setItem(HOSTNAME_STORAGE_KEY, deviceHostname);
    localStorage.setItem(SETUP_DONE_KEY, 'true');
    const currentNameEl = document.getElementById('currentNameDisplay');
    if (currentNameEl) {
        currentNameEl.textContent = deviceHostname;
    }
    closeNameModal();
    addLog('👤 User Name updated: ' + deviceHostname);
}

// DNS Info Modal controls (Why DNS is limited on Android)
function openDnsInfoModal() {
    const modal = document.getElementById('dnsInfoModal');
    if (modal) {
        modal.classList.add('show');
    }
}

function closeDnsInfoModal() {
    const modal = document.getElementById('dnsInfoModal');
    if (modal) {
        modal.classList.remove('show');
    }
}

// Global Back Button Handler (for hardware back button)
window.handleAppBack = function() {
    const modals = ['dnsInfoModal', 'nameModal', 'helpModal'];
    for (const id of modals) {
        const el = document.getElementById(id);
        if (el && el.classList.contains('show')) {
            el.classList.remove('show');
            return true; // handled, don't exit app
        }
    }
    return false; // no modal open, allow normal back behavior
};

// Close modals when clicking outside
document.addEventListener('click', function(event) {
    const helpModal = document.getElementById('helpModal');
    if (helpModal && event.target === helpModal) {
        closeHelpModal();
    }
    const nameModal = document.getElementById('nameModal');
    if (nameModal && event.target === nameModal) {
        closeNameModal();
    }
    const dnsInfoModal = document.getElementById('dnsInfoModal');
    if (dnsInfoModal && event.target === dnsInfoModal) {
        closeDnsInfoModal();
    }
});