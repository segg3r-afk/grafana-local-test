# Local Grafana + Prometheus + ActiveMQ + Custom App (kind)

## Prereqs
- Docker Desktop
- Homebrew, kubectl, kind, helm, mvn

## Quick start
```bash
# 1) Create cluster
kind create cluster --name grafana-activemq --config k8s/kind/cluster.yaml

# 2) Namespaces
kubectl apply -f k8s/namespaces.yaml

# 3) Monitoring stack
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack -n monitoring -f k8s/monitoring/values.yaml --wait

# 4) ActiveMQ + JMX exporter
kubectl apply -f k8s/activemq/configmap-jmx-exporter.yaml
kubectl apply -f k8s/activemq/deployment.yaml
kubectl -n activemq rollout status deploy/activemq

# 5) ServiceMonitor + Prometheus alert (optional)
kubectl -n monitoring apply -f k8s/monitoring/servicemonitor-activemq.yaml
kubectl -n monitoring apply -f k8s/monitoring/prometheusrule-activemq.yaml

# 6) Dashboard
kubectl apply -f k8s/monitoring/configmap-dashboard.yaml # or re-apply generated CM

# 7) Port-forwards
kubectl -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80 &
kubectl -n monitoring port-forward svc/kube-prometheus-stack-prometheus 9090:9090 &
kubectl -n activemq port-forward svc/activemq 31616:61616 18161:8161 &
```

Open Grafana: http://localhost:3000 (admin/admin)
- Explore: query `activemq_queue_size{destination="demo.queue"}` or `custom_incrementing_metric`
- Dashboard: "Messaging and App Metrics Overview"

## Producer (enqueue messages to ActiveMQ)
```bash
cd producer
mvn -q -DskipTests package
java -jar target/activemq-producer-0.0.1-SNAPSHOT.jar --messages=20 --activemq.brokerUrl=tcp://localhost:31616 --activemq.queue=demo.queue
```

## Custom Spring Boot metrics app
Build, load into kind, deploy, and verify:
```bash
cd custom-metrics-app
mvn -q -DskipTests package
docker build -t custom-metrics-app:local .
kind load docker-image custom-metrics-app:local --name grafana-activemq
kubectl -n monitoring apply -f ../k8s/custom-app/deployment.yaml
kubectl -n monitoring apply -f ../k8s/custom-app/servicemonitor.yaml
kubectl -n monitoring rollout status deploy/custom-metrics-app
kubectl -n monitoring port-forward svc/custom-metrics-app 18080:8080 &
curl http://localhost:18080/tick # increment metric
```

Prometheus: http://localhost:9090 → query `custom_incrementing_metric_total` (or `rate(custom_incrementing_metric_total[5m])`).

## Adding a new app/metrics
1. Expose Prometheus endpoint (e.g., Spring Actuator `/actuator/prometheus`).
2. Create a `Service` exposing the port.
3. Create a `ServiceMonitor` selecting the Service and pointing to the path.
4. Optionally add a dashboard panel to visualize.

## Adding a new alert
Option A: Prometheus/Alertmanager (current repo):
- Add to a new or existing `PrometheusRule` under `k8s/monitoring/*.yaml`.
- Example rule:
```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: my-app-alerts
  namespace: monitoring
  labels:
    release: kube-prometheus-stack
spec:
  groups:
  - name: my.rules
    rules:
    - alert: HighCustomMetric
      expr: rate(custom_incrementing_metric[5m]) > 1
      for: 1m
      labels:
        severity: warning
      annotations:
        summary: Custom metric rate high
```

Option B: Grafana-managed alerting (disabled in values.yaml by default here):
- Enable `grafana.sidecar.alerts.enabled: true` and configure SMTP in `k8s/monitoring/values.yaml`.
- Provision contact points/policies/rules via ConfigMaps labeled `grafana_alert: "1"`.

## Troubleshooting
- Verify the series exists in Prometheus:
  - Prometheus UI → query the expected name. For Micrometer counters, the metric ends with `_total`.
  - Example: `custom_incrementing_metric_total` or `rate(custom_incrementing_metric_total[5m])`.
- Check scrape targets:
  - Prometheus UI → Status → Targets → find your job (e.g., `custom-metrics-app`) and ensure it is `up`.
- Validate ServiceMonitor configuration:
  - Path matches your app (`/actuator/prometheus` for Spring Boot).
  - The referenced port name exists on the Service (e.g., `http`).
  - The Service `selector` matches your Pod labels.
- Hit the app metrics endpoint directly:
  - `kubectl -n <ns> port-forward svc/<service> 18080:8080` then open `http://localhost:18080/actuator/prometheus`.
- In Grafana Explore, run the exact query you configured in the panel and adjust until data shows.
- For ActiveMQ JMX rules, confirm the exporter is running (port 5556) and the `ServiceMonitor` selects the `metrics` port.

## Multiple instances and aggregations
- Prometheus attaches labels like `instance`, `pod`, `service`, `namespace`, `job` to time series.
- To aggregate a counter across replicas, use `sum` with `rate` and group by stable labels:
  - All replicas of the same Service:
    - `sum by (service) (rate(custom_incrementing_metric_total[5m]))`
  - Aggregate across all replicas and namespaces:
    - `sum without(instance, pod) (rate(custom_incrementing_metric_total[5m]))`
- Gauge aggregation depends on semantics:
  - Total across replicas: `sum by (service) (my_gauge)`
  - Max across replicas: `max by (service) (my_gauge)`
  - For ActiveMQ queue size (gauge) per destination across brokers/pods:
    - Total per destination: `sum by (destination) (activemq_queue_size)`
    - Or ensure “any is high” alert: `max by (destination) (activemq_queue_size) > THRESHOLD`

## What is PrometheusRule?
`kind: PrometheusRule` is a CRD managed by Prometheus Operator. The operator reads these CRs and merges their rule groups into the Prometheus server it manages. Prometheus then evaluates the alerting/recording rules and (if configured) sends alerts to Alertmanager.

## Cleanup
```bash
kind delete cluster --name grafana-activemq
```


