/*
 * Copyright (C) 2012 Renato L. F. Cunha <http://renatocunha.com>
 * Copyright (C) 2007 The Android Open Source Project
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

package com.example.ffmpeg.streamer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;

// ----------------------------------------------------------------------

public class FFmpegPreview extends Activity implements Camera.PreviewCallback {
    private static final String TAG = "FFmpegPreview";
    private static final String FFSERVER_CONF = "ffserver.conf";
    private static final String FEED_FFM_NAME = "feed1.ffm";

    private Preview mPreview;
    Camera mCamera;
    int numberOfCameras;
    int cameraCurrentlyLocked;
    private File mFFmPath;
    private File mFFserverConfigPath;
    private InputStream mFFmpegInputStream;
    private OutputStream mFFmpegOutputStream;
    private InputStream mFFserverInputStream;
    private OutputStream mFFserverOutputStream;
    private Process mFFmpegProcess;
    private Process mFFserverProcess;
    private byte[] mBuffer;

    // The first rear facing camera
    int defaultCameraId;

    private static final String FFSERVER_TEMPLATE = 
        "Port 8090\n" +
        "RTSPPort %d\n" +
        "BindAddress 0.0.0.0\n" +
        "MaxHTTPConnections 2000\n" +
        "MaxClients 1000\n" +
        "MaxBandwidth 1000\n" +
        "CustomLog -\n" +
        "NoDaemon\n" +
        "\n" +
        "<Feed feed1.ffm>\n" +
        "    File %s\n" +
        "    FileMaxSize 5M\n" +
        "    ACL allow 127.0.0.1\n" +
        "</Feed>\n" +
        "\n" +
        "<Stream livefeed>\n" +
        "    Feed feed1.ffm\n" +
        "    Format rtp\n" +
        "    VideoBitRate %d\n" +
        "    VideoBufferSize 40\n" +
        "    VideoFrameRate %d\n" +
        "    VideoSize %dx%d\n" +
        "    VideoGopSize 12\n" +
        "    NoAudio\n" +
        "</Stream>"
    ;

    private void newFFserverConfig(int rtspPort, int bps, int fps, int width,
            int height) throws IOException {
        String conf = String.format(Locale.US, FFSERVER_TEMPLATE, rtspPort,
                mFFmPath, bps, fps, width, height);
        FileOutputStream fos = new FileOutputStream(mFFserverConfigPath);
        fos.write(conf.getBytes());
        fos.close();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFFmPath = new File(getExternalCacheDir(), FEED_FFM_NAME);
        mFFserverConfigPath = new File(getCacheDir(), FFSERVER_CONF);

        // Hide the window title.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Create a RelativeLayout container that will hold a SurfaceView,
        // and set it as the content of our activity.
        mPreview = new Preview(this);
        setContentView(mPreview);

        // Find the total number of cameras available
        numberOfCameras = Camera.getNumberOfCameras();

        // Find the ID of the default camera
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                defaultCameraId = i;
            }
        }

        // Copy the assets if needed
        copyAssets();

        // Start the FFmpeg process after the creation of the preview
        mPreview.post(new Runnable() {
            public void run() {
                final Camera.Size ps = mPreview.getPreviewSize();
                final int bufferSize = ps.width * ps.height * 12 / 8;
                mBuffer = new byte[bufferSize];

                setupFFserver(ps);
                setupFFmpeg(ps);
                mPreview.setPreviewCallback(FFmpegPreview.this, mBuffer);
            }
        });
    }

    private final ProcessBuilder setupProcess(String... args) {
        final ProcessBuilder pb = new ProcessBuilder(args);
        Map<String, String> env = pb.environment();
        env.put("PATH", env.get("PATH") + ":" + getFilesDir());
        env.put("LD_LIBRARY_PATH", env.get("LD_LIBRARY_PATH") + ":" + getFilesDir());
        pb.redirectErrorStream(true);
        return pb;
    }

    private void setupFFserver(Camera.Size videoDimensions) {
        try {
            newFFserverConfig(7654, 800, 15, videoDimensions.width,
                    videoDimensions.height);
        } catch (IOException e) {
            // XXX: It will not work. Warn the user.
            e.printStackTrace();
        }

        final ProcessBuilder pb = setupProcess("ffserver", "-f",
                mFFserverConfigPath.toString());
        new Thread() {
            public void run() {
                boolean keepGoing = true;
                while (keepGoing) {
                    try {
                        mFFserverProcess = pb.start();
                        mFFserverInputStream = mFFserverProcess.getInputStream();
                        mFFserverOutputStream = mFFserverProcess.getOutputStream();
                        Log.d(TAG, "FFserver has been started");
                        mFFserverProcess.waitFor();
                        Log.d(TAG, "FFserver  has stopped");
                    } catch (IOException e) {
                        keepGoing = false;
                    } catch (InterruptedException e) {
                        keepGoing = false;
                    } finally {
                        mFFserverInputStream = null;
                        mFFserverOutputStream = null;
                    }
                }
            }
        }.start();
    }

    private void setupFFmpeg(Camera.Size videoDimensions) {
        String dimensions = new String(Integer.valueOf(videoDimensions.width).toString())
                            + "x" +
                            new String(Integer.valueOf(videoDimensions.height).toString());
        final ProcessBuilder pb = setupProcess("ffmpeg", "-y", "-v", "quiet",
                "-nostdin", "-f", "rawvideo", "-vcodec", "rawvideo",
                "-pix_fmt", "nv21", "-video_size", dimensions, "-i", "pipe:",
                "-crf", "30", "-preset", "ultrafast", "-tune", "zerolatency",
                "http://127.0.0.1:8090/feed1.ffm");
        new Thread() {
            public void run() {
                boolean keepGoing = true;
                while (keepGoing) {
                    try {
                        Process p = pb.start();
                        mFFmpegInputStream = p.getInputStream();
                        mFFmpegOutputStream = p.getOutputStream();
                        Log.d(TAG, "FFmpeg has been started");
                        p.waitFor();
                        Log.d(TAG, "FFmpeg has stopped");
                    } catch (IOException e) {
                        keepGoing = false;
                    } catch (InterruptedException e) {
                        keepGoing = false;
                    } finally {
                        mFFmpegInputStream = null;
                        mFFmpegOutputStream = null;
                    }
                }
            }
        }.start();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Open the default i.e. the first rear facing camera.
        mCamera = Camera.open();
        cameraCurrentlyLocked = defaultCameraId;
        mPreview.setCamera(mCamera);
        if (mBuffer != null)
            mPreview.setPreviewCallback(this, mBuffer);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPreview.setPreviewCallback(null, null);

        // Because the Camera object is a shared resource, it's very
        // important to release it when the activity is paused.
        if (mCamera != null) {
            mPreview.setCamera(null);
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        // Inflate our menu which can gather user input for switching camera
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.ffmpeg_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
        case R.id.switch_cam:
            // check for availability of multiple cameras
            if (numberOfCameras == 1) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(this.getString(R.string.camera_alert))
                       .setNeutralButton("Close", null);
                AlertDialog alert = builder.create();
                alert.show();
                return true;
            }

            // OK, we have multiple cameras.
            // Release this camera -> cameraCurrentlyLocked
            if (mCamera != null) {
                mCamera.stopPreview();
                mPreview.setCamera(null);
                mCamera.release();
                mCamera = null;
            }

            // Acquire the next camera and request Preview to reconfigure
            // parameters.
            mCamera = Camera
                    .open((cameraCurrentlyLocked + 1) % numberOfCameras);
            cameraCurrentlyLocked = (cameraCurrentlyLocked + 1)
                    % numberOfCameras;
            mPreview.switchCamera(mCamera);

            // Start the preview
            mCamera.startPreview();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    private void copyAssets() {
        AssetManager assetManager = getAssets();
        String[] files = null;
        try {
            files = assetManager.list("");
        } catch (IOException e) {
            Log.e(TAG, "Failed to get asset file list.", e);
        }
        for (String filename : files) {
            File root = getFilesDir();
            File file = new File(root, filename);
            if(file.exists())
                continue;
            InputStream in = null;
            OutputStream out = null;
            try {
                Runtime.getRuntime().exec("chmod 771 " + file);
                in = assetManager.open(filename);
                out = openFileOutput(filename, Context.MODE_PRIVATE);
                copyFile(in, out);
                in.close();
                in = null;
                out.flush();
                out.close();
                out = null;
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy asset file: " + filename, e);
            }
        }
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        int read;
        byte[] buffer = new byte[1024];
        while((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    public void onPreviewFrame(byte[] data, Camera camera) {
        try {
            if (mFFmpegOutputStream != null) {
                mFFmpegOutputStream.write(data);
                mFFmpegOutputStream.flush();
            }
        } catch (IOException e) {
            // Broken pipe, just ignore it
        }
        camera.addCallbackBuffer(data);
    }

    public void onConfigurationChanged(Configuration whatever) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        super.onConfigurationChanged(whatever);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mFFmpegProcess != null)
            mFFmpegProcess.destroy();
        if (mFFserverProcess != null)
            mFFserverProcess.destroy();
    }
}

/* vim: set ts=4 sw=4 expandtab softtabstop=4 */
