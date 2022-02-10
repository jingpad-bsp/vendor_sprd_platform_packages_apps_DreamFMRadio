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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.EnvironmentEx;
import android.os.Handler;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.os.FileObserver;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.format.DateFormat;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * This class provider interface to recording, stop recording, save recording file, play recording
 * file
 */
public class FmRecorder implements MediaRecorder.OnErrorListener, MediaRecorder.OnInfoListener {
    private static final String TAG = "FmRecorder";
    // file prefix
    public static final String RECORDING_FILE_PREFIX = "FM";
    // file extension
    public static final String RECORDING_FILE_EXTENSION_MP3 = ".mp3";

    // recording file folder
    public static final String FM_RECORD_FOLDER = "FM Recordings";
    public static final String RECORDING_FILE_SOURCE = "FM Recordings";
    // error type no sdcard
    public static final int ERROR_SDCARD_NOT_PRESENT = 0;
    // error type sdcard not have enough space
    public static final int ERROR_SDCARD_INSUFFICIENT_SPACE = 1;
    // error type can't write sdcard
    public static final int ERROR_SDCARD_WRITE_FAILED = 2;
    // error type recorder internal error occur
    public static final int ERROR_RECORDER_INTERNAL = 3;

    public static final int ERROR_RECORD_FAILED = 4;

    public static final int ERROR_SDCARD_WRITE_PERMISSION = 5;

    // FM Recorder state not recording and not playing
    public static final int STATE_IDLE = 5;
    // FM Recorder state recording
    public static final int STATE_RECORDING = 6;
    // FM Recorder state playing
    public static final int STATE_PLAYBACK = 7;
    // FM Recorder state stop recording
    public static final int STATE_STOP_RECORDING = 8;
    // FM Recorder state saved
    public static final int STATE_SAVED = 9;
    // FM Recorder state invalid, need to check
    public static final int STATE_INVALID = -1;

    // use to record current FM recorder state
    public int mInternalState = STATE_IDLE;
    // the recording time after start recording
    private long mRecordTime = 0;
    // record start time
    private long mRecordStartTime = 0;
    // current record file
    private File mRecordFile = null;
    private FileListener mFileListener = null;
    private ParcelFileDescriptor mPfd = null;
    private final ArrayList<File> mFileQueue = new ArrayList<File>();
    // listener use for notify service the record state or error state
    private OnRecorderStateChangedListener mStateListener = null;
    // recorder use for record file
    private MediaRecorder mRecorder = null;

