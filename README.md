# 📱 File Sharing Hub (Offline Local Server Share)

An ultra-fast, premium, glassmorphic dark-mode web server application that lets you share files from your Android phone to any device (laptops, other phones, tablets) completely **offline**—no internet or mobile data required!

---

## 🔍 How the App Works (In Simple Terms)

1. **Your Phone Becomes a Web Server**: When you start the server, the app launches a lightweight HTTP web server directly on your mobile device.
2. **Local Network Connection**: Other devices (like a laptop or tablet) connect to the same Wi-Fi network or your phone's personal mobile hotspot.
3. **Direct Web Browser Access**: The app displays an **Access URL** (for example, `http://192.168.43.1:8080`). When a laptop user enters this link in their web browser:
   - They see a beautiful, responsive dark-mode portal.
   - They can **download** files shared by the phone.
   - They can **upload** files from the laptop straight to the phone.
4. **Live Auto-Sync**: The phone's screen and the laptop's browser synchronize in real-time. If a file is uploaded or deleted, the lists update automatically without needing to restart the server.

---

## 🛠️ Prerequisites (What you need to install first)

To build this app, you need to install a few standard tools on your computer. Don't worry—these are safe and standard software tools:

### 1. Node.js (JavaScript Environment)
* **What it is**: Run-time environment used to download packages and build the app interface.
* **How to get it**: Go to [nodejs.org](https://nodejs.org/) and download the **LTS (Recommended for Most Users)** version. Install it with all default settings.

### 2. Python (Scripting Language)
* **What it is**: Used to run our automated icon generator script so you don't have to manually format images.
* **How to get it**: Go to [python.org](https://www.python.org/downloads/) and download the latest version.
* > [!IMPORTANT]
  > During installation, make sure to check the box that says **"Add Python to PATH"** before clicking Install.

### 3. Java Development Kit (JDK 17)
* **What it is**: Java compiler required to compile Android code.
* **How to get it**: Download **JDK 17** from [Oracle's website](https://www.oracle.com/java/technologies/downloads/#java17) or download it automatically inside Android Studio.

### 4. Android Studio & Android SDK
* **What it is**: The official tool to build and package Android applications.
* **How to get it**: Download and install [Android Studio](https://developer.android.com/studio). Open it once and let it install the default **SDK tools** and **Command Line Tools**.

---

## 🚀 Step-by-Step Compilation Guide

Follow these steps in order. If you've never used a command line, simply open your computer's **Terminal** (Mac/Linux) or **PowerShell / Command Prompt** (Windows) and type the commands exactly as shown below:

### Step 1: Download and Navigate to the Folder
Download the repository files to your computer, open your terminal, and navigate (`cd`) into your project folder.
```bash
# Example (replace with your actual folder path)
cd C:\Path\To\Your\BluetoothShareApp
```

### Step 2: Install Code Dependencies
This command downloads the libraries and tools needed to compile the application.
```bash
npm install
```

### Step 3: Automatically Generate App Launcher Icons
We have included a Python script that takes `www/icon.png` and automatically converts, resizes, and masks it into square and round launcher icons for all Android screen densities.
```bash
python generate_android_icons.py
```

### Step 4: Sync Web Code with Android
This command copies the HTML, CSS, and JavaScript files into the Android project shell assets.
```bash
npx cap sync android
```

### Step 5: Clean and Compile the App
Now we compile the native Android Java code into the installable Android package (`.apk`):

1. **Move into the android folder**:
   ```bash
   cd android
   ```

2. **Clean up old builds**:
   - **On Windows**:
     ```powershell
     .\gradlew.bat clean
     ```
   - **On macOS / Linux**:
     ```bash
     ./gradlew clean
     ```

3. **Assemble/Build the APK**:
   - **On Windows**:
     ```powershell
     .\gradlew.bat assembleDebug
     ```
   - **On macOS / Linux**:
     ```bash
     ./gradlew assembleDebug
     ```

---

## 📲 Installing the App on your Device

Once the build finishes successfully, you will find the installable application package file here:
`android/app/build/outputs/apk/debug/app-debug.apk`

1. Copy this `app-debug.apk` file to your Android phone (via USB cable, Google Drive, email, or messaging app).
2. On your phone, tap the file to install it. 
3. *If prompted, allow installation from unknown sources.*

---

## 🎨 File Layout Reference
* [www/index.html](www/index.html) - Main app screen HTML layout.
* [www/css/style.css](www/css/style.css) - Premium dark styling configurations.
* [www/js/app.js](www/js/app.js) - Handles local UI actions, sharing server startup, and real-time syncing.
* [android/app/src/main/java/com/filesharinghub/app/HttpServerPlugin.java](android/app/src/main/java/com/filesharinghub/app/HttpServerPlugin.java) - Native background Java web server and upload/download processing engine.
