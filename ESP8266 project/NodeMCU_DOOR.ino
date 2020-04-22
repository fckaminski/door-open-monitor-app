/*********************************************************************************
 * Android app to monitor and log door opening
 * Kaminski - 05/2019 - fckaminski66@gmail.com
 *
 * The system has three parts:
 *
 * 1) This ESP8266 board:
 * - Constantly reads open/close door sensor.
 * - Updates door state in the 'door_open' Firebase child node when change is detected.
 * - Generates and uploads 'door_heartbeat' bit to Firebase.
 * - Sets and uploads 'door_open_long' bit if door stays opened for too long.
 * - Activates a local siren when door is opened and 'local_siren' setting is set.
 * - Included WebServer and support over-the-air download
 *
 * 2) Firabase Realtime database:
 * - Stores child/values: disabled, 'door_open', 'door_open_long', 'door_open_long_time' and 'history'.
 * - Generates door opening history timestamp and delete old history records (cloud function).
 * - Sends Firebase Cloud Messages when door opens and if it stays opened for too long  (cloud function).
 *
 * 3) ESP8266 Alarms Android app:
 * - Listen to door state and animates door picture accordingly.
 * - Receives Firebase Cloud Messages and trigger Android notifications.
 * - Monitors communication status with the ESP8266 based on 'door_heartbeat'.
 * - Displays Firebase door opening history in a scrollable list.
 * **********************************************************************************/

#include "FirebaseESP8266.h"
#include <ESP8266WiFi.h>
#include <ArduinoOTA.h>  //over-the-air library

#define FIREBASE_HOST "your_firebase.firebaseio.com"                   //Your Firebase project name address
#define FIREBASE_AUTH "aDAJbKTckzj3heoBC5b7ucMRA9qKnmKTckzj3heJTypovkrTg"    //Your Firebase authentication key

const char* ssid = "your_ssid";              //Your wifi name here
const char* password = "your_password";      //Your wifi password

WiFiServer webserver(80);

//Define FirebaseESP8266 data object
FirebaseData firebaseData;
String fire_error;

//configure I/O pins
const int pin_door = D2, pin_siren = D7, pin_chime = D8;

bool door_open, old_door_open, door_open_confirmed, siren_config, siren_on, chime_on, heartbeat, disabled_config;
int need_confirm;
char inputs_states[10];


//parameters door open for long time warning
int open_door_elapsed, chime_frequency = 400, open_door_config = 300;
unsigned long open_door_startMillis;

//timer parameters
unsigned long startMillis;
unsigned long currentMillis;
const unsigned long period = 5000;  //function execution period in milliseconds

void setup()
{ 
  Serial.begin(115200);
  Serial.println("Booting");
 
  //connects to local Wi-Fi
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  Serial.println("");
  Serial.println("");
  Serial.print("Conectando a ");
  Serial.print(ssid);
   
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("");
  Serial.print("Conected to wifi network ");
  Serial.println(ssid);
  webserver.begin();
  Serial.println("Web server active");
   
  Serial.print("NodeMCU IP address: ");
  Serial.print("http://");
  Serial.println(WiFi.localIP());

  //Connect to Firebase
  Firebase.begin(FIREBASE_HOST, FIREBASE_AUTH);
  Firebase.reconnectWiFi(true); 
  
  /*configure input pin enabling its built-in 10k pull-up resistor
  ps: a second 4k7 pull-up resistor had to be added to dampen electromagnetic induction 
      between the sensor cabling and the 220V power wires that shared the same conduit*/
  pinMode(pin_door, INPUT_PULLUP);  

  //debug entradas
  pinMode(D1, INPUT_PULLUP);
  pinMode(D2, INPUT_PULLUP);
  pinMode(D3, INPUT_PULLUP);
  pinMode(D4, INPUT_PULLUP);
  pinMode(D5, INPUT_PULLUP);
  pinMode(D6, INPUT_PULLUP);

  //output pins
  pinMode(pin_siren, OUTPUT);
  pinMode(pin_chime, OUTPUT);

  //configura download over-the-air
  OTAsetup();
  
  door_open_confirmed = siren_on = siren_config = chime_on = heartbeat = disabled_config = false;

  //reads door initial state
  need_confirm = 0;
  if(digitalRead(pin_door))
     old_door_open = true;
  else
     old_door_open = false;
     
  //initial start time - the number of milliseconds since the program started
  startMillis = millis();  
}

