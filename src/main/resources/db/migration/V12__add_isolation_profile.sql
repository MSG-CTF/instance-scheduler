-- 런타임이 적용할 격리 정책, 지금은 WEB과 PWN 두 값을 쓴다
-- 이 컬럼이 생기기 전 행은 코드에서 WEB으로 고정해 만들었으므로 WEB으로 채운다
ALTER TABLE challenge_instance
    ADD COLUMN isolation_profile VARCHAR(20) NOT NULL DEFAULT 'WEB';

-- DEFAULT는 옛 앱이 이 컬럼을 모르고 넣는 행을 받기 위해 남긴다
-- 그 앱은 런타임에 WEB을 고정해 보내므로 값이 어긋나지 않는다
-- 다만 PWN 인스턴스가 생긴 뒤 옛 앱으로 되돌리면 그 인스턴스도 WEB으로 뜬다
-- DEFAULT 제거는 옛 앱이 사라진 뒤 별도 마이그레이션으로 한다
