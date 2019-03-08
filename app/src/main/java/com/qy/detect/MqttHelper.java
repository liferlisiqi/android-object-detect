package com.qy.detect;

import android.content.Context;
import android.util.Log;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttHelper {
    private static final String SERVER_HOST = "tcp://127.0.0.1:61613";
    private static final String USERNAME = "admin";
    private static final String PASSWORD = "password";
    private String MQTT_TOPIC = "detect";
    private String clientid = "";
    private MqttClient client;
    private MqttConnectOptions options;

    public MqttHelper(){
        clientid+=MqttClient.generateClientId();
        try{
            client = new MqttClient(SERVER_HOST, clientid, new MemoryPersistence());
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void init(){
        setOptions();
        connect();
        subscribe(MQTT_TOPIC);

        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                Log.d("MqttHelper", "connectionLost");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                Log.d("MqttHelper", "messageArrived");
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.d("MqttHelper", "deliveryComplete");
            }
        });
    }

    public void setOptions(){
        try{

            options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setUserName(USERNAME);
            options.setPassword(PASSWORD.toCharArray());
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(20);
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void connect(){
        try{
            client.connect(options);
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void subscribe(String topic){
        try{
            MQTT_TOPIC = topic;
            client.subscribe(MQTT_TOPIC);
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void publish(String msg,boolean isRetained,int qos) {
        try {
            if (client!=null) {
                MqttMessage message = new MqttMessage();
                message.setQos(qos);
                message.setRetained(isRetained);
                message.setPayload(msg.getBytes());
                client.publish(MQTT_TOPIC, message);
            }
        } catch (MqttPersistenceException e) {
            e.printStackTrace();
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

}
