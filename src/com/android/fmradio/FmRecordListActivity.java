/**
 * Created By Spreadst
 */

package com.android.fmradio;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.app.ProgressDialog;
import android.app.AlertDialog;
import android.app.Dialog;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.DialogInterface;
import android.database.ContentObserver;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.os.storage.StorageVolume;
import android.os.Environment;
import android.os.EnvironmentEx;
import android.os.storage.StorageManager;
import android.text.TextUtils;

import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.Audio.Media;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.CheckBox;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Message;


public class FmRecordListActivity extends Activity implements OnItemClickListener, OnItemLongClickListener {

    /**
     * search FMRecord
     */
    private static final int INDEX_ID = 0;
    private static final int INDEX_DATA = 1;
    private static final int INDEX_DISPLAY_NAME = 2;
    private static final int INDEX_DISPLAY_DURATION = 3;
    private static final int INDEX_DISPLAY_DATE_ADDED = 4;
    private static final String FM_RECORD_FOLDER = "FM Recordings";

    final String[] PROJECTION = new String[] {
            AudioColumns._ID, //0
            AudioColumns.DATA, //1
            AudioColumns.DISPLAY_NAME, //2
            AudioColumns.DURATION, //3
            AudioColumns.DATE_ADDED, //4
    };

    private static final String TAG = "FmRecordListActivity";
    private ListView mListView;
    private FmRecordAdapter mAdapter = null;
    private Cursor mRecordsCursor;
    private Context mContext = null; // application context

    private FmMediaPalyer mPlayer;
    private Handler mProgressRefresher = new Handler();
    private AudioManager mAudioManager;
    private ActionMode mActionMode = null;
    private TreeSet<String> mSelectedRecordsSet = new TreeSet<String>();
    // Audio focus is held or not
    public boolean mIsAudioFocusHeld = false;

    private Uri externalStorageUri = null;
    private BroadcastReceiver mSdcardListener = null;

    private Toast mToast;
    private CharSequence mToastText;

    private static final String SCAN_FLAG = "RecordingScanFlag";
    private boolean isScanRecordingPath = true;

    private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
        public void onAudioFocusChange(int focusChange) {
            if (mPlayer == null) {
                mIsAudioFocusHeld = false;
                mAudioManager.abandonAudioFocus(this);
                return;
            }
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    mIsAudioFocusHeld = false;
                    if (mPlayer.isPlaying()) {
                        pause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    try {
                        Thread.sleep(300);  // we sleep 300ms to wait 3rdpart app to have setmode() done.
                    } catch (Exception e) {
                        Log.d(TAG, "onAudioFocusChange Sleep exception");
                    }
                    mIsAudioFocusHeld = true;
                    if (mPlayer.isPrepared()) {
                        start();
                    }
                    break;
            }
            mAdapter.updatePlayingItem();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fm_record_list_layout);
        getActionBar().setTitle(getString(R.string.fm_record_list));
        getActionBar().setDisplayHomeAsUpEnabled(true);
        mContext = getApplicationContext();
        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        mListView = (ListView) findViewById(R.id.fm_record_list);
        mListView.setEmptyView(findViewById(R.id.fm_recordlist_empty));
        mListView.setOnItemLongClickListener(FmRecordListActivity.this);
        mListView.setOnItemClickListener(FmRecordListActivity.this);
        mAdapter = new FmRecordAdapter(FmRecordListActivity.this, mRecordsCursor);
        mListView.setAdapter(mAdapter);