void loop()
{
   ArduinoOTA.handle();

    //reads door state
   door_open = old_door_open;
   if(digitalRead(pin_door) && !disabled_config)
      door_open = true;
   else
      door_open = false;

   //Requests new state confirmation, if door state has been changed
   if(door_open != old_door_open)
      need_confirm = 1;

   if(need_confirm && (door_open == old_door_open))
      need_confirm++;

   //Sends door state to Firebase, if door state chage was confirmation 100 times
   //ps: confirmation was implemented after some spurious changes have been registered due to electromagnetic interference
   if(need_confirm >= 100 && (door_open == old_door_open))
   {
      Firebase.setBool(firebaseData, "/alarm/door_open", door_open);
     
      //logs door opening to Firebase history and plays siren if door opened 
      //Timestamping is done by cloud function (see 'index.js'), to it's not necessary to adjust ESP8266 builtin clock
      if(door_open)
      {
         door_open_confirmed = true;
         open_door_startMillis = millis();
         Firebase.pushString(firebaseData,"/alarm/history", "");                
      } 
      else
      {
         Firebase.setBool(firebaseData,"/alarm/door_open_long", false);
         chime_on = false;
         door_open_confirmed = false; 
      }
                         
      need_confirm = 0;
   }

   old_door_open = door_open;

   //output to local siren
   if(siren_config)
   {
      if(door_open_confirmed)
      {
         siren_on = true;
         digitalWrite(pin_siren, HIGH);       
      }
   }
   else
   {
      siren_on = false;
      digitalWrite(pin_siren, LOW);
   }

   /******check if door is opened for longer than the predefined period and plays buzzer if it is****/
   open_door_elapsed = 0;
   if (door_open_confirmed)
   {    
      open_door_elapsed = (millis() - open_door_startMillis)/1000;
      if (open_door_elapsed >= open_door_config)
      {
          Firebase.setBool(firebaseData,"/alarm/door_open_long", true);
          chime_on = true;
      }
   }

   if(chime_on)
      tone(pin_chime,chime_frequency);      
   else
      digitalWrite(pin_chime, LOW);
  
   /******calls function 'timedLoop()' with the period 'period'***********************/
   currentMillis = millis();                   //get the current "time" (actually the number of milliseconds since the program started)
   if (currentMillis - startMillis >= period)  //test whether the period has elapsed
   {
      timedLoop();
      startMillis = currentMillis;  //IMPORTANT to save the start time of the current LED state.
   }
   /****************************************************************************************/
}

/************************Temporized function*********************************************
* ps.: placing heartbeat here reduced Firebase download quota usage by 10 times  */
void timedLoop()
{  
   heartbeat = !heartbeat;
   fire_error = "Firebase error";

   //Writes heartbeat to Firebase to allow monitoring by the app
   if(!Firebase.setBool(firebaseData, "/alarm/door_heartbeat", heartbeat))   
      fire_error += " setBool: " + firebaseData.errorReason();

   //Gets user setting from Firebase to activate local siren
   if(!Firebase.getBool(firebaseData, "/alarm/local_siren"))      
      fire_error += " getBool: " + firebaseData.errorReason(); 
   else
      siren_config = firebaseData.boolData();

   //Gets user setting from Firebase to enable/disable system
   if(!Firebase.getBool(firebaseData, "/alarm/disabled"))
      fire_error += " getBool: " + firebaseData.errorReason();
   else
      disabled_config = firebaseData.boolData();

   //Gets user setting from Firebasefor the door open for long time warning
   if(!Firebase.getInt(firebaseData, "/alarm/door_open_long_time"))
      fire_error += " getInt: " + firebaseData.errorReason();
   else
      open_door_config = firebaseData.intData();

   //displays input pins current state for diagnostics
   inputs_states[0] = '0' + digitalRead(D0);
   inputs_states[1] = '0' + digitalRead(D1);
   inputs_states[2] = '0' + digitalRead(D2);
   inputs_states[3] = '0' + digitalRead(D3);
   inputs_states[4] = '0' + digitalRead(D4);
   inputs_states[5] = '0' + digitalRead(D5);
   inputs_states[6] = '0' + digitalRead(D6);
   inputs_states[7] = '\0';
   
   //Updates web page
   statusToWeb(door_open, siren_on, open_door_elapsed, heartbeat, fire_error, inputs_states);
}

