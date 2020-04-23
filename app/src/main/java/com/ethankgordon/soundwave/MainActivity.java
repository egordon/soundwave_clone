package com.ethankgordon.soundwave;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.jtransforms.fft.DoubleFFT_1D;

import java.lang.Math;

public class MainActivity extends AppCompatActivity {
    private static final int RECORDER_SAMPLERATE = 44100;
    private static final int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
    private static final int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int FFT_SIZE = 2048;
    private AudioRecord recorder = null;
    private Thread recordingThread = null;
    private boolean isRecording = false;

    private DoubleFFT_1D fft;
    private double[] window = null;

    private LineGraphSeries<DataPoint> mSeries;
    private GraphView mGraph;

    private TuneThread tuneThread;

    private enum State {
        kNONE,
        kPUSH,
        kPULL
    };
    private TextView mPullText;
    private TextView mPushText;
    private State mPrevState = State.kNONE;

    private static final String TAG = "SoundWave";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Update Graph Params
        mGraph = (GraphView) findViewById(R.id.graph);
        mGraph.setTitle("Mic FFT");
        mGraph.getGridLabelRenderer().setHorizontalAxisTitle("kHz");
        mGraph.getGridLabelRenderer().setVerticalAxisTitle(("Power (dB)"));
        mSeries = new LineGraphSeries<>();
        mGraph.addSeries(mSeries);

        // Update Graph Viewport
        mGraph.getViewport().setScalable(false);
        mGraph.getViewport().setScalableY(false);
        mGraph.getViewport().setScrollable(false);
        mGraph.getViewport().setScrollableY(false);

        mGraph.getViewport().setXAxisBoundsManual(true);
        mGraph.getViewport().setMinX(17);
        mGraph.getViewport().setMaxX(19);

        mGraph.getViewport().setYAxisBoundsManual(true);
        mGraph.getViewport().setMinY(-10);
        mGraph.getViewport().setMaxY(80);

        // Update 18kHz tone player
        tuneThread = new TuneThread();

        // Update FFT Params
        buildHammWindow(FFT_SIZE);
        fft = new DoubleFFT_1D(FFT_SIZE);

        // Text Displays
        mPushText = (TextView) findViewById(R.id.pushText);
        mPullText = (TextView) findViewById(R.id.pullText);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(tuneThread.isRunning()) {
            tuneThread.stopTune();
        }
        stopRecording();

        // Start Tone
        tuneThread = new TuneThread();
        tuneThread.start();

        startRecording();
    }

    @Override
    protected void onPause() {
        stopRecording();

        // Stop Tone
        tuneThread.stopTune();
        super.onPause();
    }

    private void stopRecording() {
        // stops the recording activity
        if (null != recorder) {
            isRecording = false;
            recorder.stop();
            recorder.release();
            recorder = null;
            recordingThread = null;
        }
    }

    int BytesPerElement = 4; // float
    private void startRecording() {

        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC,
                RECORDER_SAMPLERATE, RECORDER_CHANNELS,
                RECORDER_AUDIO_ENCODING, FFT_SIZE * BytesPerElement);

        recorder.startRecording();
        isRecording = true;
        recordingThread = new Thread(new Runnable() {
            public void run() {
                recordingFn();
            }
        }, "AudioRecorder Thread");
        recordingThread.start();
    }

    private void recordingFn() {
        short sData[] = new short[FFT_SIZE];
        double power[] = new double[FFT_SIZE/2]; // 22.05kHz Power Spectrum
        DataPoint[] values = new DataPoint[FFT_SIZE/2]; // Plot

        while (isRecording) {
            recorder.read(sData, 0, FFT_SIZE);
            double[] audioData = new double[sData.length];

            // Apply Hamming Window
            System.arraycopy(applyWindow(sData), 0, audioData, 0, sData.length);

            // Perform FFT
            fft.realForward(audioData);

            // Convert to power spectrum
            for (int i=0; i<power.length; i++) {
                double re  = audioData[2*i];
                double im  = audioData[2*i+1];
                power[i] = 10.0 * Math.log10(Math.sqrt(re * re + im * im));
            }

            // Convert to Data Series
            for (int i=0; i<power.length; i++) {
                double x = (double)RECORDER_SAMPLERATE * i / (FFT_SIZE) / 1000.0; // in kHz
                values[i] = new DataPoint(x, power[i]);
            }

            // Plot on Graph
            final DataPoint[] finalValues = values;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mSeries.resetData(finalValues);
                }
            });

            // Calculate frequency bins
            int bin_18khz = Math.round(18000.0f / (float)RECORDER_SAMPLERATE * FFT_SIZE);

            // Noise Floor
            double noiseFloor = 0.0;
            for(int i = 20; i < 70; i++) {
                noiseFloor += power[bin_18khz - i];
                noiseFloor += power[bin_18khz + i];
            }
            noiseFloor /= 100.0;

            // Calculate left and right 10% half-bandwidth
            double peak = power[bin_18khz] - noiseFloor;
            int leftBand = 0;
            int rightBand = 0;
            boolean secondaryPeak = false;
            for(int i = 0; i < 40; i++) {
                double val = power[bin_18khz + i] - noiseFloor;
                if (val < 0.3 * peak) {
                    rightBand = i;
                    break;
                }
            }
            for(int i = 0; i < 40; i++) {
                double val = power[bin_18khz - i] - noiseFloor;
                if (val < 0.3 * peak) {
                    leftBand = i;
                    break;
                }
            }

            // Change state
            State state = State.kNONE;
            if (leftBand > 4) {
                if (mPrevState != State.kPUSH)
                    state = State.kPULL;
            } else if (rightBand > 4) {
                if (mPrevState != State.kPULL)
                    state = State.kPUSH;
            }

            // Update UI
            final State finalState = state;
            mPrevState = finalState;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateUI(finalState);
                }
            });

        }
    }


    private void updateUI(State state) {
        switch (state) {
            case kPUSH:
                mPullText.setTextColor(Color.GRAY);
                mPullText.setBackgroundColor(Color.WHITE);
                mPushText.setTextColor(Color.BLACK);
                mPushText.setBackgroundColor(Color.GREEN);
                break;
            case kPULL:
                mPushText.setTextColor(Color.GRAY);
                mPushText.setBackgroundColor(Color.WHITE);
                mPullText.setTextColor(Color.BLACK);
                mPullText.setBackgroundColor(Color.RED);
                break;
            default:
                mPushText.setTextColor(Color.GRAY);
                mPushText.setBackgroundColor(Color.WHITE);
                mPullText.setTextColor(Color.GRAY);
                mPullText.setBackgroundColor(Color.WHITE);
        }
    }

    /** build a Hamming window filter for samples of a given size
     * See http://www.labbookpages.co.uk/audio/firWindowing.html#windows
     * @param size the sample size for which the filter will be created
     */
    private void buildHammWindow(int size) {
        if(window != null && window.length == size) {
            return;
        }
        window = new double[size];
        for(int i = 0; i < size; ++i) {
            window[i] = .54 - .46 * Math.cos(2 * Math.PI * i / (size - 1.0));
        }
    }

    /** apply a Hamming window filter to raw input data
     * @param input an array containing unfiltered input data
     * @return a double array containing the filtered data
     */
    private double[] applyWindow(short[] input) {
        double[] res = new double[input.length];

        buildHammWindow(input.length);
        for(int i = 0; i < input.length; ++i) {
            res[i] = (double)input[i] * window[i];
        }
        return res;
    }
}
