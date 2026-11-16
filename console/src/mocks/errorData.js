// 가짜 에러 그룹 (팀·에러 모두 지어낸 것). 시각은 부를 때마다 "지금" 기준으로 만든다
function minutesAgo(minutes) {
  return new Date(Date.now() - minutes * 60000).toISOString();
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