/****************Publishes door state in a web page*************/
void statusToWeb(bool door_open, bool siren_on, int open_door_elapsed, bool  heartbeat, String fire_error, char *inputs_states)
{
   WiFiClient client = webserver.available(); //Verifica se tem cliente conectado
   if (!client) 
      return; 
   
   Serial.println("New client se connectes!"); 
     
   client.println("<!DOCTYPE HTML>"); 
   client.println("<html>");
   client.println("<body style=\"background-color:LightSteelBlue;\">");     
   client.println("<h1><center><font size='10'>Door Opening Monitor</center></h1>"); 
   client.println("<center><font size='5'>Welcome to Wemos D1 Mini!</center>"); 
   client.println("<br>"); 
   client.println("<center><font size='5'>The sensor should be connected to pin D2.</center>");
   client.println("<center><font size='5'>The reading is sent to Firebase realtime database.</center>");
   
   client.print("<p><center><font size='5'>Door state: ");
   if(door_open)
   {
      client.print("<font color=\"purple\">opened!</font>");
      client.print("<center><font size='5'>The door has been opened for ");
      client.print(open_door_elapsed);
      client.println(" seconds. </center>");
   }
   else
      client.print("closed");
   client.println("</center>");
   
   client.print("<center><font size='5'>Siren state: ");
   if(siren_on)
      client.print("<font color=\"red\">active!</font>");
   else
      client.print("off");
   client.println("</center></p>");

   if(fire_error != "Firebase error")
   {
      client.print("<center><font size='4'>Firebase error: ");
      client.print(fire_error);
      client.println("</center>");
   }
   client.println("<br>"); 
  
   client.println("<center><font size='4'>Supports download over-the-air via Arduino IDE.</center>");
   client.print("<center><font size='4'>Digital input current state (D0~D6): ");
   client.print(inputs_states);
   client.println("</center>");
   client.println("<center><font size='4'>Hearbeat state: ");
   client.print(heartbeat);
   client.println("</center>");   
   
   client.println("</body>");    
   client.println("</html>");
   delay(1); 
   Serial.println("Client disconnected"); 
   Serial.println(""); 
}

/********************************OTA functions***********************/
void OTAsetup()
{
  // Standard TCP port for OTA is 8266
  // ArduinoOTA.setPort(8266);
 
  // Standard Hostname is esp8266-[ChipID]
  // ArduinoOTA.setHostname("nome_do_meu_esp8266");
 
  // uncomment for password lock uploads
  // ArduinoOTA.setPassword((const char *)"123");

   ArduinoOTA.onStart([]() {
     Serial.println("Beginning...");
   });
   ArduinoOTA.onEnd([]() {
     Serial.println("nEnd!");
   });
   ArduinoOTA.onProgress([](unsigned int progress, unsigned int total) {
     Serial.printf("Progress: %u%%r", (progress / (total / 100)));
   });
   ArduinoOTA.onError([](ota_error_t error) {
     Serial.printf("Error [%u]: ", error);
     if (error == OTA_AUTH_ERROR) Serial.println("Authentication failed");
     else if (error == OTA_BEGIN_ERROR) Serial.println("Begin failed");
     else if (error == OTA_CONNECT_ERROR) Serial.println("Conection failed");
     else if (error == OTA_RECEIVE_ERROR) Serial.println("Reading failed");
     else if (error == OTA_END_ERROR) Serial.println("End failed");
   });
   ArduinoOTA.begin();
   Serial.print("IP address for OTA transmission: ");
   Serial.println(WiFi.localIP());
   
}
