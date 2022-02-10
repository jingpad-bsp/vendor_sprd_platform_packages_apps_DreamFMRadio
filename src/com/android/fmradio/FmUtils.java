/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.fmradio;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.UriPermission;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.EnvironmentEx;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.database.Cursor;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View.MeasureSpec;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import android.text.TextUtils;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.KeyEvent;
import android.provider.DocumentsContract;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.io.File;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.content.pm.PackageManager;
import android.Manifest;
import android.os.SystemProperties;
import android.os.storage.VolumeInfo;
import android.content.DialogInterface;
import com.android.fmradio.FmStation.Station;
import java.text.NumberFormat;
import android.provider.MediaStore.Audio.Media;
import android.provider.MediaStore;
/**
 * This class provider interface to compute station and frequency, get project string
 */
public class FmUtils {
    private static final String TAG = "FmUtils";

    public static boolean support50ksearch;
    // convert rate
    public static final int CONVERT_RATE = 1000;
    // FM station variables
    public static final int DEFAULT_STATION = 87500;
    // maximum station frequency
    private static final int HIGHEST_STATION = 108000;
    // minimum station frequency
    private static final int LOWEST_STATION = 87500;
    // station step
    private static final int STEP = support50ksearch ? 5 : 1;
    // for support short antenna,false- dafault.
    public static boolean supportShortAntenna;
    public static final boolean supportRecordSourceFmTuner = SystemProperties.getBoolean("persist.sys.audio.source", false);
    // for recording path select.
    public static int FM_RECORD_STORAGE_PATH = 0;
    public static String FM_RECORD_STORAGE_PATH_NAME = "";
    public static final String FM_RECORD_STORAGE_PATH_SDCARD = "SD card";
    public static final String FM_RECORD_STORAGE_PATH_PHONE = "Phone";
    public static final int SD_REQUEST_CODE = 2;
    private static final String FM_EXTERANL_STORAGE_PREFER = "fm_record_storage";
    private static final String FM_TMEP_FILE_URI = "temp_file_uri";
    private static final String FM_RECORD_DEFAULT_PATH = "default_path";
    private static final String FM_RECORD_DEFAULT_PATH_NAME = "default_path_name";
    public static final int STORAGE_PATH_INTERNAL_CATEGORY = 0;
    public static final int STORAGE_PATH_EXTERNAL_CATEGORY = 1;

    //Regularly power off.
    public static float GLOBAL_CUSTOM_TIME = 30;
    private static final String FM_REGULAYLY_POWER_OFF = "fm_regularly_poweroff";
    private static final String DEFAULT_CUSTOM_SETTING = "default_custom_setting";
    // minimum storage space for record (512KB).
    // Need to check before starting recording and during recording to avoid
    // recording keeps going but there is no free space in sdcard.
    public static final long LOW_SPACE_THRESHOLD = 512 * 1024;

    private static final String FM_IS_FIRST_TIME_PLAY = "fm_is_first_time_play";
    private static final String FM_IS_SPEAKER_MODE = "fm_is_speaker_mode";
    // StorageManager For FM record
    private static StorageManager sStorageManager = null;
    private static AlertDialog alertDialog;
    private static Toast mToast;
    // for new UI angle computer
    private static DecimalFormat decimalFormat;
    // TODO，should be adjust for 50k/100k, Regin;
    public static int freqScope = 108000 - 87500 + 100; // 20550 KHz

    public static String formatFreq(float angle) {
        float absValue = (float)(angle * freqScope / 360.0 + 87500);
        int value = Math.round(absValue);
        if (value >= 108000) value = 108000;
        //for 50k,the last two num must be 00 or 50.
        int num = value % 100 < 50 ? 0 : 50;
        value = (value / 100) * 100 + num;
        float frequency = (float) value / CONVERT_RATE;
        Log.d(TAG, "formatFreq,angle:" + angle + ", absValue:" + absValue + ", value:" + value + ",freq:" + frequency);
        return decimalFormat.format(frequency);
    }


    public static final int FM_PERMISSIONS_REQUEST_CODE = 2016;
    //bug985004 Permission to transform
    private static onErrorPermissionListener mListener;

