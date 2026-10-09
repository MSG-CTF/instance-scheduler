-- Healthcheck의 JSON 문자열, null이면 검사하지 않는다
ALTER TABLE challenge_instance
    ADD COLUMN healthcheck TEXT;
