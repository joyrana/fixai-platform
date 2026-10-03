{{- define "fixai.labels" -}}
app.kubernetes.io/part-of: fixai-platform
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "fixai.selector" -}}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/name: {{ .name }}
{{- end -}}

{{- define "fixai.image" -}}
{{- $registry := .root.Values.global.imageRegistry -}}
{{- if $registry }}{{ printf "%s/%s:%s" $registry .image .root.Values.global.imageTag }}{{ else }}{{ printf "fixai/%s:%s" .image .root.Values.global.imageTag }}{{ end -}}
{{- end -}}

{{- define "fixai.podSecurity" -}}
securityContext:
  runAsNonRoot: true
  seccompProfile:
    type: RuntimeDefault
  {{- with .fsGroup }}
  fsGroup: {{ . }}
  {{- end }}
automountServiceAccountToken: false
{{- with .root.Values.global.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}

{{- define "fixai.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]
{{- end -}}

{{- define "fixai.corsOrigins" -}}
{{- if .Values.security.corsOrigins }}{{ .Values.security.corsOrigins }}{{ else }}{{ printf "https://%s" .Values.ingress.host }}{{ end -}}
{{- end -}}

{{- define "fixai.securityEnv" -}}
- name: FIXAI_SECURITY_ENABLED
  value: {{ .Values.security.enabled | quote }}
{{- if .Values.security.enabled }}
- name: FIXAI_OIDC_ISSUER_URI
  value: {{ required "security.oidc.issuerUri is required when security.enabled" .Values.security.oidc.issuerUri | quote }}
- name: FIXAI_OIDC_AUDIENCE
  value: {{ .Values.security.oidc.audience | quote }}
{{- end }}
{{- with .Values.telemetry.otlpEndpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- end -}}

{{- define "fixai.serviceUrls" -}}
- name: WORKFLOW_SERVICE_URL
  value: http://workflow-service:8080
- name: BROKER_SERVICE_URL
  value: http://broker-service:8080
- name: CERTIFICATION_SERVICE_URL
  value: http://certification-service:8080
- name: FIX_SIMULATOR_ADMIN_URL
  value: http://fix-simulator:8080
{{- end -}}
