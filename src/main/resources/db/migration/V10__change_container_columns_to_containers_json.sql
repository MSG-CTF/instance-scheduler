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

-- 옛 컬럼은 남겨둔다, 이 마이그레이션 전에 만들어진 행은 앱을 되돌려도 그대로 읽힌다
-- 새 앱이 만든 행은 옛 컬럼이 비어 있어 되돌린 앱에서는 실행 스펙을 읽지 못한다
-- 삭제는 배포가 안정된 뒤 별도 마이그레이션으로 한다
