-- 실행 스펙을 단일 이미지, 포트 컬럼에서 컨테이너 배열(JSON 문자열)로 바꾼다
ALTER TABLE challenge_instance ADD COLUMN containers TEXT;

-- 기존 행은 이름 challenge, 공개 포트 하나짜리 단일 컨테이너 배열로 옮긴다
UPDATE challenge_instance
SET containers = json_build_array(
    json_build_object(
        'name', 'challenge',
        'image', container_image,
        'ports', json_build_array(container_port),
        'expose', true
    )
)::text
WHERE container_image IS NOT NULL AND container_port IS NOT NULL;

ALTER TABLE challenge_instance DROP COLUMN container_image;
ALTER TABLE challenge_instance DROP COLUMN container_port;
