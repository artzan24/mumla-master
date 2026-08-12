/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.servers;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.appcompat.widget.PopupMenu;

import java.util.List;

import se.lublin.humla.model.Server;
import se.lublin.mumla.R;

/**
 * Created by andrew on 05/05/14.
 */
public abstract class ServerAdapter<E extends Server> extends ArrayAdapter<E> {
    private int mViewResource;

    public ServerAdapter(Context context, int viewResource, List<E> servers) {
        super(context, 0, servers);
        mViewResource = viewResource;
    }

    @Override
    public long getItemId(int position) {
        return getItem(position).getId();
    }

    @Override
    public View getView(int position, View v, ViewGroup parent) {
        View view = v;

        if(v == null) {
            LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            view = inflater.inflate(mViewResource, parent, false);
        }

        final E server = getItem(position);

        // Ambil komponen form login dari server_list_row.xml yang baru
        final EditText etUsername = view.findViewById(R.id.server_row_edit_username);
        final EditText etPassword = view.findViewById(R.id.server_row_edit_password);
        final ImageView btnTogglePass = view.findViewById(R.id.btn_row_toggle_password);
        final Button btnConnect = view.findViewById(R.id.btn_row_connect);

        // Muat data yang pernah tersimpan sebelumnya menggunakan SharedPreferences (MumbleUserSession & RoipLoginPrefs)
        SharedPreferences prefsLogin = getContext().getSharedPreferences("RoipLoginPrefs", Context.MODE_PRIVATE);
        SharedPreferences prefsSession = getContext().getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE);

        String savedUsername = prefsLogin.getString("saved_username", server != null ? server.getUsername() : "");
        String savedPassword = prefsSession.getString("saved_ci4_password", prefsLogin.getString("saved_password", ""));

        if (etUsername != null && etUsername.getText().toString().isEmpty()) {
            etUsername.setText(savedUsername);
        }
        if (etPassword != null && etPassword.getText().toString().isEmpty()) {
            etPassword.setText(savedPassword);
        }

        // Set default awal password disamarkan menjadi titik-titik
        if (etPassword != null) {
            etPassword.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        }

        // Fitur Tombol Mata (Show/Hide Password)
        if (btnTogglePass != null && etPassword != null) {
            final boolean[] isPasswordVisible = {false};
            btnTogglePass.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int cursorPos = etPassword.getSelectionStart();
                    if (isPasswordVisible[0]) {
                        // Sembunyikan password kembali
                        etPassword.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                        btnTogglePass.setImageResource(R.drawable.ic_visibility);
                        isPasswordVisible[0] = false;
                    } else {
                        // Tampilkan password (buka mata)
                        etPassword.setTransformationMethod(null);
                        btnTogglePass.setImageResource(R.drawable.ic_visibility_off);
                        isPasswordVisible[0] = true;
                    }
                    etPassword.setSelection(cursorPos);
                }
            });
        }

        // Aksi saat tombol Connect ditekan
        if (btnConnect != null) {
            btnConnect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String usernameStr = etUsername != null ? etUsername.getText().toString().trim() : "";
                    String passwordStr = etPassword != null ? etPassword.getText().toString().trim() : "";

                    if (usernameStr.isEmpty()) {
                        if (etUsername != null) {
                            etUsername.setError("Username / NRP harus diisi!");
                            etUsername.requestFocus();
                        }
                        return;
                    }

                    // Simpan data otomatis ke SharedPreferences agar sinkron dengan MumlaActivity dan sesi login berikutnya
                    Context appCtx = getContext().getApplicationContext();

                    appCtx.getSharedPreferences("RoipLoginPrefs", Context.MODE_PRIVATE).edit()
                            .putString("saved_username", usernameStr)
                            .putString("saved_password", passwordStr)
                            .apply();

                    appCtx.getSharedPreferences("MumbleUserSession", Context.MODE_PRIVATE).edit()
                            .putString("saved_username", usernameStr)
                            .putString("saved_ci4_password", passwordStr)
                            .apply();

                    // Perbarui data server sebelum terhubung
                    if (server != null) {
                        server.setUsername(usernameStr);
                        server.setPassword(passwordStr);
                    }

                    // Panggil fungsi klik server bawaan adapter untuk melanjutkan proses koneksi ke MumlaActivity
                    onServerConnectClick(server);
                }
            });
        }

        return view;
    }

    // Method bantuan untuk menangani aksi koneksi yang dapat diimplementasikan di Fragment/Activity
    public void onServerConnectClick(Server server) {
        // Bisa dioverride atau disesuaikan dengan fungsi koneksi utama Mumla Activity Anda
    }

    private void onServerOptionsClick(final Server server, View optionsButton) {
        PopupMenu popupMenu = new PopupMenu(getContext(), optionsButton);
        popupMenu.inflate(getPopupMenuResource());
        popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                return onPopupItemClick(server, menuItem);
            }
        });
        popupMenu.show();
    }

    public abstract int getPopupMenuResource();
    public abstract boolean onPopupItemClick(Server server, MenuItem menuItem);
}