[![Build Status](https://travis-ci.org/ferdinandhuebner/k8s-dns-sky.svg?branch=master)](https://travis-ci.org/ferdinandhuebner/k8s-dns-sky)

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

# Liveness and readiness 

The application has [Spring Boot actuator](https://docs.spring.io/spring-boot/docs/current-SNAPSHOT/reference/htmlsingle/#production-ready) 
enabled at port 8080 at the context `/actuator`. You can use `/actuator/info` for readiness and
`/actuator/health` for liveness.

## Warning: Actuator security

Please note that actuator security is **disabled** by default. That means, that all actuator
features (including heapdumps, configuration information) can be accessed without a password.

If you want to change that, please set `--management.security.enabled=true` and configure a
username and password for http basic authentication with `--security.user.name=admin` and 
`--security.user.password=secret`.