    public static float getHighestFrequency() {
        return computeFrequency(HIGHEST_STATION);
    }

    /**
     * Whether the frequency is valid.
     * @param station The FM station
     * @return true if the frequency is in the valid scale, otherwise return false
     */
    public static boolean isValidStation(int station) {
        boolean isValid = (station >= LOWEST_STATION && station <= HIGHEST_STATION);
        return isValid;
    }

    /**
     * Compute station value with given frequency
     * @param frequency The station frequency
     * @return station The result value
     */
    public static int computeStation(float frequency) {
        return (int) (frequency * CONVERT_RATE);
    }

    /**
     * Compute frequency value with given station
     * @param station The station value
     * @return station The frequency
     */
    public static float computeFrequency(int station) {
        return (float) station / CONVERT_RATE;
    }

    /**
     * According station to get frequency string
     * @param station for 100KZ, range 875-1080
     * @return string like 87.5
     */
    public static String formatStation(int station) {
        float frequency = (float) station / CONVERT_RATE;
        return decimalFormat.format(frequency);
    }

    /**
     * Get the phone storage path
     * @return The phone storage path
     */
    public static String getDefaultStoragePath() {

        //get saved default storage path informations
        SharedPreferences storageSP = mContext.getSharedPreferences(FM_EXTERANL_STORAGE_PREFER, Context.MODE_PRIVATE);
        String pathName = storageSP.getString(FM_RECORD_DEFAULT_PATH_NAME, FM_RECORD_STORAGE_PATH_PHONE);
        Log.d(TAG, "getDefaultStoragePath pathName:" + pathName);
        if (pathName.equals(FM_RECORD_STORAGE_PATH_PHONE)) {
            Log.i(TAG, "getDefaultStoragePath :phone");
            return EnvironmentEx.getInternalStoragePath().getPath();
        } else if (pathName.equals(FM_RECORD_STORAGE_PATH_SDCARD)) {
            Log.i(TAG, "getDefaultStoragePath :SDCARD");
            /*  bug569412 FM show "SD card is missing" when start recording @{ */
            if (!Environment.MEDIA_MOUNTED.equals(EnvironmentEx.getExternalStoragePathState())) {
                Log.i(TAG, "SDCARD UNMOUNTED getDefaultStoragePath :phone");
                FM_RECORD_STORAGE_PATH = STORAGE_PATH_INTERNAL_CATEGORY;
                return EnvironmentEx.getInternalStoragePath().getPath();
            } else {
                String path = "";
                File canonicalFile = null;
                try {
                    File externalStorageFile = EnvironmentEx.getExternalStoragePath();
                    if (externalStorageFile != null) {
                        canonicalFile = EnvironmentEx.getExternalStoragePath().getCanonicalFile();
                    }
                    if (canonicalFile != null) {
                        path = canonicalFile.getPath();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "getCanonicalFile error:", e);
                }
                return path;
            }
        }
        Log.e(TAG,"getDefaultStoragePath return null, something must be wrong.");
        return null;
    }

    /**
     * Get the default storage state
     * @return The default storage state
     */
    public static String getDefaultStorageState(Context context) {
        ensureStorageManager(context);
        String state = sStorageManager.getVolumeState(getDefaultStoragePath());
        return state;
    }

    private static void ensureStorageManager(Context context) {
        if (sStorageManager == null) {
            sStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        }
    }

    /**
     * Get the FM play list path
     * @param context The context
     * @return The FM play list path
     */
    public static String getPlaylistPath(Context context) {
        ensureStorageManager(context);
        String[] externalStoragePaths = sStorageManager.getVolumePaths();
        String path = externalStoragePaths[0] + "/Playlists/";
        return path;
    }

