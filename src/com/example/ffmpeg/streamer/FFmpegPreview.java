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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

// ----------------------------------------------------------------------

public class FFmpegPreview extends Activity implements Camera.PreviewCallback {
    private final String TAG = "FFmpegPreview";

    private Preview mPreview;
    Camera mCamera;
    int numberOfCameras;
    int cameraCurrentlyLocked;
    private InputStream mFFmpegInputStream;
    private OutputStream mFFmpegOutputStream;
    private InputStream mFFserverInputStream;
    private OutputStream mFFserverOutputStream;

    // The first rear facing camera
    int defaultCameraId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
                setupFFserver();
                setupFFmpeg(mPreview.getPreviewSize());
            }
        });
    }

    private void setupFFserver() {
        final ProcessBuilder pb = new ProcessBuilder("ffserver", "-f",
                getFilesDir() + "/ffserver.conf");
        Map<String, String> env = pb.environment();
        env.put("PATH", env.get("PATH") + ":" + getFilesDir());
        env.put("LD_LIBRARY_PATH", env.get("LD_LIBRARY_PATH") + ":" + getFilesDir());
        pb.redirectErrorStream(true);
        new Thread() {
            public void run() {
                try {
                    Process p = pb.start();
                    mFFserverInputStream = p.getInputStream();
                    mFFserverOutputStream = p.getOutputStream();
                    Log.d(TAG, "FFserver has been started");
                    p.waitFor();
                    Log.d(TAG, "FFserver  has stopped");
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } finally {
                    mFFserverInputStream = null;
                    mFFserverOutputStream = null;
                }
            }
        }.start();
    }

    private void setupFFmpeg(Camera.Size videoDimensions) {
        String dimensions = new String(Integer.valueOf(videoDimensions.width).toString())
                            + "x" +
                            new String(Integer.valueOf(videoDimensions.height).toString());
        final ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-v", "quiet",
                "-nostdin", "-f", "rawvideo", "-vcodec", "rawvideo",
                "-pix_fmt", "nv21", "-video_size", dimensions, "-i", "-",
                "-crf", "30", "-preset", "ultrafast", "-tune", "zerolatency",
                "http://127.0.0.1:8090/feed1.ffm");
        Map<String, String> env = pb.environment();
        env.put("PATH", env.get("PATH") + ":" + getFilesDir());
        env.put("LD_LIBRARY_PATH", env.get("LD_LIBRARY_PATH") + ":" + getFilesDir());
        pb.redirectErrorStream(true);
        new Thread() {
            public void run() {
                try {
                    Process p = pb.start();
                    mFFmpegInputStream = p.getInputStream();
                    mFFmpegOutputStream = p.getOutputStream();
                    Log.d(TAG, "FFmpeg has been started");
                    p.waitFor();
                    Log.d(TAG, "FFmpeg has stopped");
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } finally {
                    mFFmpegInputStream = null;
                    mFFmpegOutputStream = null;
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
        mPreview.setPreviewCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPreview.setPreviewCallback(null);

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
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void onConfigurationChanged(Configuration whatever) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        super.onConfigurationChanged(whatever);
    }
}

/* vim: set ts=4 sw=4 expandtab softtabstop=4 */
