# File Sharing Hub (by Immortal)

File Sharing Hub is a premium, high-speed, and secure peer-to-peer (P2P) offline file sharing application built for Android and web browsers. It enables users to transfer large files instantly over local Wi-Fi networks or Mobile Hotspots without requiring any active internet connection.

---

## 🚀 Key Features

*   **100% Offline P2P Sharing**: Transfers travel entirely within the local router or hotspot network. No internet connection is ever consumed or required.
*   **Zero-Installation Client Access**: The recipient does not need to install any application. They can download and upload files directly through their default web browser (Chrome, Safari, Edge, Firefox).
*   **Dual-Protocol Local DNS (mDNS + LLMNR)**:
    *   **mDNS** (`http://immortal.local:<port>`): Resolves natively on macOS, iOS, Linux, and modern Windows/Android devices.
    *   **LLMNR** (`http://immortal:<port>`): Resolves natively on Windows PCs, acting as a seamless fallback.
*   **Smart Subnet-Aware IP Resolution**: Automatically detects the interface the query came from (such as Mobile Hotspot `ap0` or local Wi-Fi `wlan0`) and responds with the correct, reachable gateway IP (e.g. `192.168.43.1`).
*   **Zero-File Server Boot**: Allows starting the server immediately with 0 files, supporting real-time dynamic registration and unregistration of files while the server is active.
*   **Background Lifecycle Protection**: Uses an Android Foreground Service, notification channels, and power wake-locks to prevent the OS from killing the sharing server when the screen goes to sleep.
*   **Automatic Cache Cleanup**: Clears all server-cached uploads on both server start and stop to preserve the phone's internal storage.

---

## 🎨 Branding & Logo Identity

The logo has been redesigned to reflect a premium, dark-themed developer persona matching the **"Immortal"** branding.

### Design Elements:
*   **Theme**: Dark Mode / Obsidian Aesthetic.
*   **Primary Icon**: A sleek, abstract geometric representation of file transfers (neon-purple and electric-teal glow wireless waves passing through a file document glyph).
*   **Background**: Rich obsidian-black gradient backing with a soft outer glow.
*   **Typography**: Modern, clean, sans-serif design suited for futuristic styling.
*   **Color Hexes**:
    *   Obsidian Dark: `#0d0e15` to `#161722`
    *   Neon Purple: `#9f7aea` / `#7f9cf5`
    *   Electric Teal: `#319795` / `#4fd1c5`

---

## 💻 Technical Architecture
*   **Frontend**: Capacitor.js, Vanilla HTML5, CSS3, Javascript (ES6).
*   **Backend Server**: Custom Java HTTP Daemon, Multicast DNS (mDNS) responder (UDP 5353), and LLMNR responder (UDP 5355).
*   **Build System**: Gradle + Capacitor sync tools.
