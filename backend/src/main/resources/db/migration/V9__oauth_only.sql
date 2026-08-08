-- a source is authorized through OAuth or not at all, so PERSONAL_ACCESS_TOKEN is not a value
-- CredentialType can read, and any row still carrying it would fail on the next read of the table.
--
-- only the credential goes. feed_items and the gitlab watch tables are keyed on (user_id, source)
-- and not on the credential, so the whole history survives and authorizing the same source again
-- picks it straight back up. that also keeps the watch state, which is what stops an old thread
-- being announced a second time.
delete from source_credentials where credential_type = 'PERSONAL_ACCESS_TOKEN';
