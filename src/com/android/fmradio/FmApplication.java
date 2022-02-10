package com.android.fmradio;

import com.android.fmradio.CurrentStationItem;
import android.content.Context;
import android.app.Application;

import android.util.Log;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.preference.PreferenceManager;
import android.content.pm.PackageManager.NameNotFoundException;
public class FmApplication extends Application {

    private static final String TAG = "FmApplication";
    private Context mContext;

    @Override
    public void onCreate() {
        super.onCreate();
        //init context which is shared by other classes
        mContext = getApplicationContext();
        int currentVersion = 0;
        try {
            currentVersion = mContext.getPackageManager().getPackageInfo(mContext.getPackageName(), 0).versionCode;
        } catch (NameNotFoundException e) {
              Log.d(TAG, "Didn't find FM PackageName");
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        int lastVersion = prefs.getInt("VERSION_KEY", 0);
            if (currentVersion > lastVersion) {
                prefs.edit().clear().commit();
                prefs.edit().putInt("VERSION_KEY", currentVersion).commit();
            }
        CurrentStationItem.getInstance().setContext(mContext);
        FmUtils.mContext = mContext;
        FmUtils.initConfig(mContext);
    }
}
