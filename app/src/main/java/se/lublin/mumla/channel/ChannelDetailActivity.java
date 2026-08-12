package se.lublin.mumla.channel;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.model.IChannel;
import se.lublin.mumla.R;
import se.lublin.mumla.app.MumlaActivity;
import se.lublin.mumla.service.MumlaService;

public class ChannelDetailActivity extends AppCompatActivity {

    private static final String TAG = "ChannelDetailActivity";

    private int mChannelId;
    private String mChannelName;
    private String mChannelDescription;
    private TextView tvChannelStatus;
    private TextView tvChannelBusyState;
    private TextView tvTalkingInfo;
    private View mBtnPtt;

    private IHumlaService mService;
    private boolean mBound = false;
    private ImageView mToolbarJoinButton;
    private boolean mIsPttBlocked = false;
    private long mOpenTimestamp = 0;

    private final Handler mStatusHandler = new Handler(Looper.getMainLooper());
    private long mLastApiCheckTime = 0;

    private final Runnable mStatusRunnable = new Runnable() {
        @Override
        public void run() {
            if (mBound && mService != null) {
                try {
                    // --- 1. CEK KONEKSI PUTUS / SERVER DOWN ---
                    if (!mService.isConnected()) {
                        mStatusHandler.removeCallbacks(this);
                        // ... (kode alert dialog diringkas untuk kejelasan)
                        return;
                    }

                    // --- 2. JALANKAN SINKRONISASI API SETIAP 5 DETIK ---
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - mLastApiCheckTime > 5000) {
                        mLastApiCheckTime = currentTime;
                        syncAccessFromApi(); // Memanggil endpoint POST login
                    }

                    // --- 3. DETEKSI PENCABUTAN AKSES ---
                    if (mChannelId > 1) {
                        if (System.currentTimeMillis() - mOpenTimestamp > 3000) {

                            String allowedChannelsCsv = "1";
                            try {
                                SharedPreferences prefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                                if (prefs != null) {
                                    allowedChannelsCsv = prefs.getString("allowed_channels", "1");
                                }
                            } catch (Exception ex) {
                                allowedChannelsCsv = "1";
                            }

                            Log.d(TAG, "DEBUG PTT SYNC -> Current Channel ID: " + mChannelId + " | Allowed Channels in Prefs: " + allowedChannelsCsv);

                            // Pengecekan akurat agar ID 4 tidak keliru dengan ID 14 / 40
                            boolean isAccessRevoked = true;
                            try {
                                String[] channelsArray = allowedChannelsCsv.split(",");
                                for (String chId : channelsArray) {
                                    if (chId.trim().equals(String.valueOf(mChannelId))) {
                                        isAccessRevoked = false; // Ditemukan, berarti akses masih ada
                                        break;
                                    }
                                }
                            } catch (Exception e) {
                                isAccessRevoked = !allowedChannelsCsv.contains(String.valueOf(mChannelId));
                            }

                            Log.d(TAG, "DEBUG PTT SYNC -> Is Access Revoked? " + isAccessRevoked);

                            if (isAccessRevoked) {
                                Log.w(TAG, "DEBUG PTT SYNC -> Akses dicabut! Memulai proses pembersihan PTT dan keluar channel...");
                                mStatusHandler.removeCallbacks(this);

                                IHumlaSession session = mService.HumlaSession();
                                if (session != null) {
                                    try {
                                        session.setTalkingState(false);
                                        if (session.getSessionUser() != null) {
                                            sendPttDataToApi(session.getSessionUser().getName(), String.valueOf(mChannelId), "release");
                                        }
                                        session.joinChannel(1); // Pindah ke Channel 1 (Guest)
                                        Log.w(TAG, "DEBUG PTT SYNC -> Berhasil memindahkan user ke Channel 1 (Guest).");
                                    } catch (Exception ex) {
                                        Log.e(TAG, "DEBUG PTT SYNC -> Error forcing leave channel: " + ex.getMessage());
                                    }
                                }

                                runOnUiThread(() -> {
                                    try {
                                        Toast.makeText(ChannelDetailActivity.this, "Akses channel dicabut oleh server.", Toast.LENGTH_LONG).show();
                                        Log.w(TAG, "DEBUG PTT SYNC -> Menutup ChannelDetailActivity (finish).");
                                        finish();
                                    } catch (Exception e) {
                                        Log.e(TAG, "DEBUG PTT SYNC -> Error finishing activity: " + e.getMessage());
                                    }
                                });
                                return;
                            }
                        }
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error checking status: " + e.getMessage());
                }
            }

            updateJoinStateUI();
            mStatusHandler.postDelayed(this, 1000); // Cek loop setiap 1 detik
        }
    };
    private void syncAccessFromApi() {
        SharedPreferences prefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
        String inputNrp = prefs.getString("saved_username", "");
        String passwordCi4 = prefs.getString("saved_ci4_password", "");

        if (inputNrp.isEmpty()) {
            if (mService != null && mService.getTargetServer() != null) {
                inputNrp = mService.getTargetServer().getUsername();
            }
        }

        if (inputNrp == null || inputNrp.trim().isEmpty()) {
            return;
        }

        String url = "https://mumble.tekkombali.com/api/login";
        String apiKey = "RAHASIA_RADIO_24101981";

        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();

        okhttp3.RequestBody formBody = new okhttp3.FormBody.Builder()
                .add("nrp", inputNrp.trim())
                .add("password", passwordCi4)
                .build();

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("X-API-KEY", apiKey)
                .post(formBody)
                .build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                Log.e(TAG, "DEBUG PTT SYNC -> Gagal mengambil data API: " + e.getMessage());
            }

