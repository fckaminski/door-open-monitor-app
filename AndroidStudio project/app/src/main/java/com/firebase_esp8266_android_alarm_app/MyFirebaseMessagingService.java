package com.firebase_esp8266_android_alarm_app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/*
This class receives Firebase FCM messages when app is foreground
credits: https://www.androidauthority.com/android-push-notifications-with-firebase-cloud-messaging-925075/
*/

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        super.onMessageReceived(message);

        //Establish an intent, which is the action taken when the notification is clicked on. This intent simply bring app to front.
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String NOTIFICATION_CHANNEL_ID, notification_description, notification_title;

        //Receives FCM notification text
        String messageBody = "No mensage";
        String messageTitle = "No title";
        if (message.getNotification() != null) {
            messageTitle = message.getNotification().getTitle();
            messageBody = message.getNotification().getBody();
        }
        Log.d("service", "Body is: " + messageBody );

        //Searches this app persistent variable value, used to save notification option, and updates the spinner
        final SharedPreferences sp_preferences = this.getSharedPreferences("user_preferences", Context.MODE_PRIVATE );
        int spinner_position = sp_preferences.getInt("notification",0);

        Uri alarmSound;

        //defines notification sound
        if(messageBody.equals("Porta aberta por mais de 5 minutos!")) {
            NOTIFICATION_CHANNEL_ID = "channel_id_long";
            notification_title = "5 min notification";
            notification_description = "Aviso de 5 minutos porta aberta";
            alarmSound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getApplicationContext().getPackageName() + "/" + R.raw.chime);
        }
        else if(spinner_position == 2) {
            NOTIFICATION_CHANNEL_ID = "channel_id_siren";
            notification_title = "Siren notification";
            notification_description = "Porta aberta com sirene";
            alarmSound = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + getApplicationContext().getPackageName() + "/" + R.raw.siren);
        }
        else {
            NOTIFICATION_CHANNEL_ID = "channel_id_default";
            notification_title = "Default notification";
            notification_description = "Porta aberta com toque padrão";
            alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        //Before you can deliver the notification on Android 8.0 (Oreo) and higher, you must register your app's notification channel:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    notification_title, NotificationManager.IMPORTANCE_HIGH);

            // Configure the notification channel.
            notificationChannel.setDescription(notification_description);
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.CYAN);
            //notificationChannel.enableVibration(true);   ==> does not play sound when enabled
            notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            AudioAttributes att = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();

            notificationChannel.setSound(alarmSound,att);  //plays siren sound if checkbox is checked

            //We use the same channel to avoid multiple notifications. If there's already a channel, it won't be updated
            if (notificationManager != null)
               notificationManager.createNotificationChannel(notificationChannel);
        }

        //Defines notification settings
        NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getApplicationContext(), NOTIFICATION_CHANNEL_ID)
                .setLights(Color.CYAN, 300, 1000)                                         //notification device LED color
                .setSmallIcon(R.drawable.door)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(),R.drawable.security_alarm))  //notification icon
                .setVibrate(new long[]{0, 1000, 500, 1000})             //device vibration pattern
                .setContentTitle(messageTitle)                          //notification title text
                .setContentText(messageBody)                            //notification secondary text
                .setPriority(notificationManager.IMPORTANCE_HIGH)       //sets maximum priority
                .setContentIntent(pendingIntent);                       //action taken when notification gets clicked on
        ;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
           mBuilder.setSound(alarmSound);

        //triggers notification
        notificationManager.notify(0, mBuilder.build());
    }
}