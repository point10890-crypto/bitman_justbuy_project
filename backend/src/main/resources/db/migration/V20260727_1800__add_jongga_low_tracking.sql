-- 종가매매(JONGGA_V2) 익일 저가 추적: 손절폭 임계값을 실측으로 튜닝하기 위함.
-- 고가만 있으면 목표가 도달률은 계산되지만 손절폭을 바꿔가며 평가할 수 없다.
-- 컬럼명은 Hibernate 기본 네이밍(CamelCaseToUnderscores)을 따른다.
-- lowPrice1d -> low_price1d (숫자 앞에는 언더스코어가 붙지 않음. high_price1d 와 동일 규칙)
ALTER TABLE IF EXISTS analysis_track_records
    ADD COLUMN IF NOT EXISTS low_price1d BIGINT;

ALTER TABLE IF EXISTS analysis_track_records
    ADD COLUMN IF NOT EXISTS min_return1d DOUBLE PRECISION;