    private Uri getPathUri(Context context,File file,Uri uri) {
        Uri doc;
        if (file.exists()) {
            doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)+"/"+FM_RECORD_FOLDER);
        } else {
            doc = createDir(context.getContentResolver(),uri,FM_RECORD_FOLDER);
        }
        return doc;
    }

    private Uri createDir(ContentResolver cr, Uri uri, String dirName) {
        Uri dir;
        try {
            Uri doc = DocumentsContract. buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri));
            dir = DocumentsContract.createDocument(cr, doc,DocumentsContract.Document.MIME_TYPE_DIR,dirName);
        }catch (Exception e) {
            Log.e(TAG,"create bluetooth dir fail",e);
            dir = null;
        }
        return dir;
    }

    /**
     * Start recording the voice of FM, also check the pre-conditions, if not meet, will return an
     * error message to the caller. if can start recording success, will set FM record state to
     * recording and notify to the caller
     */
    public void startRecording(Context context) {
        mRecordTime = 0;
        Uri dir = null;
        FileDescriptor fd = null;
        Uri externalStorageUri=null;
        Log.d(TAG,"supportRecordSourceFmTuner =" + FmUtils.supportRecordSourceFmTuner);

        boolean isMounted = FmUtils.isMountedExternalStorage();
        if (isMounted) {
            externalStorageUri =FmUtils.getCurrentAccessUri(FmUtils.FM_RECORD_STORAGE_PATH_NAME);
        }

        String recordingSdcard = FmUtils.getDefaultStoragePath();
        Log.d(TAG,"externalStorageUri="+externalStorageUri+"   recordingSdcard="+recordingSdcard);

        if (recordingSdcard == null
                || recordingSdcard.isEmpty()) {
            Log.e(TAG, "startRecording, no sdcard storage available");
            setError(ERROR_SDCARD_NOT_PRESENT);
            return;
        }
        // check whether have sufficient storage space, if not will notify
        // caller error message
        if (!FmUtils.hasEnoughSpace(recordingSdcard)) {
            setError(ERROR_SDCARD_INSUFFICIENT_SPACE);
            Log.e(TAG, "startRecording, SD card does not have sufficient space!!");
            return;
        }

        // get external storage directory
        File sdDir = new File(recordingSdcard);
        File recordingDir = new File(sdDir, FM_RECORD_FOLDER);
        // exist a file named FM Recording, so can't create FM recording folder
        if (recordingDir.exists() && !recordingDir.isDirectory()) {
            Log.e(TAG, "startRecording, a file with name \"" + FM_RECORD_FOLDER + "\" already exists!!");
            setError(ERROR_SDCARD_WRITE_FAILED);
            return;
        } else if (!recordingDir.exists()) { // try to create recording folder
            if (isMounted) {
                dir = getPathUri(context,recordingDir,externalStorageUri);
            } else {
                boolean mkdirResult = recordingDir.mkdir();
                if (!mkdirResult) { // create recording file failed
                    setError(ERROR_RECORDER_INTERNAL);
                    return;
                }
            }
        } else {
            if (isMounted) {
                dir = getPathUri(context,recordingDir,externalStorageUri);
            }
        }
        String name = contructRecordName();
        mRecordFile = new File(recordingDir, name);
        if (isMounted) {
            try {
                Uri fileDoc = DocumentsContract.createDocument(context.getContentResolver(),dir
                    ,DocumentsContract.Document.COLUMN_MIME_TYPE,name);
                FmUtils.saveTmpFileUri(fileDoc.toString());
                mPfd = context.getContentResolver().openFileDescriptor(fileDoc,"w");
                fd = mPfd.getFileDescriptor();
                if(fd == null) {
                    Log.e(TAG,"fd is NULL");
                    throw new IllegalArgumentException("Memory related error");
                }
            } catch(Exception e) {
                Log.d(TAG,""+e);
            }
        } else {
            try {
                if (mRecordFile.createNewFile()) {
                    Log.d(TAG, "startRecording, createNewFile success with path "
                            + mRecordFile.getPath());
                }
            } catch (IOException e) {
                Log.e(TAG, "startRecording, IOException while createTempFile: " + e);
                e.printStackTrace();
                setError(ERROR_SDCARD_WRITE_FAILED);
                return;
            }
        }
        // set record parameter and start recording
        try {
            mRecorder = new MediaRecorder();
            mRecorder.setOnErrorListener(this);
            mRecorder.setOnInfoListener(this);
            // add for sharkl5 to support audio source AUDIO_SOURCE_FM_TUNER(1998)
            if(FmUtils.supportRecordSourceFmTuner) {
                mRecorder.setAudioSource(MediaRecorder.AudioSource.RADIO_TUNER);
            }else{
                mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            }

            mRecorder.setOutputFormat(12);
            mRecorder.setAudioEncoder(8);
            mRecorder.setAudioSamplingRate(44100);
            mRecorder.setAudioEncodingBitRate(320000);
            mRecorder.setAudioChannels(2);

            if (isMounted) {
                mRecorder.setOutputFile(fd);
            } else {
                mRecorder.setOutputFile(mRecordFile.getAbsolutePath());
            }
            mRecorder.prepare();
            mRecordStartTime = SystemClock.elapsedRealtime();
            mRecorder.start();

            if (!isMounted) {
                addRecordingToDatabase(context);
            }
            mFileListener= new FileListener(recordingDir.getPath());
            mFileListener.startWatching();

        } catch (IllegalStateException e) {
            Log.e(TAG, "startRecording, IllegalStateException while starting recording!", e);
            setError(ERROR_RECORDER_INTERNAL);
            return;
        } catch (IOException e) {
            Log.e(TAG, "startRecording, IOException while starting recording!", e);
            setError(ERROR_RECORDER_INTERNAL);
            return;
        }
        setState(STATE_RECORDING);
    }

    /*
    * contruct record name
    * @return record name
    * */
    private String contructRecordName() {
        long curTime = System.currentTimeMillis();
        Date date = new Date(curTime);
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.ENGLISH);
        String time = simpleDateFormat.format(date);
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(RECORDING_FILE_PREFIX).append(time).append(RECORDING_FILE_EXTENSION_MP3);
        String name = stringBuilder.toString();
        return name;
    }

    /**
     *Listening whether recording file is delete or not.
     */
    private class FileListener extends FileObserver {
        public FileListener(String path) {
            super(path, FileObserver.MOVED_FROM | FileObserver.DELETE);
            Log.d(TAG, "FileListener path="+path);
        }
        @Override
        public void onEvent(int event, String path) {
            Log.d(TAG, "onEvent: event = "+event+"; path = "+path);
            switch (event) {
                case FileObserver.MOVED_FROM:
                case FileObserver.DELETE:
                   if (path == null) {
                       return ;
                   }
                   if ( getTmpFileName().equals(path)) {
                       Log.d(TAG, "recording tmp file is deleted");
                       discardRecording();
                   }
                    break;
                default:
                    break;
            }
        }
    }

    private String getTmpFileName() {
        if (null == mRecordFile) return "";
        String filePath = mRecordFile.getPath();
        return filePath.substring(filePath.lastIndexOf("/")+1, filePath.length());
    }
    /**
     * Stop recording, compute recording time and update FM recorder state
     */
    public void stopRecording() {
        if (STATE_RECORDING != mInternalState) {
            Log.w(TAG, "stopRecording, called in wrong state!!");
            return;
        }

        mRecordTime = SystemClock.elapsedRealtime() - mRecordStartTime;
        stopRecorder();
        setState(STATE_IDLE);
    }

    /**
     * Compute the current record time
     * @return The current record time
     */
    public long getRecordTime() {
        if (STATE_RECORDING == mInternalState) {
            mRecordTime = SystemClock.elapsedRealtime() - mRecordStartTime;
        }
        return mRecordTime;
    }

    /**
     * Get FM recorder current state
     * @return FM recorder current state
     */
    public int getState() {
        return mInternalState;
    }

    /**
     * Is recording tmp file exist
     * @return boolean, recording tmp file exist or no
     */
    public boolean isRecordingTmpFileExist() {
        return mRecordFile != null && mRecordFile.exists();
    }

     /**
     * Get current record file name
     * @return The current record file name
     */
    public String getRecordFileName() {
        if (mRecordFile != null) {
            String fileName = mRecordFile.getName();

            int index = fileName.indexOf(RECORDING_FILE_EXTENSION_MP3);
            if (index > 0) {
                fileName = fileName.substring(0, index);
            }
            return fileName;
        }
        return null;
    }

    /**
     * Save recording file with the given name, and insert it's info to database
     * @param context The context
     * @param newName The name to override default recording name
     */
    public boolean saveRecording(Context context, String newName) {
        Log.d(TAG,"record file path --> " + mRecordFile.getPath());
        // insert recording file info to database
        return addRecordingToDatabase(context);
    }

    /**
     * Discard current recording file, release recorder and player
     */
    public void discardRecording() {
        Log.d(TAG,"discardRecording");
        if ((STATE_RECORDING == mInternalState) && (null != mRecorder)) {
            stopRecorder();
        }

        if ((mRecordFile != null) && mRecordFile.exists()) {
            synchronized (mFileQueue) {
                mFileQueue.add(mRecordFile);
            }

            new Handler().postDelayed(
                new Runnable(){
                    public void run(){
                        File curFile = null;
                        synchronized (mFileQueue) {
                            if (!mFileQueue.isEmpty()) {
                                curFile = mFileQueue.remove(0);
                            }
                        }

                        if ((curFile != null) && curFile.exists()) {
                            if (FmUtils.isExternalStorage()) {
                                FmUtils.deleteExStorageTempFile(curFile);
                            } else {
                                FmUtils.deleteInStorageTempFile(curFile);
                            }
                        }
                    }
                },
                200);
        }

        mRecordFile = null;
        mRecordStartTime = 0;
        mRecordTime = 0;
        setState(STATE_IDLE);
    }

    /**
     * Set the callback use to notify FM recorder state and error message
     * @param listener the callback
     */
    public void registerRecorderStateListener(OnRecorderStateChangedListener listener) {
        mStateListener = listener;
    }

    /**
     * Interface to notify FM recorder state and error message
     */
    public interface OnRecorderStateChangedListener {
        /**
         * notify FM recorder state
         * @param state current FM recorder state
         */
        void onRecorderStateChanged(int state);

        /**
         * notify FM recorder error message
         * @param error error type
         */
        void onRecorderError(int error);
    }

    /**
     * When recorder occur error, release player, notify error message, and update FM recorder state
     * to idle
     * @param mr The current recorder
     * @param what The error message type
     * @param extra The error message extra
     */
    @Override
    public void onError(MediaRecorder mr, int what, int extra) {
        Log.e(TAG, "onError, what = " + what + ", extra = " + extra);
        stopRecorder();
        setError(ERROR_RECORDER_INTERNAL);
        if (STATE_RECORDING == mInternalState) {
            setState(STATE_IDLE);
        }
    }

    @Override
    public void onInfo(MediaRecorder mr, int what, int extra) {
        Log.d(TAG, "onInfo: what=" + what + ", extra=" + extra);
        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
            onError(mr, what, extra);
        }
    }

    /**
     * Notify error message to the callback
     * @param error FM recorder error type
     */
    private void setError(int error) {
        if (mStateListener != null) {
            mStateListener.onRecorderError(error);
        }
    }

    /**
     * Notify FM recorder state message to the callback
     * @param state FM recorder current state
     */
    private void setState(int state) {
        // to avoid unnecessary callbacks.
        if(state != mInternalState) {
            mInternalState = state;
            if (mStateListener != null) {
                mStateListener.onRecorderStateChanged(state);
            }
        }
    }

    /**
     * Save recording file info to database
     * @param context The context
     */
    private boolean addRecordingToDatabase(final Context context) {
        if (!isRecordingTmpFileExist()) {
            Log.e(TAG, "saveRecording, recording file do not exist!");
            return false;
        }
        long curTime = System.currentTimeMillis();
        long modDate = mRecordFile.lastModified();
        String title = getRecordFileName();

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        cv.put(MediaStore.Audio.Media.TITLE, title);
        cv.put(MediaStore.Audio.Media.DATA, mRecordFile.getAbsolutePath());
        final int oneSecond = 1000;
        cv.put(MediaStore.Audio.Media.DATE_ADDED, (int) (curTime / oneSecond));
        cv.put(MediaStore.Audio.Media.DATE_MODIFIED, (int) (modDate / oneSecond));
        cv.put(MediaStore.Audio.Media.ARTIST, RECORDING_FILE_SOURCE);
        cv.put(MediaStore.Audio.Media.ALBUM, RECORDING_FILE_SOURCE);
        cv.put(MediaStore.Audio.Media.DURATION, mRecordTime);
        cv.put(MediaStore.Audio.Media.ALBUM_ARTIST, RECORDING_FILE_SOURCE);

        int recordingId = addToAudioTable(context, cv);
        return recordingId < 0 ? false : true;

        /*int playlistId = getPlaylistId(context);
        if (playlistId < 0) {
            // play list not exist, create FM Recording play list
            playlistId = createPlaylist(context);
        }
        if (playlistId < 0) {
            // insert playlist failed
            return;
        }
        // insert item to FM recording play list
        addToPlaylist(context, playlistId, recordingId);*/
    }

    /**
     * Get the play list ID
     * @param context Current passed in Context instance
     * @return The play list ID
     */
    public static int getPlaylistId(final Context context) {
        Cursor playlistCursor = null;
        int playlistId = -1;
        try {
            playlistCursor = context.getContentResolver().query(
                    MediaStore.Audio.Playlists.getContentUri("external"),
                    new String[] {
                        MediaStore.Audio.Playlists._ID
                    },

                    MediaStore.Audio.Playlists.DATA + " like ? AND " + MediaStore.Audio.Playlists.NAME
                            + "=?",
                    new String[] {
                            "%" + FmUtils.getPlaylistPath(context) + RECORDING_FILE_SOURCE + "%",
                            RECORDING_FILE_SOURCE
                    },
                    null);
            if (playlistCursor != null && playlistCursor.moveToFirst()) {
                playlistId = playlistCursor.getInt(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "getPlaylistId failed because of exception: ", e);
        } finally {
            if (playlistCursor != null) playlistCursor.close();
        }
        return playlistId;
    }

    private int createPlaylist(final Context context) {
        final int size = 1;
        Uri newPlaylistUri = null;
        try {
            ContentValues cv = new ContentValues(size);
            cv.put(MediaStore.Audio.Playlists.NAME, RECORDING_FILE_SOURCE);
            newPlaylistUri = context.getContentResolver().insert(
                    MediaStore.Audio.Playlists.getContentUri("external"), cv);
        } catch (Exception e) {
            Log.e(TAG, "createPlaylist failed because of exception: ", e);
        }
        if (newPlaylistUri == null) {
            Log.d(TAG, "createPlaylist, create playlist failed");
            return -1;
        }
        return Integer.valueOf(newPlaylistUri.getLastPathSegment());
    }

    private int addToAudioTable(final Context context, final ContentValues cv) {
        ContentResolver resolver = context.getContentResolver();
        int id = -1;

        Cursor cursor = null;

        try {
            String filePath = mRecordFile.getPath();
            Uri fileUri = FmUtils.getMediaFileUri(filePath);
            cursor = resolver.query(
                    fileUri,
                    new String[] {
                        MediaStore.Audio.Media._ID
                    },
                    MediaStore.Audio.Media.DATA + "=?",
                    new String[] {
                        filePath
                    },
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                // Exist in database, just update it
                id = cursor.getInt(0);
                MediaScannerConnection.scanFile(context,
                    new String[] {
                        filePath
                    },
                    null,
                    null);
            } else {
                // insert new entry to database
                Uri uri = context.getContentResolver().insert(
                        fileUri, cv);
                if (uri != null) {
                    id = Integer.valueOf(uri.getLastPathSegment());
                }
            }
        } catch (Exception e) {
            Log.e(TAG,"Failed to add recording to adudio table because of exception: ", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return id;
    }

    private void addToPlaylist(final Context context, final int playlistId, final int recordingId) {
        ContentResolver resolver = context.getContentResolver();
        Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId);
        int order = 0;
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[] {
                        MediaStore.Audio.Media._ID
                    },
                    MediaStore.Audio.Media.DATA + "=?",
                    new String[] {
                        mRecordFile.getPath()
                    },
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                // Exist in database, just update it
                order = cursor.getCount();
            }
        } catch (Exception e) {
            Log.e(TAG, "addToPlaylist query failed because of exception: ", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        ContentValues cv = new ContentValues(2);
        cv.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, recordingId);
        cv.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, order);
        context.getContentResolver().insert(uri, cv);
    }

    private void stopRecorder() {
        synchronized (this) {
            if (mRecorder != null) {
                try {
                    if (mFileListener!= null) {
                        mFileListener.stopWatching();
                        mFileListener = null;
                    }
                    Log.d(TAG,"stopRecorder");
                    mRecorder.stop();
                } catch (IllegalStateException ex) {
                    Log.e(TAG, "stopRecorder, IllegalStateException ocurr " + ex);
                    setError(ERROR_RECORDER_INTERNAL);
                } catch (RuntimeException exception) {
                    Log.e(TAG, "stopRecorder, RuntimeException ocurr " + exception);
                } finally {
                    mRecorder.release();
                    mRecorder = null;
                    try{
                        if (mPfd != null) {
                            mPfd.close();
                            mPfd = null;
                        }
                    } catch(IOException e){
                        Log.e(TAG, "IOException " + e);
                    }

                }
            }
        }
    }
}
