/*
 * Copyright (C) 2018 Nafundi
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.VisibleForTesting;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;

import org.odk.collect.android.R;
import org.odk.collect.android.map.GoogleMapFragment;
import org.odk.collect.android.map.MapFragment;
import org.odk.collect.android.map.MapPoint;
import org.odk.collect.android.map.OsmMapFragment;
import org.odk.collect.android.preferences.GeneralKeys;
import org.odk.collect.android.spatial.MapHelper;
import org.odk.collect.android.widgets.GeoTraceWidget;
import org.osmdroid.tileprovider.IRegisterReceiver;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

import static org.odk.collect.android.utilities.PermissionUtils.areLocationPermissionsGranted;

public class GeoTraceActivity extends BaseGeoMapActivity implements IRegisterReceiver {
    public static final String PREF_VALUE_GOOGLE_MAPS = "google_maps";
    public static final String MAP_CENTER_KEY = "map_center";
    public static final String MAP_ZOOM_KEY = "map_zoom";
    public static final String POINTS_KEY = "points";
    public static final String TRACES_KEY = "traces";
    public static final String BEEN_PAUSED_KEY = "been_paused";
    public static final String MODE_ACTIVE_KEY = "mode_active";
    public static final String PLAY_CHECK_KEY = "play_check";
    private static final long AUTOMATIC_REGISTRATION_TIME = 5; // In seconds

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture schedulerHandler;

    private MapFragment map;
    private int featureId = -1;  // will be a positive featureId once map is ready
    private String originalTraceString = "";

    private ImageButton zoomButton;
    private ImageButton playButton;
    private ImageButton clearButton;
    private Button manualButton;
    private ImageButton pauseButton;

    private boolean beenPaused;
    private boolean modeActive;
    private boolean playCheck;

    private AlertDialog zoomDialog;
    private View zoomDialogView;
    private Button zoomPointButton;
    private Button zoomLocationButton;

    // restored from savedInstanceState
    private MapPoint restoredMapCenter;
    private Double restoredMapZoom;
    private List<MapPoint> restoredPoints;
    private List<Trace> traces = new ArrayList<>();
    private DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            restoredMapCenter = savedInstanceState.getParcelable(MAP_CENTER_KEY);
            restoredMapZoom = savedInstanceState.getDouble(MAP_ZOOM_KEY);
            restoredPoints = savedInstanceState.getParcelableArrayList(POINTS_KEY);
            beenPaused = savedInstanceState.getBoolean(BEEN_PAUSED_KEY, false);
            modeActive = savedInstanceState.getBoolean(MODE_ACTIVE_KEY, false);
            playCheck = savedInstanceState.getBoolean(PLAY_CHECK_KEY, false);
            traces = savedInstanceState.getParcelableArrayList(TRACES_KEY);
        }

        if (!areLocationPermissionsGranted(this)) {
            finish();
            return;
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTitle(getString(R.string.geotrace_title));
        setContentView(R.layout.geotrace_layout);
        if (savedInstanceState == null) {
            // UI initialization that should only occur on start, not on restore
            playButton = findViewById(R.id.play);
            playButton.setEnabled(false);
        }
        createMapFragment().addTo(this, R.id.map_container, this::initMap);
    }

    public MapFragment createMapFragment() {
        String mapSdk = getIntent().getStringExtra(GeneralKeys.KEY_MAP_SDK);
        return (mapSdk == null || mapSdk.equals(PREF_VALUE_GOOGLE_MAPS)) ?
            new GoogleMapFragment() : new OsmMapFragment();
    }

    @Override protected void onStart() {
        super.onStart();
        if (map != null) {
            map.setGpsLocationEnabled(true);
        }
    }

    @Override protected void onStop() {
        map.setGpsLocationEnabled(false);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable(MAP_CENTER_KEY, map.getCenter());
        state.putDouble(MAP_ZOOM_KEY, map.getZoom());
        state.putParcelableArrayList(POINTS_KEY, new ArrayList<>(map.getPointsOfPoly(featureId)));
        state.putParcelableArrayList(TRACES_KEY, new ArrayList<>(traces));
        state.putBoolean(BEEN_PAUSED_KEY, beenPaused);
        state.putBoolean(MODE_ACTIVE_KEY, modeActive);
        state.putBoolean(PLAY_CHECK_KEY, playCheck);
    }

    @Override protected void onDestroy() {
        if (schedulerHandler != null && !schedulerHandler.isCancelled()) {
            schedulerHandler.cancel(true);
        }
        super.onDestroy();
    }

    @Override public void destroy() { }

    public void initMap(MapFragment newMapFragment) {
        if (newMapFragment == null) {
            finish();
            return;
        }

        map = newMapFragment;
        if (map instanceof GoogleMapFragment) {
            helper = new MapHelper(this, ((GoogleMapFragment) map).getGoogleMap(), selectedLayer);
        } else if (map instanceof OsmMapFragment) {
            helper = new MapHelper(this, ((OsmMapFragment) map).getMapView(), this, selectedLayer);
        }
        helper.setBasemap();

        clearButton = findViewById(R.id.clear);
        clearButton.setOnClickListener(v -> showClearDialog());

        pauseButton = findViewById(R.id.pause);
        pauseButton.setOnClickListener(v -> {
            playButton.setVisibility(View.VISIBLE);
            if (!map.getPointsOfPoly(featureId).isEmpty()) {
                clearButton.setEnabled(true);
            }
            pauseButton.setVisibility(View.GONE);
            manualButton.setVisibility(View.GONE);
            playCheck = true;
            modeActive = false;
            try {
                schedulerHandler.cancel(true);
            } catch (Exception e) {
                // Do nothing
            }
        });

        ImageButton saveButton = findViewById(R.id.geotrace_save);
        saveButton.setOnClickListener(v -> {
            finishWithResult();
        });

        playButton = findViewById(R.id.play);
        playButton.setOnClickListener(v -> {
            startGeoTrace();
        });

        manualButton = findViewById(R.id.manual_button);
        manualButton.setOnClickListener(v -> addVertex());

        buildDialogs();

        findViewById(R.id.layers).setOnClickListener(v -> helper.showLayersDialog());

        zoomButton = findViewById(R.id.zoom);
        zoomButton.setOnClickListener(v -> {
            playCheck = false;
            showZoomDialog();
        });

        List<MapPoint> points = new ArrayList<>();
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(GeoTraceWidget.TRACE_LOCATION)) {
            originalTraceString = intent.getStringExtra(GeoTraceWidget.TRACE_LOCATION);
            points = parsePoints(originalTraceString);
        }
        if (restoredPoints != null) {
            points = restoredPoints;
        }
        featureId = map.addDraggablePoly(points, false);
        zoomButton.setEnabled(!points.isEmpty());
        clearButton.setEnabled(!points.isEmpty());

        if (modeActive) {
            startGeoTrace();
        }

        map.setGpsLocationEnabled(true);
        map.setGpsLocationListener(this::onGpsLocation);
        if (restoredMapCenter != null && restoredMapZoom != null) {
            map.zoomToPoint(restoredMapCenter, restoredMapZoom);
        } else if (!points.isEmpty()) {
            map.zoomToBoundingBox(points, 0.6);
        } else {
            map.runOnGpsLocationReady(this::onGpsLocationReady);
        }
    }

    private void finishWithResult() {
        setResult(RESULT_OK, new Intent().putExtra(
            FormEntryActivity.GEOTRACE_RESULTS, formatPoints(traces)));
        finish();
    }

    /**
     * Parses a form result string, as previously formatted by formatPoints,
     * into a list of polyline vertices.
     */
    private List<MapPoint> parsePoints(String coords) {
        List<MapPoint> points = new ArrayList<>();
        for (String vertex : (coords == null ? "" : coords).split(";")) {
            String[] words = vertex.trim().split(" ");
            if (words.length >= 2) {
                double lat;
                double lon;
                double alt;
                double sd;
                try {
                    lat = Double.parseDouble(words[0]);
                    lon = Double.parseDouble(words[1]);
                    alt = words.length > 2 ? Double.parseDouble(words[2]) : 0;
                    sd = words.length > 3 ? Double.parseDouble(words[3]) : 0;
                } catch (NumberFormatException e) {
                    continue;
                }
                points.add(new MapPoint(lat, lon, alt, sd));
            }
        }
        return points;
    }

    /**
     * Serializes a list of polyline vertices into a string, in the format
     * appropriate for storing as the result of this form question.
     */
    private String formatPoints(List<Trace> traces) {
        StringBuilder stringBuilder = new StringBuilder();
        for (Trace trace : traces) {
            final MapPoint point = trace.mapPoint;
            final Date date = trace.date;

            final String formattedPoint = String.format(Locale.US, "%s %s %s %s %s;",
                    Double.toString(point.lat),
                    Double.toString(point.lon),
                    Float.toString((float) point.sd),
                    Double.toString(point.alt),
                    dateFormat.format(date));

            stringBuilder.append(formattedPoint);
        }
        return stringBuilder.toString().trim();
    }

    private void buildDialogs() {
        zoomDialogView = getLayoutInflater().inflate(R.layout.geo_zoom_dialog, null);

        zoomLocationButton = zoomDialogView.findViewById(R.id.zoom_location);
        zoomLocationButton.setOnClickListener(v -> {
            map.zoomToPoint(map.getGpsLocation());
            zoomDialog.dismiss();
        });

        zoomPointButton = zoomDialogView.findViewById(R.id.zoom_saved_location);
        zoomPointButton.setOnClickListener(v -> {
            map.zoomToBoundingBox(map.getPointsOfPoly(featureId), 0.6);
            zoomDialog.dismiss();
        });
    }

    private void startGeoTrace() {
        setupAutomaticMode();
        playButton.setVisibility(View.GONE);
        clearButton.setEnabled(false);
        pauseButton.setVisibility(View.VISIBLE);
    }

    private void setupAutomaticMode() {
        setGeoTraceScheduler(AUTOMATIC_REGISTRATION_TIME, TimeUnit.SECONDS);
        modeActive = true;
    }

    public void setGeoTraceScheduler(long delay, TimeUnit units) {
        schedulerHandler = scheduler.scheduleAtFixedRate(
            () -> runOnUiThread(() -> addVertex()), delay, delay, units);
    }

    @SuppressWarnings("unused")  // the "map" parameter is intentionally unused
    private void onGpsLocationReady(MapFragment map) {
        zoomButton.setEnabled(true);
        playButton.setEnabled(true);
        if (getWindow().isActive()) {
            showZoomDialog();
        }
    }

    private void onGpsLocation(MapPoint point) {
        if (modeActive) {
            map.setCenter(point);
        }
    }

    private void addVertex() {
        MapPoint point = map.getGpsLocation();
        if (point != null) {
            Timber.d("adding point: " + point.lat + "/" + point.lon);

            final Trace trace = new Trace();
            trace.mapPoint = point;
            trace.date = new Date();
            traces.add(trace);

            map.appendPointToPoly(featureId, point);
        }
    }

    private void clear() {
        map.clearFeatures();
        featureId = map.addDraggablePoly(new ArrayList<>(), false);
        clearButton.setEnabled(false);
        pauseButton.setVisibility(View.GONE);
        manualButton.setVisibility(View.GONE);
        playButton.setVisibility(View.VISIBLE);
        playButton.setEnabled(true);
        modeActive = false;
        playCheck = false;
        beenPaused = false;
    }

    private void showClearDialog() {
        if (!map.getPointsOfPoly(featureId).isEmpty()) {
            new AlertDialog.Builder(this)
                .setMessage(R.string.geo_clear_warning)
                .setPositiveButton(R.string.clear, (dialog, id) -> clear())
                .setNegativeButton(R.string.cancel, null)
                .show();
        }
    }

    public void showZoomDialog() {
        if (zoomDialog == null) {
            zoomDialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.zoom_to_where))
                .setView(zoomDialogView)
                .setNegativeButton(R.string.cancel, (dialog, id) -> dialog.cancel())
                .setOnCancelListener(dialog -> {
                    dialog.cancel();
                    zoomDialog.dismiss();
                })
                .create();
        }

        if (map.getGpsLocation() != null) {
            zoomLocationButton.setEnabled(true);
            zoomLocationButton.setBackgroundColor(Color.parseColor("#50cccccc"));
            zoomLocationButton.setTextColor(themeUtils.getPrimaryTextColor());
        } else {
            zoomLocationButton.setEnabled(false);
            zoomLocationButton.setBackgroundColor(Color.parseColor("#50e2e2e2"));
            zoomLocationButton.setTextColor(Color.parseColor("#FF979797"));
        }
        if (!map.getPointsOfPoly(featureId).isEmpty()) {
            zoomPointButton.setEnabled(true);
            zoomPointButton.setBackgroundColor(Color.parseColor("#50cccccc"));
            zoomPointButton.setTextColor(themeUtils.getPrimaryTextColor());
        } else {
            zoomPointButton.setEnabled(false);
            zoomPointButton.setBackgroundColor(Color.parseColor("#50e2e2e2"));
            zoomPointButton.setTextColor(Color.parseColor("#FF979797"));
        }
        zoomDialog.show();
    }

    @VisibleForTesting public ImageButton getPlayButton() {
        return playButton;
    }

    @VisibleForTesting public MapFragment getMapFragment() {
        return map;
    }

    public static class Trace implements Parcelable  {
        MapPoint mapPoint;
        Date date;


        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeParcelable(this.mapPoint, flags);
            dest.writeLong(this.date != null ? this.date.getTime() : -1);
        }

        public Trace() {
        }

        protected Trace(Parcel in) {
            this.mapPoint = in.readParcelable(MapPoint.class.getClassLoader());
            long tmpDate = in.readLong();
            this.date = tmpDate == -1 ? null : new Date(tmpDate);
        }

        public static final Creator<Trace> CREATOR = new Creator<Trace>() {
            @Override
            public Trace createFromParcel(Parcel source) {
                return new Trace(source);
            }

            @Override
            public Trace[] newArray(int size) {
                return new Trace[size];
            }
        };
    }
}
