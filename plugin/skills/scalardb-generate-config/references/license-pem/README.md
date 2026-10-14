# License check certificates (PEM)

Bundled license-check certificates injected into `custom-values.yaml` as
`scalar.db.cluster.node.licensing.license_check_cert_pem` (via the
`LICENSE_CHECK_CERT_PEM` Secret key).

| file | license type | subject CN |
|---|---|---|
| `trial-cert.pem` | Trial | `trial.scalar-labs.com` |
| `production-cert.pem` | Production (commercial) | `enterprise.scalar-labs.com` |

Notes:

- The certificates' `notAfter` date (2024-02-15) has passed, but ScalarDB
  Cluster uses **only the public key** for the license check, so the expiry
  does not affect operation.
- The **license key itself is never bundled or written to files** — provide it
  via the `SCALAR_DB_CLUSTER_LICENSE_KEY` environment variable when running
  `scalardb/secrets/create-scalardb-secrets.sh` (see `scalardb-start-scalardb-cluster`).
- If Scalar issues you a different certificate, replace the copied
  `license_check_cert.pem` in your project's `scalardb/secrets/` directory.
