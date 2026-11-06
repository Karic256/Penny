package com.karic.penny;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        Bundle extras = intent.getExtras(); if (extras == null) return;
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        StringBuilder body = new StringBuilder(); String sender = "Unknown"; long time = System.currentTimeMillis();
        for (SmsMessage msg : messages) { sender = msg.getOriginatingAddress(); body.append(msg.getMessageBody()); time = msg.getTimestampMillis(); }
        final String origin = sender;
        final long receivedAt = time;
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try (PennyDatabase db = new PennyDatabase(context)) {
                db.insertSms(origin, body.toString(), receivedAt);
            } catch (RuntimeException ignored) {
                // The inbox scan can recover this SMS; never log a private message body.
            } finally { pending.finish(); }
        }, "penny-sms").start();
    }
}
