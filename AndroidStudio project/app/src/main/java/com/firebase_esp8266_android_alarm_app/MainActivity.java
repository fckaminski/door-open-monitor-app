/*********************************************************************************
 * Android app to monitor and log door opening
 * Kaminski - 05/2019 - fckaminski66@gmail.com
 *
 * The system has three parts:
 *
 * 1) ESP8266 board:
 * - Constantly reads open/close door sensor.
 * - Updates door state in the 'door_open' Firebase child node when change is detected.
 * - Generates and uploads 'door_heartbeat' bit to Firebase.
 * - Sets and uploads 'door_open_long' bit if door stays opened for too long.
 * - Activates a local siren when door is opened and 'local_siren' setting is set.
 *
 * 2) Firabase Realtime database:
 * - Stores child/values: disabled, 'door_open', 'door_open_long', 'door_open_long_time' and 'history'.
 * - Generates door opening history timestamp and delete old history records (cloud function).
 * - Sends Firebase Cloud Messages when door opens and if it stays opened for too long  (cloud function).
 *
 * 3) This App:
 * - Listen to door state and animates door picture accordingly.
 * - Receives Firebase Cloud Messages and trigger Android notifications.
 * - Monitors communication status with the ESP8266 based on 'door_heartbeat'.
 * - Displays Firebase door opening history in a scrollable list.
 * **********************************************************************************/

