# 방식 4: Flyway 앱 자체 알림 — 단점 재현 가이드

이 모듈은 "Flyway 앱이 직접 Teams/Jira 로 알림을 보내는 구조" 를 의도적으로 단순하게 구현했다.
아래 시나리오를 차례대로 돌려보면 왜 일반적으로 이 방식을 권장하지 않는지 직접 눈으로 볼 수 있다.

## 빌드

```
./gradlew :db-migration:flyway-app-notify:bootJar
docker build -t flyway-app-notify:latest db-migration/flyway-app-notify
```

---

## 시나리오 A — "파드가 못 뜨면 알림도 안 나간다" (가장 큰 단점)

**재현 방법**

K8s Job 매니페스트(`k8s/migration-job.yml`) 에서 시크릿 일부를 일부러 빼고 띄운다.

```
kubectl delete secret notify-secrets --ignore-not-found
kubectl apply -f k8s/migration-job.yml
kubectl get pods -w
```

**관찰**

- 파드 상태: `CreateContainerConfigError` → 컨테이너가 시작조차 안 됨
- JVM 이 안 떴으므로 `MigrationNotifyCallback` 은 호출 0회
- Teams/Jira 에 어떤 알림도 가지 않음
- 운영팀은 ArgoCD 대시보드나 K8s 이벤트를 보지 않으면 마이그레이션 실패를 모름

**대응**

```
kubectl get events --field-selector involvedObject.name=flyway-app-notify
```

**시사점**: "실패 시 알림" 이 가장 중요한 가치인데, 정작 가장 흔한 운영 장애(시크릿 누락, 이미지 Pull 실패, OOM, ConfigMap 오류) 에서 침묵한다.

---

## 시나리오 B — "K8s Job 재시도 = 알림 스팸"

**재현 방법**

`src/main/resources/db/migration/V2__broken_on_purpose.sql` 가 일부러 실패하도록 작성돼 있다.
`backoffLimit: 3` 이므로 Job 은 최대 4번(원래 1회 + 재시도 3회) 실행된다.

```
kubectl apply -f k8s/migration-job.yml
```

**관찰**

- Teams 채널에 "Migration FAILED" 메시지가 4번 도착
- Jira 이슈에 동일 실패 코멘트 4개 추가
- 운영자는 진짜 새로운 실패인지, 같은 실패의 재시도인지 구분 못함

**시사점**: ArgoCD/Jenkins 단에서 알림을 보내면 "Job 의 최종 상태" 1번만 알려줄 수 있지만, 앱 내부 콜백은 매 시도마다 발사된다. 멱등성을 앱이 떠안아야 한다.

---

## 시나리오 C — "환경 분기를 앱 재배포로만 바꿀 수 있다"

**재현 방법**

운영 중 "Teams 채널 변경" 또는 "당분간 알림 OFF" 가 필요하다고 가정.

```
# notify.enabled / TEAMS_WEBHOOK_URL 을 바꾸려면 Job 매니페스트 수정 + 재배포
```

**관찰**

- Jenkins/ArgoCD 단이라면 파이프라인 변수 한 줄 또는 잡 설정 변경으로 끝남
- 앱 내부 알림은 ENV/ConfigMap 변경 → 재시작 → 마이그레이션 재실행 사이클이 필요
- 환경별(dev silent / prod notify) if 분기가 앱 코드(`NotifyProperties.enabled()`)와 yml 양쪽에 흩어짐

**시사점**: 알림 정책 변경에 마이그레이션 앱 라이프사이클이 끌려 들어옴.

---

## 시나리오 D — "시크릿 노출 면적 확대"

**재현 방법**

```
kubectl exec -it <flyway-app-notify-pod> -- env | grep -E "JIRA|TEAMS|DB_"
```

**관찰**

- 한 컨테이너 안에 DB 비밀번호 + Jira API 토큰 + Teams Webhook URL 이 함께 존재
- DB 마이그레이션이 권한 상승 공격 표면이 되면 외부 시스템 토큰까지 동시에 유출

**시사점**: 책임이 다른 시크릿이 한 곳에 모이는 것은 보안 관점에서 큰 안티패턴이다.
Jenkins 단에 두면 Jenkins Credential Store 한 곳에서 관리되고, 마이그레이션 컨테이너에는 DB 시크릿만 들어간다.

---

## 시나리오 E — "사전 알림이 불가능"

**관찰**

- 본 모듈에는 "마이그레이션 시작합니다" 알림을 보낼 곳이 없다
- 이유: 알림을 보내려면 앱이 떠야 하고, 앱이 뜨면 이미 마이그레이션은 시작된 뒤
- BEFORE_MIGRATE 콜백을 사용해도 "JDBC 커넥션 성립 후" 시점이라 사전 승인 워크플로에는 부적합

**시사점**: "PROD 마이그레이션 시작 — 승인 필요" 같은 사전 알림은 파이프라인 단에서만 가능하다.

---

## 시나리오 F — "재사용 불가능한 알림 코드"

**관찰**

- `db-migration/infra/jenkinsfile/jenkins-shared-library/vars/teamsNotify.groovy`,
  `jiraNotify.groovy`, `jiraTransition.groovy` 와 정확히 같은 책임의 코드를
  본 모듈의 `TeamsNotifier`, `JiraNotifier` 가 중복 구현하고 있다
- 다른 마이그레이션 앱(예: `jenkins-direct`, `argocd-gitops`) 도 이 패턴을 도입하려면
  같은 클래스를 또 복사해야 함

**시사점**: 알림은 "프로젝트마다 같은 모양" 인 인프라성 코드이므로 파이프라인 공통 라이브러리에 두는 게 맞다.

---

## 결론

이 모듈에서 직접 확인할 수 있는 사실:

1. **A 시나리오** 가 결정타 — 가장 알림이 절실한 장애에서 알림이 안 간다
2. **B/C 시나리오** 는 운영 비용을 누적시킨다
3. **D 시나리오** 는 보안 사고로 이어질 수 있다
4. **E 시나리오** 는 워크플로 자체를 막는다
5. **F 시나리오** 는 유지보수 부담을 N배로 만든다

따라서 **3번 방식(Jenkins post + ArgoCD Health 알림) 이 기본**이고, 4번은
"마이그레이션 메타정보를 감사 로그로 별도 저장" 같은 보조적 용도로만 3번과 병행하는 게 맞다.