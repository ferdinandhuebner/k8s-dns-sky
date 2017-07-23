# Configuration

The dns controller is built on top of Spring Boot. You can configure it through an   
`application.properties` file, environment variables, java system properties or command-line arguments:

* Place the configuration in a file `application.properties` and start the application with 
  `--spring.config.location=/path/to/application.properties`
* The format of an environment variable for `my-flag` is `MY_FLAG`
* The format of a java system property for `my-flag` is `-DmyFlag` or `-Dmy-flag`
* The format of a command-line argument for `my-flag` is `--myFlag` or `--my-flag`

Settings related to Java (heap, permgen, etc.), can be configured with the following environment
variables:

* `JVM_MIN_HEAP` - initial heap size, default `64m`
* `JVM_MAX_HEAP` - max heap size, default `128m`
* `JVM_MAX_METASPACE` - max metaspace size, default `64m`
* `JVM_INITIAL_CODE_CACHE` - initial code cache size, default `16m`
* `JVM_MAX_CODE_CACHE` - max code cache size, default `32m`
* `JVM_COMPRESSED_CLASS_SPACE` - max size for compressed class pointers, default `32m` 

Specifying `JAVA_TOOL_OPTIONS` will override all other settings related to java. 

# Configuration flags

## Kubernetes related flags

The controller uses [fabric8's kubernetes client](https://github.com/fabric8io/kubernetes-client) for
kubernetes communication. You can configure it with system properties or environment variables, please
consult the fabric8 documentation for details. 

If you're running the controller in-cluster, the service account credentials are picked up by default
and you don't have to explicitly configure the client. 

### kubernetes.external-master-url

- The external URL to the kubernetes master. The master URL is used to determine if a dns name is
  already in use. If you're running the controller in-cluster and in multiple clusters, specify
  the external master url
- default: `none`
- example: `https://my-master.k8s.local`
- env variable: KUBERNETES_EXTERNAL_MASTER_URL

## DNS related flags

### dns.whitelist

- List of regular expressions of dns names that are allowed for ingresses and services, 
  separated by comma. If no value is configured, all dns names are valid if they are not explicitly
  blacklisted
- default: `none`
- example: `.*\\.my-cluster.local,kubernetes\\.acme\\.corp`
- env variable: DNS_WHITELIST

### dns.blacklist

- List of regular expressions of dns names that are not allowed for ingresses and services, 
  separated by comma. If no value is configured, no dns names are explicitly blacklisted
- default: `none`
- example: `restricted\\.my-cluster\\.local`
- env variable: DNS_BLACKLIST

### dns.controller-class

- Which controller-class this dns-controller is responsible for, i.e. the value of the annotation
  `external-dns.alpha.kubernetes.io/controller` on ingresses and services. If this is not specified,
  the controller will process ingresses and services that are not annotated with this annotation
  or the value of the annotation is empty.
- default: `none`
- example: `dns-sky/v1`
- env variable: DNS_CONTROLLER_CLASS

## SkyDNS/etcd flags

### etcd.endpoints

- List of etcd endpoints separated by comma
- required
- default: `none`
- example: `https://etcd-a:2379,https://etcd-b:2379`
- env variable: ETCD_ENDPOINTS

### etcd.username

- Username for basic authentication
- default: `none`
- example: `user`
- env variable: ETCD_USERNAME

### etcd.password

- Password for basic authentication
- required if `etcd.username` is set
- default: `none`
- example: `secret`
- env variable: ETCD_PASSWORD

### etcd.cert-file

- Path to the certificate in PEM
- required for certificate authentication
- default: `none`
- example: `/path/to/etcd-client.crt`
- env variable: ETCD_CERT_FILE

### etcd.key-file

- Path to the private key in PEM (PKCS#1 or PKCS#8)
- required for certificate authentication
- default: `none`
- example: `/path/to/etcd-client.key`
- env variable: ETCD_KEY_FILE

### etcd.ca-file

- Path to the certificate that signed the etcd server's certificate in PEM (PKCS#1 or PKCS#8)
- required for certificate authentication
- default: `none`
- example: `/path/to/etcd-ca.crt`
- env variable: ETCD_CA_FILE

### etcd.timeout

- Timeout of etcd operations in seconds
- default: `5`
- example: `5`
- env variable: ETCD_TIMEOUT

### etcd.backoff-min-delay

- Minimum delay in milliseconds when retrying etcd-operations 
- default: `20`
- example: `20`
- env variable: ETCD_BACKOFF_MIN_DELAY

### etcd.backoff-max-delay

- Maximum delay in milliseconds when retrying etcd-operations 
- default: `5000`
- example: `5000`
- env variable: ETCD_BACKOFF_MAX_DELAY

### etcd.backoff-max-tries

- Maximum number of retries for etcd operations that failed 
- default: `3`
- example: `3`
- env variable: ETCD_BACKOFF_MAX_TRIEs
