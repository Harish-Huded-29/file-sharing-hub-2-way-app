package com.filesharinghub.app;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class LocalDnsResponder {
    private static final String TAG = "LocalDnsResponder";

    private final Context context;
    private volatile String hostname = "filesharehub";
    private volatile String previousHostname = null;
    private volatile int httpPort = 8080;
    private volatile String localIpAddress = null;
    private volatile boolean isRunning = false;

    // Multicast & Nsd fields
    private WifiManager.MulticastLock multicastLock;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener nsdListener;

    private Thread mdnsThread;
    private Thread llmnrThread;
    private Thread nbnsThread;
    private Thread dns53Thread;
    private Thread announcementThread;

    private MulticastSocket mdnsSocket;
    private MulticastSocket llmnrSocket;
    private DatagramSocket nbnsSocket;
    private DatagramSocket dns53Socket;

    private static final String MDNS_IPV4_GROUP = "224.0.0.251";
    private static final String MDNS_IPV6_GROUP = "ff02::fb";
    private static final int MDNS_PORT = 5353;

    private static final String LLMNR_IPV4_GROUP = "224.0.0.252";
    private static final String LLMNR_IPV6_GROUP = "ff02::1:3";
    private static final int LLMNR_PORT = 5355;

    private static final int NBNS_PORT = 137;
    private static final int DNS_PORT = 53;

    public LocalDnsResponder(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    public synchronized void start(String customHostname, int serverPort, String localIpAddress) {
        String newHostname = (customHostname != null && !customHostname.trim().isEmpty())
                ? customHostname.trim().toLowerCase(Locale.US)
                : "filesharehub";

        // If running or renaming, send Goodbye packets for the previous hostname first
        if (isRunning) {
            stop();
        } else if (previousHostname != null && !previousHostname.equalsIgnoreCase(newHostname)) {
            sendMdnsGoodbye(previousHostname, this.localIpAddress != null ? this.localIpAddress : localIpAddress);
        }

        this.previousHostname = this.hostname;
        this.hostname = newHostname;
        this.httpPort = serverPort;
        this.localIpAddress = localIpAddress;
        this.isRunning = true;

        Log.d(TAG, "🚀 Starting Parallel Local DNS Engine for hostname: '" + this.hostname + "' (Port: " + httpPort + ", IP: " + localIpAddress + ")");

        acquireMulticastLock();
        startNsdService();
        startMdnsServer();
        startLlmnrServer();
        startNbnsServer();
        startStandardDnsServer();
        startPeriodicAnnouncements();
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        Log.d(TAG, "⏹️ Stopping Local DNS Responders and sending goodbye cache-flush packets for '" + hostname + "'...");

        // Broadcast Goodbye packets (TTL = 0) so Mac, Linux, and Windows clients immediately flush their local DNS cache
        try {
            sendMdnsGoodbye(this.hostname, resolveCurrentIp());
        } catch (Exception ignored) {}

        stopNsdService();

        try {
            if (mdnsSocket != null && !mdnsSocket.isClosed()) {
                mdnsSocket.close();
            }
        } catch (Exception ignored) {}

        try {
            if (llmnrSocket != null && !llmnrSocket.isClosed()) {
                llmnrSocket.close();
            }
        } catch (Exception ignored) {}

        try {
            if (nbnsSocket != null && !nbnsSocket.isClosed()) {
                nbnsSocket.close();
            }
        } catch (Exception ignored) {}

        try {
            if (dns53Socket != null && !dns53Socket.isClosed()) {
                dns53Socket.close();
            }
        } catch (Exception ignored) {}

        if (mdnsThread != null) {
            mdnsThread.interrupt();
            mdnsThread = null;
        }

        if (llmnrThread != null) {
            llmnrThread.interrupt();
            llmnrThread = null;
        }

        if (nbnsThread != null) {
            nbnsThread.interrupt();
            nbnsThread = null;
        }

        if (dns53Thread != null) {
            dns53Thread.interrupt();
            dns53Thread = null;
        }

        if (announcementThread != null) {
            announcementThread.interrupt();
            announcementThread = null;
        }

        releaseMulticastLock();
        Log.d(TAG, "✅ All Local DNS Responders stopped cleanly");
    }

    public String getHostname() {
        return hostname != null ? hostname : "filesharehub";
    }

    public String getDnsUrl() {
        return "http://" + getHostname() + ".local:" + httpPort;
    }

    // ================= MULTICAST LOCK =================
    private void acquireMulticastLock() {
        if (context == null) return;
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("LocalDnsMulticastLock");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
                Log.d(TAG, "🔒 MulticastLock acquired");
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Could not acquire MulticastLock: " + e.getMessage());
        }
    }

    private void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
                multicastLock = null;
                Log.d(TAG, "🔓 MulticastLock released");
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Error releasing MulticastLock: " + e.getMessage());
        }
    }

    // ================= NSD MANAGER (Native Android DNS-SD) =================
    private void startNsdService() {
        if (context == null) return;
        try {
            nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) return;

            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName(this.hostname);
            serviceInfo.setServiceType("_http._tcp.");
            serviceInfo.setPort(this.httpPort);

            nsdListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo info) {
                    Log.d(TAG, "✅ NsdManager service registered: " + info.getServiceName());
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "⚠️ NsdManager registration failed: " + errorCode);
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo info) {
                    Log.d(TAG, "🔌 NsdManager service unregistered");
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                    Log.w(TAG, "⚠️ NsdManager unregistration failed: " + errorCode);
                }
            };

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception e) {
            Log.w(TAG, "⚠️ NsdManager registration error: " + e.getMessage());
        }
    }

    private void stopNsdService() {
        try {
            if (nsdManager != null && nsdListener != null) {
                nsdManager.unregisterService(nsdListener);
                nsdListener = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Error unregistering NsdManager: " + e.getMessage());
        }
    }

    // ================= mDNS UDP 5353 RESPONDER (Mac / Linux / iOS / Bonjour) =================
    private void startMdnsServer() {
        mdnsThread = new Thread(() -> {
            try {
                mdnsSocket = new MulticastSocket(null);
                mdnsSocket.setReuseAddress(true);
                mdnsSocket.setBroadcast(true);
                mdnsSocket.bind(new InetSocketAddress(MDNS_PORT));
                mdnsSocket.setTimeToLive(255);

                joinGroupOnAllInterfaces(mdnsSocket, MDNS_IPV4_GROUP, MDNS_IPV6_GROUP);

                byte[] buffer = new byte[4096];
                Log.d(TAG, "📡 mDNS Server listening on UDP 5353 (Mac / Linux / iOS / Bonjour)");

                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        mdnsSocket.receive(packet);

                        handleMdnsQuery(packet);
                    } catch (SocketException se) {
                        if (!isRunning) break;
                    } catch (Exception e) {
                        if (isRunning) {
                            Log.w(TAG, "mDNS packet processing error: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "❌ mDNS Server start error: " + e.getMessage());
                }
            }
        }, "mDNS-Responder-Thread");
        mdnsThread.start();
    }

    // ================= LLMNR UDP 5355 RESPONDER (Windows & Linux Resolved Fallback) =================
    private void startLlmnrServer() {
        llmnrThread = new Thread(() -> {
            try {
                llmnrSocket = new MulticastSocket(null);
                llmnrSocket.setReuseAddress(true);
                llmnrSocket.setBroadcast(true);
                llmnrSocket.bind(new InetSocketAddress(LLMNR_PORT));
                llmnrSocket.setTimeToLive(255);

                joinGroupOnAllInterfaces(llmnrSocket, LLMNR_IPV4_GROUP, LLMNR_IPV6_GROUP);

                byte[] buffer = new byte[2048];
                Log.d(TAG, "📡 LLMNR Server listening on UDP 5355 (Windows & Linux systemd-resolved)");

                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        llmnrSocket.receive(packet);

                        handleLlmnrQuery(packet);
                    } catch (SocketException se) {
                        if (!isRunning) break;
                    } catch (Exception e) {
                        if (isRunning) {
                            Log.w(TAG, "LLMNR packet error: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "❌ LLMNR Server start error: " + e.getMessage());
                }
            }
        }, "LLMNR-Responder-Thread");
        llmnrThread.start();
    }

    // ================= NBNS UDP 137 RESPONDER (NetBIOS Name Service) =================
    private void startNbnsServer() {
        nbnsThread = new Thread(() -> {
            try {
                nbnsSocket = new DatagramSocket(null);
                nbnsSocket.setReuseAddress(true);
                nbnsSocket.setBroadcast(true);
                nbnsSocket.bind(new InetSocketAddress(NBNS_PORT));

                byte[] buffer = new byte[2048];
                Log.d(TAG, "📡 NetBIOS NBNS Server listening on UDP 137");

                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        nbnsSocket.receive(packet);
                        handleNbnsQuery(packet, nbnsSocket);
                    } catch (SocketException se) {
                        if (!isRunning) break;
                    } catch (Exception e) {
                        if (isRunning) {
                            Log.w(TAG, "NBNS packet error: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "ℹ️ NBNS UDP 137 not bound: " + e.getMessage());
            }
        }, "NBNS-Responder-Thread");
        nbnsThread.start();
    }

    // ================= STANDARD DNS UDP 53 RESPONDER =================
    private void startStandardDnsServer() {
        dns53Thread = new Thread(() -> {
            try {
                dns53Socket = new DatagramSocket(null);
                dns53Socket.setReuseAddress(true);
                dns53Socket.bind(new InetSocketAddress(DNS_PORT));

                byte[] buffer = new byte[2048];
                Log.d(TAG, "📡 Standard DNS Server listening on UDP 53");

                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        dns53Socket.receive(packet);
                        handleStandardDnsQuery(packet);
                    } catch (SocketException se) {
                        if (!isRunning) break;
                    } catch (Exception e) {
                        if (isRunning) {
                            Log.w(TAG, "DNS 53 query error: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "ℹ️ DNS UDP 53 not bound (normal if system tethering handles 53): " + e.getMessage());
            }
        }, "DNS53-Responder-Thread");
        dns53Thread.start();
    }

    // ================= PERIODIC mDNS ANNOUNCEMENTS =================
    private void startPeriodicAnnouncements() {
        announcementThread = new Thread(() -> {
            try {
                // Broadcast immediately on startup (3 bursts spaced by 1s)
                for (int i = 0; i < 3 && isRunning; i++) {
                    broadcastMdnsAnnouncement();
                    Thread.sleep(1000);
                }

                while (isRunning) {
                    Thread.sleep(10000);
                    if (isRunning) {
                        broadcastMdnsAnnouncement();
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Log.w(TAG, "Announcement loop error: " + e.getMessage());
            }
        }, "DNS-Announcement-Thread");
        announcementThread.start();
    }

    private void broadcastMdnsAnnouncement() {
        try {
            String ip = resolveCurrentIp();
            if (ip == null || mdnsSocket == null || mdnsSocket.isClosed()) return;
            Inet6Address ipv6 = resolveCurrentIpv6();

            byte[] annBytes = buildFullMdnsAnnouncement(this.hostname, ip, ipv6, this.httpPort, 120);
            if (annBytes != null) {
                InetAddress mcastGroup = InetAddress.getByName(MDNS_IPV4_GROUP);
                DatagramPacket p = new DatagramPacket(annBytes, annBytes.length, mcastGroup, MDNS_PORT);
                sendOnAllInterfaces(mdnsSocket, p);

                try {
                    InetAddress mcastGroupV6 = InetAddress.getByName(MDNS_IPV6_GROUP);
                    DatagramPacket pV6 = new DatagramPacket(annBytes, annBytes.length, mcastGroupV6, MDNS_PORT);
                    sendOnAllInterfaces(mdnsSocket, pV6);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed sending announcement: " + e.getMessage());
        }
    }

    // ================= GOODBYE PACKET (CACHE FLUSH FOR OLD HOSTNAME) =================
    private void sendMdnsGoodbye(String oldHost, String ip) {
        if (oldHost == null || oldHost.trim().isEmpty()) return;
        String cleanOld = oldHost.trim().toLowerCase(Locale.US);
        Log.d(TAG, "👋 Broadcasting mDNS Goodbye (TTL=0) for old hostname: '" + cleanOld + "'");

        try {
            byte[] goodbyeBytes = buildFullMdnsAnnouncement(cleanOld, ip != null ? ip : "127.0.0.1", null, this.httpPort, 0);
            if (goodbyeBytes != null) {
                MulticastSocket tempSock = mdnsSocket != null && !mdnsSocket.isClosed() ? mdnsSocket : new MulticastSocket();
                InetAddress mcastGroup = InetAddress.getByName(MDNS_IPV4_GROUP);
                DatagramPacket p = new DatagramPacket(goodbyeBytes, goodbyeBytes.length, mcastGroup, MDNS_PORT);
                for (int i = 0; i < 2; i++) {
                    sendOnAllInterfaces(tempSock, p);
                }
                if (tempSock != mdnsSocket) {
                    tempSock.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed broadcasting mDNS Goodbye: " + e.getMessage());
        }
    }

    private void joinGroupOnAllInterfaces(MulticastSocket socket, String ipv4GroupStr, String ipv6GroupStr) {
        // 1. Join on default interface
        try {
            socket.joinGroup(InetAddress.getByName(ipv4GroupStr));
        } catch (Exception ignored) {}

        try {
            if (ipv6GroupStr != null) {
                socket.joinGroup(InetAddress.getByName(ipv6GroupStr));
            }
        } catch (Exception ignored) {}

        // 2. Explicitly join on all active network interfaces (including Android Hotspot ap0 / wlan0 / rndis)
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (iface.isUp() && !iface.isLoopback()) {
                        InetAddress v4Group = InetAddress.getByName(ipv4GroupStr);
                        socket.joinGroup(new InetSocketAddress(v4Group, 0), iface);
                        Log.d(TAG, "📡 Joined IPv4 " + ipv4GroupStr + " on " + iface.getName());

                        if (ipv6GroupStr != null) {
                            try {
                                InetAddress v6Group = InetAddress.getByName(ipv6GroupStr);
                                socket.joinGroup(new InetSocketAddress(v6Group, 0), iface);
                                Log.d(TAG, "📡 Joined IPv6 " + ipv6GroupStr + " on " + iface.getName());
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void sendOnAllInterfaces(MulticastSocket socket, DatagramPacket packet) {
        if (socket == null) return;
        try {
            socket.send(packet);
        } catch (Exception ignored) {}

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (iface.isUp() && !iface.isLoopback()) {
                        socket.setNetworkInterface(iface);
                        socket.send(packet);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    // ================= mDNS (LINUX / MAC / IOS / BONJOUR) QUERY HANDLER =================
    private void handleMdnsQuery(DatagramPacket packet) {
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 12) return;

        // Check if query (QR bit in flags is 0)
        int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        boolean isQuery = (flags & 0x8000) == 0;
        if (!isQuery) return;

        int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (qdCount < 1) return;

        int queryId = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        List<ParsedQuestion> questions = parseAllQuestions(data, length, qdCount);
        if (questions.isEmpty()) return;

        String targetBase = this.hostname;
        String ipToAnswer = resolveCurrentIp();
        if (ipToAnswer == null) return;
        Inet6Address ipv6ToAnswer = resolveCurrentIpv6();

        boolean anyHostQuery = false;
        boolean wantsTypeA = false;
        boolean wantsTypeAAAA = false;
        boolean wantsService = false;
        ParsedQuestion matchedQuestion = null;

        for (ParsedQuestion q : questions) {
            String queryName = q.qName.toLowerCase(Locale.US);

            boolean matchesHost = queryName.equals(targetBase)
                    || queryName.equals(targetBase + ".local")
                    || queryName.equals(targetBase + ".local.")
                    || queryName.equals(targetBase + ".lan")
                    || queryName.equals(targetBase + ".home");

            boolean matchesHttpService = queryName.equals("_http._tcp.local")
                    || queryName.equals("_http._tcp.local.")
                    || queryName.equals(targetBase + "._http._tcp.local")
                    || queryName.equals(targetBase + "._http._tcp.local.")
                    || queryName.equals("_services._dns-sd._udp.local")
                    || queryName.equals("_services._dns-sd._udp.local.");

            if (matchesHost) {
                anyHostQuery = true;
                if (matchedQuestion == null) matchedQuestion = q;
                if (q.qType == 1 || q.qType == 255) { // Type A or ANY
                    wantsTypeA = true;
                } else if (q.qType == 28) { // Type AAAA
                    wantsTypeAAAA = true;
                }
            } else if (matchesHttpService) {
                wantsService = true;
                if (matchedQuestion == null) matchedQuestion = q;
            }
        }

        if (!anyHostQuery && !wantsService) return;

        boolean isLegacyUnicast = (packet.getPort() != MDNS_PORT || queryId != 0);

        if (isLegacyUnicast && matchedQuestion != null) {
            // RFC 6762 Section 6.7: Legacy Unicast DNS Response (matching ID, echoed question, no cache-flush bit)
            byte[] response = buildLegacyUnicastResponse(queryId, matchedQuestion, ipToAnswer);
            if (response != null) {
                sendDirectUnicast(response, packet);
                Log.d(TAG, "⚡ [Linux Legacy Unicast mDNS] Responded for '" + matchedQuestion.qName + "' -> " + ipToAnswer);
            }
            return;
        }

        // Standard Multicast DNS (RFC 6762)
        if (anyHostQuery) {
            if (wantsTypeA || (!wantsTypeAAAA)) {
                // Queried for IPv4 A or ANY
                byte[] response = buildMdnsHostResponse(targetBase, ipToAnswer, ipv6ToAnswer, 120);
                if (response != null) {
                    sendMdnsResponse(response, packet);
                    Log.d(TAG, "⚡ [Linux/Mac mDNS] Responded for '" + targetBase + ".local' (Type A) -> " + ipToAnswer);
                }
            } else if (wantsTypeAAAA) {
                // Queried specifically for IPv6 AAAA
                byte[] response = buildMdnsAaaaResponse(targetBase, ipToAnswer, ipv6ToAnswer, 120);
                if (response != null) {
                    sendMdnsResponse(response, packet);
                    Log.d(TAG, "⚡ [Linux/Mac mDNS] Responded for '" + targetBase + ".local' (Type AAAA / NSEC)");
                }
            }
        } else if (wantsService) {
            byte[] response = buildFullMdnsAnnouncement(targetBase, ipToAnswer, ipv6ToAnswer, this.httpPort, 120);
            if (response != null) {
                sendMdnsResponse(response, packet);
                Log.d(TAG, "⚡ [Linux/Mac mDNS DNS-SD] Responded for service");
            }
        }
    }

    private void sendDirectUnicast(byte[] responseBytes, DatagramPacket requestPacket) {
        try {
            DatagramPacket unicastPacket = new DatagramPacket(
                    responseBytes,
                    responseBytes.length,
                    requestPacket.getAddress(),
                    requestPacket.getPort()
            );
            if (mdnsSocket != null && !mdnsSocket.isClosed()) {
                mdnsSocket.send(unicastPacket);
            }
        } catch (Exception e) {
            Log.w(TAG, "Direct unicast send error: " + e.getMessage());
        }
    }

    private void sendMdnsResponse(byte[] responseBytes, DatagramPacket requestPacket) {
        try {
            // 1. Direct unicast reply to client (Linux/Mac/iOS requester)
            sendDirectUnicast(responseBytes, requestPacket);

            // 2. Multicast response across 224.0.0.251:5353
            if (mdnsSocket != null && !mdnsSocket.isClosed()) {
                InetAddress mcastGroup = InetAddress.getByName(MDNS_IPV4_GROUP);
                DatagramPacket mcastPacket = new DatagramPacket(
                        responseBytes,
                        responseBytes.length,
                        mcastGroup,
                        MDNS_PORT
                );
                sendOnAllInterfaces(mdnsSocket, mcastPacket);

                // Also multicast to IPv6 ff02::fb:5353 if feasible
                try {
                    InetAddress mcastGroupV6 = InetAddress.getByName(MDNS_IPV6_GROUP);
                    DatagramPacket mcastPacketV6 = new DatagramPacket(
                            responseBytes,
                            responseBytes.length,
                            mcastGroupV6,
                            MDNS_PORT
                    );
                    sendOnAllInterfaces(mdnsSocket, mcastPacketV6);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "mDNS response send error: " + e.getMessage());
        }
    }

    // ================= LLMNR (WINDOWS & LINUX RESOLVED) QUERY HANDLER =================
    private void handleLlmnrQuery(DatagramPacket packet) {
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 12) return;

        int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        boolean isQuery = (flags & 0x8000) == 0;
        if (!isQuery) return;

        int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (qdCount < 1) return;

        int queryId = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        List<ParsedQuestion> questions = parseAllQuestions(data, length, qdCount);
        if (questions.isEmpty()) return;

        String targetBase = this.hostname;
        ParsedQuestion matchedQuestion = null;

        for (ParsedQuestion q : questions) {
            String queryName = q.qName.toLowerCase(Locale.US);
            boolean matches = queryName.equals(targetBase)
                    || queryName.equals(targetBase + ".local")
                    || queryName.equals(targetBase + ".lan")
                    || queryName.equals(targetBase + ".home");

            if (matches) {
                matchedQuestion = q;
                break;
            }
        }

        if (matchedQuestion == null) return;

        String ipToAnswer = resolveCurrentIp();
        if (ipToAnswer == null) return;

        if (matchedQuestion.qType == 1 || matchedQuestion.qType == 255) {
            byte[] response = buildLlmnrResponse(queryId, matchedQuestion, ipToAnswer, true);
            if (response != null && llmnrSocket != null) {
                try {
                    DatagramPacket resp = new DatagramPacket(
                            response,
                            response.length,
                            packet.getAddress(),
                            packet.getPort()
                    );
                    llmnrSocket.send(resp);
                    Log.d(TAG, "⚡ [Windows/Linux LLMNR] Responded for '" + matchedQuestion.qName + "' -> " + ipToAnswer);
                } catch (Exception e) {
                    Log.w(TAG, "LLMNR send error: " + e.getMessage());
                }
            }
        } else if (matchedQuestion.qType == 28) {
            // Type AAAA: Send response with ANCOUNT=0 for LLMNR so client quickly falls back to IPv4
            byte[] response = buildLlmnrResponse(queryId, matchedQuestion, null, false);
            if (response != null && llmnrSocket != null) {
                try {
                    DatagramPacket resp = new DatagramPacket(
                            response,
                            response.length,
                            packet.getAddress(),
                            packet.getPort()
                    );
                    llmnrSocket.send(resp);
                } catch (Exception ignored) {}
            }
        }
    }

    // ================= STANDARD DNS UDP 53 QUERY HANDLER =================
    private void handleStandardDnsQuery(DatagramPacket packet) {
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 12) return;

        int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        boolean isQuery = (flags & 0x8000) == 0;
        if (!isQuery) return;

        int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (qdCount < 1) return;

        int queryId = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        List<ParsedQuestion> questions = parseAllQuestions(data, length, qdCount);
        if (questions.isEmpty()) return;

        String targetBase = this.hostname;
        ParsedQuestion matchedQuestion = null;

        for (ParsedQuestion q : questions) {
            String queryName = q.qName.toLowerCase(Locale.US);
            boolean matches = queryName.equals(targetBase)
                    || queryName.equals(targetBase + ".local")
                    || queryName.equals(targetBase + ".local.")
                    || queryName.equals(targetBase + ".lan")
                    || queryName.equals(targetBase + ".home");

            if (matches) {
                matchedQuestion = q;
                break;
            }
        }

        if (matchedQuestion == null) return;

        String ipToAnswer = resolveCurrentIp();
        if (ipToAnswer == null) return;

        if (matchedQuestion.qType == 1 || matchedQuestion.qType == 255) {
            byte[] response = buildLlmnrResponse(queryId, matchedQuestion, ipToAnswer, true);
            if (response != null && dns53Socket != null) {
                try {
                    DatagramPacket resp = new DatagramPacket(
                            response,
                            response.length,
                            packet.getAddress(),
                            packet.getPort()
                    );
                    dns53Socket.send(resp);
                } catch (Exception ignored) {}
            }
        }
    }

    // ================= NBNS (NETBIOS) QUERY HANDLER =================
    private void handleNbnsQuery(DatagramPacket packet, DatagramSocket socket) {
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 12) return;

        int flags = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        boolean isQuery = (flags & 0x8000) == 0;
        if (!isQuery) return;

        int qdCount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (qdCount < 1) return;

        int queryId = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);

        int offset = 12;
        if (offset >= length) return;

        int nameLen = data[offset] & 0xFF;
        if (nameLen != 32 || offset + 1 + 32 > length) return;

        String decodedName = decodeNetBiosName(data, offset + 1);
        if (decodedName == null) return;

        String targetBase = this.hostname;
        if (decodedName.equalsIgnoreCase(targetBase)) {
            String ipToAnswer = resolveCurrentIp();
            if (ipToAnswer == null) return;

            byte[] qNameBytes = new byte[34];
            System.arraycopy(data, offset, qNameBytes, 0, 34);

            byte[] response = buildNbnsResponse(queryId, qNameBytes, ipToAnswer);
            if (response != null) {
                try {
                    DatagramPacket respPacket = new DatagramPacket(
                            response,
                            response.length,
                            packet.getAddress(),
                            packet.getPort()
                    );
                    socket.send(respPacket);
                    Log.d(TAG, "⚡ Responded to NetBIOS query for '" + decodedName + "' -> " + ipToAnswer);
                } catch (IOException e) {
                    Log.w(TAG, "Failed sending NetBIOS response: " + e.getMessage());
                }
            }
        }
    }

    private String decodeNetBiosName(byte[] data, int offset) {
        try {
            byte[] decoded = new byte[16];
            for (int i = 0; i < 16; i++) {
                int c1 = (data[offset + i * 2] & 0xFF) - 'A';
                int c2 = (data[offset + i * 2 + 1] & 0xFF) - 'A';
                decoded[i] = (byte) ((c1 << 4) | (c2 & 0x0F));
            }
            return new String(decoded, StandardCharsets.US_ASCII).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] buildNbnsResponse(int txId, byte[] questionNameBytes, String ipAddress) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeShort(txId);
            dos.writeShort(0x8500); // Response + Authoritative + Recursion Desired
            dos.writeShort(0); // QDCOUNT
            dos.writeShort(1); // ANCOUNT
            dos.writeShort(0); // NSCOUNT
            dos.writeShort(0); // ARCOUNT

            dos.write(questionNameBytes);

            dos.writeShort(0x0020); // Type NB
            dos.writeShort(0x0001); // Class IN
            dos.writeInt(120); // TTL 120s
            dos.writeShort(6); // RDLENGTH (2 flags + 4 IP)
            dos.writeShort(0x0000); // Flags: Unique, B-node
            InetAddress inet = InetAddress.getByName(ipAddress);
            dos.write(inet.getAddress());

            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ================= RFC 6762 mDNS BUILDERS (LINUX / MAC / IOS) =================

    /**
     * Builds an RFC 6762 compliant mDNS Response for Hostname Type A resolution.
     * Contains exclusively <host>.local records to satisfy strict Linux Avahi/systemd-resolved validation.
     */
    private byte[] buildMdnsHostResponse(String host, String ipAddress, Inet6Address ipv6Address, int ttl) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeShort(0); // ID = 0 for mDNS
            dos.writeShort(0x8400); // Response + Authoritative
            dos.writeShort(0); // QDCOUNT = 0

            int anCount = (ipv6Address != null) ? 2 : 1;
            int arCount = (ipv6Address == null) ? 1 : 0; // NSEC in Additional section if no IPv6

            dos.writeShort(anCount);
            dos.writeShort(0); // NSCOUNT
            dos.writeShort(arCount);

            String hostLocal = host + ".local";
            InetAddress inet = InetAddress.getByName(ipAddress);

            // Answer 1: <host>.local. IN A <ipAddress>
            writeQName(dos, hostLocal);
            dos.writeShort(1); // Type A
            dos.writeShort(0x8001); // Class IN + Cache-Flush
            dos.writeInt(ttl);
            dos.writeShort(4);
            dos.write(inet.getAddress());

            // Answer 2: If IPv6 available, <host>.local. IN AAAA <ipv6>
            if (ipv6Address != null) {
                writeQName(dos, hostLocal);
                dos.writeShort(28); // Type AAAA
                dos.writeShort(0x8001); // Class IN + Cache-Flush
                dos.writeInt(ttl);
                dos.writeShort(16);
                dos.write(ipv6Address.getAddress());
            } else {
                // Additional 1: NSEC record asserting only Type A exists (prevents Linux systemd-resolved AAAA timeout)
                writeQName(dos, hostLocal);
                dos.writeShort(47); // Type NSEC
                dos.writeShort(0x8001); // Class IN + Cache-Flush
                dos.writeInt(ttl);

                byte[] nextNameBytes = encodeQName(hostLocal);
                dos.writeShort(nextNameBytes.length + 3);
                dos.write(nextNameBytes);
                dos.writeByte(0); // Window 0
                dos.writeByte(1); // Length 1 byte
                dos.writeByte(0x40); // Bit 1 set = Type A exists
            }

            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error building mDNS Host response", e);
            return null;
        }
    }

    /**
     * Builds an RFC 6762 mDNS Response specifically for Type AAAA (IPv6) queries.
     * If IPv6 is absent, returns an explicit NSEC negative response asserting Type A existence.
     */
    private byte[] buildMdnsAaaaResponse(String host, String ipAddress, Inet6Address ipv6Address, int ttl) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeShort(0); // ID = 0 for mDNS
            dos.writeShort(0x8400); // Response + Authoritative
            dos.writeShort(0); // QDCOUNT = 0

            String hostLocal = host + ".local";

            if (ipv6Address != null) {
                dos.writeShort(1); // ANCOUNT = 1 (AAAA)
                dos.writeShort(0); // NSCOUNT
                dos.writeShort(1); // ARCOUNT = 1 (A in Additional)

                // Answer: AAAA
                writeQName(dos, hostLocal);
                dos.writeShort(28); // Type AAAA
                dos.writeShort(0x8001); // Class IN + Cache-Flush
                dos.writeInt(ttl);
                dos.writeShort(16);
                dos.write(ipv6Address.getAddress());

                // Additional: A
                writeQName(dos, hostLocal);
                dos.writeShort(1); // Type A
                dos.writeShort(0x8001);
                dos.writeInt(ttl);
                dos.writeShort(4);
                dos.write(InetAddress.getByName(ipAddress).getAddress());
            } else {
                // Explicit negative response using NSEC (RFC 6762 Section 6.1)
                dos.writeShort(1); // ANCOUNT = 1 (NSEC)
                dos.writeShort(0); // NSCOUNT
                dos.writeShort(1); // ARCOUNT = 1 (A in Additional)

                // Answer: NSEC
                writeQName(dos, hostLocal);
                dos.writeShort(47); // Type NSEC
                dos.writeShort(0x8001); // Class IN + Cache-Flush
                dos.writeInt(ttl);

                byte[] nextNameBytes = encodeQName(hostLocal);
                dos.writeShort(nextNameBytes.length + 3);
                dos.write(nextNameBytes);
                dos.writeByte(0); // Window 0
                dos.writeByte(1); // Length 1 byte
                dos.writeByte(0x40); // Bit 1 set = Type A exists

                // Additional: A
                writeQName(dos, hostLocal);
                dos.writeShort(1); // Type A
                dos.writeShort(0x8001); // Class IN + Cache-Flush
                dos.writeInt(ttl);
                dos.writeShort(4);
                dos.write(InetAddress.getByName(ipAddress).getAddress());
            }

            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error building mDNS AAAA response", e);
            return null;
        }
    }

    /**
     * Builds RFC 6762 Section 6.7 compliant Legacy Unicast DNS Response
     * for Linux/Unix clients sending queries from ephemeral ports with non-zero transaction IDs.
     */
    private byte[] buildLegacyUnicastResponse(int txId, ParsedQuestion question, String ipAddress) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeShort(txId); // Echo query transaction ID
            dos.writeShort(0x8400); // Response + Authoritative
            dos.writeShort(1); // QDCOUNT = 1 (Echo question)
            dos.writeShort(1); // ANCOUNT = 1
            dos.writeShort(0); // NSCOUNT
            dos.writeShort(0); // ARCOUNT

            // Echo Question
            writeQName(dos, question.qName);
            dos.writeShort(question.qType);
            dos.writeShort(0x0001); // Class IN

            // Answer: Type A with Class IN (0x0001 - NO cache-flush bit per RFC 6762 Section 6.7)
            writeQName(dos, question.qName);
            dos.writeShort(1); // Type A
            dos.writeShort(0x0001); // Class IN
            dos.writeInt(10); // TTL 10s
            dos.writeShort(4);
            InetAddress inet = InetAddress.getByName(ipAddress);
            dos.write(inet.getAddress());

            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error building Legacy Unicast response", e);
            return null;
        }
    }

    /**
     * Builds a comprehensive DNS-SD (Service Discovery) Announcement packet
     * containing PTR, SRV, TXT, and A records for instant Mac/Linux discovery.
     */
    private byte[] buildFullMdnsAnnouncement(String host, String ipAddress, Inet6Address ipv6Address, int port, int ttl) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeShort(0); // ID = 0
            dos.writeShort(0x8400); // Response + Authoritative
            dos.writeShort(0); // QDCOUNT = 0

            int anCount = (ipv6Address != null) ? 5 : 4; // PTR, SRV, TXT, A, (AAAA)
            dos.writeShort(anCount);
            dos.writeShort(0); // NSCOUNT
            dos.writeShort(0); // ARCOUNT

            String serviceInstance = host + "._http._tcp.local";
            String hostDomain = host + ".local";

            // 1. PTR Record: _http._tcp.local -> <host>._http._tcp.local
            writeQName(dos, "_http._tcp.local");
            dos.writeShort(12); // Type PTR (0x000C)
            dos.writeShort(0x0001); // Class IN (shared record, no cache-flush)
            dos.writeInt(ttl);
            byte[] ptrTargetBytes = encodeQName(serviceInstance);
            dos.writeShort(ptrTargetBytes.length);
            dos.write(ptrTargetBytes);

            // 2. SRV Record: <host>._http._tcp.local -> port, target <host>.local
            writeQName(dos, serviceInstance);
            dos.writeShort(33); // Type SRV (0x0021)
            dos.writeShort(0x8001); // Class IN + Cache-Flush
            dos.writeInt(ttl);
            byte[] srvTargetBytes = encodeQName(hostDomain);
            dos.writeShort(6 + srvTargetBytes.length); // priority (2) + weight (2) + port (2) + target
            dos.writeShort(0); // Priority
            dos.writeShort(0); // Weight
            dos.writeShort(port); // Port
            dos.write(srvTargetBytes);

            // 3. TXT Record: <host>._http._tcp.local -> path=/
            writeQName(dos, serviceInstance);
            dos.writeShort(16); // Type TXT (0x0010)
            dos.writeShort(0x8001); // Class IN + Cache-Flush
            dos.writeInt(ttl);
            byte[] txtData = "path=/".getBytes(StandardCharsets.UTF_8);
            dos.writeShort(1 + txtData.length);
            dos.writeByte(txtData.length);
            dos.write(txtData);

            // 4. A Record: <host>.local -> <ipAddress>
            writeQName(dos, hostDomain);
            dos.writeShort(1); // Type A (0x0001)
            dos.writeShort(0x8001); // Class IN + Cache-Flush
            dos.writeInt(ttl);
            dos.writeShort(4); // RDLENGTH
            InetAddress inet = InetAddress.getByName(ipAddress);
            dos.write(inet.getAddress());

            // 5. AAAA Record: If IPv6 available
            if (ipv6Address != null) {
                writeQName(dos, hostDomain);
                dos.writeShort(28); // Type AAAA (0x001C)
                dos.writeShort(0x8001); // Class IN + Cache-Flush
                dos.writeInt(ttl);
                dos.writeShort(16);
                dos.write(ipv6Address.getAddress());
            }

            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error building Full mDNS Announcement", e);
            return null;
        }
    }

    // ================= LLMNR / STANDARD DNS BUILDER (WINDOWS & LINUX RESOLVED) =================
    private byte[] buildLlmnrResponse(int txId, ParsedQuestion question, String ipAddress, boolean hasAnswer) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeShort(txId);
            dos.writeShort(0x8400); // Response + Authoritative

            dos.writeShort(1); // QDCOUNT (LLMNR mirrors the question section)
            dos.writeShort(hasAnswer && ipAddress != null ? 1 : 0); // ANCOUNT
            dos.writeShort(0); // NSCOUNT
            dos.writeShort(0); // ARCOUNT

            // Question section
            writeQName(dos, question.qName);
            dos.writeShort(question.qType);
            dos.writeShort(1); // Class IN

            // Answer section
            if (hasAnswer && ipAddress != null) {
                writeQName(dos, question.qName);
                dos.writeShort(1); // Type A
                dos.writeShort(0x0001); // Class IN (No cache-flush bit for LLMNR)
                dos.writeInt(120); // TTL 120s
                dos.writeShort(4); // RDLENGTH
                InetAddress inet = InetAddress.getByName(ipAddress);
                dos.write(inet.getAddress());
            }

            dos.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Error building LLMNR response", e);
            return null;
        }
    }

    // ================= DNS PARSING HELPERS (WITH POINTER RESOLUTION) =================

    public static class ParsedQuestion {
        public String qName;
        public int qType;
        public int qClass;
        public boolean isUnicastQuery;
    }

    private List<ParsedQuestion> parseAllQuestions(byte[] data, int length, int qdCount) {
        List<ParsedQuestion> list = new ArrayList<>(qdCount);
        int[] offsetRef = new int[]{12};
        for (int i = 0; i < qdCount && offsetRef[0] < length; i++) {
            ParsedQuestion q = parseQuestion(data, offsetRef, length);
            if (q == null) break;
            list.add(q);
        }
        return list;
    }

    private ParsedQuestion parseQuestion(byte[] data, int[] offsetRef, int totalLength) {
        try {
            String name = readDnsName(data, offsetRef, totalLength);
            if (name == null) return null;

            int cur = offsetRef[0];
            if (cur + 4 > totalLength) return null;

            int qType = ((data[cur] & 0xFF) << 8) | (data[cur + 1] & 0xFF);
            int qClassRaw = ((data[cur + 2] & 0xFF) << 8) | (data[cur + 3] & 0xFF);
            offsetRef[0] = cur + 4;

            ParsedQuestion q = new ParsedQuestion();
            q.qName = name;
            q.qType = qType;
            q.qClass = qClassRaw & 0x7FFF;
            q.isUnicastQuery = (qClassRaw & 0x8000) != 0;
            return q;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Safely decodes a DNS domain name supporting RFC 1035 compression pointers (0xC0XX)
     * and loop protection.
     */
    private String readDnsName(byte[] data, int[] offsetRef, int totalLength) {
        StringBuilder sb = new StringBuilder();
        int cur = offsetRef[0];
        boolean jumped = false;
        int jumps = 0;
        int nextStreamOffset = -1;

        while (cur < totalLength && jumps < 10) {
            int len = data[cur] & 0xFF;
            if (len == 0) {
                cur++;
                if (!jumped) {
                    nextStreamOffset = cur;
                }
                break;
            }

            if ((len & 0xC0) == 0xC0) {
                // Pointer
                if (cur + 1 >= totalLength) return null;
                int pointerOffset = ((len & 0x3F) << 8) | (data[cur + 1] & 0xFF);
                if (!jumped) {
                    nextStreamOffset = cur + 2;
                    jumped = true;
                }
                cur = pointerOffset;
                jumps++;
                continue;
            }

            // Standard label
            cur++;
            if (cur + len > totalLength) return null;
            if (sb.length() > 0) sb.append(".");
            sb.append(new String(data, cur, len, StandardCharsets.UTF_8));
            cur += len;
        }

        if (nextStreamOffset != -1) {
            offsetRef[0] = nextStreamOffset;
        } else {
            offsetRef[0] = cur;
        }

        return sb.toString();
    }

    private void writeQName(DataOutputStream dos, String name) throws IOException {
        byte[] bytes = encodeQName(name);
        dos.write(bytes);
    }

    private byte[] encodeQName(String name) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        String[] labels = name.split("\\.");
        for (String label : labels) {
            if (!label.isEmpty()) {
                byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
                dos.writeByte(labelBytes.length);
                dos.write(labelBytes);
            }
        }
        dos.writeByte(0);
        dos.flush();
        return baos.toByteArray();
    }

    private String resolveCurrentIp() {
        if (localIpAddress != null && !localIpAddress.isEmpty()) {
            return localIpAddress;
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;

                String name = iface.getName().toLowerCase(Locale.US);
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap")
                        || name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("eth")
                        || name.startsWith("p2p") || name.startsWith("swlan") || name.startsWith("bnep")) {
                    for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                        InetAddress inet = addr.getAddress();
                        if (inet instanceof Inet4Address && !inet.isLoopbackAddress()) {
                            return inet.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.43.1";
    }

    private Inet6Address resolveCurrentIpv6() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;

                String name = iface.getName().toLowerCase(Locale.US);
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap")
                        || name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("eth")
                        || name.startsWith("p2p") || name.startsWith("swlan") || name.startsWith("bnep")) {
                    for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                        InetAddress inet = addr.getAddress();
                        if (inet instanceof Inet6Address && !inet.isLoopbackAddress() && !inet.isAnyLocalAddress()) {
                            Inet6Address inet6 = (Inet6Address) inet;
                            if (!inet6.isMulticastAddress()) {
                                return inet6;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
