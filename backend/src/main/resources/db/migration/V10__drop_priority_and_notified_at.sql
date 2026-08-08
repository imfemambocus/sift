-- nothing ranks an item and the feed orders on activity_at alone, so priority has no reader.
-- keeping the column would make every adapter invent a value for something nobody reads.
alter table feed_items drop column priority;

-- notified_at belonged to browser notifications, which are not part of the app. nothing writes it.
alter table feed_items drop column notified_at;
