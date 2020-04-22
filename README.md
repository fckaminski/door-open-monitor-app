# door-open-monitor-logger

## Description:
 Alarm system that monitors and logs door opening events and displays them in an Android app
 
 Kaminski - 05/2019 - fckaminski66@gmail.com
 
 The system has three parts:

## 1) ESP8266 board connected to your local wifi:
 * Constantly reads open/close door sensor.
 * Updates door state in the 'door_open' Firebase child node when change is detected.
 * Generates and uploads 'door_heartbeat' bit to Firebase.
 * Sets and uploads 'door_open_long' bit if door stays opened for too long.
 * Activates a local siren when door is opened and 'local_siren' setting is set.
 * Included WebServer and support over-the-air download
 
## 2) Firebase cloud-hosted NoSQL realtime database:
 * Stores child/values: disabled, 'door_open', 'door_open_long', 'door_open_long_time' and 'history'.
 * Generates door opening history timestamp and delete old history records (cloud function).
 * Sends Firebase Cloud Messages when door opens and if it stays opened for too long  (cloud function).

## 3) 'ESP8266 Alarms' Android app:
 * Listen to door state and animates door picture accordingly.
 * Receives Firebase Cloud Messages and trigger Android notifications.
 * Monitors communication status with the ESP8266 based on 'door_heartbeat'.
 * Displays Firebase door opening history in a scrollable list.
 
## Instructions:

## Firebase setup:
- Create your own realtime database with Firebase. Seee the chapter "Making a Real-time Database" from the tutorial
    https://medium.com/coinmonks/arduino-to-android-real-time-communication-for-iot-with-firebase-60df579f962
- In your Realtime Database, create the child/values pairs accondingly to the Android program, or alternatively, select the "Import JSON" option and then import the  "door-export.json" file.
- Install "Node.js" to enable cloud functions. This will create a "index.js" file in a local folder. Replace it with the "index.js" from this project.
- Open a "Node.js command prompt" and run command "firebase deploy". After around one minute, upload will be completed.

## ESP8266 setup:
- Connect the door sensor to an input pin. Optional: connect a chime and a siren to two output pins.
- Using Arduino IDE, open 'NodeMCU_DOOR.ino' project.
- Install 'ESP8266 WiFi' and 'Firebase ESP8266 Client' libraries to your Arduino IDE.
- Adjust the output pins numbers used, your local WiFi SSID/password and your Firebase settings. 
- Upload the project to your board.

## Android setup:
- Import Android app project in Android Studio.
- Select menu Tools > Firebase. At the right panel, select "Real-Time Database". Log on with your Google account and select:
	a) Connect your App to Firebase by selecting the realtme database you created and clicking "Connect to Firebase".
	b) Add the Realtime Database to your app.
  If you need help here, refer to the tutorial https://medium.com/coinmonks/arduino-to-android-real-time-communication-for-iot-with-firebase-60df579f962..

- Enable your phone "Developer options" and then enable "USB debugging" and "Install via USB".
- Connect your phone to the workstation USB port and authorize file transfer.
- Check if Android Studio now list it your device in the upper menu. If so, build your application and run it.
