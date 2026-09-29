package com.redsocial.notification;

import nl.martijndwars.webpush.Encoding;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class WebPushSenderTest {
    @Test
    void preparesEncryptedSignedRequestWithoutSendingIt() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        var publicPart = (ECPublicKey) pair.getPublic();
        var privatePart = (ECPrivateKey) pair.getPrivate();
        byte[] rawPublic = new byte[65];
        rawPublic[0] = 4;
        System.arraycopy(fixed(publicPart.getW().getAffineX()), 0, rawPublic, 1, 32);
        System.arraycopy(fixed(publicPart.getW().getAffineY()), 0, rawPublic, 33, 32);
        String encodedPublic = Base64.getUrlEncoder().withoutPadding().encodeToString(rawPublic);

        var sender = new WebPushSender();
        sender.publicKey = encodedPublic;
        sender.privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(fixed(privatePart.getS()));
        sender.subject = "mailto:test@example.invalid";
        var subscription = new PushSubscription("s", "https://fcm.googleapis.com/fcm/send/test-only",
                encodedPublic, Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]),
                "test", Instant.now());

        var request = sender.service().preparePost(sender.notification(subscription, "post-id"), Encoding.AES128GCM);
        assertEquals("fcm.googleapis.com", request.getURI().getHost());
        assertNotNull(request.getFirstHeader("Authorization"));
        assertNotNull(request.getFirstHeader("TTL"));
        assertNotNull(request.getEntity());
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        System.arraycopy(raw, Math.max(0, raw.length - 32), result,
                Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        return result;
    }
}
