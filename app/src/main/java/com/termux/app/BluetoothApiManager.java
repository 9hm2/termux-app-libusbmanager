package com.termux.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BluetoothApiManager {

    @NonNull
    protected final Context context;
    @NonNull
    private final String socketName;
    @NonNull
    private final Thread lth;
    @NonNull
    private final BluetoothAdapter bluetoothAdapter;

    private final ConcurrentHashMap<String, BluetoothSocket> socketMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BluetoothGatt> gattMap = new ConcurrentHashMap<>();

    public BluetoothApiManager(@NonNull final Context context) {
        this(context, context.getApplicationContext().getPackageName() + ".bluetooth");
    }

    public BluetoothApiManager(@NonNull final Context context, @NonNull final String socketName) {
        this.context = context.getApplicationContext();
        this.socketName = socketName;
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            throw new RuntimeException("Bluetooth not supported");
        }
        lth = new Thread(server, "BluetoothApiServer");
        lth.setDaemon(true);
        lth.start();
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                   context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void client(@NonNull final LocalSocket socket) {
        try {
            final DataInputStream dis = new DataInputStream(socket.getInputStream());
            final DataOutputStream dos = new DataOutputStream(socket.getOutputStream());

            while (true) {
                final String line = dis.readUTF().trim();
                if (line.isEmpty()) continue;
                final String[] parts = line.split(" ");
                final String cmd = parts[0];

                switch (cmd) {
                    case "ENABLE": {
                        if (!checkPermissions()) {
                            dos.writeUTF("ERROR Missing Bluetooth permissions");
                            break;
                        }
                        bluetoothAdapter.enable();
                        dos.writeUTF("ENABLED");
                        break;
                    }
                    case "DISABLE": {
                        bluetoothAdapter.disable();
                        dos.writeUTF("DISABLED");
                        break;
                    }
                    case "LIST": {
                        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
                        for (BluetoothDevice device : bondedDevices) {
                            dos.writeUTF(device.getName() + " [" + device.getAddress() + "]");
                        }
                        dos.writeUTF("");
                        break;
                    }
                    case "PAIR": {
                        if (parts.length < 2) {
                            dos.writeUTF("ERROR Missing MAC");
                            break;
                        }
                        String mac = parts[1];
                        BluetoothDevice dev = bluetoothAdapter.getRemoteDevice(mac);
                        dev.createBond();
                        dos.writeUTF("PAIR_REQUESTED");
                        break;
                    }
                    case "CONNECT": {
                        if (parts.length < 3) {
                            dos.writeUTF("ERROR CONNECT <MAC> <UUID>");
                            break;
                        }
                        String mac = parts[1];
                        UUID uuid = UUID.fromString(parts[2]);
                        BluetoothDevice dev = bluetoothAdapter.getRemoteDevice(mac);
                        BluetoothSocket btSocket = dev.createRfcommSocketToServiceRecord(uuid);
                        btSocket.connect();
                        socketMap.put(mac, btSocket);
                        dos.writeUTF("CONNECTED " + mac);
                        break;
                    }
                    case "SEND": {
                        if (parts.length < 3) {
                            dos.writeUTF("ERROR SEND <MAC> <DATA>");
                            break;
                        }
                        String mac = parts[1];
                        String msg = line.substring(cmd.length() + mac.length() + 2);
                        BluetoothSocket s = socketMap.get(mac);
                        if (s != null) {
                            s.getOutputStream().write(msg.getBytes());
                            s.getOutputStream().flush();
                            dos.writeUTF("SENT");
                        } else {
                            dos.writeUTF("ERROR Not connected");
                        }
                        break;
                    }
                    case "RECV": {
                        if (parts.length < 2) {
                            dos.writeUTF("ERROR RECV <MAC>");
                            break;
                        }
                        String mac = parts[1];
                        BluetoothSocket s = socketMap.get(mac);
                        if (s != null) {
                            byte[] buffer = new byte[1024];
                            int len = s.getInputStream().read(buffer);
                            if (len > 0)
                                dos.writeUTF("DATA " + new String(buffer, 0, len));
                            else
                                dos.writeUTF("NO_DATA");
                        } else {
                            dos.writeUTF("ERROR Not connected");
                        }
                        break;
                    }
                    case "DISCONNECT": {
                        String mac = parts[1];
                        BluetoothSocket s = socketMap.get(mac);
                        if (s != null) {
                            s.close();
                            socketMap.remove(mac);
                            dos.writeUTF("DISCONNECTED " + mac);
                        } else {
                            dos.writeUTF("ERROR Not connected");
                        }
                        break;
                    }
                    case "GATT_CONNECT": {
                        String mac = parts[1];
                        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(mac);
                        BluetoothGatt gatt = device.connectGatt(context, false, new BluetoothGattCallback() {
                            @Override
                            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                                if (newState == BluetoothProfile.STATE_CONNECTED) {
                                    g.discoverServices();
                                }
                            }
                        });
                        gattMap.put(mac, gatt);
                        dos.writeUTF("GATT_CONNECTING");
                        break;
                    }
                    case "GATT_READ": {
                        String mac = parts[1];
                        UUID uuid = UUID.fromString(parts[2]);
                        BluetoothGatt gatt = gattMap.get(mac);
                        if (gatt != null) {
                            for (BluetoothGattService service : gatt.getServices()) {
                                BluetoothGattCharacteristic ch = service.getCharacteristic(uuid);
                                if (ch != null) {
                                    gatt.readCharacteristic(ch);
                                    dos.writeUTF("GATT_READ_REQUESTED");
                                    break;
                                }
                            }
                        } else {
                            dos.writeUTF("ERROR GATT not connected");
                        }
                        break;
                    }
                    case "GATT_WRITE": {
                        String mac = parts[1];
                        UUID uuid = UUID.fromString(parts[2]);
                        String hexData = parts[3];
                        byte[] data = hexStringToByteArray(hexData);
                        BluetoothGatt gatt = gattMap.get(mac);
                        if (gatt != null) {
                            for (BluetoothGattService service : gatt.getServices()) {
                                BluetoothGattCharacteristic ch = service.getCharacteristic(uuid);
                                if (ch != null) {
                                    ch.setValue(data);
                                    gatt.writeCharacteristic(ch);
                                    dos.writeUTF("GATT_WRITE_REQUESTED");
                                    break;
                                }
                            }
                        } else {
                            dos.writeUTF("ERROR GATT not connected");
                        }
                        break;
                    }
                    case "EXIT": {
                        dos.writeUTF("BYE");
                        return;
                    }
                    default:
                        dos.writeUTF("UNKNOWN_COMMAND: " + cmd);
                        break;
                }
                dos.flush();
            }
        } catch (IOException e) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, "Bluetooth Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private final Runnable server = () -> {
        try (LocalServerSocket serverSocket = new LocalServerSocket(socketName)) {
            while (!Thread.interrupted()) {
                final LocalSocket socket = serverSocket.accept();
                final Thread cth = new Thread(() -> client(socket));
                cth.setDaemon(false);
                cth.start();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    };

    public void recycle() {
        lth.interrupt();
    }

    @Override
    protected void finalize() throws Throwable {
        lth.interrupt();
        super.finalize();
    }

    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
