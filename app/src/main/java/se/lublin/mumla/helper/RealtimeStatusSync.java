package se.lublin.mumla.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RealtimeStatusSync {

    private static final String TAG = "RealtimeStatusSyncDebug";

    public static void sendStatus(Context context, String userNrp, String status_device, String channel_device) {
        if (userNrp == null || userNrp.isEmpty()) {
            Log.w(TAG, "Gagal kirim: Username/NRP masih kosong!");
            return;
        }

        // 1. Ambil Lokasi Akurat via FusedLocation
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            // Ambil IP Publik dulu meskipun izin lokasi tidak ada
            fetchPublicIpAndSend(userNrp, status_device, channel_device, 0.0, 0.0);
            return;
        }

        FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(new OnSuccessListener<Location>() {
                    @Override
                    public void onSuccess(Location location) {
                        double lat = (location != null) ? location.getLatitude() : 0.0;
                        double lng = (location != null) ? location.getLongitude() : 0.0;

                        // Lanjutkan ambil IP publik dan kirim data ke server
                        fetchPublicIpAndSend(userNrp, status_device, channel_device, lat, lng);
                    }
                });
    }

    // Fungsi untuk mendeteksi IP Publik secara otomatis dari internet
    private static void fetchPublicIpAndSend(String nrp, String status_device, String channel_device, double lat, double lng) {
        OkHttpClient client = new OkHttpClient();
        Request ipRequest = new Request.Builder()
                .url("https://api.ipify.org?format=text") // Layanan gratis untuk cek IP Publik
                .build();

        client.newCall(ipRequest).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Gagal ambil IP publik, menggunakan fallback lokal: " + e.getMessage());
                // Jika gagal konek ke internet luar, kirim dengan IP kosong/default
                executePost(nrp, status_device, channel_device, lat, lng, "Unknown");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String publicIp = "Unknown";
                if (response.isSuccessful() && response.body() != null) {
                    publicIp = response.body().string().trim();
                    //Log.d(TAG, "IP Publik Berhasil Didapatkan: " + publicIp);
                }
                // Kirim data lengkap ke API utama dengan IP Publik yang didapat
                executePost(nrp, status_device, channel_device, lat, lng, publicIp);
            }
        });
    }

    private static void executePost(String nrp, String status_device, String channel_device, double lat, double lng, String ipAddress) {
        String url = "https://mumble.tekkombali.com/api/realtime";
        String apiKey = "RAHASIA_RADIO_24101981";

        JSONObject jsonBody = new JSONObject();
        try {
            jsonBody.put("nrp", nrp);
            jsonBody.put("status_device", status_device);
            jsonBody.put("channel_device", channel_device);
            jsonBody.put("latitude", lat);
            jsonBody.put("longitude", lng);
            jsonBody.put("ip_address", ipAddress);
        } catch (org.json.JSONException e) {
            e.printStackTrace();
        }

        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create(
                jsonBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("X-API-Key", apiKey)
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "GAGAL TOTAL (onFailure): " + e.getMessage(), e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "kosong";
                if (response.isSuccessful()) {
                    //Log.d(TAG, "SUKSES BERHASIL: " + responseBody);
                } else {
                    Log.e(TAG, "GAGAL DARI SERVER: " + responseBody);
                }
            }
        });
    }
}