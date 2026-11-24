// 가짜 에러 그룹 (팀·에러 모두 지어낸 것). 시각은 부를 때마다 "지금" 기준으로 만든다
function minutesAgo(minutes) {
  return new Date(Date.now() - minutes * 60000).toISOString();
}

function dayText(daysBack) {
  const date = new Date();
  date.setDate(date.getDate() - daysBack);
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

export function errorGroups() {
  return [
    {
      id: 1,
      exceptionType: "java.lang.NullPointerException",
      location: "dev.shop.order.OrderService#place",
      env: "local",
      status: "OPEN",
      count: 42,
      firstSeen: minutesAgo(60 * 50),
      lastSeen: minutesAgo(3),
      hasExplanation: true,
    },
    {
      id: 2,
      exceptionType: "org.springframework.jdbc.BadSqlGrammarException",
      location: "dev.shop.product.ProductRepository#findAll",
      env: "local",
      status: "OPEN",
      count: 7,
      firstSeen: minutesAgo(60 * 20),
      lastSeen: minutesAgo(41),
      hasExplanation: true,
    },
    {
      id: 3,
      exceptionType: "org.springframework.beans.factory.BeanCreationException",
      location: "dev.shop.config.DataSourceConfig#dataSource",
      env: "prod",
      status: "OPEN",
      count: 1,
      firstSeen: minutesAgo(120),
      lastSeen: minutesAgo(120),
      hasExplanation: false,
    },
    {
      id: 4,
      exceptionType: "java.lang.IllegalStateException",
      location: "dev.shop.member.LoginController#login",
      env: "local",
      status: "OPEN",
      count: 15,
      firstSeen: minutesAgo(60 * 70),
      lastSeen: minutesAgo(60 * 26),
      hasExplanation: true,
    },
    {
      id: 5,
      exceptionType: "java.lang.NumberFormatException",
      location: "dev.shop.cart.CartController#add",
      env: "local",
      status: "RESOLVED",
      count: 3,
      firstSeen: minutesAgo(60 * 90),
      lastSeen: minutesAgo(60 * 72),
      hasExplanation: true,
    },
    {
      id: 6,
      exceptionType: "java.net.SocketTimeoutException",
      location: null,
      env: "prod",
      status: "IGNORED",
      count: 120,
      firstSeen: minutesAgo(60 * 24 * 12),
      lastSeen: minutesAgo(60 * 24 * 9),
      hasExplanation: false,
    },
  ];
}

const EXPLANATION =
  "원인 후보:\n" +
  "1. 로그인하지 않은 요청에서 세션의 회원이 비어 있는데 바로 getId()를 불렀을 가능성.\n" +
  "2. 회원 조회가 Optional이 아니라 null을 돌려주는데 확인 없이 사용.\n" +
  "3. 테스트 데이터에 회원이 없는 주문이 섞여 있음.\n\n" +
  "확인 방법:\n" +
  "1. 로그아웃 상태로 주문 API를 호출해 재현되는지 본다.\n" +
  "2. OrderService.place에서 member를 얻는 줄의 반환 타입을 본다.\n" +
  "3. 주문 테이블에서 member_id가 비어 있는 행을 찾는다.\n\n" +
  "해결 방향:\n" +
  "1. 주문 API에 로그인 확인을 넣고 401을 돌려준다.\n" +
  "2. 조회 결과가 없을 때의 처리를 서비스 첫 줄에 둔다.\n" +
  "3. 시드 데이터를 고치고 컬럼에 NOT NULL을 건다.";

const STACK = [
  "dev.shop.order.OrderService.place(OrderService.java:48)",
  "dev.shop.order.OrderController.create(OrderController.java:31)",
  "jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)",
  "org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:258)",
  "org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:118)",
  "org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)",
  "org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)",
  "dev.shop.common.RequestLogFilter.doFilter(RequestLogFilter.java:22)",
  "org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:138)",
  "org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:165)",
  "org.apache.coyote.http11.Http11Processor.service(Http11Processor.java:399)",
  "java.lang.Thread.run(Thread.java:1583)",
];

/** 상세 가짜 응답. 목록에 없는 번호면 null (→ 404) */
export function errorDetail(groupId) {
  const summary = errorGroups().find((group) => group.id === groupId);
  if (!summary) {
    return null;
  }
  const dailyCounts = [];
  for (let back = 13; back >= 0; back--) {
    // 지어낸 모양: 최근으로 올수록 많아진다
    dailyCounts.push({ date: dayText(back), count: back > 3 ? 0 : (4 - back) * summary.id });
  }
  const old = summary.id === 6; // 6번은 보관 기간이 지나 원본이 없는 경우
  return {
    id: summary.id,
    exceptionType: summary.exceptionType,
    location: summary.location,
    env: summary.env,
    status: summary.status,
    count: summary.count,
    firstSeen: summary.firstSeen,
    lastSeen: summary.lastSeen,
    fingerprint: `fake-fingerprint-${summary.id}`,
    aiExplanation: summary.hasExplanation ? EXPLANATION : null,
    aiExplainedAt: summary.hasExplanation ? summary.firstSeen : null,
    feedback: summary.id === 5 ? "UP" : null,
    recentEvents: old
      ? []
      : [
          {
            occurredAt: summary.lastSeen,
            message: 'Cannot invoke "Member.getId()" because "member" is ***',
            stack: STACK,
            causes: [
              "java.lang.IllegalStateException: 주문을 만들 수 없습니다",
              'java.lang.NullPointerException: Cannot invoke "Member.getId()" because "member" is ***',
            ],
            thread: "http-nio-8081-exec-3",
            phase: summary.id === 3 ? "STARTUP" : "RUNTIME",
          },
          {
            occurredAt: minutesAgo(60 * 30),
            message: 'Cannot invoke "Member.getId()" because "member" is ***',
            stack: STACK,
            causes: [],
            thread: "http-nio-8081-exec-1",
            phase: "RUNTIME",
          },
        ],
    dailyCounts: old ? dailyCounts.map((day) => ({ date: day.date, count: 0 })) : dailyCounts,
    retentionDays: summary.env === "local" ? 3 : 14,
  };
}