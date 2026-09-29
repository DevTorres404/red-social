package com.redsocial.notification;

import jakarta.enterprise.context.ApplicationScoped;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class WebPushSender {
    @ConfigProperty(name = "app.vapid.public-key") String publicKey;
    @ConfigProperty(name = "app.vapid.private-key") String privateKey;
    @ConfigProperty(name = "app.vapid.subject") String subject;

    public boolean configured() { return !publicKey.isBlank() && !privateKey.isBlank(); }
    public String publicKey() { return publicKey; }

    PushService service() throws GeneralSecurityException {
        Security.addProvider(new BouncyCastleProvider());
        return new PushService(publicKey, privateKey, subject);
    }

    Notification notification(PushSubscription subscription, String payload) throws Exception {
        return new Notification(subscription.endpoint(), subscription.p256dh(),
                subscription.auth(), payload.getBytes(StandardCharsets.UTF_8), 3600);
    }

    /** Returns push-service HTTP status. No endpoint or key is logged. */
    public int send(PushSubscription subscription, String payload) throws Exception {
        var response = service().sendAsync(notification(subscription, payload)).get(10, TimeUnit.SECONDS);
        return response.getStatusLine().getStatusCode();
    }
}
