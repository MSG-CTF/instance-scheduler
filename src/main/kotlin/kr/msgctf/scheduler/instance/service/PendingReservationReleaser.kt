package kr.msgctf.scheduler.instance.service

// 반납 확인을 못 받은 예약을 거두는 일, 정리 워커가 이 모양만 보고 부른다
// 거둔 예약 수를 돌려준다
fun interface PendingReservationReleaser {
    fun releasePendingReservations(): Int
}
