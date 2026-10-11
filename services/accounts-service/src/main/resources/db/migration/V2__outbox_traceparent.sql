-- La traza del emisor viaja con el evento (publicado luego por otro hilo) para continuarla en Kafka
ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(100);
