package net.yishanhe.wearlock;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import android.widget.Toast;

import com.github.clans.fab.FloatingActionButton;
import com.github.clans.fab.FloatingActionMenu;
import com.google.android.gms.common.api.GoogleApiClient;

import net.yishanhe.ofdm.Chunk;
import net.yishanhe.ofdm.Synchronization;
import net.yishanhe.utils.IOUtils;
import net.yishanhe.wearcomm.WearCommClient;
import net.yishanhe.wearcomm.events.ChannelOpenedEvent;
import net.yishanhe.wearcomm.events.FileReceivedEvent;
import net.yishanhe.wearcomm.events.ReceiveMessageEvent;
import net.yishanhe.wearcomm.events.SendMessageEvent;
import net.yishanhe.wearlock.events.StatusMessageEvent;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;

public class MainActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks , AudioTrack.OnPlaybackPositionUpdateListener{

    private static final String TAG = "MainActivity";

    private static final String START_ACTIVITY = "/start_activity";
    private static final String STOP_ACTIVITY = "/stop_activity";
    private static final String START_RECORDING = "/start_recording";
    private static final String RECORDING_STARTED = "/RECORDING_STARTED";
    private static final String STOP_RECORDING = "/stop_recording";
    private static final String SEND_RECORDING = "/send_recording";
    private File rec;
    public static String SAVE_DIR = "WearLock";
    private File folder;
    private String inputPin = null;
    private static final int REQUEST_WRITE_STORAGE = 112;

    final Handler handler = new Handler();

    private static final int LOCAL = 0;
    private static final int REMOTE_PREAMBLE = 1;
    private static final int REMOTE_MODULATED = 2;
    private int state;

    private long messageSentTime;
    private long fileSentTime;
    @Bind(R.id.toolbar) Toolbar toolbar;

    // fab menu and buttons.
    @Bind(R.id.fab) FloatingActionMenu fab;

//    @Bind(R.id.fab_ambient_noise) FloatingActionButton fabAmbientNoise;
//    @OnClick(R.id.fab_ambient_noise)
//    public void getAmbientNoise() {
//        EventBus.getDefault().post(new SendMessageEvent(START_RECORDING));
//        handler.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                EventBus.getDefault().post(new SendMessageEvent(STOP_RECORDING));
//            }
//        }, 5000);
//        fab.toggle(true);
//    }

