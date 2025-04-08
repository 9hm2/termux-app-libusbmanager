package com.termux.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class BluetoothApiManager {

    @NonNull
    private final Context context;
    @NonNull
    private final String socketName;
    @NonNull
    private final Thread lth;
    @NonNull
    private final BluetoothAdapter bluetoothAdapter;

    private BluetoothGatt activeGatt;

    public BluetoothApiManager(@NonNull final Context context) {
        this(context, context.getApplicationContext().getPackageName() + ".bluetooth");
    }

    public BluetoothApiManager(@NonNull final Context context, @NonNull final String socketName) {
        this.context = context.getApplicationContext();
        this.socketName = socketName;

        BluetoothManager bluetoothManager = (BluetoothManager)
                context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null)
            throw new RuntimeException("BluetoothManager not available");

        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null)
            throw new RuntimeException("BluetoothAdapter not available");

        lth = new Thread(this::runServer, "BluetoothApiServer");
        lth.setDaemon(true);
        lth.start();
    }

    private void runServer() {
        try (LocalServerSocket serverSocket = new LocalServerSocket(socketName)) {
            while (!Thread.interrupted()) {
                final LocalSocket socket = serverSocket.accept();
                final Thread cth = new Thread(() -> handleClient(socket));
                cth.setDaemon(false);
                cth.start();
            }
        } catch (IOException e) {
            postToast("Bluetooth server error: " + e.getMessage());
        }
    }

    private void handleClient(LocalSocket socket) {
        try (DataInputStream in = new DataInputStream(socket.getInputStream());
             OutputStream out = socket.getOutputStream()) {

            String command = in.readUTF();

            switch (command) {
                case "enable":
                    boolean enabled = enableBluetooth();
                    out.write(enabled ? 1 : 0);
                    break;
                case "list_paired":
                    Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
                    for (BluetoothDevice dev : paired) {
                        out.write((dev.getName() + " [" + dev.getAddress() + "]\n").getBytes());
                    }
                    break;
                case "is_enabled":
                    out.write(bluetoothAdapter.isEnabled() ? 1 : 0);
                    break;
                case "ble_scan":
                    if (!hasBluetoothPermissions()) {
                        postToast("BLE scan: permission denied");
                        break;
                    }
                    bluetoothAdapter.getBluetoothLeScanner().startScan(bleScanCallback);
                    postToast("BLE scan started");
                    break;
                case "ble_stop":
                    bluetoothAdapter.getBluetoothLeScanner().stopScan(bleScanCallback);
                    postToast("BLE scan stopped");
                    break;
                case "gatt_connect":
                    String address = in.readUTF();
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                    activeGatt = device.connectGatt(context, false, gattCallback);
                    postToast("GATT connecting to " + address);
                    break;
                default:
                    out.write(("Unknown command: " + command + "\n").getBytes());
            }

        } catch (IOException e) {
            postToast("Bluetooth client error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private boolean enableBluetooth() {
        if (!hasBluetoothPermissions()) {
            postToast("Bluetooth permissions not granted");
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(enableBtIntent);
            return false;
        }
        return true;
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                   ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void postToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        );
    }

    public void recycle() {
        lth.interrupt();
    }

    // BLE SCAN CALLBACK
    private final ScanCallback bleScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            postToast("BLE device: " + device.getName() + " [" + device.getAddress() + "]");
        }
    };

    // GATT CALLBACK
    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(@NonNull BluetoothGatt gatt, int status, int newState) {
            postToast("GATT connection state: " + newState);
        }
    };
}
