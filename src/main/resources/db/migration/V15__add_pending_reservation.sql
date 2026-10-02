-- 브로커에 보낸 예약 요청의 결과를 알 수 없을 때 그 요청을 저장하는 컬럼이다
-- 응답을 받지 못했거나, 5xx나 이유 코드가 없는 응답을 받은 경우에 값을 채운다
-- 값은 request_id와 후보 정보를 담은 JSON 문자열이다
-- 스케줄러는 다음 주기에 같은 요청을 다시 보내 결과를 확인하고, 확인이 끝나면 값을 비운다
-- 이 컬럼을 추가하기 전에 만든 행은 값이 null이다
ALTER TABLE challenge_instance
    ADD COLUMN pending_reservation TEXT;
