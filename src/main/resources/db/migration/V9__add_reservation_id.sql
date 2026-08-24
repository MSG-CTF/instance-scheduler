-- broker 용량 예약 id, 확정이나 반납 후에는 비운다
ALTER TABLE challenge_instance
    ADD COLUMN reservation_id VARCHAR(64);
