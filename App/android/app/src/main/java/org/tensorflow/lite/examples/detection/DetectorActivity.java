/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.detection;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.util.Pair;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.gson.JsonIOException;
import com.google.mlkit.vision.text.Text;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.examples.detection.customview.OverlayView;
import org.tensorflow.lite.examples.detection.customview.OverlayView.DrawCallback;
import org.tensorflow.lite.examples.detection.env.BorderedText;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.tflite.Detector;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;
import org.tensorflow.lite.examples.detection.tracking.MultiBoxTracker;
import org.tensorflow.lite.examples.detection.utilitis.LevenshteinDistanceDP;

public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
    public static final Logger LOGGER = new Logger();

    // Configuration values for the prepackaged SSD model.
    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final boolean TF_OD_API_IS_QUANTIZED = false;
    private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
    private static final String TF_OD_API_LABELS_FILE = "labelmap.txt";
    private static final String STREET_NAME_DATA_FILE = "streetnames.json";
    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.70f;
    private static final float MINIMUM_CONFIDENCE_TEXT_GEN = 0.70f;
    private static final int DETECTOR_NUM_THREAD = 4;
    private static final boolean MAINTAIN_ASPECT = false;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;

    private OverlayView trackingOverlay;
    private Integer sensorOrientation;
    private Detector detector;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;

    private BorderedText borderedText;

    TextGeneration textGeneration;
    Map<String, String> streetnames_data = new HashMap<>();

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;

        try {
            detector =
                TFLiteObjectDetectionAPIModel.create(
                    this,
                    TF_OD_API_MODEL_FILE,
                    TF_OD_API_LABELS_FILE,
                    TF_OD_API_INPUT_SIZE,
                    TF_OD_API_IS_QUANTIZED);
            cropSize = TF_OD_API_INPUT_SIZE;
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing Detector!");
            Toast toast =
                Toast.makeText(
                        getApplicationContext(), "Detector could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        detector.setNumThreads(DETECTOR_NUM_THREAD);
        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
            ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                cropSize, cropSize,
                sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
            new DrawCallback() {
                @Override
                public void drawCallback(final Canvas canvas) {
                    tracker.draw(canvas);
                    if (isDebug()) {
                        tracker.drawDebug(canvas);
                    }
                }
            });

        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);

        String street_name_string = loadJSONFromAsset(STREET_NAME_DATA_FILE);
        try {
            JSONObject jsonObject = new JSONObject(street_name_string);
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (jsonObject.get(key) instanceof JSONObject) {
                    String str = ((JSONObject) jsonObject.get(key)).toString();
                    streetnames_data.put(key, str);
                }
            }
        } catch (JsonIOException | JSONException e) {
            e.printStackTrace();
        }


        textGeneration = new TextGeneration();
        textGeneration.AddListener(new TextGeneration.TextGenCallback() {
            @Override
            public void CallBackSuccess(Text text) {
                String label = text.getText().toUpperCase()
                        .replace("\n", " ");
                boolean is_contains_duong = label.contains("DUONG");
                String new_label = is_contains_duong ? label.replace("DUONG", "") : label;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        if (streetnames_data.isEmpty())
                            return;

                        Pair<Integer, String> temp =
                                new Pair<Integer, String>(-1, null);
                        for (Map.Entry<String, String> item : streetnames_data.entrySet()) {
                            String key = is_contains_duong ? item.getKey().replace("DUONG ", "") : item.getKey();
                            int compute = LevenshteinDistanceDP.compute(key.toLowerCase(), new_label.toLowerCase());
                            LOGGER.i("Compare: " + item.getKey() + " - " + new_label + " => " + compute);
                            if (temp.first < compute) {
                                temp = new Pair<Integer, String>(compute, item.getKey());
                            }
                        }
                        int min = (int) (MINIMUM_CONFIDENCE_TEXT_GEN * 100f);
                        StreetName streetName = StreetName.createNewFromJson(temp.first < min ? "" : streetnames_data.get(temp.second));
                        tracker.SetLabel(streetName.getName());
                    }
                }).start();
            }

            @Override
            public void CallBackFailure(@NonNull Exception e) {

            }
        });

    }

    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }

        runInBackground(
            new Runnable() {
                @Override
                public void run() {
                    LOGGER.i("Running detection on image " + currTimestamp);
                    final long startTime = SystemClock.uptimeMillis();
                    final List<Detector.Recognition> results = detector.recognizeImage(croppedBitmap);
                    lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                    cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                    final Canvas canvas = new Canvas(cropCopyBitmap);
                    final Paint paint = new Paint();
                    paint.setColor(Color.RED);
                    paint.setStyle(Style.STROKE);
                    paint.setStrokeWidth(2.0f);

                    float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                    switch (MODE) {
                        case TF_OD_API:
                            minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                            break;
                    }

                    final List<Detector.Recognition> mappedRecognitions =
                            new ArrayList<Detector.Recognition>();

                    for (final Detector.Recognition result : results) {
                        final RectF location = result.getLocation();
                        if (location != null && result.getConfidence() >= minimumConfidence) {

                            Rect box = new Rect();
                            box.left = (int) location.left;
                            box.top = (int) location.top;
                            box.right = (int) location.right;
                            box.bottom = (int) location.bottom;

                            Bitmap recognitionBitmap = TextGeneration.cropBitmap(croppedBitmap, box);
                            textGeneration.Generate(recognitionBitmap, 0);

                            canvas.drawRect(location, paint);

                            cropToFrameTransform.mapRect(location);

                            result.setLocation(location);
                            mappedRecognitions.add(result);
                            break;
                        }
                    }

                    tracker.trackResults(mappedRecognitions, currTimestamp);
                    trackingOverlay.postInvalidate();

                    computingDetection = false;

                    runOnUiThread(
                        new Runnable() {
                            @Override
                            public void run() {
                                showFrameInfo(previewWidth + "x" + previewHeight);
                                showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                                showInference(lastProcessingTimeMs + "ms");
                            }
                        });
                }
            });
    }

    @Override
    protected int getLayoutId() {
        return R.layout.tfe_od_camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum DetectorMode {
        TF_OD_API;
    }

    @Override
    protected void setUseNNAPI(final boolean isChecked) {
        runInBackground(
            () -> {
                try {
                    detector.setUseNNAPI(isChecked);
                } catch (UnsupportedOperationException e) {
                    LOGGER.e(e, "Failed to set \"Use NNAPI\".");
                    runOnUiThread(
                        () -> {
                            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                }
            });
    }


    public String loadJSONFromAsset(String file) {
        String json = "{\"null\":\"empty\"}";
        try {
            InputStream is = getAssets().open(file);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            return null;
        }
        return json;
    }


}
