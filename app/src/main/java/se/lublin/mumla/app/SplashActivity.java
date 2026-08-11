package se.lublin.mumla.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import se.lublin.mumla.R;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        TextView title = findViewById(R.id.splash_title);
        TextView subtitle = findViewById(R.id.splash_subtitle);

        // Tahap 1: Animasi Fade-in Logo (Muncul dalam 800ms)
        logo.animate().alpha(1f).setDuration(800).withEndAction(new Runnable() {
            @Override
            public void run() {
                // Tahap 2: Setelah logo muncul, tampilkan Teks Aplikasi
                title.animate().alpha(1f).setDuration(600).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // Tahap 3: Setelah teks aplikasi muncul, tampilkan Teks Instansi
                        subtitle.animate().alpha(1f).setDuration(600).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                // Tahap 4: Beri jeda sebentar (800ms) lalu pindah ke MumlaActivity
                                logo.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        getSharedPreferences("app_prefs", MODE_PRIVATE)
                                                .edit()
                                                .putBoolean("is_splash_done", true)
                                                .apply();
                                        Intent intent = new Intent(SplashActivity.this, MumlaActivity.class);
                                        intent.putExtra("from_splash", true);
                                        startActivity(intent);
                                        finish();
                                    }
                                }, 800);
                            }
                        }).start();
                    }
                }).start();
            }
        }).start();
    }
}