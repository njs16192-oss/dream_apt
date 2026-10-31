package com.dreamapt.geocode;

/**
 * 지오코딩할 단지와 그 주소. 저장할 때 주소가 그대로인지 확인하는 데도 쓴다.
 *
 * @param sidoNm 시도명 (region 테이블)
 * @param sggNm 시군구명 (region 테이블)
 * @param roadAddress 도로명 + 건물번호 (예: "송파대로 345"). 비어 있거나 건물번호가 없을 수 있다
 */
public record GeocodeTarget(String aptSeq, String sggCd, String umdCd, String sidoNm, String sggNm, String umdNm,
        String jibun, String roadAddress) {

    /** 검색 결과의 법정동코드(admCd)가 이 값과 같아야 같은 단지로 본다 */
    public String expectedAdmCd() {
        return sggCd + umdCd;
    }
}
