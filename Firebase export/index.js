const functions = require('firebase-functions');


// Create and Deploy Firebase Cloud Functions
// Salvar alterações nesse arquivo e depos digitar "firebase deploy --only functions" no Node.js prompt command

// Para acessar o console onde comando console.log imprime, acessar menu Functions na pagina Firebase, 
// posicionar o mouse sobre a função desejada, clicar sobre os três pontos e seleciona "View logs".
DISABLE_LEGACY_METADATA_SERVER_ENDPOINTS=true

const admin = require('firebase-admin');
admin.initializeApp(functions.config().firebase);


//Funcao para escrever ON/OFF nos LEDs quando 'all_leds' for escrito por alguem
exports.updatingLeds = functions.database.ref('/leds/all_leds')
.onUpdate((change, context) => {

	//lê valores antes e depois da alteração
	var v_all_leds_before = change.before.val();
	var v_all_leds_after = change.after.val();	  	     
	   
	console.log(v_all_leds_before);
    console.log(v_all_leds_after);	   
 
	//retorna se 'all_leds' for neutra ou se não houve alteração
	if("NULL" === v_all_leds_after || v_all_leds_before===v_all_leds_after)
	    return change;	 	   	  
	    
    //cria referencia da raiz do banco de dados para poder alterar dados
    var rootSnapshot = change.after.ref.parent;
     
    
    if("ON" === v_all_leds_after)
    {
        rootSnapshot.child('green_led').set("ON");
        rootSnapshot.child('red_led').set("ON");
        rootSnapshot.child('blue_led').set("ON");
		}
		else if ("OFF" === v_all_leds_after)
		{
	      rootSnapshot.child('green_led').set("OFF");
        rootSnapshot.child('red_led').set("OFF");
        rootSnapshot.child('blue_led').set("OFF");	 	
		}
		 
		//retorna 'all_leds' para estado neutro
		rootSnapshot.child('all_leds').set("NULL");
		
		return 0;
})


 /****Verifica se a porta está aberta por mais de 5 minutos e envia mensagem FCM para os Apps clientes***/
exports.updating_door_open_long = functions.database.ref('/alarm/door_open_long')
.onUpdate((change, context) => {

	//lê valor depois da alteração
	var v_door_long = change.after.val();	  	     
    console.log(v_door_long);
        
    if(true === v_door_long)
    	sendFirebaseCloudMessage('door_long', 'chime.wav', 'Porta aberta por mais de 5 minutos!', 'channel_id_long');
    	  
    return 0;   
})


/* Funcao para gerar timestamp no historiador e apagar ocorrências antigas
 * Disparada na criação de nós no caminho '/alarm/history/{pushId}/', onde {pushId} 
 * representa o pushId autogerado, tipo '-LGuXlb0GZfwbKs0Rf8Y'.  */
exports.timestampingAlarm = functions.database.ref('/alarm/history/{pushId}/')
.onCreate((snapshot, context) => {

	//lê o valor sendo armazenado eo pushId
	var original = snapshot.val();
	var pushId = context.params.pushId;   
	   
	  //Loga no console para verificação
    console.log(`Detected new value ${original} with pushId ${pushId}`);
     
    //Alterar o valor para timesptamp, no formato Epoch time (milisegundos desde 01/01/1970)
    var myDate = new Date();        
	admin.database().ref("/alarm/history/" + pushId).set(myDate.getTime());
	   
	sendFirebaseCloudMessage('porta_aberta', 'default', 'Porta aberta!', 'channel_id_default');
	sendFirebaseCloudMessage('sirene', 'siren.wav', 'Porta aberta!', 'channel_id_siren');
    return deleteOldChildren('/alarm/history/', 100);      
})

/****Realiza buffer circular: remove as entradas antigas do caminho especificado até atingir a quantidade passada em max_log_count***/
function deleteOldChildren(firebasePath, max_log_count) {
	
	  const parentRef = admin.database().ref(firebasePath);
	  
	  //faz a query dos itens no caminho passado
    return parentRef.once('value').then(snapshot => {
        if (snapshot.numChildren() >= max_log_count) {
            let childCount = 0;
            const updates = {};

            //itera para cada nó retornado pela query
            snapshot.forEach(function(child) {
            	  
            	  //Define os filhos que serão apagados
                if (++childCount <= snapshot.numChildren() - max_log_count) {
            	    console.log(`Key apagada: ${ JSON.stringify(child.key)}`);                	
                    updates[child.key] = null;
                }
            });

            // Update the parent. This effectively removes the extra children.
            return parentRef.update(updates);
        }
		return null;
    });
 
}

 /****Envia mensagem FCM para os Apps clientes assinates dos respectivos tópicos
      O channel_id é necesário para Android >= Oreo      ****/
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

	/* Send a message to the devices subscriping  to the provided topic*/
    return admin.messaging().send(message).then(result => {
        return console.log("Successfully sent message:", result);
    })	   
}


/* Funcao para apagar keys antigas
 * Disparada na criação de nós no caminho '/alarm/keys/BkP1WdKogZYEY4pJXaCqkGs6RHh1/{pushId}', onde {pushId} 
 * representa o pushId autogerado, tipo '-LGuXlb0GZfwbKs0Rf8Y'.  */
exports.deleteKeys = functions.database.ref('/alarm/keys/BkP1WdKogZYEY4pJXaCqkGs6RHh1/{pushId}/')
.onCreate((snapshot, context) => {

    return deleteOldChildren('/alarm/keys/BkP1WdKogZYEY4pJXaCqkGs6RHh1', 200);      
})