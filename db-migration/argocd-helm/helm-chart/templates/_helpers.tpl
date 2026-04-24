{{/*
fullname — <chart>-<release> 형식으로 63자 제한.
ArgoCD Application 이름이 Release name 으로 들어온다.
*/}}
{{- define "db-migration-helm.fullname" -}}
{{- printf "%s-%s" .Chart.Name .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
공통 라벨 — 모든 리소스에 붙인다.
*/}}
{{- define "db-migration-helm.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
env: {{ .Values.env }}
{{- end }}