    @Bind(R.id.fab_clean) FloatingActionButton fabClean;
    @OnClick(R.id.fab_clean)
    public void clean() {
        EventBus.getDefault().post(new StatusMessageEvent(TAG, "", "/clean_status"));
        inputPin = null;
        fabProbingBeep.setEnabled(true);
        fabModulatedBeep.setEnabled(false);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (folder.isDirectory()) {
                    for (File child : folder.listFiles()) {
                        child.delete();
                    }
                }
            }
        }, 100);
    }

    // @TODO: add preference to play with remote recording or just local.
    @Bind(R.id.fab_play) FloatingActionButton fabPlay;
    @OnClick(R.id.fab_play)
    public void playLocal() {
        state = LOCAL;
        if (modulatedTrack!=null) {
            handler.post(modulatedTrack);
            EventBus.getDefault().post(new StatusMessageEvent(TAG, "Local modulated sent."));
        } else {
            handler.post(preambleTrack);
            EventBus.getDefault().post(new StatusMessageEvent(TAG, "Local preamble sent."));
        }
        fab.toggle(true);
    }

    @Bind(R.id.fab_probing_beep) FloatingActionButton fabProbingBeep;
    @OnClick(R.id.fab_probing_beep)
    public void sendProbingBeep() {
        fabProbingBeep.setEnabled(false);
        state = REMOTE_PREAMBLE;
        EventBus.getDefault().post(new SendMessageEvent(START_RECORDING));
        fab.toggle(true);

    }

    @Bind(R.id.fab_modulated_beep) FloatingActionButton fabModulatedBeep;
    @OnClick(R.id.fab_modulated_beep)
    public void sendModulatedBeep() {
        fabModulatedBeep.setEnabled(false);
        state = REMOTE_MODULATED;
        EventBus.getDefault().post(new SendMessageEvent(START_RECORDING));
        fab.toggle(true);
    }
    @Bind(R.id.fab_reset) FloatingActionButton fabReset;

    @Bind(R.id.fab_timer) FloatingActionButton fabTimer;
    @OnClick(R.id.fab_timer)
    public void measureMessageDelay() {
        Log.d(TAG, "measureMessageDelay is called.");
        EventBus.getDefault().post(new SendMessageEvent("/measure_message_delay"));
        messageSentTime = System.currentTimeMillis();
        fab.toggle(true);
    }

    @Subscribe
    public void onReceiveMessageEvent(ReceiveMessageEvent event){
        if (event.getPath().equalsIgnoreCase("/measure_message_delay")) {
            Log.d(TAG, "onReceiveMessageEvent: measure message delay.");
            EventBus.getDefault().post(new StatusMessageEvent(TAG, "Measured RTT:"+(System.currentTimeMillis()-messageSentTime)+"ms"));
        }
        if (event.getPath().equalsIgnoreCase(RECORDING_STARTED)) {
            Log.d(TAG, "onReceiveMessageEvent: remote recording started.");
            switch (state) {
                case REMOTE_PREAMBLE:
                    handler.postDelayed(preambleTrack, 100);
                    break;
                case REMOTE_MODULATED:
                    handler.postDelayed(modulatedTrack, 100);
                    break;
            }
        }
        if (event.getPath().equalsIgnoreCase("/PIN")) {
            setInputPin( new String(event.getData()));
            Log.d(TAG, "onReceiveMessageEvent: new pin code "+inputPin);
            EventBus.getDefault().post(new StatusMessageEvent(TAG, "pin "+inputPin));
            // regenerate the ofdm modulated sound.
            modem.makeModulated(inputPin);
            if (modulatedTrack!=null) {
                modulatedTrack.release();
                modulatedTrack = null;
            }
            modulatedTrack = new AudioRunnable(modem.getSampleRateInHZ(), modem.getModulatedInShort(), this);
            fabModulatedBeep.setEnabled(true);

        }
    }

    @Bind(R.id.backdrop) ImageView iv;

    private Modem modem = null;
    private WearCommClient client = null;
    private AudioRunnable preambleTrack = null;
    private AudioRunnable modulatedTrack = null;

    // @TODO test message sending delay.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate: main.");
        EventBus.getDefault().register(this);
        ButterKnife.bind(this);

        // iv.setImageDrawable(getResources().getDrawable(R.drawable.ic_action_header));
        setSupportActionBar(toolbar);


        // set fab
        fab.setClosedOnTouchOutside(true);
        fabModulatedBeep.setEnabled(false);


        // IO
        folder = new File("/sdcard/"+File.separator+SAVE_DIR+File.separator);
        if(! this.folder.exists()) {
            folder.mkdir();
        }

        // init google api client
        client = WearCommClient.getInstance(this, this);
        if (client != null) {
            client.connect();
        }

        // prepare Modem
        modem = Modem.getInstance(this);
        if (modem != null) {
            modem.LoadParameter();
            modem.prepare();

            modem.makePreamble();

            AudioManager am = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
            Log.d(TAG, "onCreate: "+am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            Log.d(TAG, "onCreate: "+am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            Log.d(TAG, "onCreate: "+am.getProperty(AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND));
            Log.d(TAG, "onCreate: "+am.getProperty(AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND));

            // load preamble/modulated to tracks
            preambleTrack = new AudioRunnable(modem.getSampleRateInHZ(), modem.getPreambleInShort(), this);
            // modulated symbol

            EventBus.getDefault().post(new StatusMessageEvent(TAG, "Init finished. FAB enabled."));
        }

        if ( ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_WRITE_STORAGE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                    this.recreate();
                    Log.d(TAG, "Write to external storage permission granted.");
                } else {
                    Log.d(TAG, "Write to external storage permission denied.");
                }
                break;
        }
    }

    @Override
    public void onMarkerReached(AudioTrack track) {
        Log.d(TAG, "onMarkerReached: main");
        switch (state) {

            case REMOTE_PREAMBLE:
            case REMOTE_MODULATED:
                // post delay
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        EventBus.getDefault().post(new SendMessageEvent(STOP_RECORDING));
                    }
                }, 250);
                fabProbingBeep.setEnabled(true);
                if (inputPin!=null) {
                    fabModulatedBeep.setEnabled(true);
                } else {
                    fabModulatedBeep.setEnabled(false);
                }
                break;
        }
    }

    @Override
    public void onPeriodicNotification(AudioTrack track) {
        Log.d(TAG, "onPeriodicNotification: main");
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.d(TAG, "onConnected: start activity");
        EventBus.getDefault().post(new SendMessageEvent(START_ACTIVITY));
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.action_settings:
                startActivity(new Intent(this, SettingActivity.class));
                break;
        }

        return super.onOptionsItemSelected(item);
    }


    @Override
    protected void onDestroy() {
//        audioWriter.unregisterListener();
        if (preambleTrack!=null) {
            preambleTrack.release();
        }

        if (modulatedTrack!=null) {
            modulatedTrack.release();
        }
        EventBus.getDefault().unregister(this);
        Log.d(TAG, "onDestroy: stop activity");
        EventBus.getDefault().post(new SendMessageEvent(STOP_ACTIVITY));
//        client.disconnect();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // close the client after it processes the STOP_ACTIVITY message.
                // TODO: use a pair of messages to do this.
                client.disconnect();
            }
        }, 500); // wait for the stop_activity message being sent out.
        super.onDestroy();
    }

    @Subscribe
    public void onChannelOpenedEvent(ChannelOpenedEvent event) {
        if (event.getChannel().getPath().equalsIgnoreCase(SEND_RECORDING)) {

            fileSentTime = System.currentTimeMillis();
            try {
                if (state == REMOTE_MODULATED) {
                    rec = File.createTempFile("modulated",".raw", folder);
                } else if (state == REMOTE_PREAMBLE) {
                    rec = File.createTempFile("preamble",".raw", folder);
                } else {
                    rec = File.createTempFile("wearlock",".raw", folder);
                }
                event.getChannel().receiveFile(client.getGoogleApiClient(), Uri.fromFile(rec), false);

                Log.d(TAG, "onChannelOpened: Saving data to file:"+rec.getName());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onFileReceivedEvent(FileReceivedEvent event) {
        Log.d(TAG, "onFileReceivedEvent: main");
        // do analysis here.
        EventBus.getDefault().post(new StatusMessageEvent(TAG, "File received. time cost:"+(System.currentTimeMillis()-fileSentTime)+"ms"));

        // run data analysis here.
//        File file = new File(Environment.getExternalStorageDirectory().getPath(), "rec.raw");
        Chunk chunk = null;

        chunk = new Chunk(true, IOUtils.loadFromFile(rec)); // true big endian
        double[] input = chunk.getDoubleBuffer();

        EventBus.getDefault().post(new StatusMessageEvent(TAG, "load "+input.length+" samples."));

        SlidingWindow sw = new SlidingWindow(4096,2048,input);

        SilenceDetector sd = new SilenceDetector(-75.0);

        boolean isClipStart = false;
        ArrayList<Integer> startIndexArray = new ArrayList<>();
        ArrayList<Integer> endIndexArray = new ArrayList<>();

        int maxStartIdx = 0;
        int maxEndIdx = 0;
        double maxSPL = -100.0;


        while (sw.hasNext()) {

            double[] chunkData = sw.next();

            if (sd.isSilence(chunkData)) {
                // silent do nothing.
                if (isClipStart == true) {
                    isClipStart = false;
                    endIndexArray.add(sw.getStart());
                }
            } else {
                // detected sound
                if (isClipStart == false) {
                    isClipStart = true;
                    startIndexArray.add(sw.getStart());
                }
            }

            if (sd.getCurrentSPL() > maxSPL ) {
                maxSPL = sd.getCurrentSPL();
                System.out.println("maxSPD updated. " + maxSPL);
                maxStartIdx = Math.max(sw.getStart()-1024,0);
                maxEndIdx = Math.min(sw.getEnd()+1024,chunk.getDoubleBuffer().length);
            }

        }

        if (isClipStart == true) {
            isClipStart = false;
            endIndexArray.add(chunk.getDoubleBuffer().length);
        }


        if (startIndexArray.size() !=  endIndexArray.size()) {
            throw new IllegalArgumentException("chunk start and end size mismatch.");
        }

        // run preamble detection.
        if (startIndexArray.size() == 0) {
            EventBus.getDefault().post(new StatusMessageEvent(TAG, "detect preamble:  not found. use the max one. start:"+maxStartIdx+" end:"+maxEndIdx));
            startIndexArray.add(maxStartIdx);
            endIndexArray.add(maxEndIdx);

        }

        int maxIndex = 0;
        double maxXcorrVal = 0.0;
        for (int i = 0; i < startIndexArray.size(); i++) {
            int start =  startIndexArray.get(i);
            int end = endIndexArray.get(i);
            Log.d(TAG, "onFileReceivedEvent: check chunk start:"+start+"end:"+end);
            double[] candidate = chunk.getDoubleBuffer(start,end);
            double xcorrVal = modem.detectPreamble(candidate);
            if (xcorrVal > maxXcorrVal) {
                maxXcorrVal = xcorrVal;
                maxIndex = i;
            }
        }

        EventBus.getDefault().post(new StatusMessageEvent(TAG, "detect preamble:  "+ maxXcorrVal));

        if (maxXcorrVal < 0.1) {
            EventBus.getDefault().post(new StatusMessageEvent(TAG, "detect preamble signal too bad abort tastk."));
            return;
        }

        switch (state) {

            case REMOTE_PREAMBLE:
                // replay to send to chose modulation.
                break;

            case REMOTE_MODULATED:

                String demodulated = modem.deModulate(chunk, startIndexArray.get(maxIndex), endIndexArray.get(maxIndex));
                // call demodulate
                EventBus.getDefault().post(new StatusMessageEvent(TAG, demodulated,"/demodulated_result"));
                break;

            default:
                break;

        }




    }


    public void setInputPin(String inputPin) {
        this.inputPin = inputPin;
    }
}
