package com.example.katchal;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00885F9B34FB");
    private static final String PREFS_NAME = "KatchalMcQueenPrefs";
    private static final String KEY_TEAM_MEMBERS = "team_members";

    // Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private boolean isConnected = false;
    private String connectedDeviceName = "";

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Vibrator vibrator;

    // Views
    private MaterialButton btnConnect;
    private View viewStatusDot;
    private TextView tvBluetoothStatus;
    private TextView tvSpeedValue;
    private Slider sliderSpeed;
    private ChipGroup chipGroupFaces;
    private MaterialButton btnHorn;
    private TextView tvTeamMembers;

    // Speed Presets
    private MaterialButton btnSpeed25;
    private MaterialButton btnSpeed50;
    private MaterialButton btnSpeed100;

    // D-Pad Buttons
    private ImageButton btnForward;
    private ImageButton btnBackward;
    private ImageButton btnLeft;
    private ImageButton btnRight;
    private ImageButton btnStop;

    private int currentSpeed = 50;

    // Permission launcher for Android 12+
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (granted == null || !granted) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    showDeviceSelectionDialog();
                } else {
                    Toast.makeText(this, R.string.msg_permission_required, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        initBluetoothAdapter();
        initViews();
        setupListeners();
        loadTeamMembers();
        updateSpeedLabel(currentSpeed);
        updateStatusDot(R.drawable.ic_status_dot_red);
    }

    private void initBluetoothAdapter() {
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
        if (bluetoothAdapter == null) {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }
    }

    private void initViews() {
        btnConnect = findViewById(R.id.btnConnect);
        viewStatusDot = findViewById(R.id.viewStatusDot);
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus);
        tvSpeedValue = findViewById(R.id.tvSpeedValue);
        sliderSpeed = findViewById(R.id.sliderSpeed);
        chipGroupFaces = findViewById(R.id.chipGroupFaces);
        btnHorn = findViewById(R.id.btnHorn);
        tvTeamMembers = findViewById(R.id.tvTeamMembers);

        btnSpeed25 = findViewById(R.id.btnSpeed25);
        btnSpeed50 = findViewById(R.id.btnSpeed50);
        btnSpeed100 = findViewById(R.id.btnSpeed100);

        btnForward = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnStop = findViewById(R.id.btnStop);

        MaterialButton btnEditTeam = findViewById(R.id.btnEditTeam);
        btnEditTeam.setOnClickListener(v -> {
            triggerHapticFeedback();
            showEditTeamDialog();
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        // Bluetooth connect toggle
        btnConnect.setOnClickListener(v -> {
            triggerHapticFeedback();
            if (isConnected) {
                disconnectBluetooth();
            } else {
                checkPermissionsAndConnect();
            }
        });

        // D-Pad Directional Controls with Touch / Click Support
        setupDirectionButton(btnForward, "F");
        setupDirectionButton(btnBackward, "B");
        setupDirectionButton(btnLeft, "L");
        setupDirectionButton(btnRight, "R");

        btnStop.setOnClickListener(v -> {
            triggerHapticFeedback();
            sendCommand("S");
        });

        // Speed Slider Listener
        sliderSpeed.addOnChangeListener((slider, value, fromUser) -> {
            currentSpeed = Math.round(value);
            updateSpeedLabel(currentSpeed);
            if (fromUser) {
                sendCommand("V" + currentSpeed);
            }
        });

        // Speed Presets
        btnSpeed25.setOnClickListener(v -> setSpeedPreset(25));
        btnSpeed50.setOnClickListener(v -> setSpeedPreset(50));
        btnSpeed100.setOnClickListener(v -> setSpeedPreset(100));

        // LED Face Chips Listener
        chipGroupFaces.setOnCheckedStateChangeListener((group, checkedIds) -> {
            triggerHapticFeedback();
            if (checkedIds.isEmpty()) return;
            int checkedId = checkedIds.get(0);
            if (checkedId == R.id.chipHappy) {
                sendCommand("E1");
            } else if (checkedId == R.id.chipSad) {
                sendCommand("E2");
            } else if (checkedId == R.id.chipAngry) {
                sendCommand("E3");
            } else if (checkedId == R.id.chipHeart) {
                sendCommand("E4");
            } else if (checkedId == R.id.chipBlink) {
                sendCommand("E5");
            }
        });

        // Horn Button
        btnHorn.setOnClickListener(v -> {
            triggerHapticFeedback();
            sendCommand("H");
        });
    }

    private void setSpeedPreset(int speed) {
        triggerHapticFeedback();
        currentSpeed = speed;
        sliderSpeed.setValue((float) speed);
        updateSpeedLabel(speed);
        sendCommand("V" + speed);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDirectionButton(ImageButton button, String command) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    triggerHapticFeedback();
                    sendCommand(command);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    sendCommand("S"); // Parar ao soltar
                    return true;
            }
            return false;
        });
    }

    private void triggerHapticFeedback() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(30);
            }
        }
    }

    private void updateSpeedLabel(int speed) {
        tvSpeedValue.setText(getString(R.string.speed_format, speed));
    }

    private void updateStatusDot(int drawableId) {
        viewStatusDot.setBackground(ContextCompat.getDrawable(this, drawableId));
    }

    // --- BLUETOOTH LOGIC ---

    private void checkPermissionsAndConnect() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.msg_bluetooth_not_available, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, R.string.msg_bluetooth_disabled, Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                });
                return;
            }
        }
        showDeviceSelectionDialog();
    }

    @SuppressLint("MissingPermission")
    private void showDeviceSelectionDialog() {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            Toast.makeText(this, R.string.msg_no_paired_devices, Toast.LENGTH_LONG).show();
            return;
        }

        List<String> deviceNames = new ArrayList<>();
        List<BluetoothDevice> deviceList = new ArrayList<>();

        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName() != null ? device.getName() : device.getAddress();
            deviceNames.add(name + "\n(" + device.getAddress() + ")");
            deviceList.add(device);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.select_device_title)
                .setItems(deviceNames.toArray(new String[0]), (dialog, which) -> {
                    BluetoothDevice selectedDevice = deviceList.get(which);
                    connectToDevice(selectedDevice);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void connectToDevice(BluetoothDevice device) {
        tvBluetoothStatus.setText(R.string.status_connecting);
        tvBluetoothStatus.setTextColor(ContextCompat.getColor(this, R.color.mcqueen_yellow));
        updateStatusDot(R.drawable.ic_status_dot_yellow);

        executorService.execute(() -> {
            try {
                bluetoothAdapter.cancelDiscovery();
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                isConnected = true;
                connectedDeviceName = device.getName() != null ? device.getName() : device.getAddress();

                mainHandler.post(() -> {
                    tvBluetoothStatus.setText(getString(R.string.status_connected, connectedDeviceName));
                    tvBluetoothStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
                    updateStatusDot(R.drawable.ic_status_dot_green);
                    btnConnect.setText(R.string.btn_disconnect);
                    btnConnect.setBackgroundColor(ContextCompat.getColor(this, R.color.mcqueen_red));
                    btnConnect.setTextColor(ContextCompat.getColor(this, R.color.white));
                    Toast.makeText(this, getString(R.string.status_connected, connectedDeviceName), Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                closeSocket();
                mainHandler.post(() -> {
                    isConnected = false;
                    tvBluetoothStatus.setText(R.string.msg_connection_failed);
                    tvBluetoothStatus.setTextColor(ContextCompat.getColor(this, R.color.mcqueen_red_bright));
                    updateStatusDot(R.drawable.ic_status_dot_red);
                    btnConnect.setText(R.string.btn_connect);
                    btnConnect.setBackgroundColor(ContextCompat.getColor(this, R.color.mcqueen_yellow));
                    btnConnect.setTextColor(ContextCompat.getColor(this, R.color.black));
                    Toast.makeText(this, R.string.msg_connection_failed, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void disconnectBluetooth() {
        closeSocket();
        isConnected = false;
        tvBluetoothStatus.setText(R.string.status_disconnected);
        tvBluetoothStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        updateStatusDot(R.drawable.ic_status_dot_red);
        btnConnect.setText(R.string.btn_connect);
        btnConnect.setBackgroundColor(ContextCompat.getColor(this, R.color.mcqueen_yellow));
        btnConnect.setTextColor(ContextCompat.getColor(this, R.color.black));
    }

    private void closeSocket() {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
                bluetoothSocket = null;
            }
        } catch (IOException ignored) {
        }
    }

    private void sendCommand(String cmd) {
        if (!isConnected || outputStream == null) {
            return;
        }
        executorService.execute(() -> {
            try {
                outputStream.write((cmd + "\n").getBytes());
                outputStream.flush();
            } catch (IOException e) {
                mainHandler.post(this::disconnectBluetooth);
            }
        });
    }

    // --- TEAM MEMBERS LOGIC ---

    private void loadTeamMembers() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String defaultMembers = getString(R.string.default_team_members);
        String savedMembers = prefs.getString(KEY_TEAM_MEMBERS, defaultMembers);
        tvTeamMembers.setText(savedMembers);
    }

    private void showEditTeamDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.edit_team_dialog_title);

        final EditText input = new EditText(this);
        input.setText(tvTeamMembers.getText().toString());
        input.setPadding(32, 32, 32, 32);
        builder.setView(input);

        builder.setPositiveButton(R.string.save, (dialog, which) -> {
            triggerHapticFeedback();
            String newMembers = input.getText().toString().trim();
            if (!newMembers.isEmpty()) {
                tvTeamMembers.setText(newMembers);
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_TEAM_MEMBERS, newMembers).apply();
            }
        });

        builder.setNegativeButton(R.string.cancel, null);
        builder.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectBluetooth();
        executorService.shutdown();
    }
}