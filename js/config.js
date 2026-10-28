// 앱 여는 주소는 이 파일 한 곳에만 둔다.
// 아래 스킴·패키지명은 아직 실제 폰에서 확인되지 않은 추정값이다.
// 확인 전까지 appOpen.enabled = false → [메이퇀에서 열기]는 H5 페이지만 연다.
// 실기기에서 확인되면 값을 고치고 README의 "확인된 값" 표에 날짜와 함께 기록할 것.
window.CHIFAN_CONFIG = {
  // 배달 H5 홈 (앱이 없거나 앱 열기가 꺼져 있을 때 여는 주소)
  h5Url: 'https://h5.waimai.meituan.com/waimai/mindex/home',

  appOpen: {
    enabled: false,              // 실기기 확인 전에는 false 유지

    android: {
      // intent://<path>#Intent;scheme=<scheme>;package=<package>;S.browser_fallback_url=<h5>;end
      scheme: 'imeituan',        // 미확인
      path: '',                  // 미확인 (비워 두면 앱 첫 화면)
      package: 'com.sankuai.meituan' // 미확인
    },

    ios: {
      url: 'imeituan://',        // 미확인
      fallbackMs: 1600           // 이 시간 안에 페이지가 숨겨지지 않으면 H5로
    }
  }
};
