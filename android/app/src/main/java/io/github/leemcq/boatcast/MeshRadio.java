package io.github.leemcq.boatcast;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bluetooth Low Energy side of the mesh, used only for finding each other:
 * - every host and repeater advertises 8 bytes (mesh, id, depth, room, battery);
 * - a phone looking for a way in advertises "seeking", so nearby phones start repeating;
 * - the Wi-Fi name and password of a hotspot are read from it over a short GATT connection.
 * The slides themselves always travel over Wi-Fi.
 */
final class MeshRadio {
    private static final String TAG = "boatcast";
    /** 16-bit service id carried in every advertisement (kept short: an advert holds 31 bytes). */
    static final ParcelUuid ADVERT = ParcelUuid.fromString("0000b0a7-0000-1000-8000-00805f9b34fb");
    static final UUID SERVICE = UUID.fromString("6b6f6174-6361-7374-0001-000000000001");
    static final UUID CREDENTIALS = UUID.fromString("6b6f6174-6361-7374-0001-000000000002");

    interface CredentialsCallback {
        /** Called on the radio handler; json is null on failure. */
        void onCredentials(String json);
    }

    private final Context ctx;
    private final Handler handler;
    private final BluetoothAdapter adapter;
    private final Map<Integer, MeshPlanner.Node> nodes = new ConcurrentHashMap<>();
    private final Map<Integer, BluetoothDevice> devices = new ConcurrentHashMap<>();

    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private volatile byte[] credentials = new byte[0];
    private byte[] advertised;
    private boolean advertisedConnectable;
    private int scanMode = -1;

