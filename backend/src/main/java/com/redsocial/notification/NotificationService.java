package com.redsocial.notification;

import com.redsocial.common.CurrentUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class NotificationService {
    @Inject NotificationRepository notifications;
    @Inject CurrentUser currentUser;

    public List<Notification> getAll() { return notifications.findByUser(currentUser.id()); }
    public long getUnreadCount() { return notifications.countUnread(currentUser.id()); }
    public void markAllAsRead() { notifications.markAllAsRead(currentUser.id()); }
}
