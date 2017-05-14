# k8s-dns-sky

A [kubernetes external DNS controller](https://github.com/kubernetes-incubator/external-dns)
for [SkyDNS](https://github.com/skynetservices/skydns) written in scala.

# Getting started

A minimal example that uses default configuration values, a service-account token or `~/.kube/config`
for kubernetes credentials and an etcd without authentication at http://127.0.0.1:2379

```bash
java -jar dns-controller.jar --etcd.endpoints=http://127.0.0.1:2379
```

# Kubernetes Deployment

See [docs/deployment.md](docs/deployment.md) and [docs/deployment-rbac.md](docs/deployment-rbac.md).

# Configuration

See [docs/configuration.md](docs/configuration.md) for all configuration options.