            @Override
            public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBodyString = response.body().string();

                    try {
                        org.json.JSONObject jsonObj = new org.json.JSONObject(responseBodyString);
                        boolean isStatus = jsonObj.optBoolean("status", false);

                        if (isStatus) {
                            org.json.JSONArray channelsArray = jsonObj.optJSONArray("allowed_channels");
                            StringBuilder channelIdsBuilder = new StringBuilder("1"); // Channel guest selalu ada

                            if (channelsArray != null) {
                                for (int i = 0; i < channelsArray.length(); i++) {
                                    org.json.JSONObject ch = channelsArray.getJSONObject(i);
                                    String chId = ch.optString("id", "");
                                    if (!chId.isEmpty() && !chId.equals("1")) {
                                        channelIdsBuilder.append(",").append(chId);
                                    }
                                }
                            }

                            String newChannelsString = channelIdsBuilder.toString();
                            String oldChannelsString = prefs.getString("allowed_channels", "");

                            if (!newChannelsString.equals(oldChannelsString)) {
                                prefs.edit().putString("allowed_channels", newChannelsString).apply();
                                Log.d(TAG, "DEBUG PTT SYNC -> Sukses perbarui allowed_channels di Activity Detail: " + newChannelsString);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "DEBUG PTT SYNC -> Error parsing JSON dari API: " + e.getMessage());
                    }
                }
            }
        });
    }
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            MumlaService.MumlaBinder binder = (MumlaService.MumlaBinder) service;
            mService = binder.getService();
            mBound = true;
            updateJoinStateUI();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mService = null;
            mBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_channel_single);
        mOpenTimestamp = System.currentTimeMillis();

        Toolbar toolbar = findViewById(R.id.toolbar_single);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
        }

        mChannelId = getIntent().getIntExtra("channel_id", -1);
        mChannelName = getIntent().getStringExtra("channel_name");
        mChannelDescription = getIntent().getStringExtra("channel_description");

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
            getSupportActionBar().setTitle(mChannelName != null ? mChannelName : "Channel Detail");
        }

        tvChannelStatus = findViewById(R.id.single_channel_status);
        tvChannelBusyState = findViewById(R.id.tv_channel_busy_state);
        tvTalkingInfo = findViewById(R.id.tv_talking_info);
        TextView tvChannelDesc = findViewById(R.id.single_channel_description);

        mBtnPtt = findViewById(R.id.pushtotalk);

        if (tvChannelDesc != null) {
            if (mChannelDescription != null && !mChannelDescription.isEmpty()) {
                tvChannelDesc.setText(mChannelDescription);
            } else {
                tvChannelDesc.setText("Tidak ada deskripsi");
            }
        }

        // Setup Touch Listener PTT
        if (mBtnPtt != null) {
            mBtnPtt.setClickable(true);
            mBtnPtt.setOnTouchListener((v, motionEvent) -> {
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!isUserJoinedToChannel()) {
                            Toast.makeText(this, "Anda belum terhubung", Toast.LENGTH_SHORT).show();
                            v.setPressed(false);
                            v.setSelected(false);
                            v.refreshDrawableState();
                            return true;
                        }

                        if (isChannelBusy()) {
                            mIsPttBlocked = true;
                            Toast.makeText(this, "Channel Sibuk", Toast.LENGTH_SHORT).show();
                            v.setPressed(false);
                            v.setSelected(false);
                            v.refreshDrawableState();
                            return true;
                        }

                        mIsPttBlocked = false;
                        v.setPressed(true);
                        v.setSelected(true);
                        v.refreshDrawableState();

                        if (mService != null && mService.isConnected()) {
                            try {
                                IHumlaSession session = mService.HumlaSession();
                                if (session != null) {
                                    session.setTalkingState(true);
                                    sendPttDataToApi(session.getSessionUser().getName(), String.valueOf(mChannelId), "speak");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error starting talk: " + e);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        v.setSelected(false);
                        v.refreshDrawableState();

                        if (!isUserJoinedToChannel() || mIsPttBlocked) {
                            mIsPttBlocked = false;
                            return true;
                        }

                        if (mService != null && mService.isConnected()) {
                            try {
                                IHumlaSession session = mService.HumlaSession();
                                if (session != null) {
                                    session.setTalkingState(false);
                                    sendPttDataToApi(session.getSessionUser().getName(), String.valueOf(mChannelId), "release");
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error stopping talk: " + e);
                            }
                        }
                        return true;
                }
                return false;
            });
        }
    }

    // ==========================================
    // FUNGSI: KIRIM DATA PTT KE API CI4
    // ==========================================
    private void sendPttDataToApi(final String username, final String channelId, final String statusSpeak) {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL("https://mumble.tekkombali.com/api/logspeak");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("X-API-KEY", "RAHASIA_RADIO_24101981");
                conn.setDoOutput(true);

                String jsonInputString = String.format(
                        "{\"username\": \"%s\", \"channel_id\": \"%s\", \"status_speak\": \"%s\"}",
                        username, channelId, statusSpeak
                );

                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
                conn.getResponseCode();
            } catch (Exception e) {
                Log.e(TAG, "Gagal kirim PTT ke API: " + e.getMessage());
            }
        }).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, MumlaService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mStatusHandler.post(mStatusRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mStatusHandler.removeCallbacks(mStatusRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBound) {
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_channel_detail, menu);

        MenuItem joinMenuItem = menu.findItem(R.id.action_join_channel);
        if (joinMenuItem != null) {
            View actionView = joinMenuItem.getActionView();
            if (actionView != null) {
                mToolbarJoinButton = actionView.findViewById(R.id.toolbar_btn_join);
                if (mToolbarJoinButton != null) {
                    mToolbarJoinButton.setOnClickListener(v -> handleJoinAction());
                }
            }
        }

        updateJoinStateUI();
        return true;
    }

    private void showPermissionDeniedDialog() {
        if (!isFinishing()) {
            androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.perm_denied))
                    .setMessage(getString(R.string.perm_denied))
                    .setPositiveButton("OK", (d, which) -> d.dismiss())
                    .create();

            // Tampilkan dialog terlebih dahulu agar window-nya terinisialisasi
            dialog.show();

            // Ubah latar belakang window dialog menjadi transparan dan terapkan sudut melengkung
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_rounded_dialog);
            }
        }
    }

    private void handleJoinAction() {
        if (mService != null) {
            try {
                if (mService.isConnected()) {
                    IHumlaSession session = mService.HumlaSession();
                    if (session != null) {
                        session.joinChannel(mChannelId);

                        // Cek setelah jeda singkat apakah server menolak masuk
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (!isUserJoinedToChannel()) {
                                showPermissionDeniedDialog();
                            }
                            updateJoinStateUI();
                        }, 1500);
                    }
                } else {
                    Toast.makeText(this, "Tidak terhubung ke server", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error joining channel: " + e);
                showPermissionDeniedDialog();
            }
        } else {
            Toast.makeText(this, "Layanan belum siap", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateJoinStateUI() {
        boolean isJoined = isUserJoinedToChannel();
        boolean isPttEnabled = isJoined && (mChannelId != 0 && mChannelId != 1);

        // 1. Update State Tombol Join di Toolbar
        if (mToolbarJoinButton != null) {
            mToolbarJoinButton.setActivated(isJoined);
            mToolbarJoinButton.setSelected(isJoined);
            mToolbarJoinButton.setEnabled(!isJoined && mChannelId != 0 && mChannelId != 1);
            mToolbarJoinButton.refreshDrawableState();
        }

        // 2. Update State & Visual Tombol PTT
        if (mBtnPtt != null) {
            mBtnPtt.setEnabled(isPttEnabled);
            if (mBtnPtt instanceof ImageView) {
                if (isPttEnabled) {
                    ((ImageView) mBtnPtt).setImageResource(R.drawable.ic_action_microphone);
                } else {
                    ((ImageView) mBtnPtt).setImageResource(R.drawable.ic_mic_off);
                }
            } else if (mBtnPtt instanceof ImageButton) {
                if (isPttEnabled) {
                    ((ImageButton) mBtnPtt).setImageResource(R.drawable.ic_action_microphone);
                } else {
                    ((ImageButton) mBtnPtt).setImageResource(R.drawable.ic_mic_off);
                }
            }
            mBtnPtt.refreshDrawableState();
        }

        // 3. Update Status Utama
        if (tvChannelStatus != null) {
            if (mChannelId == 0 || mChannelId == 1) {
                tvChannelStatus.setText("Status: Channel Default Mic Off");
            } else if (!isJoined) {
                tvChannelStatus.setText("Status: Belum Terhubung");
            } else {
                tvChannelStatus.setText("Status: Terhubung Group");
            }
        }

        // 4. Update Status Channel (Busy / Idle)
        if (tvChannelBusyState != null) {
            if (isJoined && mChannelId != 0 && mChannelId != 1) {
                tvChannelBusyState.setVisibility(View.VISIBLE);
                if (isChannelBusy()) {
                    tvChannelBusyState.setText("Channel Status: Busy");
                    tvChannelBusyState.setTextColor(android.graphics.Color.parseColor("#FF9800"));
                } else {
                    tvChannelBusyState.setText("Channel Status: Idle");
                    tvChannelBusyState.setTextColor(android.graphics.Color.parseColor("#4CAF50"));
                }
            } else {
                tvChannelBusyState.setVisibility(View.GONE);
            }
        }

        // 5. Update Info User yang Berbicara
        if (tvTalkingInfo != null) {
            String talkingUser = getTalkingUserName();
            if (isJoined && talkingUser != null) {
                tvTalkingInfo.setText(talkingUser + " sedang berbicara...");
                tvTalkingInfo.setVisibility(View.VISIBLE);
            } else {
                tvTalkingInfo.setText("");
                tvTalkingInfo.setVisibility(View.GONE);
            }
        }
    }

    private boolean isUserJoinedToChannel() {
        if (mChannelId == 0 || mChannelId == 1) {
            return false;
        }

        if (mService == null || !mService.isConnected()) {
            return false;
        }
        try {
            IHumlaSession session = mService.HumlaSession();
            if (session != null) {
                IChannel activeChannel = session.getSessionChannel();
                if (activeChannel != null) {
                    return (mChannelId == activeChannel.getId());
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Error checking active channel in detail: " + e);
        }
        return false;
    }

    private boolean isChannelBusy() {
        if (mService == null || !mService.isConnected()) {
            return false;
        }
        try {
            java.util.List<? extends se.lublin.humla.model.IUser> users = mService.HumlaSession().getSessionChannel().getUsers();
            if (users != null) {
                for (se.lublin.humla.model.IUser user : users) {
                    if (user != null) {
                        switch (user.getTalkState()) {
                            case TALKING:
                            case SHOUTING:
                            case WHISPERING:
                                return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Error checking channel busy state: " + e);
        }
        return false;
    }

    private String getTalkingUserName() {
        if (mService == null || !mService.isConnected()) {
            return null;
        }
        try {
            int selfSession = mService.HumlaSession().getSessionId();
            java.util.List<? extends se.lublin.humla.model.IUser> users = mService.HumlaSession().getSessionChannel().getUsers();
            if (users != null) {
                for (se.lublin.humla.model.IUser user : users) {
                    if (user != null) {
                        switch (user.getTalkState()) {
                            case TALKING:
                            case SHOUTING:
                            case WHISPERING:
                                if (user.getSession() == selfSession) {
                                    return "Anda";
                                }
                                return user.getName();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Error getting talking user name: " + e);
        }
        return null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int pttKey = se.lublin.mumla.Settings.getInstance(this).getPushToTalkKey();

        if (mService != null && keyCode == pttKey) {
            if (event.getRepeatCount() > 0) {
                return true;
            }

            if (!isUserJoinedToChannel()) {
                Toast.makeText(this, "Anda belum terhubung", Toast.LENGTH_SHORT).show();
                if (mBtnPtt != null) {
                    mBtnPtt.setPressed(false);
                    mBtnPtt.setSelected(false);
                    mBtnPtt.refreshDrawableState();
                }
                return true;
            }

            if (isChannelBusy()) {
                mIsPttBlocked = true;
                Toast.makeText(this, "Channel Sibuk", Toast.LENGTH_SHORT).show();
                if (mBtnPtt != null) {
                    mBtnPtt.setPressed(false);
                    mBtnPtt.setSelected(false);
                    mBtnPtt.refreshDrawableState();
                }
                return true;
            }

            mIsPttBlocked = false;

            if (mBtnPtt != null) {
                mBtnPtt.setPressed(true);
                mBtnPtt.setSelected(true);
                mBtnPtt.refreshDrawableState();
            }

            try {
                IHumlaSession session = mService.HumlaSession();
                if (session != null) {
                    session.setTalkingState(true);
                    sendPttDataToApi(session.getSessionUser().getName(), String.valueOf(mChannelId), "speak");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error starting talk via physical key: " + e);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int pttKey = se.lublin.mumla.Settings.getInstance(this).getPushToTalkKey();

        if (mService != null && keyCode == pttKey) {
            if (mBtnPtt != null) {
                mBtnPtt.setPressed(false);
                mBtnPtt.setSelected(false);
                mBtnPtt.refreshDrawableState();
            }

            if (!isUserJoinedToChannel() || mIsPttBlocked) {
                mIsPttBlocked = false;
                return true;
            }

            try {
                IHumlaSession session = mService.HumlaSession();
                if (session != null) {
                    session.setTalkingState(false);
                    sendPttDataToApi(session.getSessionUser().getName(), String.valueOf(mChannelId), "release");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping talk via physical key: " + e);
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}