package se.lublin.mumla.preference;

import android.os.Bundle;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public abstract class MumlaPreferenceFragment extends PreferenceFragmentCompat {

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        String fragmentClassName = preference.getFragment();
        if (fragmentClassName != null) {
            Bundle result = new Bundle();
            result.putString("fragmentClassName", fragmentClassName);
            CharSequence title = preference.getTitle();
            if (title != null) {
                result.putString("title", title.toString());
            }
            getParentFragmentManager().setFragmentResult("launchFragment", result);
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        Bundle args = getArguments();
        if (args != null && args.containsKey("title")) {
            String title = args.getString("title");
            if (getActivity() != null && ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar() != null) {
                ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setTitle(title);
            }
        }
    }
}