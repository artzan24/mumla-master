package se.lublin.mumla.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Build;
import android.annotation.SuppressLint;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import se.lublin.mumla.R;

public class SplashActivity extends AppCompatActivity {

    private LinearLayout layoutLoader;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        TextView title = findViewById(R.id.splash_title);
        TextView subtitle = findViewById(R.id.splash_subtitle);

        // Inisialisasi komponen loader dan status teks di bawah
        layoutLoader = findViewById(R.id.layout_loader);
        statusText = findViewById(R.id.status_text);

        // Tahap 1: Animasi Fade-in Logo (Muncul dalam 800ms)
        logo.animate().alpha(1f).setDuration(800).withEndAction(new Runnable() {
            @Override
            public void run() {
                // Tahap 2: Tampilkan Teks Judul Aplikasi
                title.animate().alpha(1f).setDuration(600).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // Tahap 3: Tampilkan Teks Instansi
                        subtitle.animate().alpha(1f).setDuration(600).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                // Tahap 4: Munculkan Loader & Slogan di bawah secara bersamaan
                                layoutLoader.animate().alpha(1f).setDuration(400).withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        // Beri jeda tipis (500ms) lalu jalankan pengecekan auto-login
                                        logo.postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                checkAutoLoginAndProceed();
                                            }
                                        }, 500);
                                    }
                                }).start();
                            }
                        }).start();
                    }
                }).start();
            }
        }).start();

        try {
            String checkedNrp = null;
            String checkedPassword = null;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                        androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
                );
                android.content.SharedPreferences encryptedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        "MumbleUserSessionEncrypted",
                        masterKeyAlias,
                        this,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                checkedNrp = encryptedPrefs.getString("KEY_NRP", null);
                checkedPassword = encryptedPrefs.getString("KEY_PASSWORD", null);
            } else {
                android.content.SharedPreferences fallbackPrefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                checkedNrp = fallbackPrefs.getString("KEY_NRP", null);
                checkedPassword = fallbackPrefs.getString("KEY_PASSWORD", null);
            }

            // Cek di Logcat dengan informasi NRP dan status Password
            if (checkedNrp != null && !checkedNrp.isEmpty()) {
                boolean isPasswordExist = (checkedPassword != null && !checkedPassword.isEmpty());
                android.util.Log.d("CEK_STORAGE", "STATUS: Data Tersimpan! NRP: " + checkedNrp + " | Password Tersimpan: " + isPasswordExist);
            } else {
                android.util.Log.d("CEK_STORAGE", "STATUS: Belum ada data kredensial yang tersimpan.");
            }
        } catch (Exception e) {
            android.util.Log.e("CEK_STORAGE", "Gagal membaca storage: " + e.getMessage());
        }
    }

    @SuppressLint("NewApi")
    private void checkAutoLoginAndProceed() {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("is_splash_done", true)
                .apply();

        String savedNrp = null;
        String savedPassword = null;

        // 1. Baca data login terenkripsi dengan aman untuk semua versi Android
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String masterKeyAlias = androidx.security.crypto.MasterKeys.getOrCreate(
                        androidx.security.crypto.MasterKeys.AES256_GCM_SPEC
                );

                android.content.SharedPreferences encryptedPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                        "MumbleUserSessionEncrypted",
                        masterKeyAlias,
                        this,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );

                savedNrp = encryptedPrefs.getString("KEY_NRP", null);
                savedPassword = encryptedPrefs.getString("KEY_PASSWORD", null);
            } else {
                // Fallback otomatis untuk Android di bawah API 23 (Lollipop 5.0 / 5.1)
                android.content.SharedPreferences fallbackPrefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
                savedNrp = fallbackPrefs.getString("KEY_NRP", null);
                savedPassword = fallbackPrefs.getString("KEY_PASSWORD", null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            android.content.SharedPreferences fallbackPrefs = getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);
            savedNrp = fallbackPrefs.getString("KEY_NRP", null);
            savedPassword = fallbackPrefs.getString("KEY_PASSWORD", null);
        }

        if (savedNrp == null || savedPassword == null || savedNrp.trim().isEmpty()) {
            navigateToMain(false);
            return;
        }

        if (!isNetworkAvailable()) {
            showNetworkErrorDialog();
            return;
        }

        /*if (statusText != null) {
            statusText.setText("Memverifikasi sesi...");
        }*/

        navigateToMain(true);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                );
            } else {
                // Pengecekan jaringan untuk Android API 21-22
                android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                return activeNetwork != null && activeNetwork.isConnected();
            }
        }
        return false;
    }

    private void showNetworkErrorDialog() {
        // Sembunyikan loader jika dialog error muncul
        if (layoutLoader != null) {
            layoutLoader.setVisibility(View.GONE);
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Koneksi Jaringan Tidak Ada")
                .setMessage("Tidak dapat terhubung ke internet. Periksa koneksi jaringan perangkat Anda.")
                .setPositiveButton("Coba Lagi", (dialog, which) -> {
                    recreate(); // Muat ulang splash screen
                })
                .setNegativeButton("Masuk Manual", (dialog, which) -> {
                    navigateToMain(false); // Lempar ke halaman login manual
                })
                .setCancelable(false)
                .show();
    }

    private void navigateToMain(boolean isAutoLogin) {
        Intent intent = new Intent(SplashActivity.this, MumlaActivity.class);
        intent.putExtra("from_splash", true);
        intent.putExtra("is_auto_login", isAutoLogin);
        startActivity(intent);
        finish();
    }
}