package dev.aiops.server.security;
// 키 인증에 성공했을 때 돌려주는 결과
public record ResolvedKey (
    Long teanatId, // 이 키가 속한 팀(테넌트) id
    String env,    // 키의 환경 (local / prod)
    Long keyId     // 키 자체의 id
){
}
