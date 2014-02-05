package fr.xebia.kouignamann.cloud.mqtt

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.vertx.groovy.platform.Verticle
import org.vertx.java.core.json.impl.Json

class MqttDataManagementVerticle extends Verticle implements MqttCallback {
    def logger

    def client
    MqttConnectOptions options

    /*Object waiter = new Object();
    boolean donext = false;
    Throwable ex = null;


    public int state = BEGIN;

    static final int BEGIN = 0;
    public static final int CONNECTED = 1;
    static final int PUBLISHED = 2;
    static final int SUBSCRIBED = 3;
    static final int DISCONNECTED = 4;
    static final int FINISH = 5;
    static final int ERROR = 6;
    static final int DISCONNECT = 7;
    */

    def start() {
        logger = container.logger

        configure()

        logger.info "Start -> Done initialize handler";
    }

    def configure() {
        // FIXME how to use conf.json with cloudbees
        //String uri = config['server-uri']
        //String clientId = config['client-id']
        def uri = 'tcp://m10.cloudmqtt.com:10325'
        def clientId = 'cloud'


        client = new MqttClient(uri, clientId, new MemoryPersistence())
        client.setCallback(this)

        options = new MqttConnectOptions()
        options.setPassword('kouign-amann'.getChars())
        options.setUserName('kouign-amann')
        options.setCleanSession(true)


        try {
            client.connect(options)
            logger.info "MQTT connected"
            client.subscribe('fr.xebia.kouignamann.nuc.central.processSingleVote', 2)
        } catch (MqttException e) {
            logger.error "Cannot connect", e
        }


        /*
                client.connect(options, new IMqttActionListener() {
            @Override
            void onSuccess(IMqttToken iMqttToken) {
                client.subscribe('fr.xebia.kouignamann.nuc.central.processSingleVote', 2)
            }

            @Override
            void onFailure(IMqttToken iMqttToken, Throwable throwable) {
                logger.fatal 'Cannot connect to broker', throwable
            }
        })*/

    }



    @Override
    void connectionLost(Throwable throwable) {
        logger.info "connectionLost", throwable
        while (!client.isConnected()) {
            try {
                client?.connect(options)
                sleep 1000
            } catch (Exception e) {
                e.printStackTrace()
            }
        }
    }

    @Override
    void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
        //logger.info mqttMessage
        def jsonMessage = Json.decodeValue(new String(mqttMessage.getPayload()), Map)
        def dtInterval = getInterval(new Date(jsonMessage.voteTime))
        vertx.eventBus.send("com.bloidonia.jdbcpersistor",
                [action: "insert", stmt: """
                    INSERT INTO votes VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                    `nfc_id` = values(nfc_id),
                    `rasp_id` = values(rasp_id),
                    `slot_dt` = values(slot_dt),
                    `note` = values(note),
                    `dt` = values(dt)
                    """, values: [jsonMessage.nfcId + "_" + dtInterval, jsonMessage.nfcId,
                        jsonMessage.hardwareUid, dtInterval, jsonMessage.note, new Date(jsonMessage.voteTime).format('yyyy-MM-dd HH:mm:ss')]
                ],
                { response ->
                    //logger.info response
                })
    }

    def getInterval(Date date) {
        Calendar c = date.toCalendar()
        if (c.get(Calendar.MINUTE) > 30) {
            c.set(Calendar.HOUR, c.get(Calendar.HOUR) + 1)
        }
        return c.format("YYYY-MM-dd-HH")
    }

    @Override
    void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {
        //logger.info "deliveryComplete"
    }

}