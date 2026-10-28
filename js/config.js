// 앱 여는 주소는 이 파일 한 곳에만 둔다.
// 아래 스킴·패키지명은 아직 실제 폰에서 확인되지 않은 추정값이다.
// 화면에는 [앱으로 열기]와 [웹] 두 버튼이 있다. 사용자가 고른다.
// [앱으로 열기]는 폰 종류(안드로이드/아이폰)를 자동으로 알아보고 앱을 시도하며, 안 열리면 H5로 넘어간다.
// appOpen.enabled = false로 두면 [앱으로 열기] 버튼을 숨긴다(웹만).
// 실기기에서 확인되면 값을 고치고 README의 "확인된 값" 표에 날짜와 함께 기록할 것.
window.CHIFAN_CONFIG = {
  // 배달 H5 홈 (앱이 없거나 앱 열기가 꺼져 있을 때 여는 주소)
  h5Url: 'https://h5.waimai.meituan.com/waimai/mindex/home',

  appOpen: {
    enabled: true,               // 사용자가 [앱으로 열기]를 고를 때만 시도. 실패하면 H5

    android: {
      // intent://<path>#Intent;scheme=<scheme>;package=<package>;S.browser_fallback_url=<h5>;end
      scheme: 'imeituan',        // 확인됨 2026-10-28 (안드로이드 실기기에서 앱 실행)
      path: '',                  // 비워 두면 앱 첫 화면
      package: 'com.sankuai.meituan', // 확인됨 2026-10-28 (설치·실행)
      // 복사한 검색어가 있을 때 쓰는 경로. {q} = 검색어(URL 인코딩).
      // 공개된 커뮤니티 URL 스킴 목록에 나온 형식이며 공식 문서는 없음 → 미확인.
      // 앱이 못 알아들으면 첫 화면만 열리고, 검색어는 클립보드에 있으니 붙여넣으면 된다.
      searchPath: 'www.meituan.com/search?q={q}'
    },

    ios: {
      url: 'imeituan://',        // 미확인
      searchUrl: 'imeituan://www.meituan.com/search?q={q}', // 미확인 (안드로이드와 같은 형식)
      fallbackMs: 1600           // 이 시간 안에 페이지가 숨겨지지 않으면 H5로
    }
  },

  // 배달앱 설치 링크. 스토어 링크가 안 맞으면 사용자가 searchTerm을 스토어 검색창에 붙여넣는다.
  install: {
    searchTerm: '美团',          // 설치 버튼을 누르면 함께 복사됨
    android: {
      market: 'market://details?id=com.sankuai.meituan',  // 패키지명 미확인. 중국 폰 제조사 스토어도 market://을 받음
      web: 'https://play.google.com/store/apps/details?id=com.sankuai.meituan', // market://이 안 열릴 때
      fallbackMs: 1600
    },
    ios: {
      url: 'https://apps.apple.com/cn/app/id423084029'    // 앱 ID 미확인. 중국 지역 Apple ID가 필요할 수 있음
    }
  }
};
