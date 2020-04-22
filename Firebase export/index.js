const functions = require('firebase-functions');


/* Create and Deploy Firebase Cloud Functions
  To upload fucntions to Firebase, open the Node.js command prompt and type "firebase deploy" 

  To check the console where the 'console.log' functions prints, select Functions menu  in your Firebase, hover mouse over the function , click on the three dots and select "View logs".*/
DISABLE_LEGACY_METADATA_SERVER_ENDPOINTS=true

const admin = require('firebase-admin');
admin.initializeApp(functions.config().firebase);


 /****Listen to 'door_open_long' update. If its state is true, send FCM message to subscribed apps***/
exports.updating_door_open_long = functions.database.ref('/alarm/door_open_long')
.onUpdate((change, context) => {

	//reads value after updating
	var v_door_long = change.after.val();	  	     
    console.log(v_door_long);
        
    if(true == v_door_long)
        sendFirebaseCloudMessage('door_long', 'chime.wav', 'Porta aberta por mais de 5 minutos!', 'channel_id_long');
    	  
    return 0;   
})


/* Generates history timestamp and deletes old logged events
 * Triggered on creation of a new node in the path '/alarm/history/{pushId}/', where {pushId} 
 * represents the pushId autogenerated, such as '-LGuXlb0GZfwbKs0Rf8Y'.  */
exports.timestampingAlarm = functions.database.ref('/alarm/history/{pushId}/')
.onCreate((snapshot, context) => {

	//reads current value and the pushId
	var original = snapshot.val();
	var pushId = context.params.pushId;   
	   
	//logs to console for debugging
    console.log(`Detected new value ${original} with pushId ${pushId}`);
     
    //changes the value to the generated timesptamp in Epoch time format (milliseconds since 01/01/1970)
    var myDate = new Date();        
	admin.database().ref("/alarm/history/" + pushId).set(myDate.getTime());
	   
	sendFirebaseCloudMessage('porta_aberta', 'default', 'Porta aberta!', 'channel_id_default');
	sendFirebaseCloudMessage('sirene', 'siren.wav', 'Porta aberta!', 'channel_id_siren');
    return deleteOldChildren('/alarm/history/', 100);      
})

/****Circular buffer: remove oldest entries from the path 'firebasePath' until we have the count set by 'max_log_count'***/
function deleteOldChildren(firebasePath, max_log_count) {
	
	const parentRef = admin.database().ref(firebasePath);
	  
	//query items in the path 'firebasePath' asynchronously
    return parentRef.once('value').then(snapshot => {
        if (snapshot.numChildren() >= max_log_count) {
            let childCount = 0;
            const updates = {};

            //for each returned node by the query
            snapshot.forEach(function(child) {
            	  
            	  //define excessive children nodes to be deleted
                if (++childCount <= snapshot.numChildren() - max_log_count) {
            	    console.log(`Key deleted: ${ JSON.stringify(child.key)}`);                	
                    updates[child.key] = null;
                }
            });

            //update the parent. This effectively removes the extra children.
            return parentRef.update(updates);
        }
    });
 
}

 /****Sends FCM message to the clients apps subscribing the topic
         channel_id is required for Android >= Oreo      ****/
function sendFirebaseCloudMessage(topic, sound, body, channel_id) {

	  var message = {
       notification: {
  	      title: 'Alarme de porta',	      
   	      body: body     	      	    
  	   },
  	      android: {
  	   	     notification: {
  	    	      title: 'Alarme de porta',
   	    	      body: body,
   	      	    color: '#ff00ff',
  	  		      sound : sound,
                tag: 'unique_tag',	  		      
  	  		      channel_id: channel_id
  	        }
       },
  	   topic: topic
	  };

	  // Send a message to the devices subscriping  to the provided topic
	  admin.messaging().send(message).then((response) => {
    	  // Response is a message ID string.
    	  console.log('Successfully sent message:', response);
    }).catch((error) => {
    	  console.log('Error sending message:', error);
       });
}