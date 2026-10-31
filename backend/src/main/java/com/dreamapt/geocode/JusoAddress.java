package com.dreamapt.geocode;

/**
 * 도로명주소 검색 API 결과 한 건. 좌표제공 API는 admCd~buldSlno로 건물을 찾는다.
 * rn·mtYn·lnbrMnnm·lnbrSlno는 결과가 단지 주소와 같은 건물인지 확인하는 데 쓴다.
 *
 * @param admCd 법정동코드 10자리 (시군구 5자리 + 읍면동 5자리)
 * @param rnMgtSn 도로명코드 12자리
 * @param udrtYn 지하 여부 (0: 지상, 1: 지하)
 * @param buldMnnm 건물 본번
 * @param buldSlno 건물 부번 (없으면 0)
 * @param roadAddr 전체 도로명주소 (로그·확인용)
 * @param rn 도로명 (예: "송파대로")
 * @param mtYn 산 여부 (0: 대지, 1: 산)
 * @param lnbrMnnm 지번 본번
 * @param lnbrSlno 지번 부번 (없으면 0)
 */
public record JusoAddress(String admCd, String rnMgtSn, String udrtYn, String buldMnnm, String buldSlno,
        String roadAddr, String rn, String mtYn, String lnbrMnnm, String lnbrSlno) {
}
