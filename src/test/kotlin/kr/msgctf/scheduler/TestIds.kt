package kr.msgctf.scheduler

import java.util.Locale
import java.util.UUID

// 테스트에서 번호로 구분하던 id를 같은 번호가 보이는 UUID로 만든다
// 기본 로케일이 아라비아 숫자가 아닌 환경에서도 깨지지 않게 로케일을 고정한다
fun testUuid(value: Long): UUID =
    UUID.fromString(String.format(Locale.ROOT, "00000000-0000-0000-0000-%012d", value))