package com.firebase_esp8266_android_alarm_app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Objects;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener {

    //Reference to Firebase access
    FirebaseDatabase database = FirebaseDatabase.getInstance();
    DatabaseReference myRef = database.getReference();
    DatabaseReference dbHistory = database.getReference("/alarm/history");
    private FirebaseAuth mAuth;

    DatabaseReference dbchild_porta = myRef.child("/alarm/door_open");
    DatabaseReference dbchild_sirene = myRef.child("/alarm/local_siren");
    DatabaseReference dbchild_disabled = myRef.child("/alarm/disabled");
    DatabaseReference door_heartbeat = myRef.child("/alarm/door_heartbeat");

    //Arrays to receive alarm history from Firebase
    ArrayList<String> listItems = new ArrayList<String>();
    ArrayList<String> listKeys = new ArrayList<String>();
    ArrayAdapter<String> arrayAdapter;

    //Runtime accessed screen objects
    ImageView mImagePorta, mImageLed;
    TextView textoStatus;
    CheckBox cbSirene;
    Spinner spNotifica;
    ListView lv;

    //Persistent variables access to store user settings
    SharedPreferences sp_preferences;
    SharedPreferences.Editor sp_editor;

    //Heartbeat monitoring variables
    Handler handler = new Handler();
    int counter = 0, delay = 5000; //milliseconds

    String[] notifyOptions={"Disabled", "Default sound","Siren sound"};
    boolean first_scan, disable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mImagePorta = (ImageView) findViewById(R.id.porta);
        mImageLed = (ImageView) findViewById(R.id.led);
        textoStatus = (TextView) findViewById(R.id.textView);
        cbSirene = (CheckBox) findViewById(R.id.checkBoxSiren);
        spNotifica = (Spinner) findViewById(R.id.spinnerNotify);

        //Loads sounds files in folder '\app\src\main\res\raw'
        final MediaPlayer mpCloseDoor = MediaPlayer.create(this, R.raw.close_door);
        final MediaPlayer mpOpenDoor = MediaPlayer.create(this, R.raw.open_door);
        final MediaPlayer mpSiren = MediaPlayer.create(this, R.raw.siren);

        door_heartbeat.setValue("OFF");
        textoStatus.setText("Waiting for ESP8266...");

        first_scan = true;

        /******Populates spinner with the notification options.
         Spinners can only be filled by using an ArrayAdapter, that couples the 'notifyOptions' array data to the spinner ***/
        arrayAdapter = new ArrayAdapter(this,android.R.layout.simple_spinner_item,notifyOptions);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spNotifica.setAdapter(arrayAdapter);

        //Busca valor da variável persistente deste App, utilizada para salvar opção de notificação, e atualiza o spinner
        sp_preferences = this.getSharedPreferences("user_preferences", Context.MODE_PRIVATE );
        sp_editor = sp_preferences.edit();
        int spinner_position = sp_preferences.getInt("notification",0);
        spNotifica.setSelected(false);
        spNotifica.setSelection(spinner_position,false);

        //initializes spinner listener
        spNotifica.setOnItemSelectedListener(this);

        //configures FCM notifications accordingly to the spinner selection
        notifyConfigUpdate(spinner_position);

        //Firebase anonymous authentication
        mAuth = FirebaseAuth.getInstance();
        signInAnonymously();

        //sends local siren checkbox settings to Firebase
        cbSirene.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
        {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                dbchild_sirene.setValue(isChecked);
            }
        });

        /*****Function executes each 'delay' milliseconds. Increments heartbeat watchdog counting from ESP8266.****/
        handler.postDelayed(new Runnable() {
            public void run() {
                counter++;

                //If watchdog counting > 2, turns the led red to indicate communication fault with the ESP8266
                if (counter >= 2) {
                    mImageLed.setImageResource(R.drawable.red_on);
                    textoStatus.setText("ESP8266 inactive");
                }
                handler.postDelayed(this, delay);
            }
        }, delay);

        /*****Listen to the door state updates from Firebase***/
        dbchild_porta.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // This method is called once with the initial value and again whenever data at this location is updated.
                String value = dataSnapshot.getValue().toString();
                Log.d("door", "Value is: " + value);

                if (Objects.equals(value, "true")) {

                    mImagePorta.setImageResource(R.drawable.door_opened);

                    //plays door opening sound or siren, except during App startup.
                    if(!first_scan)
                        mpOpenDoor.start();   //toca som de porta abrindo
                    first_scan = false;
                }
                else if (Objects.equals(value, "false")) {
                    mImagePorta.setImageResource(R.drawable.door_closed);

                    //plays door closing sound, except during App startup.
                    if(!first_scan)
                        mpCloseDoor.start();
                    first_scan = false;
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Failed to read value
                Log.w("file", "Failed to read value.", error.toException());
            }
        });

        /***Listen to the heartbeat from Firebase. When heartbeat is received from ESP8266, turns led green and resets watchdog counter*****/
        door_heartbeat.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // This method is called once with the initial value and again
                // whenever data at this location is updated.
                if(!first_scan) {
                    mImageLed.setImageResource(R.drawable.green_on);
                    if (disable)
                        textoStatus.setText("ESP8266 inactive");
                    else
                        textoStatus.setText("ESP8266 active");
                    counter = 0;
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Failed to read value
                Log.w("file", "Failed to read value.", error.toException());
            }
        });

        /***Listen to the local siren configuration from Firebase, to synchronize different users*****/
        dbchild_sirene.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // This method is called once with the initial value and again whenever data at this location is updated.
                String value = dataSnapshot.getValue().toString();
                Log.d("sirene", "Value is: " + value);

                if (Objects.equals(value, "true"))
                    cbSirene.setChecked(true);
                else
                    cbSirene.setChecked(false);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Failed to read value
                Log.w("file", "Failed to read value.", error.toException());
            }
        });

        /***Listen to the enable/disable configuration from Firebase*****/
        dbchild_disabled.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                // This method is called once with the initial value and again whenever data at this location is updated.
                String value = dataSnapshot.getValue().toString();
                Log.d("disable", "Value is: " + value);

                if (Objects.equals(value, "true"))
                    disable = true;
                else
                    disable = false;
            }

            @Override
            public void onCancelled(DatabaseError error) {
                // Failed to read value
                Log.w("file", "Failed to read value.", error.toException());
            }
        });

        /**************Query the alarms history from Firebase. Answer is received through listener*************/
        Query query = dbHistory;
        addChildEventListener();

        /******ListViews can only be filled by using an ArrayAdapter,  that couples the 'listItems' list with the ListView.
        *     The file 'listview_custom_layout.xml' was created to describe the layout of each line from the list,
        *     since the default layout was separating the lines with excessive space. This file is then fed as the 2nd argument.  ***/
        arrayAdapter = new ArrayAdapter<String>(this, R.layout.listview_custom_layout, listItems );

        //Populates ListView with the defined ArrayAdapter
        lv = (ListView) findViewById(R.id.historico);
        lv.setAdapter(arrayAdapter);

        /***********disables system when led is long clicked.**************/
        mImageLed.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {

                dbchild_disabled.setValue(!disable);
                if(disable)
                    Toast.makeText(getApplicationContext(), "System enabled", Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(getApplicationContext(), "System disabled", Toast.LENGTH_LONG).show();
                return true;    // <- set to true
            }
        });
    }
    /*****************************OnCreate ends here********************************/

    //Executes when an option from notification spinner is selected
    @Override
    public void onItemSelected(AdapterView<?> arg0, View arg1, int position, long id) {

        notifyConfigUpdate(position);

        //saves new config in the persistent variable
        sp_editor.putInt("notification", position);
        sp_editor.commit();
    }

    @Override
    public void onNothingSelected(AdapterView<?> arg0) {
    }

    /****When updating notification configuration, subscribe or unsubscribe message topic
     * FCM "sirene" or "porta_aberta": see Firebase cloud function 'sendFirebaseCloudMessage'
     * Two topics were created, to separate users that chose standard notification sound from users that picked siren sound.
     ******************************************************************************************************************/
    private void notifyConfigUpdate(int position) {

        Log.d("notifica", "Value is: " + String.valueOf(position) );
        switch(position) {
            case 0:
                FirebaseMessaging.getInstance().unsubscribeFromTopic("porta_aberta");
                FirebaseMessaging.getInstance().unsubscribeFromTopic("sirene");
                FirebaseMessaging.getInstance().unsubscribeFromTopic("door_long");
                break;
            case 1:
                FirebaseMessaging.getInstance().subscribeToTopic("porta_aberta");
                FirebaseMessaging.getInstance().unsubscribeFromTopic("sirene");
                FirebaseMessaging.getInstance().subscribeToTopic("door_long");
                break;
            case 2:
                FirebaseMessaging.getInstance().subscribeToTopic("sirene");
                FirebaseMessaging.getInstance().unsubscribeFromTopic("porta_aberta");
                FirebaseMessaging.getInstance().subscribeToTopic("door_long");
                break;
        }
    }

    //Listen to Firebase 'history' child node: The app will receive notifications whenever new child nodes are added, removed or changed
    private void addChildEventListener() {
        ChildEventListener childListener = new ChildEventListener() {

            @Override
            //Also triggered when app starts or when screen is rotated
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                adicionaFirebaseChildToList(dataSnapshot);
            }

            @Override
            //Triggered by the Firebase cloud function that generates timestamp
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                adicionaFirebaseChildToList(dataSnapshot);
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                String key = dataSnapshot.getKey();
                int index = listKeys.indexOf(key);

                if (index != -1) {
                    listItems.remove(index);
                    listKeys.remove(index);
                }
                arrayAdapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        };
        dbHistory.addChildEventListener(childListener);
    }

    //Adds child 'history' values in the items list and notifies adapter to refresh screen
    public void adicionaFirebaseChildToList(DataSnapshot dataSnapshot) {

        //if 'history' key value is not a number, returns
        if(!isInteger(dataSnapshot.getValue().toString()))
            return;

        //converts date/time from Unix Epoch format (milliseconds since 01/01/1970) to format "dd/MM/yy  -  HH:mm:ss"
        SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yy  -  HH:mm:ss");
        long dateUnixEpoch = (long) dataSnapshot.getValue();
        Date date = new Date(dateUnixEpoch);
        String dateTimeAsString = formatter.format(date);

        //adds new item to the list, so that the most recent will appear on the top
        listItems.add(0, dateTimeAsString);
        listKeys.add(0, dataSnapshot.getKey());

        //updates list view
        arrayAdapter.notifyDataSetChanged();
    }

    //When initializing your Activity, check to see if the user is currently signed in.
    @Override
    public void onStart() {
        super.onStart();
        // Check if user is signed in (non-null) and update UI accordingly.
        FirebaseUser currentUser = mAuth.getCurrentUser();
    }
    //sign in as an anonymous use
    private void signInAnonymously() {

        mAuth.signInAnonymously()
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            // Sign in success, update UI with the signed-in user's information
                            Log.d("auth", "signInAnonymously:success");
                            FirebaseUser user = mAuth.getCurrentUser();
                        } else {
                            // If sign in fails, display a message to the user.
                            Log.e("auth", "signInAnonymously:failure", task.getException());
                            Toast.makeText(MainActivity.this, "Authentication failed.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    /*******auxiliar:  Check if a String represents an integer in Java***********************
     * *    credits: https://stackoverflow.com/questions/5439529/determine-if-a-string-is-an-integer-in-java***********/
    public static boolean isInteger(String s) {
        return isInteger(s,10);
    }

    public static boolean isInteger(String s, int radix) {
        if(s.isEmpty()) return false;
        for(int i = 0; i < s.length(); i++) {
            if(i == 0 && s.charAt(i) == '-') {
                if(s.length() == 1) return false;
                else continue;
            }
            if(Character.digit(s.charAt(i),radix) < 0) return false;
        }
        return true;
    }
}