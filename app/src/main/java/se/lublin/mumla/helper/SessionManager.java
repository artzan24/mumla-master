package se.lublin.mumla.model;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.List;

public class SessionManager {
    // Nama file SharedPreferences untuk penyimpanan lokal aplikasi
    private static final String PREF_NAME = "MumlaSessionPrefs";
    private static final String KEY_IS_LOGGED_IN = "IsLoggedIn";
    private static final String KEY_NRP = "Nrp";
    private static final String KEY_REALNAME = "Realname";
    private static final String KEY_KESATUAN = "Kesatuan";
    private static final String KEY_ALLOWED_CHANNELS = "AllowedChannels";

    private SharedPreferences pref;
    private SharedPreferences.Editor editor;
    private Context context;
    private Gson gson;

    // Konstruktor untuk inisialisasi SessionManager
    public SessionManager(Context context) {
        this.context = context;
        // Membuka SharedPreferences dengan mode private (hanya aplikasi ini yang bisa akses)
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
        gson = new Gson();
    }

    // Fungsi untuk menyimpan data sesi setelah login berhasil
    public void createLoginSession(Profile profile, List<Channel> allowedChannels) {
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_NRP, profile.getNrp());
        editor.putString(KEY_REALNAME, profile.getRealname());
        editor.putString(KEY_KESATUAN, profile.getKesatuan());

        // Mengubah List<Channel> menjadi format teks JSON menggunakan Gson agar bisa disimpan ke SharedPreferences
        String channelsJson = gson.toJson(allowedChannels);
        editor.putString(KEY_ALLOWED_CHANNELS, channelsJson);

        // Simpan perubahan secara permanen di memori HP
        editor.apply();
    }

    // Cek apakah user sudah login atau belum
    public boolean isLoggedIn() {
        return pref.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    // Mengambil daftar channel yang diizinkan untuk user yang sedang login
    public List<Channel> getAllowedChannels() {
        String channelsJson = pref.getString(KEY_ALLOWED_CHANNELS, null);
        if (channelsJson == null) {
            return null;
        }
        // Mengubah kembali teks JSON menjadi objek List<Channel>
        Type type = new TypeToken<List<Channel>>() {}.getType();
        return gson.fromJson(channelsJson, type);
    }

    // Fungsi untuk menghapus sesi saat pengguna logout
    public void logoutUser() {
        editor.clear();
        editor.apply();
    }
}