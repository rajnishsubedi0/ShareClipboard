package com.example.shareclipboard;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

public class NsdHelper {
    private static final String TAG = "NsdHelper";
    public static final String SERVICE_TYPE = "_clipshare._tcp.";

    private final NsdManager nsdManager;
    private final Consumer<NsdServiceInfo> foundCallback;
    private final Consumer<NsdServiceInfo> lostCallback;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;

    private final Object resolveLock = new Object();
    private final Deque<NsdServiceInfo> pendingResolves = new ArrayDeque<>();
    private boolean resolving = false;

    private volatile String registeredServiceName;

    public NsdHelper(Context context,
                     Consumer<NsdServiceInfo> onServiceFound,
                     Consumer<NsdServiceInfo> onServiceLost) {
        this.nsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.foundCallback = onServiceFound;
        this.lostCallback = onServiceLost;
    }

    public String getRegisteredServiceName() {
        return registeredServiceName;
    }

    public void registerService(int port, String deviceName) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(deviceName);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                registeredServiceName = info.getServiceName();
                Log.d(TAG, "Registered as " + info.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "Registration failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                Log.d(TAG, "Unregistered");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int errorCode) {
                Log.e(TAG, "Unregistration failed: " + errorCode);
            }
        };
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    public void startDiscovery() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "Discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                String type = service.getServiceType();
                boolean sameType = type != null && type.startsWith("_clipshare._tcp");
                boolean isSelf = service.getServiceName() != null
                        && service.getServiceName().equals(registeredServiceName);
                if (sameType && !isSelf) {
                    resolveService(service);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                lostCallback.accept(service);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped");
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Start discovery failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Stop discovery failed: " + errorCode);
            }
        };
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void resolveService(NsdServiceInfo service) {
        synchronized (resolveLock) {
            pendingResolves.add(service);
        }
        resolveNext();
    }

    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (resolveLock) {
            if (resolving) {
                return;
            }
            next = pendingResolves.poll();
            if (next == null) {
                return;
            }
            resolving = true;
        }
        try {
            nsdManager.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    Log.e(TAG, "Resolve failed: " + errorCode);
                    finishResolve();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    foundCallback.accept(info);
                    finishResolve();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "resolveService error", e);
            finishResolve();
        }
    }

    private void finishResolve() {
        synchronized (resolveLock) {
            resolving = false;
        }
        resolveNext();
    }

    public void stopDiscovery() {
        synchronized (resolveLock) {
            pendingResolves.clear();
        }
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Log.e(TAG, "stopDiscovery error", e);
            }
        }
        discoveryListener = null;
    }

    public void unregisterService() {
        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception e) {
                Log.e(TAG, "unregister error", e);
            }
        }
        registrationListener = null;
    }
}
