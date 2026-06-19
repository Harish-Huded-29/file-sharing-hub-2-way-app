// Load Capacitor dynamically
(function() {
    // Check if running in native app
    if (window.Capacitor) {
        console.log('Running in Capacitor native app');
    } else {
        console.log('Running in browser - limited functionality');
    }

    // Initialize Capacitor plugins when ready
    document.addEventListener('DOMContentLoaded', function() {
        if (window.Capacitor) {
            // Request necessary permissions
            requestPermissions();
        }
    });

    async function requestPermissions() {
        try {
            // Request notification permission for background service
            if (window.Capacitor.Plugins && window.Capacitor.Plugins.LocalNotifications) {
                await window.Capacitor.Plugins.LocalNotifications.requestPermissions();
            }

            // Request storage permission
            if (window.Capacitor.Plugins && window.Capacitor.Plugins.Filesystem) {
                await window.Capacitor.Plugins.Filesystem.requestPermissions();
            }
        } catch (error) {
            console.error('Permission request error:', error);
        }
    }
})();