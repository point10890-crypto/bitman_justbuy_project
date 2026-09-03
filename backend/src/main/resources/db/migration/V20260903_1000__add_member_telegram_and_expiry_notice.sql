-- 회원 개인 텔레그램 알림 + 만료 D-3/D-1 예고
-- 지금까지 텔레그램은 관리자·채널 전용이라 회원에게 가는 알림이 0건이었다(리텐션 장치 없음).
-- 컬럼명은 Hibernate 기본 네이밍(CamelCaseToUnderscores)을 따른다.
ALTER TABLE IF EXISTS users ADD COLUMN IF NOT EXISTS telegram_chat_id VARCHAR(64);

-- 만료 예고를 "정확히 한 번"만 보내기 위한 마커.
-- expiry_notice_for = 예고를 보낸 대상 종료일. 연장되면 종료일이 바뀌어 마커가 자동으로 무효가 된다.
-- expiry_notice_stage = 그 종료일에 대해 마지막으로 보낸 단계(3 = D-3, 1 = D-1). 더 급한 단계만 추가 발송.
ALTER TABLE IF EXISTS users ADD COLUMN IF NOT EXISTS expiry_notice_for DATE;
ALTER TABLE IF EXISTS users ADD COLUMN IF NOT EXISTS expiry_notice_stage INTEGER;