    MeshRadio(Context ctx, Handler handler) {
        this.ctx = ctx.getApplicationContext();
        this.handler = handler;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    boolean available() {
        return adapter != null && adapter.isEnabled();
    }

    /** Nodes heard recently. */
    List<MeshPlanner.Node> nodes() {
        long now = SystemClock.elapsedRealtime();
        List<MeshPlanner.Node> l = new ArrayList<>();
        for (MeshPlanner.Node n : nodes.values()) {
            if (now - n.seenMs <= MeshPlanner.STALE_MS) l.add(n);
        }
        return l;
    }

    // ------------------------------------------------------------- scanning

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int type, ScanResult r) {
            handle(r);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handle(r);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "mesh: BLE scan failed " + errorCode);
            scanMode = -1;
        }
    };

    private void handle(ScanResult r) {
        ScanRecord rec = r.getScanRecord();
        if (rec == null) return;
        MeshPlanner.Node n = MeshPlanner.decode(rec.getServiceData(ADVERT), r.getRssi(), SystemClock.elapsedRealtime());
        if (n == null) return;
        nodes.put(n.id, n);
        devices.put(n.id, r.getDevice());
    }

    /** Scan hard while looking for a way in, gently once connected (saves battery). */
    void scan(boolean eager) {
        if (!available()) return;
        int mode = eager ? ScanSettings.SCAN_MODE_LOW_LATENCY : ScanSettings.SCAN_MODE_LOW_POWER;
        if (mode == scanMode) return;
        try {
            if (scanner == null) scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) return;
            if (scanMode != -1) scanner.stopScan(scanCallback);
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(ADVERT).build());
            scanner.startScan(filters, new ScanSettings.Builder().setScanMode(mode).build(), scanCallback);
            scanMode = mode;
        } catch (RuntimeException e) {
            Log.w(TAG, "mesh: cannot scan: " + e);
            scanMode = -1;
        }
    }

    // ---------------------------------------------------------- advertising

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "mesh: BLE advertise failed " + errorCode);
            advertised = null;
        }
    };

    /** Advertise our state; connectable when others may read our hotspot's credentials. */
    void advertise(byte[] payload, boolean connectable) {
        if (!available()) return;
        if (advertised != null && Arrays.equals(advertised, payload) && advertisedConnectable == connectable) return;
        try {
            if (advertiser == null) advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return; // this phone cannot advertise; it can still join
            if (advertised != null) advertiser.stopAdvertising(advertiseCallback);
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(connectable)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(ADVERT)
                    .addServiceData(ADVERT, payload)
                    .build();
            advertiser.startAdvertising(settings, data, advertiseCallback);
            advertised = payload;
            advertisedConnectable = connectable;
        } catch (RuntimeException e) {
            Log.w(TAG, "mesh: cannot advertise: " + e);
            advertised = null;
        }
    }

    void stopAdvertising() {
        if (advertiser != null && advertised != null) {
            try {
                advertiser.stopAdvertising(advertiseCallback);
            } catch (RuntimeException ignored) {
                // adapter turned off
            }
        }
        advertised = null;
    }

    // ------------------------------------------------- credentials (server)

    /** Serve {"ssid","pass","ip","port"} of our hotspot to phones that connect over BLE. */
    void serveCredentials(String json) {
        credentials = json.getBytes(StandardCharsets.UTF_8);
        if (gattServer != null || !available()) return;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        try {
            gattServer = bm.openGattServer(ctx, new BluetoothGattServerCallback() {
                @Override
                public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                        BluetoothGattCharacteristic ch) {
                    byte[] all = credentials;
                    byte[] part = offset >= all.length ? new byte[0] : Arrays.copyOfRange(all, offset, all.length);
                    try {
                        gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part);
                    } catch (RuntimeException ignored) {
                        // reader went away
                    }
                }
            });
            if (gattServer == null) return;
            BluetoothGattService svc = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            svc.addCharacteristic(new BluetoothGattCharacteristic(CREDENTIALS,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ));
            gattServer.addService(svc);
        } catch (RuntimeException e) {
            Log.w(TAG, "mesh: cannot open GATT server: " + e);
            gattServer = null;
        }
    }

    void stopServingCredentials() {
        if (gattServer != null) {
            try {
                gattServer.close();
            } catch (RuntimeException ignored) {
                // adapter turned off
            }
            gattServer = null;
        }
    }

    // ------------------------------------------------- credentials (client)

    /** Read a node's hotspot credentials over a short BLE connection (10 s limit). */
    void fetchCredentials(int nodeId, CredentialsCallback cb) {
        BluetoothDevice dev = devices.get(nodeId);
        if (dev == null || !available()) {
            cb.onCredentials(null);
            return;
        }
        final boolean[] done = {false};
        final BluetoothGatt[] holder = new BluetoothGatt[1];
        Runnable finishFail = () -> finish(done, holder, cb, null);
        handler.postDelayed(finishFail, 10_000);
        try {
            holder[0] = dev.connectGatt(ctx, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                    if (state == BluetoothProfile.STATE_CONNECTED) {
                        g.requestMtu(247);
                    } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        handler.post(() -> finish(done, holder, cb, null));
                    }
                }

                @Override
                public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                    g.discoverServices();
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    BluetoothGattService s = g.getService(SERVICE);
                    BluetoothGattCharacteristic c = s == null ? null : s.getCharacteristic(CREDENTIALS);
                    if (c == null || !g.readCharacteristic(c)) handler.post(() -> finish(done, holder, cb, null));
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                    String json = status == BluetoothGatt.GATT_SUCCESS && c.getValue() != null
                            ? new String(c.getValue(), StandardCharsets.UTF_8) : null;
                    handler.post(() -> {
                        handler.removeCallbacks(finishFail);
                        finish(done, holder, cb, json);
                    });
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (RuntimeException e) {
            handler.removeCallbacks(finishFail);
            finish(done, holder, cb, null);
        }
    }

    private static void finish(boolean[] done, BluetoothGatt[] holder, CredentialsCallback cb, String json) {
        if (done[0]) return;
        done[0] = true;
        if (holder[0] != null) {
            try {
                holder[0].close();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
        cb.onCredentials(json);
    }

    void stop() {
        stopAdvertising();
        stopServingCredentials();
        if (scanner != null && scanMode != -1) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
                // adapter turned off
            }
        }
        scanMode = -1;
        nodes.clear();
        devices.clear();
    }
}