        mSdcardListener = new SdcardListener();
        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("file");
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        //filter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        registerReceiver(mSdcardListener, filter);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                break;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (mPlayer != null) {
            stopPlayback();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        FmUtils.setOnErrorPermissionListener(new FmUtils.onErrorPermissionListener() {
            @Override
            public void onCallback() {
                finish();
            }
        });
        if (!FmUtils.hasStoragePermission(this)) { // No StoragePermission
            String[] permissionsToRequest = new String[2];
            permissionsToRequest[0] = Manifest.permission.WRITE_EXTERNAL_STORAGE;
            permissionsToRequest[1] = Manifest.permission.READ_EXTERNAL_STORAGE;
            requestPermissions(permissionsToRequest, FmUtils.FM_PERMISSIONS_REQUEST_CODE);
            return;
        } else {
            queryFileAsync();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SCAN_FLAG, isScanRecordingPath);
    }

    @Override
    protected void onRestoreInstanceState(Bundle outState) {
        super.onRestoreInstanceState(outState);
        isScanRecordingPath = outState.getBoolean(SCAN_FLAG, false);
    }

    private void queryFileAsync() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                initCursor();
                for (Iterator selectIter = mSelectedRecordsSet.iterator(); selectIter.hasNext(); ) {
                    String selectData = (String) selectIter.next();
                    boolean needRemove = true;
                    if (mRecordsCursor != null && mRecordsCursor.moveToFirst()) {
                        do {
                            String data = mRecordsCursor.getString(INDEX_DATA);
                            if (data.equals(selectData)) {
                                needRemove = false;
                                break;
                            }
                        }while (mRecordsCursor.moveToNext());
                    }
                    if (needRemove) {
                        selectIter.remove();
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void aVoid) {
                mAdapter.changeCursor(mRecordsCursor);
                if(mActionMode != null) mActionMode.invalidate();
            }
        }.execute();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }

    private void initCursor() {
        try {
            scanRecordingPath();

            String selection = AudioColumns.ALBUM + "=?";
            String[] selectionArgs = new String[]{FmRecorder.RECORDING_FILE_SOURCE};
            mRecordsCursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, PROJECTION,
                    selection, selectionArgs, AudioColumns._ID + " collate NOCASE DESC");
            mRecordsCursor.setNotificationUri(getContentResolver(), MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void scanRecordingPath() {
        if (!isScanRecordingPath)
            return;

        File recordingDir = new File(EnvironmentEx.getInternalStoragePath().getPath(), FmRecorder.RECORDING_FILE_SOURCE);
        MediaScannerConnection.scanFile(FmRecordListActivity.this,
                new String[]{recordingDir.getPath()},
                null,
                null);
        isScanRecordingPath = false;
    }

    private static class ViewHolder {
        TextView title; //recrodings display name
        TextView duration;
        TextView playTime; //current time of the recordings playing
        TextView addedDate; //created date of the recordings
        LinearLayout recordStart; //paly and pause button
        SeekBar curSeekBar;
        CheckBox checkBox;
    }

    private class FmRecordAdapter extends CursorAdapter {

        private LayoutInflater mInflater;
        private int mCurrentPlayItemID = -1;

        public FmRecordAdapter(Context context, Cursor c) {
            super(context, c);
            mInflater = LayoutInflater.from(context);
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup parent) {
            View view = mInflater.inflate(R.layout.fm_record_item, null, false);
            ViewHolder holder = new ViewHolder();
            holder.title = (TextView) view.findViewById(R.id.fm_record_name);
            holder.duration = (TextView) view.findViewById(R.id.record_duration);
            holder.playTime = (TextView) view.findViewById(R.id.record_play_time);
            holder.addedDate = (TextView) view.findViewById(R.id.record_added_date);
            holder.recordStart = (LinearLayout) view.findViewById(R.id.record_start);
            holder.curSeekBar = (SeekBar) view.findViewById(R.id.progress);
            holder.checkBox = (CheckBox) view.findViewById(R.id.delete_check);
            view.setTag(holder);
            return view;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
            String displayName = cursor.getString(INDEX_DISPLAY_NAME);
            String data = cursor.getString(INDEX_DATA);
            final ViewHolder holder = (ViewHolder) view.getTag();
            holder.title.setText(displayName);
            holder.title.setTag(data);
            holder.duration.setText(FmUtils.makeTimeString(cursor.getInt(INDEX_DISPLAY_DURATION)));
            holder.addedDate.setText(FmUtils.getDateToString(cursor.getLong(INDEX_DISPLAY_DATE_ADDED), "yyyy.MM.dd"));
            if (mActionMode != null) {
                holder.checkBox.setVisibility(View.VISIBLE);
                holder.checkBox.setChecked(mSelectedRecordsSet.contains(data));
                holder.recordStart.setEnabled(false);
                holder.recordStart.setVisibility(View.GONE);
            } else {
                holder.checkBox.setVisibility(View.GONE);
                holder.recordStart.setEnabled(true);
                holder.recordStart.setVisibility(View.VISIBLE);
            }
            final int curId = cursor.getInt(INDEX_ID);
            ImageView recordIcon = (ImageView)((View)holder.recordStart.getParent()).findViewById(R.id.record_icon);
            if (mPlayer != null && mCurrentPlayItemID == curId) {
                holder.curSeekBar.setMax(mPlayer.mDuration);
                holder.curSeekBar.setProgress(mPlayer.getCurrentPosition());
                mPlayer.setUISeekBar(holder.curSeekBar);
                mPlayer.setUIPlayingTime(holder.playTime);
                holder.curSeekBar.setVisibility(View.VISIBLE);
                holder.playTime.setVisibility(View.VISIBLE);
                holder.addedDate.setVisibility(View.INVISIBLE);
                if (mPlayer.isPlaying()) {
                    recordIcon.setBackgroundDrawable(getResources().getDrawable(R.drawable.ic_pause));
                } else {
                    recordIcon.setBackgroundDrawable(getResources().getDrawable(R.drawable.ic_play));
                }
            } else {
                holder.playTime.setVisibility(View.INVISIBLE);
                holder.addedDate.setVisibility(View.VISIBLE);
                holder.curSeekBar.setVisibility(View.INVISIBLE);
                recordIcon.setBackgroundDrawable(getResources().getDrawable(R.drawable.ic_play));
            }

            holder.recordStart.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    itemStartPlay(curId);
                }
            });
        }

        private void itemStartPlay(int curId) {
            if (isCallStatus() || !requestAudioFocus()) {
                showToast(getText(R.string.no_allow_play_calling));
                return;
            }
            if (mPlayer != null && mCurrentPlayItemID == curId) {
                if (mPlayer.isPlaying()) {
                    pause();
                } else {
                    start();
                }
            } else {
                if (mPlayer != null) {
                    if (mProgressRefresher != null){
                        mProgressRefresher.removeCallbacksAndMessages(null);
                    }
                    mPlayer.release();
                    mPlayer = null;
                    updatePlayingItem();
                }
                mPlayer = new FmMediaPalyer();
                mPlayer.setActivity(FmRecordListActivity.this);
                try {
                    Uri mUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, curId);
                    mPlayer.setDataSourceAndPrepare(mUri);
                    mCurrentPlayItemID = curId;
                } catch (Exception e) {
                    mCurrentPlayItemID = -1;
                    showToast(getText(R.string.playback_failed));
                    return;
                }
            }
            updatePlayingItem();
        }

        private void updatePlayingItem() {
            if (mListView != null) {
                int start = mListView.getFirstVisiblePosition();
                for (int i = start, j = mListView.getLastVisiblePosition(); i <= j; i++) {
                    int id = ((Cursor) mListView.getItemAtPosition(i)).getInt(INDEX_ID);
                    if (mCurrentPlayItemID == id) {
                        View view = mListView.getChildAt(i - start);
                        getView(i, view, mListView);
                    }
                }
            }
        }
    }

    private void pause() {
        if (mProgressRefresher != null){
            mProgressRefresher.removeCallbacksAndMessages(null);
        }
        if (mPlayer != null){
            mPlayer.pause();
        }
    }

    public void start() {
        if (isCallStatus() || !requestAudioFocus()) {
            showToast(getText(R.string.no_allow_play_calling));
            return;
        }
        //Modify for 662375,Click recording file alternately and quickly,play error will occur high frequently.
        if (mPlayer != null && mPlayer.isPrepared()) {
            mPlayer.start();
            if (mProgressRefresher != null) {
                mProgressRefresher.removeCallbacksAndMessages(null);
                mProgressRefresher.postDelayed(new ProgressRefresher(), 300);
            }
        }
        mAdapter.updatePlayingItem();
    }

    private boolean isCallStatus() {
        int audioMode = mAudioManager.getMode();
        boolean flag = (audioMode == AudioManager.MODE_IN_COMMUNICATION || audioMode == AudioManager.MODE_RINGTONE);
        Log.d(TAG, "isCallStatus audioMode:" + audioMode);
        return (flag);
    }

    class ProgressRefresher implements Runnable {
        @Override
        public void run() {
            if (mPlayer != null && !mPlayer.mSeeking && mPlayer.mDuration != 0) {
                int currentTime = mPlayer.getCurrentPosition();
                mPlayer.mSeekBar.setProgress(currentTime);
                mPlayer.mPlayingTime.setText(FmUtils.makeTimeString(currentTime));
            }
            mProgressRefresher.removeCallbacksAndMessages(null);
            if (mPlayer != null) {
                mProgressRefresher.postDelayed(new ProgressRefresher(), 20);
            }
        }
    }

    private boolean requestAudioFocus() {
        if (mIsAudioFocusHeld) {
            return true;
        }
        int audioFocus = mAudioManager.requestAudioFocus(mAudioFocusListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        mIsAudioFocusHeld = (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioFocus);
        return mIsAudioFocusHeld;
    }

    private void stopPlayback() {
        if (mProgressRefresher != null) {
            mProgressRefresher.removeCallbacksAndMessages(null);
        }
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
            mIsAudioFocusHeld = false;
            mAudioManager.abandonAudioFocus(mAudioFocusListener);
        }
        mAdapter.updatePlayingItem();
    }

    public void onCompletion() {
        stopPlayback();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (mActionMode != null && mRecordsCursor != null) {
            CheckBox checkBox = (CheckBox) view.findViewById(R.id.delete_check);
            TextView textView = (TextView) view.findViewById(R.id.fm_record_name);
            String data = String.valueOf(textView.getTag());
            if (null == checkBox) {
                return;
            }
            if (checkBox.isChecked()) {
                if (mSelectedRecordsSet.contains(data)) {
                    mSelectedRecordsSet.remove(data);
                }
                checkBox.setChecked(false);
            } else {
                mSelectedRecordsSet.add(data);
                checkBox.setChecked(true);
            }
            mActionMode.invalidate();
            mAdapter.notifyDataSetChanged();
        }

        if (mActionMode == null) {
            int curId = (int) id;
            if (curId != mAdapter.mCurrentPlayItemID || mPlayer == null) {
                mAdapter.itemStartPlay(curId);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (mRecordsCursor != null) {
            mRecordsCursor.close();
            mRecordsCursor = null;
        }
        if (mSdcardListener != null) {
            unregisterReceiver(mSdcardListener);
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case FmUtils.FM_PERMISSIONS_REQUEST_CODE: {
                boolean resultsAllGranted = true;
                if (grantResults.length > 0) {
                    for (int result : grantResults) {
                        if (PackageManager.PERMISSION_GRANTED != result) {
                            resultsAllGranted = false;
                        }
                    }
                } else {
                    //case: when show request dialog, re-enter activity will request permission again and return 0;
                    //just return in this case.
                    return;
                }

                if (resultsAllGranted) {
                    Log.d(TAG, "Get Storage Permission");
                    queryFileAsync();
                } else {
                    Log.d(TAG, "No Storage Permission, finish !");
                    FmUtils.showErrorPermissionDialog(getResources().getString(R.string.record_error),
                            getResources().getString(R.string.error_permissions),
                            getResources().getString(R.string.dialog_dismiss),
                            FmRecordListActivity.this);
                }
            }
        }
    }

    /* Long click enter ActionMode to Delete item */
    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (mActionMode == null && mAdapter != null) {
            TextView textView = (TextView) view.findViewById(R.id.fm_record_name);
            startActionMode(new longClickCallback(String.valueOf(textView.getTag())));
        }
        return true;
    }

    private class longClickCallback implements ActionMode.Callback {
        private String data = null;
        private AlertDialog deleteDialog;

        public longClickCallback(String data) {
            this.data = data;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mActionMode = mode;
            MenuInflater menuInflater = mode.getMenuInflater();
            menuInflater.inflate(R.menu.delete_record_action_menu, menu);
            stopPlayback();
            mSelectedRecordsSet.add(data);
            mAdapter.notifyDataSetChanged();
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            MenuItem deleteButton = menu.findItem(R.id.item_delete_record);
            mode.setTitle(String.valueOf(mSelectedRecordsSet.size()));
            if (deleteButton == null) {
                return true;
            }
            if (mSelectedRecordsSet.size() != 0) {
                deleteButton.setEnabled(true);
            } else {
                deleteButton.setEnabled(false);
            }
            return true;
        }
        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            switch (item.getItemId()) {
                case R.id.item_delete_record:
                    deleteDialog = new AlertDialog.Builder(FmRecordListActivity.this)
                            .setMessage(FmUtils.getNumberFormattedQuantityString(FmRecordListActivity.this, R.plurals.confirm_delfile, mSelectedRecordsSet.size()))
                            .setPositiveButton(R.string.button_ok, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if(!checkExternalStorageUri()){
                                        Log.d(TAG, "external file cannot delete because we need permission to access SD card");
                                        return;
                                    }
                                    deleteFileAysnc();
                                }
                            })
                            .setNegativeButton(R.string.button_cancel, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {

                                }
                            }).create();
                    deleteDialog.show();
                    break;
                default:
                    break;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (deleteDialog != null && deleteDialog.isShowing()) {
                deleteDialog.dismiss();
            }
            mActionMode = null;
            mSelectedRecordsSet.clear();
            mAdapter.notifyDataSetChanged();
        }
    }

    private boolean isFinishOrDestroy() {
        if (FmRecordListActivity.this.isDestroyed() || FmRecordListActivity.this.isFinishing()) {
            return true;
        }
        return false;
    }

    private boolean deleteInternalFile(String data) {
        int row = getContentResolver().delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,AudioColumns.DATA + "='" + data + "'", null);
        return row > 0 ? true : false;
    }

    private void deleteExternalFile(Uri externalStorageUri, String itemName, String data) {
        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(externalStorageUri,
                DocumentsContract.getTreeDocumentId(externalStorageUri) + "/" + FmRecorder.FM_RECORD_FOLDER + "/" + itemName);
        Log.d(TAG, "deleteExternalFile fileUri : " + fileUri);
        try {
            DocumentsContract.deleteDocument(mContext.getContentResolver(), fileUri);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "deleteExternalFile: " + e);
            MediaScannerConnection.scanFile(mContext, new String[]{data}, null, null);
        } catch (Exception e) {
            Log.d(TAG, "" + e);
        }
    }

    private SpannableString setSpString(String string) {
        SpannableString title = new SpannableString(string);
        int titleColor = getResources().getColor(R.color.actionbar_overflow_title_color);
        title.setSpan(new ForegroundColorSpan(titleColor), 0, title.length(), 0);
        return title;
    }

    public boolean checkExternalStorageUri(){
        String externalStorageName = FmUtils.getExactStorageName(FmUtils.FM_RECORD_STORAGE_PATH_SDCARD);
        if (!TextUtils.isEmpty(externalStorageName)) {
            externalStorageUri = FmUtils.getCurrentAccessUri(FmUtils.FM_RECORD_STORAGE_PATH_SDCARD);
        }
        Log.d(TAG, "doInBackground externalStorageUri : " + externalStorageUri + " externalStorageName : " + externalStorageName);
        if (!TextUtils.isEmpty(externalStorageName) && externalStorageUri == null) {
            if (requestScopedDirectoryAccess(FmUtils.FM_RECORD_STORAGE_PATH_SDCARD)) {
                return false;
            }
        }
        return true;
    }

    private void deleteFileAysnc() {
        AsyncTask<Void, Long, Void> task = new AsyncTask<Void, Long, Void>() {
            ProgressDialog pd = null;

            @Override
            protected void onPreExecute() {
                pd = new ProgressDialog(FmRecordListActivity.this);
                pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pd.setTitle(setSpString(getResources().getString(R.string.progress_deleting)));
                pd.setCancelable(false);
                pd.setMax(mSelectedRecordsSet.size());
                pd.show();
            }

            @Override
            protected Void doInBackground(Void... params) {
                String externalStorageName = FmUtils.getExactStorageName(FmUtils.FM_RECORD_STORAGE_PATH_SDCARD);
                long index = 0;
                for(Iterator iter = mSelectedRecordsSet.iterator(); iter.hasNext(); ){
                    String deleteItemData = (String) iter.next();
                    if (deleteItemData != null) {
                        String itemName = deleteItemData.substring(deleteItemData.lastIndexOf("/") + 1);
                        Log.d(TAG, "deleteItemData : " + deleteItemData);
                        if (deleteItemData.contains("emulated")) {
                            if (!deleteInternalFile(deleteItemData)) {
                                Log.i(TAG, "delete internal file " + deleteItemData + " failed!");
                            }
                            index++;
                        } else if (!TextUtils.isEmpty(externalStorageName) && deleteItemData.contains(externalStorageName)) {
                            deleteExternalFile(externalStorageUri, itemName, deleteItemData);
                            index++;
                        } else{
                            publishProgress(-1L);
                            continue;
                        }
                    }
                    publishProgress(index);
                }
                Log.i(TAG, "delete finished.");
                return null;
            }

            @Override
            protected void onProgressUpdate(Long... values) {
                if (values[0] == -1L) {
                    showToast(getText(R.string.toast_storage_device_missing));
                } else {
                    pd.setProgress(values[0].intValue());
                }

            }

            @Override
            protected void onPostExecute(Void result) {
                if (!isFinishOrDestroy()) {
                    pd.cancel();
                }
                mAdapter.notifyDataSetChanged();
                if (mActionMode != null) {
                    mActionMode.finish();
                }
            }
        };
        task.execute((Void[]) null);
    }

    /**
     * @return true if there is a external volume to request perrmission.
     */
    private boolean requestScopedDirectoryAccess(String storagePath) {
        int requestCode = -1;
        boolean result = false;

        StorageManager storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = storageManager.getStorageVolumes();
        if (storagePath.equals(FmUtils.FM_RECORD_STORAGE_PATH_SDCARD)) {
            storagePath = EnvironmentEx.getExternalStoragePath().toString();
            requestCode = FmUtils.SD_REQUEST_CODE;
            Log.i(TAG,"SD storagePath: " + storagePath);
        }
        for (StorageVolume volume : volumes) {
            File volumePath = volume.getPathFile();
            Log.i(TAG,"requestScopedDirectoryAccess volumePath: " + volumePath);
            if (!volume.isPrimary() && volumePath != null &&
                    Environment.getExternalStorageState(volumePath).equals(Environment.MEDIA_MOUNTED)
                    && volumePath.toString().contains(storagePath)) {
                Log.i(TAG,"really createAccessIntent for : " + volumePath);
                final Intent intent = volume.createOpenDocumentTreeIntent();
                if (intent != null) {
                    startActivityForResult(intent, requestCode);
                    result = true;
                }
            }
        }

        return result;
    }

    private void showToast(CharSequence text) {
        if (mToast == null || text != mToastText || !text.equals(mToastText)) {
            mToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
            mToastText = text;
        }
        mToast.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult");

        if (resultCode == Activity.RESULT_OK) {
            Uri uri = null;
            if (data != null) {
                uri = data.getData();
                if (uri != null) {
                    String documentId = DocumentsContract.getTreeDocumentId(uri);
                    if (!documentId.endsWith(":") || "primary:".equals(documentId) ) {
                        FmUtils.showPermissionFailDialog(FmRecordListActivity.this,
                                R.string.error_external_storage_access, R.string.superuser_request_confirm);
                        return;
                    }

                    final ContentResolver resolver = getContentResolver();
                    final int modeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    resolver.takePersistableUriPermission(uri, modeFlags);

                }
            }
        } else {
            FmUtils.showPermissionFailDialog(FmRecordListActivity.this,
                    R.string.error_external_storage_access, R.string.feedback_description_external_access);
        }
    }

    private class SdcardListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "on receive:" + action);
            queryFileAsync();
        }
    }
}