    /**
     * Check if has enough space for record
     * @param recordingSdcard The recording sdcard path
     * @return true if has enough space for record
     */
    public static boolean hasEnoughSpace(String recordingSdcard) {
        boolean ret = false;
        try {
            StatFs fs = new StatFs(recordingSdcard);
            long blocks = fs.getAvailableBlocks();
            long blockSize = fs.getBlockSize();
            long spaceLeft = blocks * blockSize;
            ret = spaceLeft > LOW_SPACE_THRESHOLD ? true : false;
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "hasEnoughSpace, sdcard may be unmounted:" + recordingSdcard);
        }
        return ret;
    }

    /**
     * check it is the first time to use Fm
     */
    public static boolean isFirstTimePlayFm(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isFirstTime = prefs.getBoolean(FM_IS_FIRST_TIME_PLAY, true);
        return isFirstTime;
    }

    /**
     * Called when first time play FM.
     * @param context The context
     */
    public static void setIsFirstTimePlayFm(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(FM_IS_FIRST_TIME_PLAY, false);
        editor.commit();
    }

    /**
     * Get whether speaker mode is in use when audio focus lost.
     * @param context the Context
     * @return true for speaker mode, false for non speaker mode
     */
    public static boolean getIsSpeakerModeOnFocusLost(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        return prefs.getBoolean(FM_IS_SPEAKER_MODE, false);
    }

    /**
     * Set whether speaker mode is in use.
     * @param context the Context
     * @param isSpeaker speaker state
     */
    public static void setIsSpeakerModeOnFocusLost(Context context, boolean isSpeaker) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(FM_IS_SPEAKER_MODE, isSpeaker);
        editor.commit();
    }

    public static boolean hasStoragePermission(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return false;
    }

    /* add feature for support OTG. @{ */
    public static Context mContext = null;

    public static Uri getCurrentAccessUri(String storagePath) {
        String exactStorageName = getExactStorageName(storagePath);
        Log.d(TAG,"getCurrentAccessUri, storagePath : " + storagePath+" exactStorageName : "+exactStorageName);
        List<UriPermission> uriPermissions = mContext.getContentResolver().getPersistedUriPermissions();
        for (UriPermission permission : uriPermissions) {
            Log.d(TAG, "getCurrentAccessUri permission: " + permission.toString());
            if (exactStorageName != null && permission.getUri().toString().contains(exactStorageName)) {
                return permission.getUri();
            }
        }
        return null;
    }

    public static String getExactStorageName(String storagePath) {
        Log.d(TAG, "getExactStorageName, storagePath: " + storagePath);
        String path;
        String[] pathName;
        if (storagePath.equals(FM_RECORD_STORAGE_PATH_SDCARD)) {
            path = EnvironmentEx.getExternalStoragePath().toString();
            pathName = path.split("/");
            return pathName[pathName.length - 1];
        }
        return null;
    }

    public static Uri getMediaFileUri(String filePath) {
        Uri fileUri = null;
        String internalPath = EnvironmentEx.getInternalStoragePath().toString();

        if (filePath.startsWith(internalPath)) {
            fileUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        } else {
            String[] pathDir = filePath.split("/");
            String volumeName = pathDir[2].toLowerCase();
            fileUri = MediaStore.Audio.Media.getContentUri(volumeName);
        }
        Log.d(TAG, "getMediaFileUri, filePath: " + filePath + ", fileUri:" + fileUri);
        return fileUri;
    }

    public static boolean isExternalStorage() {
        return (!TextUtils.equals(FM_RECORD_STORAGE_PATH_PHONE, FM_RECORD_STORAGE_PATH_NAME));
    }

    public static boolean isMountedExternalStorage() {
        boolean isMounted = false;
        if (isExternalStorage()) {
            isMounted = Environment.MEDIA_MOUNTED.equals(EnvironmentEx.getExternalStoragePathState());
            Log.d(TAG, "isMountedExternalStorage isMounted:" + isMounted);
        }
        return (isMounted);
    }

    public static void saveRecordDefaultPath() {
        SharedPreferences storageSP = mContext.getSharedPreferences(FM_EXTERANL_STORAGE_PREFER,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor storageEdit = storageSP.edit();
        storageEdit.putInt(FM_RECORD_DEFAULT_PATH, FM_RECORD_STORAGE_PATH);
        storageEdit.putString(FM_RECORD_DEFAULT_PATH_NAME, FM_RECORD_STORAGE_PATH_NAME);
        storageEdit.commit();
    }

    public static void restoreRecordDefaultPath() {
        Log.d(TAG,"restoreRecordDefault");
        SharedPreferences storageSP = mContext.getSharedPreferences(FM_EXTERANL_STORAGE_PREFER,
                Context.MODE_PRIVATE);
        FmUtils.FM_RECORD_STORAGE_PATH = storageSP.getInt(FM_RECORD_DEFAULT_PATH, 0);
        FmUtils.FM_RECORD_STORAGE_PATH_NAME = storageSP.getString(FM_RECORD_DEFAULT_PATH_NAME, FM_RECORD_STORAGE_PATH_PHONE);

    }

    public static String getTempFileUri() {
        SharedPreferences storagePref = mContext.getSharedPreferences(FM_EXTERANL_STORAGE_PREFER, Context.MODE_PRIVATE);
        return storagePref.getString(FM_TMEP_FILE_URI, "");
    }

    public static void saveTmpFileUri(String tmpUri) {
        Log.d(TAG, "saveOtgTmpUri="+tmpUri);
        SharedPreferences storagePref = mContext.getSharedPreferences(FM_EXTERANL_STORAGE_PREFER, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit= storagePref.edit();
        edit.putString(FM_TMEP_FILE_URI,tmpUri);
        edit.apply();
    }

    public static void getDefaultCustomTime(){
        SharedPreferences customSP = mContext.getSharedPreferences(FM_REGULAYLY_POWER_OFF,
                Context.MODE_PRIVATE);
        FmUtils.GLOBAL_CUSTOM_TIME = customSP.getFloat(DEFAULT_CUSTOM_SETTING, (float) 30.0);
    }

    public static void setDefaulCustomTime() {
        SharedPreferences storageSP = mContext.getSharedPreferences(FM_REGULAYLY_POWER_OFF,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor storageEdit = storageSP.edit();
        storageEdit.putFloat(DEFAULT_CUSTOM_SETTING, FmUtils.GLOBAL_CUSTOM_TIME);
        storageEdit.commit();
    }

    public static void deleteExStorageTempFile(File mFile) {
        String filePath = mFile.getPath();
        Uri uri = Uri.parse(getTempFileUri());
        Log.d(TAG, "deleteExStorageTempFile="+uri.toString());
        try {
            DocumentsContract.deleteDocument(mContext.getContentResolver(), uri);
        } catch(FileNotFoundException e) {
            Log.d(TAG,""+e);
        } catch(Exception e) {
            Log.d(TAG,""+e);
        }
    }

    public static void deleteInStorageTempFile(File mFile) {
        String filePath = mFile.getAbsolutePath();
        Log.d(TAG, "deleteInternalStorageTempFile="+filePath);
        try {
            if (!mFile.delete()) {
                // deletion failed, possibly due to hot plug out SD card
                Log.d(TAG, "deleteInternalStorageTempFile, delete file failed!");
            }
            mContext.getContentResolver().delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,MediaStore.Audio.AudioColumns.DATA + "='" + filePath+"'",null);
        } catch(Exception e) {
            Log.d(TAG,""+e);
        }
    }

    /**
     * temp porting code for AndroidN: Replace Environment.getInternalStoragePath().getPath()
     */
    public static String getInternalStoragePath() {
        String path = "/storage/emulated/0";
        return path;
    }

    public static boolean isAirplane(Context context) {
        boolean isair = Settings.System.getInt(context.getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0) ==1;
        Log.d(TAG,"isAirplaneMode: "+isair );
        return  isair;
    }

    public static void checkAndResetStoragePath(Context context, String removedPath) {
        //get saved default storage path informations
        SharedPreferences storageSP = context.getSharedPreferences(FM_EXTERANL_STORAGE_PREFER, Context.MODE_PRIVATE);
        SharedPreferences.Editor storageEdit = storageSP.edit();
        String pathName = storageSP.getString(FM_RECORD_DEFAULT_PATH_NAME, FM_RECORD_STORAGE_PATH_PHONE);
        Log.d(TAG, "checkAndResetStoragePath pathName:" + pathName);
        if (pathName.equals(FM_RECORD_STORAGE_PATH_SDCARD)) {
            if (!Environment.MEDIA_MOUNTED.equals(EnvironmentEx.getExternalStoragePathState())) {
                FM_RECORD_STORAGE_PATH = STORAGE_PATH_INTERNAL_CATEGORY;
                FM_RECORD_STORAGE_PATH_NAME = FM_RECORD_STORAGE_PATH_PHONE;
                storageEdit.putInt(FM_RECORD_DEFAULT_PATH, FM_RECORD_STORAGE_PATH);
                storageEdit.putString(FM_RECORD_DEFAULT_PATH_NAME, FM_RECORD_STORAGE_PATH_PHONE);
                storageEdit.commit();
            }
        }
    }
   /*
    *Judgement whether station's name you want to save is exist in DB
    *@param true for exist, false for not.
    */
    public static boolean isStationNameExist(String strName) {
        String mSelection = Station.STATION_NAME + "=?";
        String[] mSelectionArgs =  new String[] {strName};
        Cursor cursor = null;
        boolean isExist = false;
        try {
            cursor = mContext.getContentResolver().query(Station.CONTENT_URI,
                    FmStation.COLUMNS, mSelection, mSelectionArgs, FmStation.Station.FREQUENCY);
            isExist = (cursor == null ? false : cursor.getCount() > 0);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return isExist;
    }

    public static void showPermissionFailDialog(Context context ,final int title ,final int msg) {
        final DialogInterface.OnClickListener dialogListener =
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if(alertDialog != null){
                            alertDialog.dismiss();
                            alertDialog = null;
                        }
                    }
                };
        alertDialog = new AlertDialog.Builder(context)
                    .setCancelable(false)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.dialog_dismiss, dialogListener)
                    .show();
    }
    public static String getNumberFormattedQuantityString(Context context, int id, int quantity) {
        final String localizedQuantity = NumberFormat.getInstance().format(quantity);
        return context.getResources().getQuantityString(id, quantity, localizedQuantity);
    }

    public static void showErrorPermissionDialog(String title, String msg, String positiveButtonMsg, Context context){
        new AlertDialog.Builder(context)
            .setMessage(msg)
            .setCancelable(false)
            .setOnKeyListener(new Dialog.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface dialog, int keyCode,
                                     KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        if(mListener != null){
                            mListener.onCallback();
                        }
                    }
                    return true;
                }
            })
            .setPositiveButton(positiveButtonMsg,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if(mListener != null){
                                mListener.onCallback();
                            }
                        }
                    })
            .show();
        }
    public interface onErrorPermissionListener{
        void onCallback();
    }
    public static void setOnErrorPermissionListener(onErrorPermissionListener listener){
        mListener = listener;
    }

    public static String makeTimeString(int millSec) {
        String str = "";
        int hour = 0;
        int minute = 0;
        int second = 0;
        second = millSec / 1000;
        if (second > 59) {
            minute = second / 60;
            second = second % 60;
        }
        if (minute > 59) {
            hour = minute / 60;
            minute = minute % 60;
        }
        str = (hour < 10 ? "0" + hour : hour) + ":"
                + (minute < 10 ? "0" + minute : minute) + ":"
                + (second < 10 ? "0" + second : second);
        return str;
    }

    public static String getDateToString(long millSecond, String pattern) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
        return simpleDateFormat.format(new Date(millSecond*1000));
    }

    public static void initConfig(Context context) {
        support50ksearch = context.getResources().getBoolean(R.bool.config_support_50k_step);
        decimalFormat = new DecimalFormat(support50ksearch ? "0.00" : "0.0");
        decimalFormat.setDecimalFormatSymbols(new DecimalFormatSymbols(Locale.US));
        supportShortAntenna = context.getResources().getBoolean(R.bool.config_support_antenna);
    }

    public static void showToast(Context context, String text) {
        if (mToast != null) {
            mToast.cancel();
        }
        mToast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
        mToast.show();
    }
}
